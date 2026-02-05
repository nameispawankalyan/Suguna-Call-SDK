require('dotenv').config();
const express = require('express');
const { createServer } = require('http');
const { Server } = require('socket.io');
const admin = require('firebase-admin');
const cors = require('cors');
const { AccessToken } = require('livekit-server-sdk');
const Encryption = require('./encryption');
const tenantManager = require('./tenantManager');
const roomManager = require('./roomManager');

// 1. Initialize Firebase & Tenants
tenantManager.initialize();

const app = express();
app.use(cors());
app.use(express.json());

const httpServer = createServer(app);
const io = new Server(httpServer, {
    cors: { origin: "*", methods: ["GET", "POST"] }
});

// Global Socket Map
const userSocketMap = new Map(); // userId -> { socketId, appId }

// Helper Checks
async function checkUserEligibility(appId, targetUserId, callType) {
    const tenant = tenantManager.getApp(appId);
    if (!tenant) return { allowed: false, error: 'Invalid App ID' };

    try {
        const db = admin.database(tenant.firebaseApp);
        const snapshot = await db.ref(`BroadCast/${targetUserId}`).once('value');
        if (!snapshot.exists()) return { allowed: false, error: 'User Unavailable' };

        const user = snapshot.val();
        const isCallEnabled = Encryption.decrypt(user.CallEnabled) === 'true';
        const isAudioEnabled = Encryption.decrypt(user.AudioCallEnabled) === 'true';
        const isVideoEnabled = Encryption.decrypt(user.VideoCallEnabled) === 'true';

        if (!isCallEnabled) return { allowed: false, error: 'User is not receiving calls' };
        if (callType === 'Audio' && !isAudioEnabled) return { allowed: false, error: 'Audio calls disabled' };
        if (callType === 'Video' && !isVideoEnabled) return { allowed: false, error: 'Video calls disabled' };
        if (user.isBusy === true) return { allowed: false, error: 'User is on another call' };

        return { allowed: true };
    } catch (e) {
        return { allowed: false, error: 'Server Error' };
    }
}

io.on('connection', (socket) => {
    socket.on('join', (data) => {
        const { userId, appId } = data;
        if (!userId) return;

        socket.userId = userId;
        socket.appId = appId || "friendzone_001";
        userSocketMap.set(userId, { socketId: socket.id, appId: socket.appId });
        console.log(`User ${userId} joined from ${appId}`);
    });

    // --- 1. DIRECT CALL ---
    socket.on('make_call', async ({ targetId, type, senderName, senderImage, coins }) => {
        const appId = socket.appId || "friendzone_001";
        const userId = socket.userId;
        const tenant = tenantManager.getApp(appId);

        console.log(`[CallRequest] ${userId} -> ${targetId} (${type})`);

        if (!userId || !targetId) return;

        // 1. Coin Balance Check
        const minCoins = type === 'Audio' ? 100 : 300;
        if (coins < minCoins) {
            socket.emit('call_failed', { reason: `Insufficient Coins. Need ${minCoins}.` });
            return;
        }

        // 2. Target Status Check
        const eligibility = await checkUserEligibility(appId, targetId, type);
        if (!eligibility.allowed) {
            socket.emit('call_failed', { reason: eligibility.error });
            return;
        }

        const targetData = userSocketMap.get(targetId);

        // 3. Set Busy Status & Init History
        if (tenant) {
            const db = admin.database(tenant.firebaseApp);
            db.ref(`BroadCast/${userId}`).update({ isBusy: true });
            db.ref(`BroadCast/${targetId}`).update({ isBusy: true });

            const callId = `room_${[userId, targetId].sort().join('_')}`;
            tenantManager.initializeCallHistory(appId, callId, userId, targetId, type);
        }

        const callIdInstance = `room_${userId}_${Date.now()}`;

        // 4. Connect via Socket
        if (targetData && targetData.socketId) {
            io.to(targetData.socketId).emit("incoming_call", {
                senderUserId: userId,
                callType: type,
                senderName,
                senderImage,
                roomId: callIdInstance
            });
        }

        // 5. Always wakeup via FCM
        tenantManager.sendFCM(appId, targetId, {
            senderUserId: userId,
            senderName,
            senderImage,
            callType: type,
            roomName: callIdInstance
        });

        // 6. Return success with target details
        let targetName = "FriendZone User";
        let targetImage = "";
        if (tenant) {
            const db = admin.database(tenant.firebaseApp);
            const userSnap = await db.ref(`Profile_Details/${targetId}`).once('value');
            if (userSnap.exists()) {
                const u = userSnap.val();
                targetName = Encryption.decrypt(u.ProfileName || u.UserName || u.Name) || u.ProfileName || u.UserName || u.Name || "FriendZone User";
                targetImage = Encryption.decrypt(u.ProfileImage) || u.ProfileImage || "";
            }
        }

        socket.emit('call_success', { targetId, targetName, targetImage, type });
    });

    // --- 2. RANDOM CALL ---
    socket.on('random_call', async ({ type, senderName, senderImage, coins, language }) => {
        const appId = socket.appId || "friendzone_001";
        const userId = socket.userId;
        const tenant = tenantManager.getApp(appId);

        if (!userId) return;

        const minCoins = type === 'Audio' ? 100 : 300;
        if (coins < minCoins) {
            socket.emit('call_failed', { reason: `Insufficient Coins. Need ${minCoins}.` });
            return;
        }

        if (!tenant) {
            socket.emit('call_failed', { reason: 'App Configuration Error' });
            return;
        }

        try {
            const db = admin.database(tenant.firebaseApp);
            const snapshot = await db.ref('BroadCast').once('value');
            const allUsers = snapshot.val();

            let matchFound = null;

            if (allUsers) {
                const candidates = Object.values(allUsers).filter(user => {
                    const uId = user.uid || user.UserId || "";
                    if (uId === userId) return false;

                    const isCallEnabled = Encryption.decrypt(user.CallEnabled) === 'true';
                    const isAudioEnabled = Encryption.decrypt(user.AudioCallEnabled) === 'true';
                    const isVideoEnabled = Encryption.decrypt(user.VideoCallEnabled) === 'true';
                    const userLang = Encryption.decrypt(user.LanguageCode) || "";

                    if (type === 'Audio' && !isAudioEnabled) return false;
                    if (type === 'Video' && !isVideoEnabled) return false;
                    if (!isCallEnabled) return false;
                    if (user.isBusy === true) return false;

                    if (userLang && language && !userLang.toLowerCase().includes(language.toLowerCase())) return false;

                    return true;
                });

                if (candidates.length > 0) {
                    matchFound = candidates[Math.floor(Math.random() * candidates.length)];
                }
            }

            if (matchFound) {
                const targetId = matchFound.uid || matchFound.UserId;
                const targetData = userSocketMap.get(targetId);

                db.ref(`BroadCast/${userId}`).update({ isBusy: true });
                db.ref(`BroadCast/${targetId}`).update({ isBusy: true });

                const callIdInstance = `room_${userId}_${Date.now()}`;

                if (targetData && targetData.socketId) {
                    io.to(targetData.socketId).emit("incoming_call", {
                        senderUserId: userId,
                        callType: type,
                        senderName,
                        senderImage,
                        roomId: callIdInstance
                    });
                }

                tenantManager.sendFCM(appId, targetId, {
                    senderUserId: userId,
                    senderName,
                    senderImage,
                    callType: type,
                    roomName: callIdInstance
                });

                const targetName = Encryption.decrypt(matchFound.ProfileName || matchFound.UserName || matchFound.Name) || matchFound.ProfileName || matchFound.UserName || matchFound.Name || "FriendZone User";
                const targetImage = Encryption.decrypt(matchFound.ProfileImage) || matchFound.ProfileImage || "";
                socket.emit('call_success', { targetId, targetName, targetImage, type });

                const callIdHistory = `room_${[userId, targetId].sort().join('_')}`;
                tenantManager.initializeCallHistory(appId, callIdHistory, userId, targetId, type);

            } else {
                socket.emit('call_failed', { reason: 'No matching online users found.' });
            }
        } catch (e) {
            console.error(e);
            socket.emit('call_failed', { reason: 'Search Error' });
        }
    });

    socket.on("cancel_call", (data) => {
        const { targetUserId } = data;
        const userId = socket.userId;
        const appId = socket.appId || "friendzone_001";
        const tenant = tenantManager.getApp(appId);

        console.log(`[CancelCall] ${userId} -> ${targetUserId}`);

        if (tenant) {
            const db = admin.database(tenant.firebaseApp);
            db.ref(`BroadCast/${userId}`).update({ isBusy: false });
            if (targetUserId) db.ref(`BroadCast/${targetUserId}`).update({ isBusy: false });
        }

        const targetData = userSocketMap.get(targetUserId);
        if (targetData) {
            io.to(targetData.socketId).emit("call_cancelled", { from: userId });
        }

        tenantManager.sendCancelFCM(appId, targetUserId, `room_${userId}`);
    });

    socket.on("reject_call", (data) => {
        const { targetUserId } = data;
        const userId = socket.userId;
        const appId = socket.appId || "friendzone_001";
        const tenant = tenantManager.getApp(appId);

        console.log(`[RejectCall] ${userId} / ${targetUserId}`);

        if (tenant) {
            const db = admin.database(tenant.firebaseApp);
            db.ref(`BroadCast/${userId}`).update({ isBusy: false });
            if (targetUserId) db.ref(`BroadCast/${targetUserId}`).update({ isBusy: false });
        }

        const targetData = userSocketMap.get(targetUserId);
        if (targetData) {
            io.to(targetData.socketId).emit("call_rejected", { from: userId });
        }
    });

    socket.on("accept_call", async (data) => {
        const { senderUserId, callType, webhookUrl, pricePerMin } = data;
        const receiverUserId = socket.userId;
        const appId = socket.appId || "friendzone_001";
        const sortedIds = [senderUserId, receiverUserId].sort();
        const roomName = `room_${sortedIds[0]}_${sortedIds[1]}`;

        try {
            tenantManager.updateCallStatus(appId, roomName, "Answered");

            let senderName = "Caller";
            let receiverName = "Receiver";

            const tenant = tenantManager.getApp(appId);
            if (tenant) {
                const db = admin.database(tenant.firebaseApp);
                const [senderSnap, receiverSnap] = await Promise.all([
                    db.ref(`Profile_Details/${senderUserId}`).once('value'),
                    db.ref(`Profile_Details/${receiverUserId}`).once('value')
                ]);

                if (senderSnap.exists()) {
                    const u = senderSnap.val();
                    senderName = Encryption.decrypt(u.ProfileName || u.UserName || u.Name) || u.ProfileName || u.UserName || u.Name || "Caller";
                }
                if (receiverSnap.exists()) {
                    const u = receiverSnap.val();
                    receiverName = Encryption.decrypt(u.ProfileName || u.UserName || u.Name) || u.ProfileName || u.UserName || u.Name || "Receiver";
                }
            }

            const senderToken = await createToken(roomName, senderUserId, 'host', senderName);
            const receiverToken = await createToken(roomName, receiverUserId, 'host', receiverName);

            if (webhookUrl) {
                roomManager.startMonitoring(roomName, senderUserId, webhookUrl, pricePerMin || 20, appId);
            }

            const senderData = userSocketMap.get(senderUserId);
            if (senderData) {
                io.to(senderData.socketId).emit("call_started", { token: senderToken, roomName, serverUrl: process.env.LIVEKIT_URL });
            }
            socket.emit("call_started", { token: receiverToken, roomName, serverUrl: process.env.LIVEKIT_URL });
        } catch (e) {
            console.error(e);
        }
    });

    socket.on("end_call", (data) => {
        const appId = socket.appId || "friendzone_001";
        const tenant = tenantManager.getApp(appId);
        if (tenant) {
            const db = admin.database(tenant.firebaseApp);
            db.ref(`BroadCast/${socket.userId}`).update({ isBusy: false });
        }
        roomManager.stopMonitoring(data.roomName);
    });

    socket.on("disconnect", () => {
        if (socket.userId) {
            const tenant = tenantManager.getApp(socket.appId || "friendzone_001");
            if (tenant) admin.database(tenant.firebaseApp).ref(`BroadCast/${socket.userId}`).update({ isBusy: false });
            userSocketMap.delete(socket.userId);
        }
    });
});

const createToken = async (roomName, userId, role, name) => {
    const at = new AccessToken(process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET, { identity: userId, name: name });
    const isHost = role === 'host';
    at.addGrant({ roomJoin: true, room: roomName, canPublish: isHost, canSubscribe: true, canPublishData: isHost });
    return await at.toJwt();
};

app.get('/', (req, res) => res.send('🚀 Suguna Signaling Server Ready.'));

const PORT = process.env.PORT || 5000;
httpServer.listen(PORT, () => console.log(`Suguna Server on port ${PORT}`));
