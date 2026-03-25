const fs = require('fs');

const filePath = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\android-sdk\\suguna-rtc\\src\\main\\java\\com\\suguna\\rtc\\chatroom\\SugunaChatRoomActivity.kt';
let content = fs.readFileSync(filePath, 'utf8');

const target = `        // Also add locally
        messageAdapter.addMessage(ChatMessage(localUserId, localName, localImage, text, currentTime))
        findViewById<RecyclerView>(R.id.rvMessages).scrollToPosition(messageAdapter.itemCount - 1)`;

const replacement = `        // Also add locally
        val msg = ChatMessage(localUserId, localName, localImage, text, currentTime)
        messageAdapter.addMessage(msg)
        chatHistory.add(msg) // Add to history so it’s included when broadcasting to rejoinees
        findViewById<RecyclerView>(R.id.rvMessages).scrollToPosition(messageAdapter.itemCount - 1)`;

if (content.includes(target)) {
    content = content.replace(target, replacement);
    fs.writeFileSync(filePath, content, 'utf8');
    console.log("Success with Direct Match");
} else {
    console.log("Failed direct match. trying substring fallback...");
    const idx = content.indexOf('messageAdapter.addMessage(ChatMessage(localUserId, localName, localImage, text, currentTime))');
    if (idx !== -1) {
         const before = content.substring(0, idx);
         const after = content.substring(idx + 'messageAdapter.addMessage(ChatMessage(localUserId, localName, localImage, text, currentTime))'.length);
         const middle = `val msg = ChatMessage(localUserId, localName, localImage, text, currentTime)\n        messageAdapter.addMessage(msg)\n        chatHistory.add(msg)`;
         fs.writeFileSync(filePath, before + middle + after, 'utf8');
         console.log("Success with substring IndexOf");
    }
}
