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

const path = require('path');
const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname)));

// --- ADMIN DASHBOARD APIs ---
app.get('/admin', (req, res) => {
    res.sendFile(path.join(__dirname, 'dashboard.html'));
});

app.get('/admin/lib/livekit.js', (req, res) => {
    const fs = require('fs');
    const filePath = path.join(__dirname, 'livekit-client.js');
    console.log(`[LibraryRequest] Requested from IP: ${req.ip}`);
    if (fs.existsSync(filePath)) {
        res.type('application/javascript');
        res.setHeader('Access-Control-Allow-Origin', '*');
        res.setHeader('Cache-Control', 'public, max-age=3600');
        res.sendFile(filePath);
    } else {
        console.error(`[LibraryError] File not found at: ${filePath}`);
        res.status(404).send('LiveKit Library not found on server');
    }
});

app.post('/api/admin/end-room', async (req, res) => {
    try {
        const roomName = req.query.room;
        if (!roomName) return res.status(400).json({ error: "Room missing" });

        const { RoomServiceClient } = require('livekit-server-sdk');
        const livekitUrl = (process.env.LIVEKIT_URL || "").replace('wss://', 'https://');
        const svc = new RoomServiceClient(livekitUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET);
        await svc.deleteRoom(roomName);
        
        console.log(`[Admin] Ended Room: ${roomName}`);
        res.json({ success: true });
    } catch (e) {
        console.error("End Room Error:", e);
        res.status(500).json({ error: e.message });
    }
});

app.get('/api/admin/active-rooms', async (req, res) => {
    console.log(`[DashboardRequest] from IP: ${req.ip}, User-Agent: ${req.headers['user-agent']}`);
    try {
        const { RoomServiceClient } = require('livekit-server-sdk');
        const livekitUrl = (process.env.LIVEKIT_URL || "").replace('wss://', 'https://');
        const svc = new RoomServiceClient(livekitUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET);
        
        const rooms = await svc.listRooms();
        console.log(`LiveKit API: Fetched ${rooms.length} rooms.`);
        
        // Enrich rooms with participant data
        const enrichedRooms = await Promise.all(rooms.map(async (room) => {
            try {
                // Parse room metadata for callType
                let roomMeta = {};
                try {
                    if (room.metadata) roomMeta = JSON.parse(room.metadata);
                } catch(e) {}

                let detectedCallType = roomMeta.callType || "";
                const participants = await svc.listParticipants(room.name);

                const enrichedParticipants = await Promise.all(participants.map(async (p) => {
                    let metadata = {};
                    try {
                        if (p.metadata) {
                            metadata = JSON.parse(p.metadata);
                        }
                    } catch (e) {
                        console.error(`Metadata Parse Error [${p.identity}]:`, e.message);
                    }
                    
                    if (!detectedCallType && metadata.callType) {
                        detectedCallType = metadata.callType;
                    }

                    return {
                        identity: p.identity,
                        name: p.name || metadata.name || metadata.ProfileName || "User",
                        image: metadata.image || metadata.ProfileImage || "",
                        gender: metadata.gender || metadata.Gender || "",
                        role: metadata.role || (metadata.isHost ? "Host" : "Participant")
                    };
                }));
                
                return { 
                    sid: room.sid,
                    name: room.name,
                    creationTime: room.creationTime ? Number(room.creationTime) : 0,
                    numParticipants: room.numParticipants ? Number(room.numParticipants) : 0,
                    callType: detectedCallType || "Audio", // Call Type from Room or Participant Metadata
                    participants: enrichedParticipants 
                };
            } catch (e) {
                console.error(`Enrichment Failed for room ${room.name}:`, e.message);
                return { 
                    sid: room.sid,
                    name: room.name,
                    creationTime: room.creationTime ? Number(room.creationTime) : 0,
                    numParticipants: room.numParticipants ? Number(room.numParticipants) : 0,
                    participants: [] 
                };
            }
        }));

        res.json(JSON.parse(JSON.stringify({ 
            success: true, 
            rooms: enrichedRooms, 
            serverTime: Date.now() 
        }, (key, value) => typeof value === 'bigint' ? value.toString() : value)));
    } catch (e) {
        console.error("active-rooms API Error:", e.message);
        res.status(500).json({ success: false, error: e.message });
    }
});

app.get('/api/admin/room-details/:roomId', async (req, res) => {
    const { roomId } = req.params;
    try {
        const { RoomServiceClient } = require('livekit-server-sdk');
        const livekitUrl = (process.env.LIVEKIT_URL || "").replace('wss://', 'https://');
        const svc = new RoomServiceClient(livekitUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET);
        
        const participants = await svc.listParticipants(roomId);
        const enrichedParticipants = await Promise.all(participants.map(async (p) => {
            let metadata = {};
            try { if (p.metadata) metadata = JSON.parse(p.metadata); } catch (e) {}
            
            let firebaseInfo = {};
            if (!p.identity.startsWith('admin_')) {
                const appId = metadata.appId || "friendzone_001";
                const tenant = tenantManager.getApp(appId);
                if (tenant) {
                    const db = admin.database(tenant.firebaseApp);
                    const snap = await db.ref(`Profile_Details/${p.identity}`).once('value');
                    if (snap.exists()) {
                        const u = snap.val();
                        firebaseInfo = {
                            name: Encryption.decrypt(u.ProfileName || u.UserName || u.Name) || u.ProfileName || u.UserName || u.Name,
                            image: Encryption.decrypt(u.ProfileImage) || u.ProfileImage,
                            gender: Encryption.decrypt(u.Gender) || u.Gender
                        };
                    }
                }
            }
            return {
                identity: p.identity,
                name: p.name || firebaseInfo.name,
                image: firebaseInfo.image,
                gender: firebaseInfo.gender,
                metadata: metadata
            };
        }));
        
        res.json(JSON.parse(JSON.stringify({ success: true, participants: enrichedParticipants }, (key, value) => typeof value === 'bigint' ? value.toString() : value)));
    } catch (e) {
        res.status(500).json({ success: false, error: e.message });
    }
});

// End Room is now handled above at line 37

app.post('/api/admin/spy-token', async (req, res) => {
    const { roomId } = req.body;
    try {
        const { AccessToken } = require('livekit-server-sdk');
        const at = new AccessToken(process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET, {
            identity: `admin_${Math.floor(Math.random() * 10000)}`,
            hidden: true, // Make admin invisible in the room
        });
        at.addGrant({ roomJoin: true, room: roomId, canPublish: false, canSubscribe: true });
        res.json({ success: true, token: await at.toJwt(), url: process.env.LIVEKIT_URL });
    } catch (e) {
        res.status(500).json({ success: false, error: e.message });
    }
});

app.get('/api/getToken', async (req, res) => {
    const { roomName, userId, userName, userImage, isHost } = req.query;
    if (!roomName || !userId) return res.status(400).json({ error: "Missing roomName or userId" });

    try {
        const role = isHost === 'true' ? 'host' : 'participant';
        const name = userName || 'User';
        const image = userImage || '';
        const appId = "friendzone_001"; 

        // Always pass 'host' to grant canPublish to all participants for data channel delivery
        const token = await createToken(
            roomName, userId, 'host', name, 'participant', appId, "", image, "", "Audio"
        );
        res.json({ success: true, token: token, serverUrl: process.env.LIVEKIT_URL });
    } catch (e) {
        res.status(500).json({ success: false, error: e.message });
    }
});

app.post('/api/promote-participant', async (req, res) => {
    const { roomName, userId } = req.body;
    if (!roomName || !userId) return res.status(400).json({ error: "Missing roomName or userId" });

    try {
        const { RoomServiceClient } = require('livekit-server-sdk');
        const livekitUrl = (process.env.LIVEKIT_URL || "").replace('wss://', 'https://');
        const svc = new RoomServiceClient(livekitUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET);

        await svc.updateParticipant(roomName, userId, {
            permission: {
                canPublish: true,
                canSubscribe: true,
                canPublishData: true
            }
        });

        console.log(`[Promote] Promoted user ${userId} in room ${roomName} to publish.`);
        res.json({ success: true });
    } catch (e) {
        console.error("Promote Error:", e);
        res.status(500).json({ success: false, error: e.message });
    }
});

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

        const callId = `room_suguna_${Date.now()}`;

        // 3. Set Busy Status & Init History
        if (tenant) {
            const db = admin.database(tenant.firebaseApp);
            db.ref(`BroadCast/${userId}`).update({ isBusy: true });
            db.ref(`BroadCast/${targetId}`).update({ isBusy: true });

            tenantManager.initializeCallHistory(appId, callId, userId, targetId, type);
        }

        // 4. Connect via Socket
        // 4. Connect via Socket
        let isOnline = false;
        if (targetData && targetData.socketId) {
            isOnline = true;
            io.to(targetData.socketId).emit("incoming_call", {
                senderUserId: userId,
                callType: type,
                senderName,
                senderImage,
                roomId: callId
            });
        }

        // 5. Always wakeup via FCM
        tenantManager.sendFCM(appId, targetId, {
            senderUserId: userId,
            senderName,
            senderImage,
            callType: type,
            roomName: callId
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

        socket.emit('call_success', { targetId, targetName, targetImage, type, roomId: callId, isOnline: isOnline });
    });

    socket.on("call_received", (data) => {
        const { senderUserId } = data; // Sender ID to notify
        const senderData = userSocketMap.get(senderUserId);
        if (senderData) {
            io.to(senderData.socketId).emit("call_ringing", { from: socket.userId });
        }
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

                const callId = `room_suguna_${Date.now()}`;

                db.ref(`BroadCast/${userId}`).update({ isBusy: true });
                db.ref(`BroadCast/${targetId}`).update({ isBusy: true });

                if (targetData && targetData.socketId) {
                    io.to(targetData.socketId).emit("incoming_call", {
                        senderUserId: userId,
                        callType: type,
                        senderName,
                        senderImage,
                        roomId: callId
                    });
                }

                tenantManager.sendFCM(appId, targetId, {
                    senderUserId: userId,
                    senderName,
                    senderImage,
                    callType: type,
                    roomName: callId
                });

                const targetName = Encryption.decrypt(matchFound.ProfileName || matchFound.UserName || matchFound.Name) || matchFound.ProfileName || matchFound.UserName || matchFound.Name || "FriendZone User";
                const targetImage = Encryption.decrypt(matchFound.ProfileImage) || matchFound.ProfileImage || "";
                socket.emit('call_success', { targetId, targetName, targetImage, type, roomId: callId });

                tenantManager.initializeCallHistory(appId, callId, userId, targetId, type);

            } else {
                socket.emit('call_failed', { reason: 'No matching online users found.' });
            }
        } catch (e) {
            console.error(e);
            socket.emit('call_failed', { reason: 'Search Error' });
        }
    });

    socket.on("cancel_call", (data) => {
        const { targetUserId, roomId } = data;
        const userId = socket.userId;
        const appId = socket.appId || "friendzone_001";
        const tenant = tenantManager.getApp(appId);

        console.log(`[CancelCall] ${userId} -> ${targetUserId} (Room: ${roomId})`);

        if (tenant) {
            const db = admin.database(tenant.firebaseApp);
            db.ref(`BroadCast/${userId}`).update({ isBusy: false });
            if (targetUserId) db.ref(`BroadCast/${targetUserId}`).update({ isBusy: false });

            if (roomId) {
                tenantManager.updateCallStatus(appId, roomId, "Cancelled");
            }
        }

        const targetData = userSocketMap.get(targetUserId);
        if (targetData) {
            io.to(targetData.socketId).emit("call_cancelled", { from: userId, roomId });
        }

        tenantManager.sendCancelFCM(appId, targetUserId, roomId || `room_${userId}`);

        // Trigger Webhook for Missed/Cancelled Call
        tenantManager.sendWebhook(appId, {
            event: 'CALL_CANCELLED',
            roomId: roomId || `room_${userId}`,
            userId: userId, // Caller
            receiverId: targetUserId
        });
    });

    socket.on("reject_call", (data) => {
        const { targetUserId, roomId } = data;
        const userId = socket.userId;
        const appId = socket.appId || "friendzone_001";
        const tenant = tenantManager.getApp(appId);

        console.log(`[RejectCall] ${userId} / ${targetUserId} (Room: ${roomId})`);

        if (tenant) {
            const db = admin.database(tenant.firebaseApp);
            db.ref(`BroadCast/${userId}`).update({ isBusy: false });
            if (targetUserId) db.ref(`BroadCast/${targetUserId}`).update({ isBusy: false });

            if (roomId) {
                tenantManager.updateCallStatus(appId, roomId, "Decline");
            }

            // Trigger Webhook for Declined/Rejected Call
            tenantManager.sendWebhook(appId, {
                event: 'CALL_REJECTED',
                roomId: roomId,
                userId: userId, // Receiver (Rejector)
                senderId: targetUserId // Caller
            });
        }

        const targetData = userSocketMap.get(targetUserId);
        if (targetData) {
            io.to(targetData.socketId).emit("call_rejected", { from: userId, roomId });
        }
    });

    socket.on("call_timeout", (data) => {
        const { targetUserId, roomId } = data; // targetUserId is Caller
        const userId = socket.userId; // Receiver (Timeout)
        const appId = socket.appId || "friendzone_001";
        const tenant = tenantManager.getApp(appId);

        console.log(`[CallTimeout] ${userId} / ${targetUserId} (Room: ${roomId})`);

        if (tenant) {
            const db = admin.database(tenant.firebaseApp);
            db.ref(`BroadCast/${userId}`).update({ isBusy: false });
            if (targetUserId) db.ref(`BroadCast/${targetUserId}`).update({ isBusy: false });

            if (roomId) {
                tenantManager.updateCallStatus(appId, roomId, "Missed Call");
            }

            // Trigger Webhook for Missed Call (Silent Timeout)
            tenantManager.sendWebhook(appId, {
                event: 'CALL_MISSED',
                roomId: roomId,
                userId: targetUserId, // Caller
                receiverId: userId // Receiver (Missed Call Count Owner)
            });
        }

        const targetData = userSocketMap.get(targetUserId);
        if (targetData) {
            io.to(targetData.socketId).emit("call_rejected", { from: userId, roomId });
        }
    });

    socket.on("call_timeout_sender", (data) => {
        const { targetUserId, roomId } = data; // targetUserId is Receiver
        const userId = socket.userId; // Caller (Timeout)
        const appId = socket.appId || "friendzone_001";
        const tenant = tenantManager.getApp(appId);

        console.log(`[CallTimeoutSender] ${userId} / ${targetUserId} (Room: ${roomId})`);

        if (tenant) {
            const db = admin.database(tenant.firebaseApp);
            db.ref(`BroadCast/${userId}`).update({ isBusy: false });
            if (targetUserId) db.ref(`BroadCast/${targetUserId}`).update({ isBusy: false });

            if (roomId) {
                tenantManager.updateCallStatus(appId, roomId, "Missed Call");
            }

            tenantManager.sendWebhook(appId, {
                event: 'CALL_MISSED',
                roomId: roomId,
                userId: userId, // Caller
                receiverId: targetUserId // Receiver
            });
        }

        const targetData = userSocketMap.get(targetUserId);
        if (targetData) {
            io.to(targetData.socketId).emit("call_cancelled", { from: userId, roomId });
        }
    });

    socket.on("accept_call", async (data) => {
        console.log("FULL accept_call data:", JSON.stringify(data, null, 2));
        const { senderUserId, callType, webhookUrl, pricePerMin, roomId, gender } = data;
        
        // Try multiple possible keys for name and image
        let sName = data.senderName || data.name || data.userName || data.ProfileName;
        let sImg = data.senderImage || data.image || data.userImage || data.ProfileImage;
        let rName = data.receiverName || data.rName || data.name || data.ProfileName;
        let rImg = data.receiverImage || data.rImage || data.image || data.ProfileImage;

        const receiverUserId = socket.userId;
        const appId = socket.appId || "friendzone_001";

        // FALLBACK: If names are missing, try Firebase (User's app might not be sending them yet)
        if (!sName || !rName) {
            try {
                const tenant = tenantManager.getApp(appId);
                if (tenant) {
                    const db = admin.database(tenant.firebaseApp);
                    const [sSnap, rSnap] = await Promise.all([
                        db.ref(`Profile_Details/${senderUserId}`).once('value'),
                        db.ref(`Profile_Details/${receiverUserId}`).once('value')
                    ]);
                    if (!sName && sSnap.exists()) {
                        const u = sSnap.val();
                        sName = Encryption.decrypt(u.ProfileName || u.UserName || u.Name) || u.ProfileName || u.UserName || u.Name;
                        if (!sImg) sImg = Encryption.decrypt(u.ProfileImage) || u.ProfileImage;
                    }
                    if (!rName && rSnap.exists()) {
                        const u = rSnap.val();
                        rName = Encryption.decrypt(u.ProfileName || u.UserName || u.Name) || u.ProfileName || u.UserName || u.Name;
                        if (!rImg) rImg = Encryption.decrypt(u.ProfileImage) || u.ProfileImage;
                    }
                }
            } catch (e) {
                console.error("Firebase Fallback Error:", e.message);
            }
        }

        sName = sName || "Caller";
        rName = rName || "Receiver";
        sImg = sImg || "";
        rImg = rImg || "";

        const roomName = roomId || `room_${[senderUserId, receiverUserId].sort().join('_')}`;

        try {
            tenantManager.updateCallStatus(appId, roomName, "Answered");

            try {
                const { RoomServiceClient } = require('livekit-server-sdk');
                const livekitUrl = (process.env.LIVEKIT_URL || "").replace('wss://', 'https://');
                const svc = new RoomServiceClient(livekitUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET);
                await svc.updateRoomMetadata(roomName, JSON.stringify({ callType: callType || "Audio" }));
            } catch (metaErr) {
                console.warn(`Room metadata update skipped (Room might not exist yet): ${metaErr.message}`);
            }

            const senderToken = await createToken(roomName, senderUserId, 'host', sName, "sender", appId, webhookUrl, sImg, gender, callType);
            const receiverToken = await createToken(roomName, receiverUserId, 'host', rName, "receiver", appId, webhookUrl, rImg, gender, callType);

            if (webhookUrl) {
                roomManager.startMonitoring(roomName, senderUserId, webhookUrl, pricePerMin || 20, appId, callType, receiverUserId);
            }

            const senderData = userSocketMap.get(senderUserId);
            if (senderData) {
                io.to(senderData.socketId).emit("call_started", { token: senderToken, roomName, serverUrl: process.env.LIVEKIT_URL, pricePerMin: pricePerMin || 20 });
            }
            socket.emit("call_started", { token: receiverToken, roomName, serverUrl: process.env.LIVEKIT_URL, pricePerMin: pricePerMin || 20 });
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

            // Cleanup any active call sessions where this user is the caller/owner
            for (const [rId, sess] of roomManager.sessions.entries()) {
                if (sess.userId === socket.userId) {
                    console.log(`[Socket] Disconnected user ${socket.userId} had active session ${rId}. Stopping monitor.`);
                    roomManager.stopMonitoring(rId);
                }
            }
        }
    });
});

// --- API: VIOLATION REPORTING ---
app.post('/api/violation', async (req, res) => {
    const { appId, userId, reason, event } = req.body;

    if (event !== 'VIOLATION_DETECTED') return res.status(400).json({ error: 'Invalid Event' });
    if (!userId) return res.status(400).json({ error: 'Missing UserId' });

    const tenant = tenantManager.getApp(appId || "friendzone_001");
    if (!tenant) return res.status(500).json({ error: 'Tenant Error' });

    try {
        const db = admin.database(tenant.firebaseApp);

        // 1. Get Current Strikes
        const violationsRef = db.ref(`Violations_Summary/${userId}`);
        const snap = await violationsRef.once('value');
        let strikes = 0;
        if (snap.exists()) {
            strikes = snap.val().strikes || 0;
        }

        // 2. Increment Strikes
        strikes += 1;

        // 3. Save Violation Record
        const newViolationRef = db.ref(`Violations_Log/${userId}`).push();
        await newViolationRef.set({
            timestamp: Date.now(),
            reason: reason,
            strikeCount: strikes
        });

        // 4. Update Summary
        let isBanned = false;
        if (strikes >= 3) {
            isBanned = true;
            // Ban the user
            await db.ref(`Profile_Details/${userId}`).update({
                IsBanned: true,
                BanReason: "Repeated Violations: Sharing Contact Info"
            });
            await db.ref(`BroadCast/${userId}`).update({
                IsBanned: true
            });
            console.log(`🚨 User ${userId} BANNED due to 3 strikes.`);
        }

        await violationsRef.update({
            strikes: strikes,
            lastViolation: Date.now(),
            isBanned: isBanned
        });

        console.log(`[VIOLATION] User ${userId} -> Strike ${strikes}/3. Reason: ${reason}`);

        return res.json({ success: true, strikes: strikes, isBanned: isBanned });

    } catch (e) {
        console.error("Violation Update Error:", e);
        return res.status(500).json({ error: "Database Error" });
    }
});

const createToken = async (roomName, userId, role, name, metadata, appId, webhook, image, gender, callType) => {
    const metaObj = {
        role: metadata,
        appId: appId,
        webhook: webhook,
        image: image || "",
        gender: gender || "",
        callType: callType || "Audio"
    };
    const at = new AccessToken(process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET, {
        identity: userId,
        name: name,
        metadata: JSON.stringify(metaObj) // Store as JSON string in metadata
    });
    const isHost = role === 'host';
    at.addGrant({ roomJoin: true, room: roomName, canPublish: isHost, canSubscribe: true, canPublishData: isHost });
    return await at.toJwt();
};

app.get('/', (req, res) => res.send('🚀 Suguna Signaling Server Ready.'));

const PORT = process.env.PORT || 5000;
httpServer.listen(PORT, () => console.log(`Suguna Server on port ${PORT}`));
