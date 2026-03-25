const fs = require('fs');
const file = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\android-sdk\\suguna-rtc\\src\\main\\java\\com\\suguna\\rtc\\chatroom\\SugunaChatRoomActivity.kt';

let content = fs.readFileSync(file, 'utf8');

if (!content.includes('private var roomLanguage: String = "English"')) {
    content = content.replace('private var localImage: String = ""', 'private var localImage: String = ""\n    private var roomLanguage: String = "English"\n    private var promotionHandler: android.os.Handler? = null\n    private var promotionRunnable: Runnable? = null');
    console.log("Variable added");
}

if (!content.includes('roomLanguage = intent.getStringExtra("ROOM_LANGUAGE")')) {
    content = content.replace('isHostLocal = intent.getBooleanExtra("isHost", false)', 'isHostLocal = intent.getBooleanExtra("isHost", false)\n        roomLanguage = intent.getStringExtra("ROOM_LANGUAGE") ?: "English"');
    console.log("Intent check added");
}

if (!content.includes('startPromotionCycle()')) {
    const matchStr = `        if (isHostLocal) {
             try {
                  val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("frienzone")
                  db.collection("BestieRooms").document(localUserId).update("status", "Active")
             } catch (e: Exception) {}
        }`;
    const replaceStr = matchStr + '\n        if (isHostLocal) startPromotionCycle()';
    if (content.includes(matchStr)) {
        content = content.replace(matchStr, replaceStr);
        console.log("Cycle start added");
    } else {
        console.log("Match not found, search fallback");
    }
}

const methods = `
    private fun startPromotionCycle() {
        if (!isHostLocal) return
        promotionHandler = android.os.Handler(android.os.Looper.getMainLooper())
        promotionRunnable = object : Runnable {
            override fun run() {
                 sendRoomPromotion()
                 promotionHandler?.postDelayed(this, 15 * 60 * 1000)
            }
        }
        promotionHandler?.postDelayed(promotionRunnable!!, 60 * 1000)
    }

    private fun sendRoomPromotion() {
         com.google.firebase.database.FirebaseDatabase.getInstance().getReference("Profile_Details")
              .get().addOnSuccessListener { snapshot ->
                   for (uDoc in snapshot.children) {
                        try {
                            val langEnc = uDoc.child("Language").getValue(String::class.java) ?: ""
                            val decLang = if (langEnc.isNotEmpty()) pawankalyan.gpk.friendzone.Encryption.Encryption.decrypt(langEnc) ?: "" else ""
                            
                            if (decLang.equals(roomLanguage, ignoreCase = true)) {
                                 val tokenEnc = uDoc.child("fcmToken").getValue(String::class.java) ?: ""
                                 val token = if (tokenEnc.isNotEmpty()) pawankalyan.gpk.friendzone.Encryption.Encryption.decrypt(tokenEnc) ?: "" else ""
                                 
                                 if (token.isNotEmpty()) {
                                      pawankalyan.gpk.friendzone.Utils.sendNotification(
                                           messageType = "Offers",
                                           currentUserID = localUserId,
                                           message = "Join my $roomLanguage Voice Chat Room: $roomOwnerName 🎙️",
                                           token = token
                                      )
                                 }
                            }
                        } catch (e: Exception) {}
                   }
              }
    }
`;

if (!content.includes('fun startPromotionCycle()')) {
    content = content.replace('override fun onRequestPermissionsResult', methods + '\n\n    override fun onRequestPermissionsResult');
    console.log("Methods added");
}

if (!content.includes('override fun onDestroy()')) {
    const onDestroy = `
    override fun onDestroy() {
        super.onDestroy()
        promotionHandler?.removeCallbacks(promotionRunnable!!)
    }
    `;
    content = content.replace('override fun onRequestPermissionsResult', onDestroy + '\n\n    override fun onRequestPermissionsResult');
    console.log("OnDestroy added");
}

fs.writeFileSync(file, content, 'utf8');
console.log("Success: Added room notification updates");
