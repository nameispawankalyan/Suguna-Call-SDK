require('dotenv').config();
const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const jwt = require('jsonwebtoken');
const Encryption = require('./encryption');
const tenantManager = require('./tenantManager'); // Import TenantManager

// Initialize Tenants
tenantManager.initialize();

const app = express();
app.use(cors());
app.use(express.json());

const server = http.createServer(app);
const io = new Server(server, {
    cors: { origin: "*", methods: ["GET", "POST"] }
});

const APP_ID = process.env.SUGUNA_APP_ID;

// Helper: Check User Status & Balance using Tenant DB (Supports Encrypted Data)
async function checkUserEligibility(appId, targetUserId, callType) {
    const tenant = tenantManager.getApp(appId);
    if (!tenant) return { allowed: false, error: 'Invalid App ID' };

    try {
        const db = require('firebase-admin').database(tenant.firebaseApp);
        const snapshot = await db.ref(`BroadCast/${targetUserId}`).once('value');

        if (!snapshot.exists()) return { allowed: false, error: 'User not registered for calls' };

        const user = snapshot.val();

        // 1. Decrypt Basic Enabled Checks
        const isCallEnabled = Encryption.decrypt(user.CallEnabled) === 'true';
        const isAudioEnabled = Encryption.decrypt(user.AudioCallEnabled) === 'true';
        const isVideoEnabled = Encryption.decrypt(user.VideoCallEnabled) === 'true';

        if (!isCallEnabled) return { allowed: false, error: 'User is not accepting calls' };

        // 2. Specific Call Type Enabled
        if (callType === 'Audio' && !isAudioEnabled) return { allowed: false, error: 'User disabled audio calls' };
        if (callType === 'Video' && !isVideoEnabled) return { allowed: false, error: 'User disabled video calls' };

        // 3. Busy Status Check (isBusy is usually raw boolean updated by server)
        if (user.isBusy === true || user.status === 'busy') return { allowed: false, error: 'User is currently busy' };

        return { allowed: true, userData: user };
    } catch (e) {
        console.error("DB Error:", e);
        return { allowed: false, error: 'Server DB Error' };
    }
}

async function checkCallerBalance(appId, callerId, callType) {
    const tenant = tenantManager.getApp(appId);
    if (!tenant) return false;

    try {
        const db = require('firebase-admin').database(tenant.firebaseApp);
        const snapshot = await db.ref(`Wallet/CoinBalance/${callerId}`).once('value');
        if (!snapshot.exists()) return false;

        const val = snapshot.val();
        // Encrypted Coins Decryption
        const bonusCoins = parseInt(Encryption.decrypt(val.BonusCoins) || "0");
        const rechargeCoins = parseInt(Encryption.decrypt(val.RechargeCoins) || "0");
        const total = bonusCoins + rechargeCoins;

        const min = callType === 'Audio' ? 100 : 300;
        return total >= min;
    } catch (e) { return false; }
}

// Middleware: Decodes Query Params
io.use((socket, next) => {
    const userId = socket.handshake.query.userId;
    const appId = socket.handshake.query.appId || "friendzone_001"; // Default App ID
    const roomId = socket.handshake.query.roomId || "default_room";
    const role = socket.handshake.query.role || "host";

    if (userId) {
        socket.decoded = { userId, appId, roomId, role };
        next();
    } else {
        next(new Error("User ID missing"));
    }
});

const connectedUsers = {}; // userId -> socketId (Global Map - might need per-app partitioning if userIds clash)

io.on('connection', (socket) => {
    const { userId, appId, role, roomId } = socket.decoded;
    console.log(`User Connected: ${userId} (${role}) on App: ${appId}`);

    connectedUsers[userId] = socket.id;

    // --- 1. DIRECT CALL (With Validation & Multi-Tenant Support) ---
    socket.on('make_call', async ({ targetId, type, callerName, callerImage, coins }) => {
        // 1. Validation
        const minCoins = type === 'Audio' ? 100 : 300;
        if (coins < minCoins) {
            socket.emit('call_failed', { reason: `Insufficient Coins. Need ${minCoins}.` });
            return;
        }

        const eligibility = await checkUserEligibility(appId, targetId, type);
        if (!eligibility.allowed) {
            socket.emit('call_failed', { reason: eligibility.error });
            return;
        }

        const socketIdToCall = connectedUsers[targetId];
        const tenant = tenantManager.getApp(appId);

        // 2. Mark Users Busy (DB safe update)
        if (tenant) {
            const db = require('firebase-admin').database(tenant.firebaseApp);
            db.ref(`BroadCast/${userId}`).update({ isBusy: true });
            db.ref(`BroadCast/${targetId}`).update({ isBusy: true });

            // Create Call History Record
            const callId = `call_${Date.now()}`;
            tenantManager.initializeCallHistory(appId, callId, userId, targetId, type);
        }

        // 3. Connect
        if (socketIdToCall) {
            io.to(socketIdToCall).emit("incoming_call", {
                fromUserId: userId,
                callType: type,
                callerName,
                callerImage,
                roomId: `room_${userId}_${Date.now()}`
            });
        }

        // Always try FCM for wakeup (Reliability)
        tenantManager.sendFCM(appId, targetId, {
            senderUserId: userId,
            senderName: callerName,
            senderImage: callerImage,
            callType: type
        });

        socket.emit('call_success', { targetId, type });
    });

    // --- 2. RANDOM CALL LOGIC (No Gender Filter + Decryption Support) ---
    socket.on('random_call', async ({ type, callerName, callerImage, coins, language }) => {
        const minCoins = type === 'Audio' ? 100 : 300;
        if (coins < minCoins) {
            socket.emit('call_failed', { reason: `Insufficient Coins. Need ${minCoins}.` });
            return;
        }

        const tenant = tenantManager.getApp(appId);
        if (!tenant) {
            socket.emit('call_failed', { reason: 'App Configuration Error' });
            return;
        }

        try {
            // Fetch All Broadcast Users
            const db = require('firebase-admin').database(tenant.firebaseApp);
            // NOTE: Removing Gender Filter as requested
            const snapshot = await db.ref('BroadCast').once('value');
            const allUsers = snapshot.val();

            let matchFound = null;

            if (allUsers) {
                const candidates = Object.values(allUsers).filter(user => {
                    // Exclude Self
                    if (user.uid === userId || user.UserId === userId) return false;

                    // Decrypt Filter Logic
                    const isCallEnabled = Encryption.decrypt(user.CallEnabled) === 'true';
                    const isAudioEnabled = Encryption.decrypt(user.AudioCallEnabled) === 'true';
                    const isVideoEnabled = Encryption.decrypt(user.VideoCallEnabled) === 'true';
                    const userLang = Encryption.decrypt(user.LanguageCode || user.Language) || "";

                    if (type === 'Audio' && !isAudioEnabled) return false;
                    if (type === 'Video' && !isVideoEnabled) return false;
                    if (!isCallEnabled) return false;
                    if (user.isBusy === true) return false;

                    // Language Match (Case Insensitive)
                    if (userLang && language && !userLang.toLowerCase().includes(language.toLowerCase())) return false;

                    return true;
                });

                if (candidates.length > 0) {
                    matchFound = candidates[Math.floor(Math.random() * candidates.length)];
                }
            }

            if (matchFound) {
                const targetId = matchFound.uid || matchFound.UserId; // Adjust key
                const socketIdToCall = connectedUsers[targetId];

                // Set Busy
                db.ref(`BroadCast/${userId}`).update({ isBusy: true });
                db.ref(`BroadCast/${targetId}`).update({ isBusy: true });

                // Notify Target
                if (socketIdToCall) {
                    io.to(socketIdToCall).emit("incoming_call", {
                        fromUserId: userId,
                        callType: type,
                        callerName,
                        callerImage,
                        roomId: `room_${userId}_${Date.now()}`
                    });
                }

                // Send FCM
                tenantManager.sendFCM(appId, targetId, {
                    senderUserId: userId,
                    senderName: callerName,
                    senderImage: callerImage,
                    callType: type
                });

                const targetNameDecrypted = Encryption.decrypt(matchFound.Name) || "FriendZone User";
                socket.emit('call_success', { targetId, targetName: targetNameDecrypted, type });

                // Log History
                const callId = `call_${Date.now()}`;
                tenantManager.initializeCallHistory(appId, callId, userId, targetId, type);

            } else {
                socket.emit('call_failed', { reason: 'No matching online users found.' });
            }

        } catch (e) {
            console.error(e);
            socket.emit('call_failed', { reason: 'Search Error' });
        }
    });

    // --- 3. CALL END / STATUS RESET ---
    socket.on('end_call', ({ targetId }) => {
        const tenant = tenantManager.getApp(appId);
        if (tenant) {
            const db = require('firebase-admin').database(tenant.firebaseApp);
            if (targetId) db.ref(`BroadCast/${targetId}`).update({ isBusy: false });
            db.ref(`BroadCast/${userId}`).update({ isBusy: false });
        }

        const socketIdToCall = connectedUsers[targetId];
        if (socketIdToCall) io.to(socketIdToCall).emit('call_ended');
    });

    socket.on('disconnect', () => {
        console.log(`User Disconnected: ${userId}`);
        delete connectedUsers[userId];

        const tenant = tenantManager.getApp(appId);
        if (tenant) {
            const db = require('firebase-admin').database(tenant.firebaseApp);
            // Auto-Reset Status on Disconnect
            db.ref(`BroadCast/${userId}`).update({ isBusy: false });
        }
    });
});

app.get('/', (req, res) => res.send('Suguna Secured Multi-Tenant RTC Server Checked & Running'));

const PORT = process.env.PORT || 5000;
server.listen(PORT, () => console.log(`Secured Server running on port ${PORT}`));
