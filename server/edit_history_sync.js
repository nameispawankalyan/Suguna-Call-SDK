const fs = require('fs');
const file = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\android-sdk\\suguna-rtc\\src\\main\\java\\com\\suguna\\rtc\\chatroom\\SugunaChatRoomActivity.kt';

let content = fs.readFileSync(file, 'utf8');

const regex = /"chat_history"\s*->\s*\{[\s\S]*?val\s+hArr\s*=\s*json\.getJSONArray\("messages"\)[\s\S]*?for\s*\(i\s+in\s+0\s+until\s+hArr\.length\(\)\)\s*\{[\s\S]*?\}\s*\}/;

const replacement = `"chat_history" -> {
                                    val hArr = json.getJSONArray("messages")
                                    if (chatHistory.isEmpty()) {
                                         for (i in 0 until hArr.length()) {
                                             val obj = hArr.getJSONObject(i)
                                             val msg = ChatMessage(obj.getString("sender_id"), obj.getString("name"), obj.optString("image"), obj.getString("msg"), obj.getString("time"))
                                             messageAdapter.addMessage(msg)
                                             chatHistory.add(msg)
                                         }
                                         findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvMessages).scrollToPosition(messageAdapter.itemCount - 1)
                                    }
                                }
                                "chat_history_request" -> {
                                     if (isHostLocal) {
                                          broadcastChatHistory()
                                     }
                                }`;

if (content.match(regex)) {
    content = content.replace(regex, replacement);
    fs.writeFileSync(file, content, 'utf8');
    console.log("Success: Replaced chat_history correctly");
} else {
    console.log("Error: Regex did not match");
}
