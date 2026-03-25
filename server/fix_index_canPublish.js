const fs = require('fs');

const filePath = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\server\\index.js';
let content = fs.readFileSync(filePath, 'utf8');

const target = `        const role = isHost === 'true' ? 'host' : 'participant';
        const name = userName || 'User';
        const image = userImage || '';
        const appId = "friendzone_001"; 

        const token = await createToken(
            roomName, userId, role, name, role, appId, "", image, "", "Audio"
        );`;

const replacement = `        const role = isHost === 'true' ? 'host' : 'participant';
        const name = userName || 'User';
        const image = userImage || '';
        const appId = "friendzone_001"; 

        // Always pass 'host' to grant canPublish to all participants for data channel delivery
        const token = await createToken(
            roomName, userId, 'host', name, 'participant', appId, "", image, "", "Audio"
        );`;

if (content.includes(target)) {
    content = content.replace(target, replacement);
    fs.writeFileSync(filePath, content, 'utf8');
    console.log("Success with Direct Match");
} else {
    console.log("Failed direct match. Trying substring fallback...");
    const idx = content.indexOf('app.get(\'/api/getToken\'');
    if (idx !== -1) {
        const nextIdx = content.indexOf('const token = await createToken(', idx);
        if (nextIdx !== -1) {
             const endIdx = content.indexOf(');', nextIdx);
             const before = content.substring(0, nextIdx);
             const after = content.substring(endIdx + 2);
             
             const middle = `const token = await createToken(
            roomName, userId, 'host', name, 'participant', appId, "", image, "", "Audio"
        );`;
             fs.writeFileSync(filePath, before + middle + after, 'utf8');
             console.log("Success with substring IndexOf");
        }
    }
}
