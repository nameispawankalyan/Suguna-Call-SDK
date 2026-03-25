const fs = require('fs');

const filePath = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\android-sdk\\suguna-rtc\\src\\main\\java\\com\\suguna\\rtc\\chatroom\\SugunaChatRoomActivity.kt';
let content = fs.readFileSync(filePath, 'utf8');

const target = `            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                runOnUiThread {
                    // 1. Send Explicit acceptance target to trigger local mic
                    val acceptJson = JSONObject().apply {
                        put("type", "seat_accept")
                        put("target_id", req.id)
                    }
                    sugunaClient.publishData(acceptJson.toString())

                    // 2. Broadcast Seat state
                    broadcastSeatState()
                }
            }`;

const replacement = `            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        // 1. Send Explicit acceptance target to trigger local mic
                        val acceptJson = JSONObject().apply {
                            put("type", "seat_accept")
                            put("target_id", req.id)
                        }
                        sugunaClient.publishData(acceptJson.toString())

                        // 2. Broadcast Seat state
                        broadcastSeatState()
                    } else {
                        android.widget.Toast.makeText(this@SugunaChatRoomActivity, "Promote Fail: \${response.code}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }`;

if (content.includes(target)) {
    content = content.replace(target, replacement);
    fs.writeFileSync(filePath, content, 'utf8');
    console.log("Success with Direct Match");
} else {
    console.log("Failed direct match, using substring with simpler matching...");
    const idx = content.indexOf('override fun onResponse(call: okhttp3.Call, response: okhttp3.Response)');
    if (idx !== -1) {
         const nextIdx = content.indexOf('}', idx); // close runOnUiThread
         const finalIdx = content.indexOf('}', nextIdx + 2); // close onResponse
         
         const before = content.substring(0, idx);
         const after = content.substring(finalIdx + 2);
         
         const middle = `            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        val acceptJson = JSONObject().apply {
                            put("type", "seat_accept")
                            put("target_id", req.id)
                        }
                        sugunaClient.publishData(acceptJson.toString())
                        broadcastSeatState()
                    } else {
                        android.widget.Toast.makeText(this@SugunaChatRoomActivity, "Promote Fail: \${response.code}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }`;
         fs.writeFileSync(filePath, before + middle + after, 'utf8');
         console.log("Success with substring IndexOf");
    } else {
         console.log("Failed completely to find onResponse method declaration");
    }
}
