const fs = require('fs');

const filePath = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\android-sdk\\suguna-rtc\\src\\main\\java\\com\\suguna\\rtc\\chatroom\\SugunaChatRoomActivity.kt';
let content = fs.readFileSync(filePath, 'utf8');

const target = `            override fun onConnected(userId: String) {
                runOnUiThread {
                    localUserId = userId
                    updateSeats()
                }
            }`;

const replacement = `            override fun onConnected(userId: String) {
                runOnUiThread {
                    localUserId = userId
                    updateSeats()

                    if (isHostLocal) {
                         // Trigger manual promotions 1 minute after joining
                         android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                             val url = "https://asia-south1-friendzone-a40d9.cloudfunctions.net/manualRoomPromotions"
                             val request = okhttp3.Request.Builder().url(url).build()
                             okhttp3.OkHttpClient().newCall(request).enqueue(object : okhttp3.Callback {
                                 override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                                 override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {}
                             })
                         }, 60000)
                    }
                }
            }`;

if (content.includes(target)) {
    content = content.replace(target, replacement);
    fs.writeFileSync(filePath, content, 'utf8');
    console.log("Success with Direct Match");
} else {
    console.log("Failed direct match. trying substring fallback...");
    const idx = content.indexOf('localUserId = userId\n                    updateSeats()');
    if (idx !== -1) {
         const nextIdx = content.indexOf('}', idx);
         if (nextIdx !== -1) {
              const before = content.substring(0, nextIdx);
              const after = content.substring(nextIdx);
              const middle = `\n\n                    if (isHostLocal) {\n                         android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({\n                             val url = "https://asia-south1-friendzone-a40d9.cloudfunctions.net/manualRoomPromotions"\n                             val request = okhttp3.Request.Builder().url(url).build()\n                             okhttp3.OkHttpClient().newCall(request).enqueue(object : okhttp3.Callback {\n                                 override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}\n                                 override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {}\n                             })\n                         }, 60000)\n                    }\n`;
              fs.writeFileSync(filePath, before + middle + after, 'utf8');
              console.log("Success with substring IndexOf");
         }
    }
}
