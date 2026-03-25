package com.suguna.rtc.chatroom

import android.app.Service
import android.content.Intent
import android.os.IBinder

class SugunaChatRoomService : Service() {

    private var currentUserId: String = ""
    private var isHost: Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentUserId = intent?.getStringExtra("USER_ID") ?: ""
        isHost = intent?.getBooleanExtra("isHost", false) ?: false
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.d("SugunaChatRoomService", "onTaskRemoved - Host=$isHost, User=$currentUserId")
        if (isHost && currentUserId.isNotEmpty()) {
            try {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("frienzone")
                db.collection("BestieRooms").document(currentUserId).update("status", "Offline")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        stopSelf()
    }
}
