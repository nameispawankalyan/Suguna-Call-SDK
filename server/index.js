require('dotenv').config();
const express = require('express');
const { AccessToken } = require('livekit-server-sdk');
const cors = require('cors');

const path = require('path');

const app = express();
app.use(cors());
app.use(express.json());

const roomManager = require('./roomManager');

// Serve the Web Demo statically so Camera permissions work (localhost is secure context)
app.use('/demo', express.static(path.join(__dirname, '../examples/web-demo')));

const createToken = async (roomName, participantName, role) => {
    // If we don't have keys, error out
    if (!process.env.LIVEKIT_API_KEY || !process.env.LIVEKIT_API_SECRET) {
        throw new Error('LIVEKIT_API_KEY or LIVEKIT_API_SECRET is missing in .env file');
    }

    const at = new AccessToken(process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET, {
        identity: participantName,
    });

    // Permission Logic
    // Host: Can publish video/audio
    // Audience: Can ONLY subscribe (watch)
    const isHost = role === 'host';

    at.addGrant({
        roomJoin: true,
        room: roomName,
        canPublish: isHost,
        canSubscribe: true,
        canPublishData: isHost,
    });

    return await at.toJwt();
};

app.post('/getToken', async (req, res) => {
    // Expected JSON: { "roomName": "live-1", "participantName": "UserA", "role": "host", "webhookUrl": "...", "pricePerMin": 10 }
    const { roomName, participantName, role = 'audience', webhookUrl, pricePerMin } = req.body;

    if (!roomName || !participantName) {
        return res.status(400).send('roomName and participantName are required');
    }

    try {
        const token = await createToken(roomName, participantName, role);

        // Start Monitoring if Webhook URL is provided and user is Host
        if (role === 'host' && webhookUrl) {
            roomManager.startMonitoring(roomName, participantName, webhookUrl, pricePerMin || 10);
        }

        res.json({ token, role });
    } catch (e) {
        console.error("Token Generation Error:", e);
        res.status(500).send('Error: ' + e.message);
    }
});

app.post('/endCall', (req, res) => {
    const { roomName } = req.body;
    if (roomName) {
        roomManager.stopMonitoring(roomName);
        res.json({ success: true });
    } else {
        res.status(400).json({ error: "Missing roomName" });
    }
});

const PORT = process.env.PORT || 5000;
app.listen(PORT, () => {
    console.log(`🚀 LiveKit Token Server running on port ${PORT}`);
    console.log(`Endpoint: POST http://localhost:${PORT}/getToken`);
});
