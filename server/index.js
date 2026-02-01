require('dotenv').config();
const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const jwt = require('jsonwebtoken');

const app = express();
app.use(cors());
app.use(express.json());

const server = http.createServer(app);
const io = new Server(server, {
    cors: { origin: "*", methods: ["GET", "POST"] }
});

const APP_ID = process.env.SUGUNA_APP_ID;
const APP_CERTIFICATE = process.env.SUGUNA_APP_CERTIFICATE;

// --- Suguna RTC Token Generation Endpoint ---
app.post('/generateRtcToken', (req, res) => {
    const { roomId, userId, role = 'publisher' } = req.body;

    if (!roomId || !userId) {
        return res.status(400).json({ error: 'roomId and userId are required' });
    }

    // RTC Privileges (Agora Style)
    const privileges = {
        canPublish: role === 'publisher',
        canSubscribe: true,
        streamType: 'high-definition'
    };

    // Create a secure RTC token
    const rtcToken = jwt.sign(
        { roomId, userId, appId: APP_ID, privileges },
        process.env.JWT_SECRET,
        { expiresIn: '24h' }
    );

    res.json({ rtcToken, appId: APP_ID, expiresIn: 86400 });
});

// --- Socket.io Authentication Middleware ---
io.use((socket, next) => {
    const token = socket.handshake.auth.token;
    const roomId = socket.handshake.query.roomId;

    if (!token) {
        return next(new Error("Authentication error: Token missing"));
    }

    jwt.verify(token, process.env.JWT_SECRET, (err, decoded) => {
        if (err) return next(new Error("Authentication error: Invalid token"));

        // Ensure user is authorized for THIS specific room
        if (decoded.roomId !== roomId) {
            return next(new Error("Authentication error: Unauthorized for this room"));
        }

        socket.decoded = decoded;
        next();
    });
});

// Store active users: userId -> socketId
const connectedUsers = {};

io.on('connection', (socket) => {
    const { roomId, userId } = socket.decoded;
    console.log(`Verified User Connected: ${userId}`);

    // Register user
    connectedUsers[userId] = socket.id;

    // --- 1. WhatsApp-like Direct Calling ---

    socket.on('call-user', ({ userToCall, signalData, fromUserId }) => {
         const socketIdToCall = connectedUsers[userToCall];
         if (socketIdToCall) {
             io.to(socketIdToCall).emit("call-made", { signal: signalData, from: fromUserId });
             console.log(`Call forwarded from ${fromUserId} to ${userToCall}`);
         } else {
             console.log(`User ${userToCall} is offline or not found.`);
             // Optional: Emit 'user-offline' back to caller
         }
    });

    socket.on('accept-call', ({ signal, to }) => {
         const socketIdToCall = connectedUsers[to];
         io.to(socketIdToCall).emit("call-accepted", signal);
    });

    socket.on('reject-call', ({ to }) => {
         const socketIdToCall = connectedUsers[to];
         io.to(socketIdToCall).emit("call-rejected");
    });

    // --- 2. Existing Room Logic ---

    socket.on('join-room', () => {
        socket.join(roomId);
        socket.to(roomId).emit('user-joined', userId);
    });

    socket.on('disconnect', () => {
        console.log(`User ${userId} disconnected`);
        delete connectedUsers[userId]; // Clean up
        socket.to(roomId).emit('user-left', userId);
    });

    socket.on('offer', (data) => socket.to(roomId).emit('offer', data));
    socket.on('answer', (data) => socket.to(roomId).emit('answer', data));
    socket.on('ice-candidate', (data) => socket.to(roomId).emit('ice-candidate', data));
});

app.get('/', (req, res) => res.send('Suguna Secured RTC Server is running across all platforms.'));

const PORT = process.env.PORT || 5000;
server.listen(PORT, () => console.log(`Secured Server running on port ${PORT}`));
