const fs = require('fs');

const filePath = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\server\\roomManager.js';
let content = fs.readFileSync(filePath, 'utf8');

const target = `            try {
                const rooms = await svc.listRooms([roomId]);

            } catch (e) {`;

const replacement = `            try {
                const rooms = await svc.listRooms([roomId]);
                if (!rooms || rooms.length === 0 || rooms[0].numParticipants < 2) {
                    console.log(\`Room \${roomId} has \${rooms[0] ? rooms[0].numParticipants : 0} participants. Stopping monitor.\`);
                    this.stopMonitoring(roomId);
                    return;
                }
            } catch (e) {`;

if (content.includes(target)) {
    content = content.replace(target, replacement);
    fs.writeFileSync(filePath, content, 'utf8');
    console.log("Success");
} else {
    console.log("Failed to match target, trying with regex or manual substring replacement...");
    const idx = content.indexOf('const rooms = await svc.listRooms([roomId]);');
    if (idx !== -1) {
        const insertIdx = idx + 'const rooms = await svc.listRooms([roomId]);'.length;
        const insertContent = `\n                if (!rooms || rooms.length === 0 || rooms[0].numParticipants < 2) {\n                    console.log(\`Room \${roomId} has \${rooms[0] ? rooms[0].numParticipants : 0} participants. Stopping monitor.\`);\n                    this.stopMonitoring(roomId);\n                    return;\n                }`;
        content = content.substring(0, insertIdx) + insertContent + content.substring(content.indexOf('} catch (e) {', insertIdx));
        fs.writeFileSync(filePath, content, 'utf8');
        console.log("Success via manual index");
    } else {
        console.log("Failed completely to replace");
    }
}
