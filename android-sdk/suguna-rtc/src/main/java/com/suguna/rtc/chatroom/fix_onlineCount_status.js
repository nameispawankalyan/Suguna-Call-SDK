const fs = require('fs');

const filePath = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\android-sdk\\suguna-rtc\\src\\main\\java\\com\\suguna\\rtc\\chatroom\\SugunaChatRoomActivity.kt';
let content = fs.readFileSync(filePath, 'utf8');

const target = `        if (isHostLocal) {
             try {
                  val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("frienzone")
                  db.collection("BestieRooms").document(localUserId).update("onlineCount", count)
             } catch (e: Exception) {}
        }`;

const replacement = `        if (isHostLocal) {
             try {
                  val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("frienzone")
                  db.collection("BestieRooms").document(localUserId).update(
                      mapOf("onlineCount" to count, "status" to "Active")
                  )
             } catch (e: Exception) {}
        }`;

if (content.includes(target)) {
    content = content.replace(target, replacement);
    fs.writeFileSync(filePath, content, 'utf8');
    console.log("Success with Direct Match");
} else {
    console.log("Failed direct match. trying substring fallback...");
    const idx = content.indexOf('db.collection("BestieRooms").document(localUserId).update("onlineCount", count)');
    if (idx !== -1) {
         const before = content.substring(0, idx);
         const after = content.substring(idx + 'db.collection("BestieRooms").document(localUserId).update("onlineCount", count)'.length);
         const middle = `db.collection("BestieRooms").document(localUserId).update(mapOf("onlineCount" to count, "status" to "Active"))`;
         fs.writeFileSync(filePath, before + middle + after, 'utf8');
         console.log("Success with substring IndexOf");
    }
}
