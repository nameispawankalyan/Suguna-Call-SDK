const fs = require('fs');

const filePath = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\android-sdk\\suguna-rtc\\src\\main\\java\\com\\suguna\\rtc\\SugunaClient.kt';
let content = fs.readFileSync(filePath, 'utf8');

const target = `    fun publishData(message: String) {
        scope.launch {
            try {
                val data = message.toByteArray(Charsets.UTF_8)
                room?.localParticipant?.publishData(data)
            } catch (e: Exception) {
                // Log error
            }
        }
    }`;

const replacement = `    fun publishData(message: String) {
        scope.launch {
            try {
                val data = message.toByteArray(Charsets.UTF_8)
                // Use reliable DataPublishOptions to prevent packet drop for seat states
                room?.localParticipant?.publishData(data, reliable = true)
            } catch (e: Exception) {
                android.util.Log.e("SugunaClient", "Publish Data Error: \${e.message}", e)
            }
        }
    }`;

if (content.includes(target)) {
    content = content.replace(target, replacement);
    fs.writeFileSync(filePath, content, 'utf8');
    console.log("Success with Direct Match");
} else {
    console.log("Failed direct match, using substring with simpler matching...");
    const idx = content.indexOf('fun publishData(message: String)');
    if (idx !== -1) {
        const nextIdx = content.indexOf('}', idx); // close scope launch
        const finalIdx = content.indexOf('}', nextIdx + 1); // close publishData
        
        const before = content.substring(0, idx);
        const after = content.substring(finalIdx + 1);
        
        fs.writeFileSync(filePath, before + replacement + after, 'utf8');
        console.log("Success with substring IndexOf");
    } else {
         console.log("Failed completely to find method declaration");
    }
}
