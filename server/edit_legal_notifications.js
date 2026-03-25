const fs = require('fs');

// 1. SugunaChatRoomActivity edits:
const file1 = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\android-sdk\\suguna-rtc\\src\\main\\java\\com\\suguna\\rtc\\chatroom\\SugunaChatRoomActivity.kt';
let content1 = fs.readFileSync(file1, 'utf8');

const newMethod = `    private fun sendRoomPromotion() {
         val intent = android.content.Intent("com.suguna.rtc.TRIGGER_ROOM_PROMOTION")
         intent.putExtra("ROOM_LANGUAGE", roomLanguage)
         intent.putExtra("ROOM_OWNER_NAME", roomOwnerName)
         intent.putExtra("LOCAL_USER_ID", localUserId)
         sendBroadcast(intent)
    }`;

const regex = /private\s+fun\s+sendRoomPromotion\(\)\s*\{[\s\S]*?com\.google\.firebase\.database[\s\S]*?sendNotification[\s\S]*?\}\s*\}\s*\}\s*\}\s*\}\s*/m;

if (content1.match(regex)) {
    content1 = content1.replace(regex, newMethod + '\n');
    fs.writeFileSync(file1, content1, 'utf8');
    console.log("Success: Cleaned up SugunaChatRoomActivity via Regex");
} else {
    // Fallback search simple match without exact regex matching outer limits triggers flawlessly node
    const partialMatch = `private fun sendRoomPromotion() {`;
    if (content1.includes('com.google.firebase.database.FirebaseDatabase.getInstance().getReference("Profile_Details")')) {
         // reconstruct replace logic
         const splitA = content1.split('private fun sendRoomPromotion() {');
         const splitB = splitA[1].split('}')[0]; // simple split inside layout triggers node setups triggers framing index framing
         // Actually safer Regex to match method block correctly:
         const methodRegex = /private\s+fun\s+sendRoomPromotion\(\)\s*\{[\s\S]*?sendBroadcast\("triggered"\)[\s\S]*?sendNotification[\s\S]*?\}\n/m;
         // Let me use a large block replacement simply using lines split setups flawlessly node
         const startIdx = content1.indexOf('private fun sendRoomPromotion() {');
         const endIdx = content1.indexOf('override fun onRequestPermissionsResult');
         if (startIdx !== -1 && endIdx !== -1) {
              content1 = content1.substring(0, startIdx) + newMethod + '\n\n    ' + content1.substring(endIdx);
              fs.writeFileSync(file1, content1, 'utf8');
              console.log("Success: Replaced method using substring");
         } else {
              console.log("Failed to find substring markers");
         }
    } else {
         console.log("Database string not found inside content1");
    }
}

// 2. MyApp.kt edits:
const file2 = 'c:\\Android Studio\\FriendZone\\app\\src\\main\\java\\pawankalyan\\gpk\\friendzone\\Utils\\MyApp.kt';
let content2 = fs.readFileSync(file2, 'utf8');

const receiverCode = `
        val promotionReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == "com.suguna.rtc.TRIGGER_ROOM_PROMOTION") {
                    val roomLanguage = intent.getStringExtra("ROOM_LANGUAGE") ?: "English"
                    val roomOwnerName = intent.getStringExtra("ROOM_OWNER_NAME") ?: "Host"
                    val localUserId = intent.getStringExtra("LOCAL_USER_ID") ?: ""
                    
                    if (localUserId.isNotEmpty() && context != null) {
                        sendRoomPromotionFromApp(context, roomLanguage, roomOwnerName, localUserId)
                    }
                }
            }
        }
        val filter = android.content.IntentFilter("com.suguna.rtc.TRIGGER_ROOM_PROMOTION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            registerReceiver(promotionReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(promotionReceiver, filter)
        }
`;

if (!content2.includes('val promotionReceiver = object')) {
    content2 = content2.replace('createNotificationChannel()', 'createNotificationChannel()\n        ' + receiverCode);
    
    // Add sendRoomPromotionFromApp inside class
    const helperMethod = `
    private fun sendRoomPromotionFromApp(context: android.content.Context, roomLanguage: String, roomOwnerName: String, localUserId: String) {
         com.google.firebase.database.FirebaseDatabase.getInstance().getReference(Profile_Details)
              .get().addOnSuccessListener { snapshot ->
                   for (uDoc in snapshot.children) {
                        try {
                            val langEnc = uDoc.child("Language").getValue(String::class.java) ?: ""
                            val decLang = if (langEnc.isNotEmpty()) Encryption.decrypt(langEnc) ?: "" else ""
                            
                            if (decLang.equals(roomLanguage, ignoreCase = true)) {
                                 val tokenEnc = uDoc.child("fcmToken").getValue(String::class.java) ?: ""
                                 val token = if (tokenEnc.isNotEmpty()) Encryption.decrypt(tokenEnc) ?: "" else ""
                                 
                                 if (token.isNotEmpty()) {
                                      sendNotification(
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
    
    content2 = content2.replace('private fun updateUserStatus', helperMethod + '\n    private fun updateUserStatus');
    fs.writeFileSync(file2, content2, 'utf8');
    console.log("Success: Added BroadcastReceiver to MyApp.kt");
} else {
    console.log("MyApp already has receiver");
}
