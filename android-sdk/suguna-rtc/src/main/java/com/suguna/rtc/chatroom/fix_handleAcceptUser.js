const fs = require('fs');

const filePath = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\android-sdk\\suguna-rtc\\src\\main\\java\\com\\suguna\\rtc\\chatroom\\SugunaChatRoomActivity.kt';
let content = fs.readFileSync(filePath, 'utf8');

const target = `    private fun handleAcceptUser(req: SeatParticipant) {
        // Find next empty seat sequential`;

const replacement = `    private fun handleAcceptUser(req: SeatParticipant) {
        // Clear from request list if they were pending
        requestList.removeIf { it.id == req.id }
        updateRequestCount()

        // Find next empty seat sequential`;

if (content.includes(target)) {
    content = content.replace(target, replacement);
    fs.writeFileSync(filePath, content, 'utf8');
    console.log("Success with Direct Match");
} else {
    console.log("Failed direct match. trying substring fallback...");
    const idx = content.indexOf('private fun handleAcceptUser(req: SeatParticipant)');
    if (idx !== -1) {
        const nextIdx = content.indexOf('// Find next empty seat sequential', idx);
        if (nextIdx !== -1) {
             const before = content.substring(0, nextIdx);
             const after = content.substring(nextIdx);
             const middle = `// Clear from request list if they were pending\n        requestList.removeIf { it.id == req.id }\n        updateRequestCount()\n\n        `;
             fs.writeFileSync(filePath, before + middle + after, 'utf8');
             console.log("Success with substring IndexOf");
        }
    }
}
