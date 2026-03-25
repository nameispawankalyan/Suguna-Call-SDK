const fs = require('fs');

const filePath = 'c:\\Android Studio\\FriendZone\\app\\src\\main\\java\\pawankalyan\\gpk\\friendzone\\UI\\Fragments\\Navigations\\ChatRoomFragment.kt';
const sdkFilePath = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\android-sdk\\suguna-rtc\\src\\main\\java\\com\\suguna\\rtc\\chatroom\\SugunaChatRoomActivity.kt';

if (fs.existsSync(sdkFilePath)) {
    let content = fs.readFileSync(sdkFilePath, 'utf8');
    
    const target = `            override fun onDataReceived(data: String) {
                runOnUiThread {
                    try {
                        val json = JSONObject(data)`;
                        
    const replacement = `            override fun onDataReceived(data: String) {
                runOnUiThread {
                    android.util.Log.d("SugunaSignal", "Data Received: \$data")
                    try {
                        val json = JSONObject(data)`;
                        
    if (content.includes(target)) {
        content = content.replace(target, replacement);
        
        // Also add log inside catch
        const catchTarget = `                    } catch (e: Exception) {
                        e.printStackTrace()
                    }`;
        const catchReplacement = `                    } catch (e: Exception) {
                        android.util.Log.e("SugunaSignal", "Data Parse Error: \${e.message}", e)
                        e.printStackTrace()
                    }`;
        content = content.replace(catchTarget, catchReplacement);
        
        fs.writeFileSync(sdkFilePath, content, 'utf8');
        console.log("Success with Activity Logging");
    } else {
        console.log("Failed to match onDataReceived string header");
    }
}
