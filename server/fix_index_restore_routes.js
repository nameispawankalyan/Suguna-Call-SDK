const fs = require('fs');

const filePath = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\server\\index.js';
let content = fs.readFileSync(filePath, 'utf8');

const target = `    } catch (e) {
        res.status(500).json({ success: false, error: e.message });
    }
});

const httpServer = createServer(app);`;

const replacement = `    } catch (e) {
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

        const token = await createToken(
            roomName, userId, role, name, role, appId, "", image, "", "Audio"
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

        console.log(\`[Promote] Promoted user \${userId} in room \${roomName} to publish.\`);
        res.json({ success: true });
    } catch (e) {
        console.error("Promote Error:", e);
        res.status(500).json({ success: false, error: e.message });
    }
});

const httpServer = createServer(app);`;

if (content.includes(target)) {
    content = content.replace(target, replacement);
    fs.writeFileSync(filePath, content, 'utf8');
    console.log("Success with Direct Match");
} else {
    // Substring fallback with regex-capable matching
    console.log("Failed. Trying Substring matching...");
    const idx = content.indexOf('app.post(\'/api/admin/spy-token\'');
    if (idx !== -1) {
        const nextIdx = content.indexOf('const httpServer = createServer(app);', idx);
        if (nextIdx !== -1) {
             const before = content.substring(0, nextIdx);
             const after = content.substring(nextIdx);
             
             const middle = `app.get('/api/getToken', async (req, res) => {
    const { roomName, userId, userName, userImage, isHost } = req.query;
    if (!roomName || !userId) return res.status(400).json({ error: "Missing roomName or userId" });

    try {
        const role = isHost === 'true' ? 'host' : 'participant';
        const name = userName || 'User';
        const image = userImage || '';
        const appId = "friendzone_001"; 

        const token = await createToken(
            roomName, userId, role, name, role, appId, "", image, "", "Audio"
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

        console.log(\`[Promote] Promoted user \${userId} in room \${roomName} to publish.\`);
        res.json({ success: true });
    } catch (e) {
        console.error("Promote Error:", e);
        res.status(500).json({ success: false, error: e.message });
    }
});\n\n`;

             fs.writeFileSync(filePath, before + middle + after, 'utf8');
             console.log("Success with explicit index");
        }
    }
}
