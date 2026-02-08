const admin = require("firebase-admin");
const Encryption = require("./encryption");
const fs = require("fs");
const path = require("path");

class TenantManager {
    constructor() {
        this.apps = new Map(); // appId -> { firebaseApp, config }

        // Initial configuration for FriendZone
        this.appConfigs = {
            "friendzone_001": {
                name: "FriendZone",
                serviceAccount: "./configs/friendzone.json",
                databaseURL: "https://friendzone-a40d9-default-rtdb.asia-southeast1.firebasedatabase.app",
                encryptionKey: "90083A40204036E21A98F25FDAD274D4A65E4A1A2F70C0B37013DD3FCDE3E277",
                webhookUrl: "https://asia-south1-friendzone-a40d9.cloudfunctions.net/sugunaWebhook"
            },
        };
    }

    initialize() {
        console.log("Initializing Tenant Manager...");
        for (const [appId, config] of Object.entries(this.appConfigs)) {
            try {
                const serviceAccountPath = path.resolve(__dirname, config.serviceAccount);
                if (fs.existsSync(serviceAccountPath)) {
                    const firebaseApp = admin.initializeApp({
                        credential: admin.credential.cert(serviceAccountPath),
                        databaseURL: config.databaseURL
                    }, appId); // Initialize as a named app

                    this.apps.set(appId, { firebaseApp, config });
                    console.log(`✅ Loaded configuration for App: ${config.name} (${appId})`);
                } else {
                    console.warn(`⚠️ Service account not found for ${appId} at ${serviceAccountPath}`);
                }
            } catch (error) {
                console.error(`❌ Error initializing app ${appId}:`, error);
            }
        }
    }

    getApp(appId) {
        return this.apps.get(appId);
    }

    async sendFCM(appId, userId, callData) {
        console.log(`[FCM] Attempting to send to ${userId} for App ${appId}`);
        const tenant = this.getApp(appId);
        if (!tenant) {
            console.error(`[FCM] App configuration not found for AppID: ${appId}`);
            return;
        }

        try {
            const db = admin.database(tenant.firebaseApp);
            const snapshot = await db.ref(`Profile_Details/${userId}/FcmToken`).once("value");
            const encryptedToken = snapshot.val();

            console.log(`[FCM] Encrypted Token for ${userId}: ${encryptedToken ? "FOUND" : "MISSING"}`);

            if (encryptedToken) {
                const rawToken = Encryption.decrypt(encryptedToken);
                console.log(`[FCM] Decrypted Token: ${rawToken ? "VALID (" + rawToken.substring(0, 10) + "...)" : "FAILED to Decrypt"}`);

                if (rawToken) {
                    const sortedIds = [callData.senderUserId, userId].sort();
                    const roomTag = `room_${sortedIds[0]}_${sortedIds[1]}`;

                    const message = {
                        data: {
                            type: "SugunaCall",
                            senderId: callData.senderUserId,
                            senderName: callData.senderName,
                            senderImage: callData.senderImage,
                            callType: callData.callType,
                            callId: callData.roomName, // Use actual roomName from Caller side
                            appId: appId
                        },
                        token: rawToken,
                        android: {
                            priority: "high",
                            ttl: 0,
                            // notification: { // Commented out to prevent system notification
                            //     channelId: "suguna_call_channel",
                            //     clickAction: "SUGUNA_INCOMING_CALL",
                            //     priority: "max",
                            //     visibility: "public",
                            //     sound: "default",
                            //     tag: roomTag 
                            // }
                        }
                    };
                    await admin.messaging(tenant.firebaseApp).send(message);
                    console.log(`🚀 FCM Sent for App: ${tenant.config.name} to User: ${userId}`);
                }
            }
        } catch (error) {
            console.error(`FCM Error for App ${appId}:`, error);
        }
    }

    async sendCancelFCM(appId, userId, callId) {
        const tenant = this.getApp(appId);
        if (!tenant) return;

        try {
            const db = admin.database(tenant.firebaseApp);
            const snapshot = await db.ref(`Profile_Details/${userId}/FcmToken`).once("value");
            const encryptedToken = snapshot.val();

            if (encryptedToken) {
                const rawToken = Encryption.decrypt(encryptedToken);
                if (rawToken) {
                    const message = {
                        data: {
                            type: "CancelCall",
                            callId: callId
                        },
                        token: rawToken,
                        android: { priority: "high" }
                    };
                    await admin.messaging(tenant.firebaseApp).send(message);
                    console.log(`🛑 Cancel Signal Sent to User: ${userId}`);
                }
            }
        } catch (error) {
            console.error(`Cancel FCM Error:`, error);
        }
    }

    async initializeCallHistory(appId, callId, callerUid, receiverUid, callType) {
        const tenant = this.getApp(appId);
        if (!tenant) return;

        try {
            const db = admin.database(tenant.firebaseApp);
            const startTime = Date.now().toString();

            const historyData = {
                CallID: Encryption.encrypt(callId),
                CallerUid: Encryption.encrypt(callerUid),
                ReceiverUid: Encryption.encrypt(receiverUid),
                CallType: Encryption.encrypt(callType || "Audio"),
                Status: Encryption.encrypt("Calling"),
                RequestTime: Encryption.encrypt(startTime),
                TotalCoins: Encryption.encrypt("0"),
                TotalBeans: Encryption.encrypt("0")
            };

            // 1. Save in Global Node
            await db.ref(`CallHistory/${callId}`).set(historyData);

            // 2. Save in User Node (For both users)
            await db.ref(`CallHistory/${callerUid}/${callId}`).set(historyData);
            await db.ref(`CallHistory/${receiverUid}/${callId}`).set(historyData);

            console.log(`Initialized CallHistory (Global & UserWise): ${callId}`);
        } catch (error) {
            console.error(`Error initializing CallHistory for App ${appId}:`, error);
        }
    }

    async updateCallStatus(appId, callId, status, extraData = {}) {
        const tenant = this.getApp(appId);
        if (!tenant) return;

        try {
            const db = admin.database(tenant.firebaseApp);
            const ref = db.ref(`CallHistory/${callId}`);

            const updates = {
                Status: Encryption.encrypt(status)
            };

            if (status === "Answered") {
                const now = Date.now().toString();
                updates.StartTime = Encryption.encrypt(now);
                updates.AnswerTime = Encryption.encrypt(now);
            }

            if (["Ended", "Declined", "Decline", "No Answer", "Missed Call", "Cancelled", "Rejected"].includes(status)) {
                const now = Date.now().toString();
                updates.EndTime = Encryption.encrypt(now);

                // Duration Calculation
                const snap = await ref.once('value');
                if (snap.exists()) {
                    const val = snap.val();
                    const callerUid = Encryption.decrypt(val.CallerUid);
                    const receiverUid = Encryption.decrypt(val.ReceiverUid);

                    if (val.AnswerTime) {
                        const ansTime = parseInt(Encryption.decrypt(val.AnswerTime));
                        const duration = Math.max(0, parseInt(now) - ansTime);
                        updates.Duration = Encryption.encrypt(duration.toString());
                    }

                    // Perform Updates on all nodes
                    await ref.update(updates);
                    if (callerUid) await db.ref(`CallHistory/${callerUid}/${callId}`).update(updates);
                    if (receiverUid) await db.ref(`CallHistory/${receiverUid}/${callId}`).update(updates);
                } else {
                    await ref.update(updates);
                }
            } else {
                // For "Answered" or other statuses, update global and find UIDs if possible
                await ref.update(updates);
                const snap = await ref.once('value');
                if (snap.exists()) {
                    const val = snap.val();
                    const callerUid = Encryption.decrypt(val.CallerUid);
                    const receiverUid = Encryption.decrypt(val.ReceiverUid);
                    if (callerUid) await db.ref(`CallHistory/${callerUid}/${callId}`).update(updates);
                    if (receiverUid) await db.ref(`CallHistory/${receiverUid}/${callId}`).update(updates);
                }
            }
            console.log(`Updated CallHistory Status (Global & UserWise): ${callId} -> ${status}`);
        } catch (error) {
            console.error(`Error updating CallHistory for App ${appId}:`, error);
        }
    }

    async sendWebhook(appId, body) {
        const tenant = this.getApp(appId);
        if (!tenant || !tenant.config.webhookUrl) return;

        try {
            console.log(`[Webhook] Sending ${body.event} to ${tenant.config.webhookUrl}`);
            await fetch(tenant.config.webhookUrl, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
        } catch (e) {
            console.error(`[Webhook] Failed for ${appId}:`, e.message);
        }
    }
}

module.exports = new TenantManager();
