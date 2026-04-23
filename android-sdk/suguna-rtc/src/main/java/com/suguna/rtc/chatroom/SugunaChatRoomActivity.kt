package com.suguna.rtc.chatroom

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.suguna.rtc.R
import com.suguna.rtc.SugunaClient
import android.widget.ImageView
import com.suguna.rtc.chatroom.dialogs.OnlineUsersBottomSheet
import com.suguna.rtc.chatroom.dialogs.RequestsBottomSheet
import com.suguna.rtc.chatroom.dialogs.SeatControlsBottomSheet
import com.suguna.rtc.chatroom.dialogs.SeatInviteDialog
import com.suguna.rtc.chatroom.dialogs.ReactionsBottomSheet
import com.suguna.rtc.chatroom.dialogs.QuickGiftBottomSheet
import io.livekit.android.room.track.VideoTrack
import io.socket.client.Socket
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayList
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.SeekBar
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.suguna.rtc.utils.Encryption

class SugunaChatRoomActivity : AppCompatActivity(), ChatRoomActions {

    private lateinit var sugunaClient: SugunaClient
    
    private val seatAdapter = SugunaChatRoomSeatAdapter()
    private val messageAdapter = SugunaChatRoomMessageAdapter()
    private lateinit var seatManager: SeatManager
    private var roomLevel = 8

    private var localUserId: String = ""
    private var localName: String = "Unknown User"
    private var localImage: String = ""
    private var roomLanguage: String = "English"

    
    private var isMuted = false
    private var isHostLocal = false
    private var roomOwnerId: String = ""
    private var roomOwnerName: String = ""
    private var roomOwnerImage: String = ""
    private var listParticipants = mutableListOf<SeatParticipant>()
    
    private val requestList = mutableListOf<SeatParticipant>()
    private val seatedUsers = java.util.TreeMap<Int, SeatParticipant>() // TreeMap enforces seat-id order
    private val hostMutedUsers = mutableSetOf<String>()
    private val selfMutedUsers = mutableSetOf<String>() // Added for self mutes
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var musicSyncHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var musicSyncRunnable: Runnable? = null
    private var currentPlayingUri: String? = null
    
    private val invitedUsers = mutableSetOf<String>()
    private val chatHistory = mutableListOf<ChatMessage>()
    private var isRequestSent = false // Track request sent state
    private var isInviteDialogShowing = false
    
    private val promoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var promoRunnable: Runnable? = null
    
    private val musicReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.suguna.rtc.ACTION_PLAY_MUSIC") {
                val name = intent.getStringExtra("SONG_NAME") ?: "Music"
                val uriString = intent.getStringExtra("SONG_URI")
                val artUri = intent.getStringExtra("SONG_ART")
                val duration = intent.getLongExtra("SONG_DURATION", 0L)
                if (isHostLocal) {
                    Toast.makeText(this@SugunaChatRoomActivity, "Starting Playback: $name", Toast.LENGTH_SHORT).show()
                    startMusicBroadcasting(name, uriString, artUri, duration)
                }
            }
        }
    }

    private val closeChatRoomReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.suguna.rtc.ACTION_CLOSE_CHATROOM_SEAT") {
                android.util.Log.d("SugunaChatRoom", "Received Close ChatRoom signal due to direct call start")
                isExplicitLeave = true
                if (!isHostLocal) {
                    handleLeaveSeat() // Leave seat to notify host
                    try {
                        val leaveJson = org.json.JSONObject().apply {
                            put("type", "user_left_room")
                            put("user_id", localUserId)
                        }
                        sugunaClient.publishData(leaveJson.toString())
                    } catch (e: Exception) {}
                }
                finish()
            }
        }
    }

    private var wasMutedBeforePhoneCall = true // Track state before call

    private val audioFocusChangeListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                 android.util.Log.d("SugunaChatRoom", "Audio focus lost. Muting room locally...")
                 wasMutedBeforePhoneCall = isMuted
                 sugunaClient.setMicrophoneEnabled(false)
                 sugunaClient.muteAllRemoteAudio(true)
                 mediaPlayer?.pause()
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                 android.util.Log.d("SugunaChatRoom", "Audio focus gained. Restoring room...")
                 sugunaClient.muteAllRemoteAudio(false)
                 if (!wasMutedBeforePhoneCall && !selfMutedUsers.contains(localUserId) && !hostMutedUsers.contains(localUserId)) {
                     sugunaClient.setMicrophoneEnabled(true)
                     isMuted = false
                 }
            }
        }
    }

    private val phoneStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val state = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)
                when (state) {
                    android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        android.util.Log.d("SugunaChatRoom", "Phone call answered ($state). Muting chatroom locally...")
                        wasMutedBeforePhoneCall = isMuted
                        sugunaClient.setMicrophoneEnabled(false)
                        sugunaClient.muteAllRemoteAudio(true)
                        mediaPlayer?.pause()
                    }
                    android.telephony.TelephonyManager.EXTRA_STATE_IDLE -> {
                        android.util.Log.d("SugunaChatRoom", "Phone call ended ($state). Restoring chatroom...")
                        sugunaClient.muteAllRemoteAudio(false)
                        if (!wasMutedBeforePhoneCall && !selfMutedUsers.contains(localUserId) && !hostMutedUsers.contains(localUserId)) {
                             sugunaClient.setMicrophoneEnabled(true)
                             isMuted = false
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Clear active Direct Call Activities if any
        val closeDirectCallIntent = android.content.Intent("com.suguna.rtc.ACTION_CLOSE_DIRECT_CALL")
        sendBroadcast(closeDirectCallIntent)

        setContentView(R.layout.activity_suguna_chat_room)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        val token = intent.getStringExtra("TOKEN") ?: ""
        val serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        localUserId = intent.getStringExtra("USER_ID") ?: "user_${System.currentTimeMillis()}"
        localName = intent.getStringExtra("USER_NAME") ?: "User"
        localImage = intent.getStringExtra("USER_IMAGE") ?: ""
        isHostLocal = intent.getBooleanExtra("isHost", false)
        roomLanguage = intent.getStringExtra("ROOM_LANGUAGE") ?: "English"
        roomOwnerId = intent.getStringExtra("ROOM_OWNER_ID") ?: ""
        roomOwnerName = intent.getStringExtra("ROOM_OWNER_NAME") ?: "Host"
        roomOwnerImage = intent.getStringExtra("ROOM_OWNER_IMAGE") ?: if (isHostLocal) localImage else ""
        roomLevel = intent.getIntExtra("roomLevel", 8)

        checkNotificationPermission()
        startChatRoomService()

        seatManager = SeatManager()
        seatManager.generateSeats(roomLevel)

        if (isHostLocal) {
            try {
                 val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("friendzone")
                 db.collection("BestieRooms").document(localUserId).update(
                     mapOf("status" to "Active", "onlineCount" to 1)
                 )
            } catch (e: Exception) {}
        }

        setupSeatsRecyclerView()
        setupMessagesRecyclerView()
        setupMessageSender()
        setupControls()
        setupTrendingGiftsBar()
        
        val cFilter = android.content.IntentFilter("com.suguna.rtc.ACTION_CLOSE_CHATROOM_SEAT")
        val pFilter = android.content.IntentFilter(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(closeChatRoomReceiver, cFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(phoneStateReceiver, pFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeChatRoomReceiver, cFilter)
            registerReceiver(phoneStateReceiver, pFilter)
        }

        if (token.isEmpty()) {
            Toast.makeText(this, "Error: Invalid Token", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.requestAudioFocus(
            audioFocusChangeListener,
            android.media.AudioManager.STREAM_MUSIC,
            android.media.AudioManager.AUDIOFOCUS_GAIN
        )

        findViewById<TextView>(R.id.tvRoomId).text = "ID: ${intent.getStringExtra("ROOM_ID") ?: "--"}"
        findViewById<TextView>(R.id.tvRoomName).text = intent.getStringExtra("ROOM_NAME") ?: "Suguna Chat Room"

        val ivRoomOwner = findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivRoomOwner)
        if (roomOwnerImage.isNotEmpty()) {
             com.bumptech.glide.Glide.with(this).load(roomOwnerImage).into(ivRoomOwner)
        }

        // Request Audio Permission upfront for dynamic seating
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
             requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
        } else {
             startRtc(serverUrl, token)
        }

        val mFilter = android.content.IntentFilter("com.suguna.rtc.ACTION_PLAY_MUSIC")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(this, musicReceiver, mFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(musicReceiver, mFilter)
        }

        animationOverlay = findViewById(R.id.animationOverlay)
        
        // Robust Socket Listener Registration
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val sock = com.suguna.rtc.utils.SocketManager.getSocket()
            if (sock != null) {
                sock.off("cr_gift_received") // Clear old
                sock.on("cr_gift_received") { args: Array<Any>? ->
                    val data = args?.getOrNull(0) as? org.json.JSONObject
                    if (data != null) {
                        val sId = data.optString("senderId")
                        val gUrl = data.optString("giftUrl")
                        val rIdsArr = data.optJSONArray("receiverIds")
                        val singleRId = data.optString("receiverId")
                        
                        runOnUiThread {
                             // Hint for receiver/room (Vibration)
                             val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                             vibrator.vibrate(100)

                             val rootView = findViewById<android.view.ViewGroup>(android.R.id.content)
                             if (rootView != null) {
                                 if (rIdsArr != null) {
                                     for (i in 0 until rIdsArr.length()) {
                                         val rId = rIdsArr.getString(i)
                                         startActualAnimation(gUrl, rId, rootView)
                                     }
                                 } else if (singleRId.isNotEmpty()) {
                                     startActualAnimation(gUrl, singleRId, rootView)
                                 }
                             }
                        }
                    }
                }
            }
        }, 1500)
    }

    private fun startChatRoomService() {
        try {
            val rName = intent.getStringExtra("ROOM_NAME") ?: "Suguna Chat Room"
            val intent = android.content.Intent(this, SugunaChatRoomService::class.java).apply {
                putExtra("USER_ID", localUserId)
                putExtra("isHost", isHostLocal)
                putExtra("ROOM_NAME", rName)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopChatRoomService() {
        try {
            stopService(android.content.Intent(this, SugunaChatRoomService::class.java))
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun checkNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 112)
            }
        }
    }

    private var isExplicitLeave = false

    override fun onDestroy() {
        super.onDestroy()
        promoRunnable?.let { promoHandler.removeCallbacks(it) }
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        // Remove Socket Listeners to prevent Ghost Activity leaks
        val sock = com.suguna.rtc.utils.SocketManager.getSocket()
        sock?.off("music_play")
        sock?.off("music_sync")
        sock?.off("music_pause")
        sock?.off("music_resume")
        sock?.off("music_stop")
        sock?.off("cr_state_sync")
        sock?.off("cr_seat_status")
        sock?.off("cr_chat_received")
        sock?.off("cr_handle_request")

        if (isExplicitLeave) {
            val rId = intent.getStringExtra("ROOM_ID") ?: ""
            if (rId.isNotEmpty()) {
                com.suguna.rtc.utils.SocketManager.crLeave(rId, localUserId)
            }

            if (isHostLocal) {
                try {
                    val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("friendzone")
                    db.collection("BestieRooms").document(localUserId).update(
                        mapOf("status" to "Offline", "onlineCount" to 0)
                    )
                } catch (e: Exception) {}
            }
            
            stopChatRoomService()

            if (::sugunaClient.isInitialized) {
                sugunaClient.leaveRoom()
            }
        }
        
        try { unregisterReceiver(closeChatRoomReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(phoneStateReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(musicReceiver) } catch (e: Exception) {}
        
        try {
             val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
             audioManager.abandonAudioFocus(audioFocusChangeListener)
        } catch (e: Exception) {}
    }

    private fun startRtc(serverUrl: String, token: String) {
        // Initialize SDK
        sugunaClient = SugunaClient(this, serverUrl)
        
        sugunaClient.setEventListener(object : SugunaClient.SugunaEvents {
            override fun onConnected(userId: String) {
                runOnUiThread {
                    localUserId = userId
                    updateSeats()

                    // Request State and History from Host for reliable synchronization upon join
                    if (!isHostLocal) {
                        // CRITICAL: When audience joins, ensure they are MUTED by default
                        // to prevent accidental audio broadcasting before being seated.
                        isMuted = true
                        sugunaClient.setMicrophoneEnabled(false)

                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                val rId = intent.getStringExtra("ROOM_ID") ?: ""
                                com.suguna.rtc.utils.SocketManager.crJoin(rId, localUserId, localName, localImage, isHostLocal)
                            } catch (e: Exception) {}
                        }, 1200)
                    } else {
                         // Host Joins
                         val rId = intent.getStringExtra("ROOM_ID") ?: ""
                         com.suguna.rtc.utils.SocketManager.crJoin(rId, localUserId, localName, localImage, true)
                    }

                    // Setup Socket Listeners for Room Management
                    val sock = com.suguna.rtc.utils.SocketManager.getSocket()
                    sock?.on("cr_state_sync") { args: Array<Any>? ->
                        runOnUiThread {
                            val data = args?.getOrNull(0) as? org.json.JSONObject
                            val pRoomId = data?.optString("roomId") ?: ""
                            val cRoomId = intent.getStringExtra("ROOM_ID") ?: ""
                            if (pRoomId.isNotEmpty() && pRoomId != cRoomId) return@runOnUiThread

                            // --- SYNC SEATS ---
                            val seatsJson = data?.optJSONObject("seats")
                            if (seatsJson != null) {
                                val keys = seatsJson.keys()
                                val newSeatedUsers = java.util.TreeMap<Int, SeatParticipant>()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    val uObj = seatsJson.getJSONObject(key)
                                    val u = SeatParticipant(
                                        uObj.getString("userId"),
                                        uObj.getString("name"),
                                        uObj.getString("image")
                                    )
                                    newSeatedUsers[key.toInt()] = u
                                }
                                
                                val isHostOnline = listParticipants.any { it.id == roomOwnerId } || isHostLocal
                                if (!isHostLocal && !isHostOnline && seatedUsers.isNotEmpty()) {
                                     // Host is offline! Freeze state.
                                     return@runOnUiThread
                                }

                                if (newSeatedUsers.isNotEmpty() || (isHostLocal && seatedUsers.isEmpty())) {
                                    // 🚀 ANTI-OVERWRITE: Merge instead of full clear IF count is suspiciously low
                                    if (!isHostLocal && newSeatedUsers.size < seatedUsers.size && isHostOnline) {
                                         // Possibly a partial sync, be careful. 
                                    }
                                    
                                    val wasSeatedLocalBefore = seatedUsers.values.any { it.id == localUserId } || isHostLocal
                                    
                                    // ANTI-OVERWRITE: Only replace if we have valid data
                                    if (newSeatedUsers.isNotEmpty() || (isHostLocal && seatedUsers.isEmpty())) {
                                        seatedUsers.clear()
                                        seatedUsers.putAll(newSeatedUsers)
                                    }
                                    
                                    val isSeatedLocalNow = seatedUsers.values.any { it.id == localUserId } || isHostLocal
                                    
                                    // 🎤 AUTO-UNMUTE: If I just appeared in the seat list and was not seated before
                                    if (!wasSeatedLocalBefore && isSeatedLocalNow && !isHostLocal) {
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            if (!hostMutedUsers.contains(localUserId) && !selfMutedUsers.contains(localUserId)) {
                                                isMuted = false
                                                sugunaClient.setMicrophoneEnabled(true)
                                                setupControls()
                                                updateSeats()
                                            }
                                        }, 1500)
                                    }
                                }
                            }

                            // --- SYNC MUTE STATES ---
                            val hostMutedArr = data?.optJSONArray("hostMutedIds")
                            if (hostMutedArr != null) {
                                hostMutedUsers.clear()
                                for (i in 0 until hostMutedArr.length()) {
                                    hostMutedUsers.add(hostMutedArr.getString(i))
                                }
                            }

                            val selfMutedArr = data?.optJSONArray("selfMutedIds")
                            if (selfMutedArr != null) {
                                selfMutedUsers.clear()
                                for (i in 0 until selfMutedArr.length()) {
                                    selfMutedUsers.add(selfMutedArr.getString(i))
                                }
                            }

                            // --- ENFORCE MUTE ---
                            if (hostMutedUsers.contains(localUserId) || selfMutedUsers.contains(localUserId)) {
                                if (!isMuted) {
                                    isMuted = true
                                    sugunaClient.setMicrophoneEnabled(false)
                                    setupControls()
                                }
                            }

                            updateSeats()
                        }
                    }
                    
                    sock?.on("cr_handle_request") { args: Array<Any>? ->
                        runOnUiThread {
                            val reqJson = args?.getOrNull(0) as? org.json.JSONObject
                            if (reqJson != null) {
                                val rUId = reqJson.optString("userId") ?: reqJson.optString("sender_id") ?: ""
                                val rUName = reqJson.optString("name") ?: "User"
                                val rUImage = reqJson.optString("image") ?: ""
                                
                                if (rUId.isNotEmpty()) {
                                    val reqU = SeatParticipant(rUId, rUName, rUImage)
                                    if (!requestList.any { it.id == reqU.id }) {
                                        requestList.add(reqU)
                                        updateRequestCount()
                                    }
                                }
                            }
                        }
                    }
                    
                    sock?.on("cr_seat_status") { args: Array<Any>? ->
                        runOnUiThread {
                             val sData = args?.getOrNull(0) as? org.json.JSONObject
                             val pRoomId = sData?.optString("roomId") ?: ""
                             val cRoomId = intent.getStringExtra("ROOM_ID") ?: ""
                             if (pRoomId.isNotEmpty() && pRoomId != cRoomId) return@runOnUiThread

                             val action = sData?.optString("action") ?: ""
                             val targetId = sData?.optString("userId") ?: ""
                             
                             if (targetId == localUserId) {
                                 if (action == "promote") {
                                      val sId = sData?.optInt("seatId", -1) ?: -1
                                      android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                          sugunaClient.setMicrophoneEnabled(true)
                                          sugunaClient.muteAllRemoteAudio(false)
                                          sugunaClient.setSpeakerphoneEnabled(true)
                                          
                                          // Tell the Host (and everyone) that I am ready in the seat
                                          try {
                                              val confirmJson = org.json.JSONObject().apply {
                                                  put("type", "seat_confirm")
                                                  put("seat_id", sId)
                                                  put("user_id", localUserId)
                                                  put("name", localName)
                                                  put("image", localImage)
                                              }
                                              sugunaClient.publishData(confirmJson.toString())
                                          } catch(e: Exception) {}
                                      }, 1000)
                                      isMuted = false
                                      isRequestSent = false // clear request
                                      setupControls()
                                 } else if (action == "remove") {
                                      isMuted = true
                                      // Clear mute states
                                      selfMutedUsers.remove(localUserId)
                                      hostMutedUsers.remove(localUserId)
                                      sugunaClient.setMicrophoneEnabled(false)
                                      setupControls()
                                 }
                             }
                        }
                    }

                    sock?.on("cr_chat_received") { args: Array<Any>? ->
                         runOnUiThread {
                             val data = args?.getOrNull(0) as? org.json.JSONObject
                             if (data != null) {
                                  val rawTime = data.optString("time", System.currentTimeMillis().toString())
                                 val msg = ChatMessage(
                                     senderId = data.getString("userId"),
                                     name = data.getString("name"),
                                     image = data.optString("image"),
                                     message = data.getString("msg"),
                                     timestamp = getFormattedTime(rawTime)
                                 )
                                 messageAdapter.addMessage(msg)
                                 findViewById<RecyclerView>(R.id.rvMessages).scrollToPosition(messageAdapter.itemCount - 1)
                             }
                         }
                    }

                sock?.on("cr_reaction") { args ->
                     runOnUiThread {
                         val data = args?.getOrNull(0) as? org.json.JSONObject
                         if (data != null) {
                             val uId = data.getString("userId")
                             val url = data.getString("url")
                             val type = data.optString("reactionType", "Lottie")
                             seatAdapter.playReaction(uId, url, type)
                         }
                     }
                }

                    sock?.on("cr_online_count") { args: Array<Any>? ->
                         runOnUiThread {
                             val count = args?.getOrNull(0) as? Int ?: 1
                             findViewById<TextView>(R.id.tvOnlineCount).text = count.toString()
                         }
                    }

                    sock?.on("cr_seat_invite") { args: Array<Any>? ->
                         runOnUiThread {
                             val data = args?.getOrNull(0) as? org.json.JSONObject
                             val hostN = data?.optString("hostName", "Host") ?: "Host"
                             showInviteDialog(hostN)
                         }
                    }
                    
                    sock?.on("seat_invite_accept") { args: Array<Any>? ->
                         runOnUiThread {
                              if (isHostLocal) {
                                  val json = args?.getOrNull(0) as? org.json.JSONObject
                                  if (json != null) {
                                      val pRoomId = json.optString("roomId") ?: ""
                                      val cRoomId = intent.getStringExtra("ROOM_ID") ?: ""
                                      if (pRoomId.isNotEmpty() && pRoomId != cRoomId) return@runOnUiThread

                                      val senderId = json.getString("sender_id")
                                      val name = json.getString("name")
                                      val image = json.optString("image")
                                      val p = SeatParticipant(senderId, name, image)
                                      handleAcceptUser(p)
                                  }
                              }
                         }
                    }

                    sock?.on("cr_chat_history_res") { args: Array<Any>? ->
                        runOnUiThread {
                            val data = args?.getOrNull(0) as? org.json.JSONObject
                            val messages = data?.optJSONArray("messages")
                            if (messages != null && chatHistory.isEmpty()) { // Only load if locally empty to avoid duplicates
                                for (i in 0 until messages.length()) {
                                    val mObj = messages.getJSONObject(i)
                                    val msg = ChatMessage(
                                        senderId = mObj.getString("userId"),
                                        name = mObj.getString("name"),
                                        image = mObj.optString("image"),
                                        message = mObj.getString("msg"),
                                        timestamp = mObj.optString("time", "")
                                    )
                                    if (chatHistory.none { it.timestamp == msg.timestamp && it.message == msg.message }) {
                                        chatHistory.add(msg)
                                        messageAdapter.addMessage(msg)
                                    }
                                }
                                findViewById<RecyclerView>(R.id.rvMessages).scrollToPosition(messageAdapter.itemCount - 1)
                            }
                        }
                    }

                    sock?.on("cr_blocked") { args: Array<Any>? ->
                         runOnUiThread {
                             val data = args?.getOrNull(0) as? org.json.JSONObject
                             Toast.makeText(this@SugunaChatRoomActivity, data?.optString("message", "You are blocked"), Toast.LENGTH_LONG).show()
                             
                             // STOP AUDIO IMMEDIATELY
                             if (::sugunaClient.isInitialized) {
                                 sugunaClient.leaveRoom()
                             }
                             finish()
                         }
                    }

                    if (isHostLocal && promoRunnable == null) {
                         val prefs = getSharedPreferences("RoomPrefs", android.content.Context.MODE_PRIVATE)
                         prefs.edit().putLong("currentSessionStartTime", System.currentTimeMillis()).apply()

                         promoRunnable = object : Runnable {
                             override fun run() {
                                 val currentPrefs = getSharedPreferences("RoomPrefs", android.content.Context.MODE_PRIVATE)
                                 val lastTime = currentPrefs.getLong("lastPromoTime", 0L)
                                 val currentTime = System.currentTimeMillis()
                                 val joinedTime = currentPrefs.getLong("currentSessionStartTime", currentTime)

                                 if (currentTime - joinedTime >= 60000 && currentTime - lastTime >= 15 * 60 * 1000) {
                                     currentPrefs.edit().putLong("lastPromoTime", currentTime).apply()
                                     val url = "https://asia-south1-friendzone-a40d9.cloudfunctions.net/manualRoomPromotions?roomId=$localUserId"
                                     val request = okhttp3.Request.Builder().url(url).build()
                                     okhttp3.OkHttpClient().newCall(request).enqueue(object : okhttp3.Callback {
                                         override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                                         override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {}
                                     })
                                 }
                                 promoHandler.postDelayed(this, 15000) // Check frequently
                             }
                         }
                         promoHandler.postDelayed(promoRunnable!!, 60000)
                    }
                }
            }

            override fun onLocalStreamReady(videoTrack: VideoTrack) {}
            override fun onRemoteStreamReady(userId: String?, videoTrack: VideoTrack) {}
            override fun onLocalStreamUpdate(videoTrack: VideoTrack, isMuted: Boolean) {}
            override fun onRemoteStreamUpdate(userId: String?, videoTrack: VideoTrack, isMuted: Boolean) {}

            override fun onUserJoined(participant: io.livekit.android.room.participant.RemoteParticipant) {
                runOnUiThread {
                    val pId = participant.identity?.value ?: ""
                    val isHost = pId == roomOwnerId
                    
                    // Clear stale mute states for safely re-joined users
                    if (isHostLocal) {
                        selfMutedUsers.remove(pId)
                        hostMutedUsers.remove(pId)
                    }
                    
                    var pImage = ""
                    try {
                        val meta = org.json.JSONObject(participant.metadata ?: "{}")
                        pImage = meta.optString("image")
                    } catch (e: Exception) {}

                    val p = SeatParticipant(pId, participant.name ?: "User", pImage, isHost = isHost)
                    
                    val idx = listParticipants.indexOfFirst { it.id == pId }
                    if (idx == -1) listParticipants.add(p) else listParticipants[idx] = p

                    // 🔊 AUDIO RECOVERY: Ensure we are NOT muting remote audio when someone joins
                    // especially if we are the Host.
                    sugunaClient.muteAllRemoteAudio(false)

                    updateSeats()
                    updateOnlineCount()
                    setupControls()
                    updateRoomOwnerImage()

                    if (isHostLocal) {
                        broadcastSeatState()
                        // Double broadcast strategy for high reliability (async data channel readiness)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            broadcastSeatState()
                        }, 2500)
                    } else {
                        // Request state explicitly when anyone joins (since it could be the Host)
                        try {
                             val jState = JSONObject().apply { put("type", "seat_state_request") }
                             sugunaClient.publishData(jState.toString())
                        } catch (e: Exception) {}
                    }

                    val isSeatedLocal = seatedUsers.values.any { it.id == localUserId }
                    if (isSeatedLocal) {
                         val mySeat = seatedUsers.entries.find { it.value.id == localUserId }?.key ?: -1
                         if (mySeat != -1) {
                              val confirmJson = JSONObject().apply {
                                   put("type", "seat_confirm")
                                   put("seat_id", mySeat)
                                   put("user_id", localUserId)
                                   put("name", localName)
                                   put("image", localImage)
                              }
                              sugunaClient.publishData(confirmJson.toString())
                         }
                    }
                }
            }

            override fun onActiveSpeakerChanged(speakers: List<String>) {
                runOnUiThread {
                    speakerIds = speakers
                    // Use incremental updates to prevent reaction disruption
                    seatAdapter.updateSpeakingStates(speakers)
                }
            }

            override fun onDataReceived(data: String) {
                runOnUiThread {
                    android.util.Log.d("SugunaSignal", "Data Received: $data")
                    try {
                        val json = JSONObject(data)
                        if (json.has("type")) {
                            when (json.getString("type")) {
                                "chat" -> {
                                    val rawTime = json.optString("time", System.currentTimeMillis().toString())
                                    val msg = ChatMessage(
                                        senderId = json.getString("sender_id"),
                                        name = json.getString("name"),
                                        image = json.getString("image"),
                                        message = json.getString("msg"),
                                        timestamp = getFormattedTime(rawTime)
                                    )
                                    messageAdapter.addMessage(msg)
                                    chatHistory.add(msg) // Add to history
                                    findViewById<RecyclerView>(R.id.rvMessages).scrollToPosition(messageAdapter.itemCount - 1)
                                }
                                // Seat state handled below to prevent duplication
                                "mic_update" -> {
                                    val uId = json.getString("user_id")
                                    val isMuted = json.getBoolean("is_muted")
                                    if (isMuted) selfMutedUsers.add(uId) else selfMutedUsers.remove(uId)
                                    updateSeats()
                                    // 🔥 IF I AM HOST, broadcast this change to everyone so late joiners see it
                                    if (isHostLocal) broadcastSeatState()
                                }
                                "chat_history" -> {
                                    val hArr = json.getJSONArray("messages")
                                    if (chatHistory.isEmpty()) {
                                         for (i in 0 until hArr.length()) {
                                             val obj = hArr.getJSONObject(i)
                                             val rawT = obj.optString("time", System.currentTimeMillis().toString())
                                             val msg = ChatMessage(obj.getString("sender_id"), obj.getString("name"), obj.optString("image"), obj.getString("msg"), getFormattedTime(rawT))
                                             messageAdapter.addMessage(msg)
                                             chatHistory.add(msg)
                                         }
                                         findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvMessages).scrollToPosition(messageAdapter.itemCount - 1)
                                    }
                                }
                                "clear_messages" -> {
                                    messageAdapter.clearMessages()
                                    chatHistory.clear()
                                }
                                "cr_reaction" -> {
                                      val uId = json.getString("userId")
                                      val url = json.getString("url")
                                      val type = json.optString("reactionType", "Lottie")
                                      seatAdapter.playReaction(uId, url, type)
                                }
                                "cr_gift_anim" -> {
                                    val giftUrl = json.getString("gift_url")
                                    val giftName = json.optString("gift_name", "Gift")
                                    val sName = json.optString("sender_name", "Someone")
                                    val count = json.optInt("count", 1)
                                    val rIds = json.getJSONArray("receiver_ids")
                                    
                                    // Hint for everyone in the room (Vibration only as requested)
                                    vibrateDevice()
                                    
                                    for (i in 0 until rIds.length()) {
                                        val rid = rIds.getString(i)
                                        startActualAnimation(giftUrl, rid, findViewById(R.id.animationOverlay))
                                    }
                                }
                                "chat_history_request" -> {
                                     if (isHostLocal) {
                                          broadcastChatHistory()
                                     }
                                }
                                "seat_state_request" -> {
                                     if (isHostLocal) {
                                          broadcastSeatState()
                                     }
                                }
                                 "music_play" -> {
                                      if (isFinishing || isDestroyed) return@runOnUiThread
                                      val name = json.getString("name")
                                      val art = json.optString("art")
                                      val duration = json.getLong("duration")
                                      val audioUrl = json.optString("url")
                                      showMusicOverlay(name, art, duration)
                                      
                                      if (!isHostLocal && !audioUrl.isNullOrEmpty()) {
                                          playRemoteMusicForAudience(audioUrl, 0)
                                      }
                                 }
                                 "music_sync" -> {
                                      if (isFinishing || isDestroyed) return@runOnUiThread
                                      val progress = json.getInt("progress")
                                      findViewById<SeekBar>(R.id.sbOverlayProgress).progress = progress
                                      
                                      // Handle Late Joiners seamlessly
                                      if (!isHostLocal && mediaPlayer == null) {
                                          val syncUrl = json.optString("url")
                                          val currentPos = json.optInt("currentPosition", 0)
                                          if (!syncUrl.isNullOrEmpty()) {
                                              playRemoteMusicForAudience(syncUrl, currentPos)
                                          }
                                      }
                                      
                                      // Handle Pause state from Host sync
                                      if (!isHostLocal && mediaPlayer != null) {
                                          val isHostPlaying = json.optBoolean("isPlaying", true)
                                          if (isHostPlaying && mediaPlayer?.isPlaying == false) {
                                              mediaPlayer?.start()
                                          } else if (!isHostPlaying && mediaPlayer?.isPlaying == true) {
                                              mediaPlayer?.pause()
                                          }
                                      }
                                 }
                                 "music_pause" -> {
                                      if (isFinishing || isDestroyed) return@runOnUiThread
                                      if (!isHostLocal) mediaPlayer?.pause()
                                 }
                                 "music_resume" -> {
                                      if (isFinishing || isDestroyed) return@runOnUiThread
                                      if (!isHostLocal) mediaPlayer?.start()
                                 }
                                 "music_stop" -> {
                                      findViewById<View>(R.id.musicOverlay).visibility = View.GONE
                                      if (!isHostLocal) {
                                           mediaPlayer?.stop()
                                           mediaPlayer?.release()
                                           mediaPlayer = null
                                      }
                                 }
                                "seat_invite" -> {
                                    val targetId = json.getString("target_id")
                                    if (localUserId == targetId) {
                                        val hostName = json.optString("host_name", "Host")
                                        showInviteDialog(hostName)
                                    }
                                }
                                "seat_invite_accept" -> {
                                    if (isHostLocal) {
                                        val senderId = json.getString("sender_id")
                                        val name = json.getString("name")
                                        val image = json.getString("image")
                                        val p = SeatParticipant(senderId, name, image)
                                        handleAcceptUser(p)
                                    }
                                }
                                "seat_request" -> {
                                    if (isHostLocal) {
                                        val reqU = SeatParticipant(json.getString("sender_id"), json.getString("name"), json.optString("image"))
                                        if (!requestList.any { it.id == reqU.id }) {
                                            requestList.add(reqU)
                                            updateRequestCount()
                                        }
                                    }
                                }
                                 "seat_accept" -> {
                                     val targetId = json.getString("target_id")
                                     if (localUserId == targetId) {
                                         android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                             sugunaClient.setMicrophoneEnabled(true)
                                         }, 1000)
                                         isMuted = false
                                         setupControls()
                                     }
                                 }
                                "seat_reject" -> {
                                    val targetId = json.getString("target_id")
                                    if (localUserId == targetId) {
                                        isRequestSent = false
                                        setupControls()
                                        updateSeats()
                                    }
                                    if (isHostLocal) {
                                        invitedUsers.remove(targetId)
                                    }
                                }
                                "seat_remove" -> {
                                    val targetId = json.getString("target_id")
                                    if (localUserId == targetId) {
                                        isRequestSent = false
                                        isMuted = true
                                        seatedUsers.values.removeIf { it.id == localUserId }
                                        setupControls() // Refresh button
                                        sugunaClient.setMicrophoneEnabled(false)
                                        updateSeats()
                                    }
                                }
                                "force_exit" -> {
                                    val targetId = json.optString("target_id")
                                    if (localUserId == targetId) {
                                        Toast.makeText(this@SugunaChatRoomActivity, "You have been removed from the room by the Host.", Toast.LENGTH_LONG).show()
                                         // STOP AUDIO IMMEDIATELY
                                         if (::sugunaClient.isInitialized) {
                                             sugunaClient.leaveRoom()
                                         }
                                         finish()
                                    }
                                }
                                 "seat_confirm" -> {
                                      if (isHostLocal) {
                                           val targetId = json.getString("user_id")
                                           invitedUsers.remove(targetId) // Clear invited status since they sat down
                                           val sId = json.getInt("seat_id")
                                           val u = SeatParticipant(
                                                targetId,
                                                json.getString("name"),
                                                json.optString("image")
                                           )
                                           // Remove user from any other seats first to prevent duplicates
                                           seatedUsers.entries.removeIf { it.value.id == targetId }
                                           seatedUsers[sId] = u
                                           shiftSeats() // Ensure no gaps if they confirmed a weird ID
                                           updateSeats()
                                           broadcastSeatState()
                                      }
                                 }
                                 "seat_state" -> {
                                     val isHostOnline = listParticipants.any { it.id == roomOwnerId } || isHostLocal
                                     if (!isHostLocal && isHostOnline) {
                                         val sArray = json.getJSONArray("seats")
                                         seatedUsers.clear()
                                         for (i in 0 until sArray.length()) {
                                             val obj = sArray.getJSONObject(i)
                                             val sId = obj.getInt("seat_id")
                                             val u = SeatParticipant(obj.getString("user_id"), obj.getString("name"), obj.optString("image"))
                                             seatedUsers[sId] = u
                                         }

                                         // Forced Mutes
                                         val muteArr = json.optJSONArray("host_muted_ids")
                                         hostMutedUsers.clear()
                                         if (muteArr != null) {
                                             for (j in 0 until muteArr.length()) hostMutedUsers.add(muteArr.getString(j))
                                         }

                                         val selfMuteArr = json.optJSONArray("self_muted_ids")
                                         selfMutedUsers.clear()
                                         if (selfMuteArr != null) {
                                             for (j in 0 until selfMuteArr.length()) selfMutedUsers.add(selfMuteArr.getString(j))
                                         }

                                         val isSeatedLocal = seatedUsers.values.any { it.id == localUserId }

                                         if (hostMutedUsers.contains(localUserId)) {
                                             if (!isMuted) {
                                                 isMuted = true
                                                 sugunaClient.setMicrophoneEnabled(false)
                                             }
                                          } else if (isSeatedLocal && isHostOnline) {
                                              val isSelfMuted = selfMutedUsers.contains(localUserId)
                                              if (!isSelfMuted && isMuted) {
                                                   isMuted = false
                                                   sugunaClient.setMicrophoneEnabled(true)
                                              }
                                          } else {
                                              // Not seated OR Host is offline
                                              if (!isMuted) {
                                                   isMuted = true
                                                   sugunaClient.setMicrophoneEnabled(false)
                                              }
                                           }

                                           updateSeats()
                                           setupControls()
                                           updateRoomOwnerImage()
                                      }
                                  }
                                 "seat_leave" -> {
                                      val targetId = json.getString("user_id")
                                      if (isHostLocal) {
                                           invitedUsers.remove(targetId)
                                      }
                                      val removed = seatedUsers.values.removeIf { it.id == targetId }
                                      if (removed && isHostLocal) {
                                          hostMutedUsers.remove(targetId)
                                          selfMutedUsers.remove(targetId)
                                          shiftSeats()
                                          broadcastSeatState()
                                      }
                                      updateSeats()
                                 }
                                 "user_left_room" -> {
                                      val targetId = json.getString("user_id")
                                      listParticipants.removeAll { it.id == targetId }
                                      if (isHostLocal) {
                                           invitedUsers.remove(targetId)
                                           requestList.removeAll { it.id == targetId }
                                           updateRequestCount()
                                           hostMutedUsers.remove(targetId) // Clear forced mute on leave
                                           val removed = seatedUsers.values.removeIf { it.id == targetId }
                                           if (removed) {
                                                shiftSeats()
                                                broadcastSeatState()
                                           }
                                      }
                                      selfMutedUsers.remove(targetId) // Clear self mute state
                                      updateSeats()
                                      updateOnlineCount()
                                      setupControls()
                                 }
                                 "invite_reject" -> {
                                      if (isHostLocal) {
                                           val senderId = json.getString("sender_id")
                                           invitedUsers.remove(senderId)
                                           // Also update the bottom sheet if it's currently open
                                           val bs = supportFragmentManager.findFragmentByTag("OnlineUsersBottomSheet")
                                           // It'll refresh next time they open it
                                      }
                                 }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SugunaSignal", "Data Parse Error: ${e.message}", e)
                        e.printStackTrace()
                    }
                }
            }

            override fun onUserLeft(userId: String?) {
                runOnUiThread {
                    listParticipants.removeAll { it.id == userId }
                    
                    if (isHostLocal && userId != null) {
                        hostMutedUsers.remove(userId)
                        requestList.removeAll { it.id == userId }
                        updateRequestCount()
                        val removed = seatedUsers.values.removeIf { it.id == userId }
                        if (removed) {
                             shiftSeats()
                             broadcastSeatState()
                        }
                    }
                    if (userId != null) selfMutedUsers.remove(userId)

                     updateSeats()
                     updateOnlineCount()
                     setupControls()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@SugunaChatRoomActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        })

        val role = if (isHostLocal) SugunaClient.ROLE_HOST else SugunaClient.ROLE_AUDIENCE
        sugunaClient.initialize(token, role, isVideoCall = false, defaultSpeakerOn = true)
        updateSeats() // Initial update

        // Back Press Custom Dialog
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showLeaveDialog()
            }
        })
    }

    private fun showLeaveDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_leave_room, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
            
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        tvTitle.text = findViewById<TextView>(R.id.tvRoomName).text.toString()
        
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMinimize).setOnClickListener { 
            moveTaskToBack(true)
            dialog.dismiss() 
        }
        
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLeave).setOnClickListener { 
            isExplicitLeave = true
            if (!isHostLocal) {
                try {
                    val leaveJson = org.json.JSONObject().apply {
                        put("type", "user_left_room")
                        put("user_id", localUserId)
                    }
                    sugunaClient.publishData(leaveJson.toString())
                } catch (e: Exception) {}
            }
            finish()
            dialog.dismiss() 
        }

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnKeep).setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun setupSeatsRecyclerView() {
        val rvSeats = findViewById<RecyclerView>(R.id.rvSeats)
        
        val span = 4 // absolute standard 4 column grid
        val gridLayoutManager = GridLayoutManager(this, span)
        
        rvSeats.layoutManager = gridLayoutManager
        rvSeats.adapter = seatAdapter
        
        seatAdapter.setOnSeatClickListener(object : SugunaChatRoomSeatAdapter.OnSeatClickListener {
            override fun onSeatClick(position: Int, seat: SeatParticipant) {
                if (seat.id == "request_seat") {
                     showRequestsBottomSheet()
                } else if (seat.id.startsWith("audience_request_")) {
                     if (seat.image != "SENT") {
                         sendSeatRequest()
                         isRequestSent = true
                         updateSeats()
                         setupControls()
                     }
                } else if (!seat.id.startsWith("id_")) {
                     showSeatControlBottomSheet(position, seat)
                }
            }
        })
    }

    private fun setupMessagesRecyclerView() {
        val rvMessages = findViewById<RecyclerView>(R.id.rvMessages)
        rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true // Scroll to bottom on new items
        }
        rvMessages.adapter = messageAdapter
    }

    private fun setupTrendingGiftsBar() {
        fetchTrendingGifts()
    }

    private fun fetchTrendingGifts() {
        val rvTrending = findViewById<RecyclerView>(R.id.rvTrendingGifts)
        val bar = findViewById<android.view.View>(R.id.trendingGiftsBar)

        FirebaseDatabase.getInstance().reference.child("VirtualGifts")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val allGifts = mutableListOf<GiftModel>()
                    for (giftSnap in snapshot.children) {
                        val rawUrl = giftSnap.child("Url").value?.toString() ?: ""
                        val rawType = giftSnap.child("Type").value?.toString() ?: ""
                        val id = Encryption.decrypt(giftSnap.child("ID").value?.toString() ?: "") ?: giftSnap.child("ID").value?.toString() ?: ""
                        val name = Encryption.decrypt(giftSnap.child("Name").value?.toString() ?: "") ?: giftSnap.child("Name").value?.toString() ?: "Gift"
                        val url = Encryption.decrypt(rawUrl) ?: rawUrl
                        val type = Encryption.decrypt(rawType) ?: rawType ?: "Static"
                        val priceStr = Encryption.decrypt(giftSnap.child("Price").value?.toString() ?: "") ?: giftSnap.child("Price").value?.toString() ?: "0"
                        val price = priceStr.toIntOrNull() ?: 0
                        
                        allGifts.add(GiftModel(id, name, url, price, type))
                    }
                    
                    if (allGifts.isNotEmpty()) {
                        fetchTrendingLogic(allGifts, bar, rvTrending)
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    bar.visibility = View.GONE
                }
            })
    }

    private fun fetchTrendingLogic(allGifts: List<GiftModel>, bar: View, rv: RecyclerView) {
        val dates = listOf(getFormattedDate(0), getFormattedDate(-1), getFormattedDate(-2))
        val trendingCounts = mutableMapOf<String, Int>()
        var loaded = 0
        
        for (date in dates) {
            FirebaseDatabase.getInstance().reference.child("GlobalGiftingStats").child(date)
                .get().addOnSuccessListener { snapshot ->
                    for (giftSnap in snapshot.children) {
                        val gid = giftSnap.key ?: continue
                        val count = giftSnap.getValue(Int::class.java) ?: 0
                        trendingCounts[gid] = (trendingCounts[gid] ?: 0) + count
                    }
                    loaded++
                    if (loaded == 3) finalizeTrending(allGifts, trendingCounts, bar, rv)
                }.addOnFailureListener {
                    loaded++
                    if (loaded == 3) finalizeTrending(allGifts, trendingCounts, bar, rv)
                }
        }
    }

    private fun finalizeTrending(allGifts: List<GiftModel>, counts: Map<String, Int>, bar: View, rv: RecyclerView) {
        val trendingGifts = allGifts.filter { counts.containsKey(it.id) }
            .sortedByDescending { counts[it.id] ?: 0 }
            .take(15)

        if (trendingGifts.isNotEmpty()) {
            bar.visibility = View.VISIBLE
            rv.adapter = TrendingGiftAdapter(trendingGifts) { gift ->
                showQuickGiftSheet(gift)
            }
        } else {
            bar.visibility = View.GONE
        }
    }

    private fun getFormattedDate(daysOffset: Int): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, daysOffset)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return dateFormat.format(calendar.time)
    }

    private fun showQuickGiftSheet(gift: GiftModel) {
        val allReceivers = seatedUsers.values.toMutableList()
        // Ensure Host is always in the list for gifting
        if (!allReceivers.any { it.id == roomOwnerId }) {
            allReceivers.add(0, SeatParticipant(roomOwnerId, roomOwnerName, roomOwnerImage))
        }
        
        // Anti-Self Gifting: User cannot gift themselves
        allReceivers.removeAll { it.id == localUserId }

        val sheet = QuickGiftBottomSheet(gift, allReceivers, roomOwnerId) { g, rIds, count ->
            // 1. Local Coin Check
            val totalPrice = g.price * count * rIds.size
            FirebaseDatabase.getInstance().reference.child("Wallet").child("CoinBalance").child(localUserId)
                .child("RechargeCoins").get().addOnSuccessListener { snapshot ->
                    val balance = if (snapshot.exists()) {
                        val enc = snapshot.getValue(String::class.java) ?: ""
                        (com.suguna.rtc.utils.Encryption.decrypt(enc) ?: "0").toIntOrNull() ?: 0
                    } else 0

                    if (balance < totalPrice) {
                        Toast.makeText(this@SugunaChatRoomActivity, "Not enough coins! Please recharge.", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    // 2. Cloud Function for financials (Coins & Beans)
                    val functions = com.google.firebase.functions.FirebaseFunctions.getInstance("asia-south1")
                    val data = hashMapOf(
                        "receiverIds" to rIds,
                        "giftId" to g.id,
                        "count" to count,
                        "isRoom" to true,
                        "roomId" to (this@SugunaChatRoomActivity.intent.getStringExtra("ROOM_ID") ?: ""),
                        "giftContext" to "Room"
                    )
 
                    functions.getHttpsCallable("sendVirtualGift")
                        .call(data)
                        .addOnCompleteListener { task: com.google.android.gms.tasks.Task<com.google.firebase.functions.HttpsCallableResult> ->
                            if (isFinishing || isDestroyed) return@addOnCompleteListener
                            if (task.isSuccessful) {
                                // SUCCESS: Now trigger Animations and Feedback
                                vibrateDevice()
                                
                                // Broadcast ANIMATION to Everyone in Room
                                try {
                                    val animJson = org.json.JSONObject().apply {
                                        put("type", "cr_gift_anim")
                                        put("gift_url", g.image)
                                        put("gift_name", g.name)
                                        put("sender_name", localName)
                                        put("count", count)
                                        val rArray = org.json.JSONArray()
                                        rIds.forEach { rArray.put(it) }
                                        put("receiver_ids", rArray)
                                    }
                                    sugunaClient.publishData(animJson.toString())
                                } catch (e: Exception) {}

                                // Trigger Local Animation for Self
                                rIds.forEach { rid ->
                                    startActualAnimation(g.image, rid, findViewById(R.id.animationOverlay))
                                }
                            } else {
                                Toast.makeText(this@SugunaChatRoomActivity, "Error: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    
                    // 3. Chat Message (SDK internal sync)
                    val msg = if (rIds.size > 1) "Sent $count x ${g.name} to ${rIds.size} users" else "Sent $count x ${g.name}"
                    com.suguna.rtc.utils.SocketManager.crChat(this@SugunaChatRoomActivity.intent.getStringExtra("ROOM_ID") ?: "", localUserId, localName, localImage, msg)
                }
        }
        sheet.show(supportFragmentManager, "QuickGift")
    }

    private fun setupMessageSender() {
        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnSend = findViewById<android.widget.ImageButton>(R.id.btnSendMessage)

        etMessage.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                updateBottomBarVisibility()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendChatMessage(text)
                etMessage.setText("")
                hideKeyboard()
                updateBottomBarVisibility()
            } else {
                Toast.makeText(this, "Please enter a message!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun sendChatMessage(text: String) {
        val rId = intent.getStringExtra("ROOM_ID") ?: ""
        val isHostOnline = listParticipants.any { it.id == roomOwnerId } || isHostLocal
        
        if (!isHostOnline) {
             Toast.makeText(this, "Host is offline. Cannot send messages.", Toast.LENGTH_SHORT).show()
             return
        }
        
        com.suguna.rtc.utils.SocketManager.crChat(rId, localUserId, localName, localImage, text)
    }

    private fun updateRoomOwnerImage() {
        val ivRoomOwner = findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivRoomOwner)
        val host = listParticipants.find { it.id == roomOwnerId }
        if (host != null && !host.image.isNullOrEmpty()) {
             roomOwnerImage = host.image
             com.bumptech.glide.Glide.with(this).load(host.image).into(ivRoomOwner)
        }
    }

    private fun setupControls() {
        val btnOnlineCountSub = findViewById<android.view.View>(R.id.btnOnlineCountSub)
        btnOnlineCountSub?.setOnClickListener {
            showOnlineUsersDialog()
        }

        findViewById<View>(R.id.btnMenu)?.setOnClickListener {
            showChatRoomMenu()
        }

        val btnViewRequests = findViewById<android.view.View>(R.id.btnViewRequests)
        val btnRequestSeat = findViewById<android.view.View>(R.id.btnRequestSeat)
        val btnReactions = findViewById<android.widget.ImageButton>(R.id.btnReactions)
        val btnMusicSelection = findViewById<android.widget.ImageButton>(R.id.btnMusicSelection)

        btnViewRequests?.visibility = View.GONE
        btnRequestSeat?.visibility = View.GONE
        btnMusicSelection?.visibility = if (isHostLocal) View.VISIBLE else View.GONE
        
        updateBottomBarVisibility()

        btnReactions?.setOnClickListener {
             showReactionsBottomSheet()
        }

        btnMusicSelection?.setOnClickListener {
            val intent = Intent(this, MusicSelectionActivity::class.java)
            // Use MediaPlayer's Current Path or pass the stored URI
            intent.putExtra("CURRENT_PLAYING_URI", currentPlayingUri) 
            startActivity(intent)
        }

        findViewById<android.widget.ImageButton>(R.id.btnGift)?.setOnClickListener {
            val seatedIds = ArrayList<String>()
            val seatedNames = ArrayList<String>()
            val seatedImages = ArrayList<String>()
            
            // 1. ALWAYS ADD HOST/OWNER FIRST
            if (!roomOwnerId.isNullOrEmpty()) {
                val host = listParticipants.find { it.id == roomOwnerId }
                val hostName = host?.name ?: roomOwnerName ?: "Host"
                
                seatedIds.add(roomOwnerId)
                seatedNames.add(hostName)
                seatedImages.add(host?.image ?: "")
            }

            // 2. Add other seated users
            for (p in seatedUsers.values) {
                if (p.id != roomOwnerId) { // Prevent duplicates
                    seatedIds.add(p.id)
                    seatedNames.add(p.name)
                    seatedImages.add(p.image ?: "")
                }
            }
            
            val intent = android.content.Intent("com.suguna.rtc.ACTION_SHOW_GIFTS").apply {
                putExtra("RECEIVER_ID", roomOwnerId)
                putExtra("IS_ROOM", true)
                putExtra("ROOM_ID", this@SugunaChatRoomActivity.intent.getStringExtra("ROOM_ID") ?: roomOwnerId)
                putExtra("CONTEXT", "SugunaRoom")
                putStringArrayListExtra("PARTICIPANT_IDS", seatedIds)
                putStringArrayListExtra("PARTICIPANT_NAMES", seatedNames)
                putStringArrayListExtra("PARTICIPANT_IMAGES", seatedImages)
            }
            sendBroadcast(intent)
        }

        if (isHostLocal) {
            btnViewRequests?.visibility = View.VISIBLE
            btnViewRequests?.setOnClickListener { showRequestsBottomSheet() }
            updateRequestCount()
        } else {
            val hOnline = listParticipants.any { it.id == roomOwnerId }
            val sLocal = seatedUsers.values.any { it.id == localUserId }
            btnRequestSeat?.visibility = if (!sLocal && hOnline && !isRequestSent) View.VISIBLE else View.GONE
            btnRequestSeat?.setOnClickListener { 
                sendSeatRequest() 
                isRequestSent = true
                it.visibility = View.GONE
                updateSeats()
            }
        }
    }

    private fun updateBottomBarVisibility() {
        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnSend = findViewById<android.widget.ImageButton>(R.id.btnSendMessage)
        val btnReactions = findViewById<android.widget.ImageButton>(R.id.btnReactions)
        
        val isNotEmpty = etMessage?.text?.toString()?.trim()?.isNotEmpty() == true
        val isSeated = seatedUsers.values.any { it.id == localUserId } || isHostLocal
        
        btnSend?.visibility = if (isNotEmpty) View.VISIBLE else View.GONE
        btnReactions?.visibility = if (isSeated && !isNotEmpty) View.VISIBLE else View.GONE
    }

    private fun showReactionsBottomSheet() {
        val rId = intent.getStringExtra("ROOM_ID") ?: ""
        ReactionsBottomSheet { reaction: ReactionModel ->
            if (seatAdapter.isReactionActive(localUserId)) {
                Toast.makeText(this, "Please wait for current reaction to finish!", Toast.LENGTH_SHORT).show()
                return@ReactionsBottomSheet
            }
            
            val json = org.json.JSONObject().apply {
                put("type", "cr_reaction")
                put("userId", localUserId)
                put("url", reaction.url)
                put("reactionType", reaction.type)
            }
            sugunaClient.publishData(json.toString())
            
            // Sync via Socket for reliability
            val sock = com.suguna.rtc.utils.SocketManager.getSocket()
            val sockData = org.json.JSONObject().apply {
                put("roomId", rId)
                put("userId", localUserId)
                put("url", reaction.url)
                put("reactionType", reaction.type) // Consistent field name
            }
            sock?.emit("cr_reaction", sockData)

            // Local Play
            seatAdapter.playReaction(localUserId, reaction.url, reaction.type)
        }.show(supportFragmentManager, "Reactions")
    }

    private fun getFormattedTime(rawTime: String): String {
        return try {
            var longTime = rawTime.toLongOrNull() ?: return rawTime
            // Handle seconds vs milliseconds
            if (longTime < 20000000000L) { // Likely seconds (e.g. 1711800000)
                longTime *= 1000
            }
            
            val date = java.util.Date(longTime)
            val now = System.currentTimeMillis()
            
            val calendar = java.util.Calendar.getInstance()
            val today = calendar.get(java.util.Calendar.DAY_OF_YEAR)
            val year = calendar.get(java.util.Calendar.YEAR)
            
            calendar.time = date
            val msgDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
            val msgYear = calendar.get(java.util.Calendar.YEAR)
            
            val sdfTime = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            
            if (today == msgDay && year == msgYear) {
                sdfTime.format(date)
            } else if ((today - msgDay == 1 || (today == 1 && msgDay > 360)) && year == msgYear) {
                "Yesterday ${sdfTime.format(date)}"
            } else {
                val diff = now - longTime
                val days = diff / (1000 * 60 * 60 * 24)
                if (days < 7) {
                    val sdfDay = java.text.SimpleDateFormat("EEEE h:mm a", java.util.Locale.getDefault())
                    sdfDay.format(date)
                } else {
                    val sdfDate = java.text.SimpleDateFormat("dd/MM/yyyy h:mm a", java.util.Locale.getDefault())
                    sdfDate.format(date)
                }
            }
        } catch (e: Exception) {
            rawTime
        }
    }

    private fun showOnlineUsersDialog() {
        val rId = intent.getStringExtra("ROOM_ID") ?: ""
        OnlineUsersBottomSheet(
            this, listParticipants, isHostLocal, localUserId, localName, localImage, roomOwnerId, seatedUsers,
            rId,
            roomOwnerName,
            invitedUserIds = invitedUsers,
            onInviteSent = { targetUser: SeatParticipant ->
                invitedUsers.add(targetUser.id)
                com.suguna.rtc.utils.SocketManager.crInvite(rId, targetUser.id, localName)
                try {
                    val pJson = org.json.JSONObject().apply {
                        put("type", "seat_invite")
                        put("target_id", targetUser.id)
                        put("host_name", localName)
                    }
                    sugunaClient.publishData(pJson.toString())
                } catch(e: Exception) {}
                Toast.makeText(this, "Invitation sent!", Toast.LENGTH_SHORT).show()
            },
            onBlockUser = { targetUser: SeatParticipant ->
                if (seatedUsers.values.any { it.id == targetUser.id }) {
                    com.suguna.rtc.utils.SocketManager.crSeatAction(rId, "remove", targetUser.id, targetUser.name, targetUser.image ?: "", -1)
                }
                try {
                    val kickJson = org.json.JSONObject().apply {
                        put("type", "force_exit")
                        put("target_id", targetUser.id)
                    }
                    sugunaClient.publishData(kickJson.toString())
                } catch (e: Exception) {}
                com.suguna.rtc.utils.SocketManager.crBlockUser(rId, targetUser.id, targetUser.name, targetUser.image)
                Toast.makeText(this, "User blocked!", Toast.LENGTH_SHORT).show()
            }
        ).show()
    }

    private fun showInviteDialog(hostName: String) {
        if (isFinishing || isDestroyed || isInviteDialogShowing) return
        val rId = intent.getStringExtra("ROOM_ID") ?: ""
        val host = listParticipants.find { it.id == roomOwnerId }
        val hostImage = host?.image ?: ""

        try {
            SeatInviteDialog(
                this, hostName, hostImage,
                onAccept = {
                    isInviteDialogShowing = false
                    com.suguna.rtc.utils.SocketManager.crInviteAccept(rId, localUserId, localName, localImage, roomOwnerId)
                },
                onReject = {
                    isInviteDialogShowing = false
                    com.suguna.rtc.utils.SocketManager.crSeatAction(rId, "reject", localUserId, localName, localImage, -1)
                    try {
                        val rejectJson = org.json.JSONObject().apply {
                            put("type", "invite_reject")
                            put("sender_id", localUserId)
                        }
                        sugunaClient.publishData(rejectJson.toString())
                    } catch (e: Exception) {}
                }
            ).show()
            isInviteDialogShowing = true
        } catch (e: Exception) {
            isInviteDialogShowing = false
            android.util.Log.e("SugunaChatRoom", "Failed to show invite dialog: ${e.message}")
        }
    }

    private fun updateSeats() {
        // FORCE EXACTLY 7 audience seats yielding exactly 8 absolute grid nodes (1 Host + 7 placeholders)
        seatManager.generateSeats(7)
        val seats = seatManager.getSeats().toMutableList()
        
        // 1. Position 0 is ALWAYS the HOST (Room Owner)
        val hostFromList = listParticipants.find { it.id == roomOwnerId }
        
        if (isHostLocal) {
             seats[0] = seats[0].copy(
                 id = localUserId, name = localName, image = localImage, 
                 isSpeaking = speakerIds.contains(localUserId),
                 isMuted = selfMutedUsers.contains(localUserId) || hostMutedUsers.contains(localUserId)
             )
        } else if (hostFromList != null) {
             seats[0] = seats[0].copy(
                 id = hostFromList.id, name = hostFromList.name, image = hostFromList.image, 
                 isSpeaking = speakerIds.contains(roomOwnerId),
                 isMuted = selfMutedUsers.contains(roomOwnerId) || hostMutedUsers.contains(roomOwnerId)
             )
        } else {
             // Fallback for Host (Room Owner)
             seats[0] = seats[0].copy(
                 id = roomOwnerId,
                 name = roomOwnerName,
                 image = "", 
                 isMuted = true
             )
        }

        // 2. Map seated audience items sequentially
        val activeSeatedUsers = seatedUsers.values.toMutableList()
        
        // Position audience sequentially from the seatedUsers map
        val isSeatedLocal = activeSeatedUsers.any { it.id == localUserId }
        
        var foundEmpty = false
        var nextAvailableIndex = 1

        // Fill seated users
        for (i in 0 until activeSeatedUsers.size) {
            val idx = i + 1
            if (idx < seats.size) {
                val seatedUser = activeSeatedUsers[i]
                seats[idx] = seats[idx].copy(
                    id = seatedUser.id,
                    name = seatedUser.name,
                    image = seatedUser.image ?: "",
                    isSpeaking = speakerIds.contains(seatedUser.id),
                    isMuted = selfMutedUsers.contains(seatedUser.id) || hostMutedUsers.contains(seatedUser.id)
                )
                nextAvailableIndex = idx + 1
            }
        }

        // Add the single "Request" seat exactly after the last occupied seat
        if (nextAvailableIndex < seats.size) {
             foundEmpty = true
             if (isHostLocal) {
                 seats[nextAvailableIndex] = seats[nextAvailableIndex].copy(
                     id = "request_seat",
                     name = "Requests (${requestList.size})",
                     image = ""
                 )
             } else {
                 val isHostOnline = listParticipants.any { it.id == roomOwnerId } || isHostLocal
                  
                 if (!isSeatedLocal && isHostOnline) {
                      seats[nextAvailableIndex] = seats[nextAvailableIndex].copy(
                          id = "audience_request_$nextAvailableIndex",
                          name = "Request Seat",
                          image = if (isRequestSent) "SENT" else ""
                      )
                 }
             }
             nextAvailableIndex++
        }

        // Fill any remaining slots as empty placeholders
        for (i in nextAvailableIndex until seats.size) {
             seats[i] = seats[i].copy(
                 id = "id_$i",
                 name = "Seat $i",
                 image = ""
             )
        }

        seatAdapter.setSeats(seats)
        updateBottomBarVisibility()
    }

    // Keep active speaker list to use globally
    private var speakerIds = listOf<String>()
    
    // Updates
    private fun updateOnlineCount() {
        val count = listParticipants.size + 1
        findViewById<TextView>(R.id.tvOnlineCount).text = "$count"
        findViewById<TextView>(R.id.tvOnlineCountSub).text = "Online: $count"
        
        if (isHostLocal && !isFinishing && !isDestroyed) {
             try {
                  val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("friendzone")
                  db.collection("BestieRooms").document(localUserId).update(
                      mapOf("onlineCount" to count, "status" to "Active")
                  )
             } catch (e: Exception) {}
        }
    }

    private fun sendSeatRequest() {
        val rId = intent.getStringExtra("ROOM_ID") ?: ""
        com.suguna.rtc.utils.SocketManager.crSeatRequest(rId, localUserId, localName, localImage, roomOwnerId)
        Toast.makeText(this, "Seat request sent!", Toast.LENGTH_SHORT).show()
    }

    private fun updateRequestCount() {
        findViewById<TextView>(R.id.tvRequestCount).text = "Requests (${requestList.size})"
        updateSeats()
    }

    private fun showRequestsBottomSheet() {
        RequestsBottomSheet(
            this, requestList,
            onAccept = { req: SeatParticipant ->
                requestList.remove(req)
                updateRequestCount()
                handleAcceptUser(req)
            },
            onReject = { req: SeatParticipant ->
                requestList.remove(req)
                updateRequestCount()

                val rejectJson = JSONObject().apply {
                    put("type", "seat_reject")
                    put("target_id", req.id)
                }
                sugunaClient.publishData(rejectJson.toString())
            }
        ).show()
    }

    private fun handleAcceptUser(req: SeatParticipant) {
        if (!isHostLocal) return
        
        // Anti-Overwrite: Remove user from any other seats first to prevent duplicates
        seatedUsers.entries.removeIf { it.value.id == req.id }
        
        requestList.removeIf { it.id == req.id }
        updateRequestCount()

        val maxSeats = 8
        var nextSeat = -1
        for (i in 1 until maxSeats) {
            if (!seatedUsers.containsKey(i)) {
                nextSeat = i
                break
            }
        }

        if (nextSeat == -1) {
            Toast.makeText(this, "No empty seats!", Toast.LENGTH_SHORT).show()
            return
        }

        // PRE-COMMIT seat on Host to prevent race conditions during multiple acceptance
        seatedUsers[nextSeat] = req
        updateSeats()
        broadcastSeatState()

        // V2 Scalable: Perform Action via Server (Redis will update everyone)
        val rId = intent.getStringExtra("ROOM_ID") ?: ""
        com.suguna.rtc.utils.SocketManager.crSeatAction(rId, "promote", req.id, req.name, req.image ?: "", nextSeat)

        // Promoting for permissions is still needed on RTC server
        val promoteUrl = "https://call.suguna.co/api/promote-participant"
        val promoteJson = JSONObject().apply {
            put("roomName", rId)
            put("userId", req.id)
        }

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = promoteJson.toString().toRequestBody(mediaType)
        val request = okhttp3.Request.Builder().url(promoteUrl).post(body).build()

        okhttp3.OkHttpClient().newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {}
        })
    }

    private fun shiftSeats() {
        if (!isHostLocal) return
        // Get all users currently seated (TreeMap ensures they are in seat order)
        val currentUsers = seatedUsers.values.toList() 
        seatedUsers.clear()
        
        // Re-assign sequentially into seats 1, 2, 3...
        for (i in currentUsers.indices) {
            val newSeatId = i + 1
            if (newSeatId <= 7) { // Within audience seat limit
                seatedUsers[newSeatId] = currentUsers[i]
            }
        }
    }

    private fun showChatRoomMenu() {
        val bottomSheet = com.suguna.rtc.chatroom.dialogs.ChatRoomMenuBottomSheet(
            this, 
            isHostLocal, 
            onEarningsClick = {
                try {
                    val intent = Intent()
                    intent.setClassName(this, "pawankalyan.gpk.friendzone.UI.Activities.EarningsActivity")
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Opening Creator Dashboard...", Toast.LENGTH_SHORT).show()
                }
            },
            onMessengerClick = { openMessenger() }, 
            onClearChatClick = { handleClearChat() }
        )
        bottomSheet.show()
    }

    private fun handleClearChat() {
        // Clear locally
        messageAdapter.clearMessages()
        chatHistory.clear()
        
        // Broadcast to others
        try {
            val roomId = intent.getStringExtra("ROOM_ID") ?: ""
            if (roomId.isNotEmpty()) {
                com.suguna.rtc.utils.SocketManager.crClearHistory(roomId)
            }
            
            val json = JSONObject().apply {
                put("type", "clear_messages")
            }
            sugunaClient.publishData(json.toString())
            Toast.makeText(this, "Comments cleared", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openMessenger() {
        try {
            val intent = Intent(this, Class.forName("pawankalyan.gpk.friendzone.UI.Activities.Messages.MessagesListActivity"))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Messenger not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun broadcastSeatState() {
        val json = JSONObject()
        json.put("type", "seat_state")
        
        val arr = org.json.JSONArray()
        val redisSeats = JSONObject() // For Redis Map sync
        
        for ((idx, u) in seatedUsers) {
            val obj = JSONObject()
            obj.put("seat_id", idx)
            obj.put("user_id", u.id)
            obj.put("name", u.name)
            obj.put("image", u.image)
            arr.put(obj)
            
            val rObj = JSONObject()
            rObj.put("userId", u.id)
            rObj.put("name", u.name)
            rObj.put("image", u.image)
            redisSeats.put(idx.toString(), rObj)
        }
        json.put("seats", arr)

        val muteArr = org.json.JSONArray()
        hostMutedUsers.forEach { muteArr.put(it) }
        json.put("host_muted_ids", muteArr)

        val selfMuteArr = org.json.JSONArray()
        selfMutedUsers.forEach { selfMuteArr.put(it) }
        json.put("self_muted_ids", selfMuteArr)

        sugunaClient.publishData(json.toString())
        
        // Sync to Redis via Socket
        val rId = intent.getStringExtra("ROOM_ID") ?: ""
        val redisSyncObj = JSONObject().apply {
            put("seats", redisSeats)
            put("hostMutedIds", muteArr)
            put("selfMutedIds", selfMuteArr)
        }
        com.suguna.rtc.utils.SocketManager.crSyncState(rId, redisSyncObj)
    }

    private fun broadcastChatHistory() {
        // Legacy - Chat History persistent in Redis now via Socket.IO
    }

    private fun showSeatControlBottomSheet(position: Int, seat: SeatParticipant) {
        if (seat.id.startsWith("host_") || seat.id.startsWith("id_") || seat.id.isEmpty()) return // Blank placeholder

        SeatControlsBottomSheet(
            this,
            seat,
            isHostLocal,
            localUserId,
            localName,
            localImage,
            roomOwnerName,
            isMuted,
            hostMutedUsers.toList(),
            selfMutedUsers.toList(),
            onMuteClick = {
                if (isHostLocal && seat.id != localUserId) {
                     // Host is muting someone else
                     if (hostMutedUsers.contains(seat.id)) hostMutedUsers.remove(seat.id) else hostMutedUsers.add(seat.id)
                } else if (seat.id == localUserId) {
                     // Local user is muting themselves
                     isMuted = !isMuted
                     sugunaClient.setMicrophoneEnabled(!isMuted)
                     if (isMuted) selfMutedUsers.add(localUserId) else selfMutedUsers.remove(localUserId)
                     
                     // Sync to others
                     val muteJson = org.json.JSONObject().apply {
                         put("type", "mic_update")
                         put("user_id", localUserId)
                         put("is_muted", isMuted)
                     }
                     sugunaClient.publishData(muteJson.toString())
                }
                updateSeats()
                if (isHostLocal) broadcastSeatState()
            },
            onRemoveClick = {
                invitedUsers.remove(seat.id)
                seatedUsers.values.removeIf { it.id == seat.id }
                hostMutedUsers.remove(seat.id)
                selfMutedUsers.remove(seat.id)
                shiftSeats()
                updateSeats()
                broadcastSeatState()
                val removeJson = JSONObject().apply {
                    put("type", "seat_remove")
                    put("target_id", seat.id)
                }
                sugunaClient.publishData(removeJson.toString())
            },
            onLeaveClick = {
                handleLeaveSeat()
            }
        ).show()
    }

    private fun handleLeaveSeat() {
        val rId = intent.getStringExtra("ROOM_ID") ?: ""
        // Multi-stage removal for reliability
        val mySeat = seatedUsers.entries.find { it.value.id == localUserId }?.key
        if (mySeat != null) {
            com.suguna.rtc.utils.SocketManager.crSeatAction(rId, "remove", localUserId, localName, localImage, mySeat)
        }
        com.suguna.rtc.utils.SocketManager.crLeave(rId, localUserId)

        try {
            val leaveJson = org.json.JSONObject().apply {
                put("type", "seat_leave")
                put("user_id", localUserId)
            }
            sugunaClient.publishData(leaveJson.toString())
        } catch(e: Exception) {}

        sugunaClient.setMicrophoneEnabled(false)
        isMuted = true
        selfMutedUsers.remove(localUserId)
        hostMutedUsers.remove(localUserId)
        isRequestSent = false
        seatedUsers.values.removeIf { it.id == localUserId }
        setupControls()
        updateSeats()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
             val token = intent.getStringExtra("TOKEN") ?: ""
             val serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
             startRtc(serverUrl, token)
        }
    }

    // Receiver for triggering Outgoing Call Activity correctly inside Chat Room context
    private val callStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                "com.suguna.rtc.ACTION_CALL_SUCCESS" -> {
                    val targetId = intent.getStringExtra("TARGET_ID") ?: ""
                    val targetName = intent.getStringExtra("TARGET_NAME") ?: "User"
                    val targetImage = intent.getStringExtra("TARGET_IMAGE") ?: ""
                    val type = intent.getStringExtra("TYPE") ?: "Audio"
                    val roomId = intent.getStringExtra("ROOM_NAME") ?: ""
                    val price = intent.getIntExtra("PRICE_PER_MIN", 20)
                    
                    val outIntent = android.content.Intent().apply {
                        setClassName(this@SugunaChatRoomActivity, "pawankalyan.gpk.friendzone.UI.Activities.SugunaCalls.SugunaOutgoingCallActivity")
                        putExtra("TARGET_USER_ID", targetId)
                        putExtra("TARGET_NAME", targetName)
                        putExtra("TARGET_IMAGE", targetImage)
                        putExtra("CALL_TYPE", type)
                        putExtra("ROOM_NAME", roomId)
                        putExtra("PRICE_PER_MIN", price)
                        putExtra("IS_RANDOM_CALL", true)
                    }
                    
                    // User requested matching the incoming call behavior:
                    // When the user initiates a call successfully, he should leave his existing chat room fully.
                    handleLeaveSeat()
                    val closeIntent = android.content.Intent("com.suguna.rtc.ACTION_CLOSE_CHATROOM_SEAT")
                    sendBroadcast(closeIntent)
                    
                    startActivity(outIntent)
                }
                "com.suguna.rtc.ACTION_CALL_FAILED" -> {
                    Toast.makeText(this@SugunaChatRoomActivity, "Failed to connect call...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun triggerReflectionCall(type: String, seat: SeatParticipant) {
        initiateReflectionCall(type, seat)
    }

    // Fallback reflection invoking SocketManager in FriendZone App dynamically
    private fun initiateReflectionCall(type: String, seat: SeatParticipant) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                // 1. Check Target Status First (Firebase Realtime DB)
                val dbRef = com.google.firebase.database.FirebaseDatabase.getInstance()
                    .reference.child("BroadCast").child(seat.id)
                
                dbRef.get().addOnSuccessListener { targetSnap ->
                    if (!targetSnap.exists()) {
                         android.widget.Toast.makeText(this, "User is currently unavailable.", android.widget.Toast.LENGTH_SHORT).show()
                         return@addOnSuccessListener
                    }
                    
                    val encStatus = targetSnap.child("Status").getValue(String::class.java) ?: ""
                    val encCallEnabled = targetSnap.child("CallEnabled").getValue(String::class.java) ?: ""
                    val encAudio = targetSnap.child("AudioCallEnabled").getValue(String::class.java) ?: ""
                    val encVideo = targetSnap.child("VideoCallEnabled").getValue(String::class.java) ?: ""
                    val isBusy = targetSnap.child("isBusy").getValue(Boolean::class.java) ?: false

                    val status = com.suguna.rtc.utils.Encryption.decrypt(encStatus) ?: ""
                    val callEnabled = com.suguna.rtc.utils.Encryption.decrypt(encCallEnabled)?.toBoolean() ?: false
                    val audioEnabled = com.suguna.rtc.utils.Encryption.decrypt(encAudio)?.toBoolean() ?: false
                    val videoEnabled = com.suguna.rtc.utils.Encryption.decrypt(encVideo)?.toBoolean() ?: false

                    if (!status.equals("Activated", ignoreCase = true) || !callEnabled) {
                         android.widget.Toast.makeText(this, "${seat.name} has disabled calls.", android.widget.Toast.LENGTH_SHORT).show()
                         return@addOnSuccessListener
                    }
                    
                    if (isBusy) {
                         android.widget.Toast.makeText(this, "${seat.name} is on another call.", android.widget.Toast.LENGTH_SHORT).show()
                         return@addOnSuccessListener
                    }

                    if (type == "Audio" && !audioEnabled) {
                         android.widget.Toast.makeText(this, "${seat.name} has disabled Audio calls.", android.widget.Toast.LENGTH_SHORT).show()
                         return@addOnSuccessListener
                    }
                    if (type == "Video" && !videoEnabled) {
                         android.widget.Toast.makeText(this, "${seat.name} has disabled Video calls.", android.widget.Toast.LENGTH_SHORT).show()
                         return@addOnSuccessListener
                    }

                    // 2. Check Local Coins
                    val coinRef = com.google.firebase.database.FirebaseDatabase.getInstance()
                        .reference.child("Wallet").child("CoinBalance").child(localUserId)
                    
                    coinRef.get().addOnSuccessListener { snapshot ->
                        var totalCoins = 0L
                        if (snapshot.exists()) {
                            val bonusEnc = snapshot.child("BonusCoins").getValue(String::class.java) ?: "0"
                            val rechargeEnc = snapshot.child("RechargeCoins").getValue(String::class.java) ?: "0"
                            val b = com.suguna.rtc.utils.Encryption.decrypt(bonusEnc)?.toLongOrNull() ?: 0L
                            val r = com.suguna.rtc.utils.Encryption.decrypt(rechargeEnc)?.toLongOrNull() ?: 0L
                            totalCoins = b + r
                        }
                        
                        val minNeeded = if (type == "Audio") 100 else 300
                        if (totalCoins < minNeeded) {
                             android.widget.Toast.makeText(this, "Insufficient Coins! You need at least $minNeeded coins.", android.widget.Toast.LENGTH_SHORT).show()
                             return@addOnSuccessListener
                        }
                        
                        // 3. All Checks Passed - Trigger Call
                        android.util.Log.d("SugunaCall", "Attempting reflection to trigger call: $type to ${seat.id}")
                        try {
                            // CORRECT PATH: Reflection should point to the SDK's own SocketManager which is shared/imported by the App
                            val socketClass = Class.forName("com.suguna.rtc.utils.SocketManager")
                            val method = socketClass.getMethod("initiateCall", String::class.java, String::class.java, String::class.java, String::class.java, Long::class.java)
                            
                            val objectInstanceField = socketClass.getDeclaredField("INSTANCE")
                            val instance = objectInstanceField.get(null)
                            
                            method.invoke(instance, seat.id, type, localName, localImage, totalCoins)
                            android.util.Log.d("SugunaCall", "Call initiated successfully via reflection")
                        } catch (e: ClassNotFoundException) {
                            android.util.Log.e("SugunaCall", "SocketManager class not found", e)
                            android.widget.Toast.makeText(this, "Call Error: SocketManager not found", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: NoSuchMethodException) {
                            android.util.Log.e("SugunaCall", "initiateCall method not found", e)
                            android.widget.Toast.makeText(this, "Call Error: Method mismatch", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) { 
                            e.printStackTrace()
                            android.util.Log.e("SugunaCall", "General reflection error", e)
                            android.widget.Toast.makeText(this, "Direct Call Service unavailable in this room context.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) { 
                e.printStackTrace() 
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(this, callStatusReceiver, android.content.IntentFilter("com.suguna.rtc.ACTION_CALL_SUCCESS"), ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, callStatusReceiver, android.content.IntentFilter("com.suguna.rtc.ACTION_CALL_FAILED"), ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(callStatusReceiver, android.content.IntentFilter("com.suguna.rtc.ACTION_CALL_SUCCESS"))
            registerReceiver(callStatusReceiver, android.content.IntentFilter("com.suguna.rtc.ACTION_CALL_FAILED"))
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(callStatusReceiver)
        } catch (e: Exception) {}
    }

    private fun startMusicBroadcasting(name: String, uriString: String?, artUri: String?, duration: Long) {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        currentPlayingUri = uriString
        
        try {
            mediaPlayer = android.media.MediaPlayer().apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                } else {
                    setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
                }
                setDataSource(this@SugunaChatRoomActivity, android.net.Uri.parse(uriString))
                
                if (uriString?.startsWith("http") == true) {
                    setOnPreparedListener { 
                        if (isFinishing || isDestroyed) return@setOnPreparedListener
                        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        am.mode = android.media.AudioManager.MODE_NORMAL
                        am.setStreamVolume(
                            android.media.AudioManager.STREAM_MUSIC,
                            am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
                            0
                        )
                        it.setVolume(1.0f, 1.0f) // Force MediaPlayer internal max
                        it.start() 
                        showMusicOverlay(name, artUri, duration)
                        
                        // Broadcast to everyone AFTER prepared
                        val playJson = JSONObject().apply {
                            put("type", "music_play")
                            put("name", name)
                            put("art", artUri)
                            put("duration", duration)
                            put("url", uriString)
                        }
                        sugunaClient.publishData(playJson.toString())
                        startMusicSyncTask()
                    }
                    prepareAsync()
                } else {
                    prepare()
                    val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    am.mode = android.media.AudioManager.MODE_NORMAL
                    am.setStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
                        0
                    )
                    setVolume(1.0f, 1.0f) // Force max MediaPlayer internal volume
                    start()
                    showMusicOverlay(name, artUri, duration)
                    
                    val playJson = JSONObject().apply {
                        put("type", "music_play")
                        put("name", name)
                        put("art", artUri)
                        put("duration", duration)
                        put("url", uriString)
                    }
                    sugunaClient.publishData(playJson.toString())
                    startMusicSyncTask()
                }
            }
            
            if (isHostLocal) {
                 Toast.makeText(this@SugunaChatRoomActivity, "Music Synced to Everyone in HD!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Playback Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMusicSyncTask() {
        musicSyncRunnable?.let { musicSyncHandler.removeCallbacks(it) }
        musicSyncRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let { mp ->
                    try {
                        // Always send sync so pause state and late joiners are caught
                        val progress = if (mp.duration > 0) (mp.currentPosition.toFloat() / mp.duration.toFloat() * 100).toInt() else 0
                        val syncJson = JSONObject().apply {
                            put("type", "music_sync")
                            put("progress", progress)
                            put("url", currentPlayingUri)
                            put("isPlaying", mp.isPlaying)
                            put("currentPosition", mp.currentPosition)
                        }
                        sugunaClient.publishData(syncJson.toString())
                        findViewById<SeekBar>(R.id.sbOverlayProgress).progress = progress
                    } catch (e: Exception) {}
                    musicSyncHandler.postDelayed(this, 1000)
                }
            }
        }
        musicSyncHandler.post(musicSyncRunnable!!)
    }

    private fun showMusicOverlay(name: String, artUri: String?, duration: Long) {
        if (!isHostLocal) return // Audience ki overlay vaddhu
        if (isFinishing || isDestroyed) return // Prevent Glide crash on destroyed activity
        val overlay = findViewById<android.view.View>(R.id.musicOverlay)
        overlay.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvOverlaySongName).text = name
        
        val ivArt = findViewById<android.widget.ImageView>(R.id.ivOverlayArt)
        com.bumptech.glide.Glide.with(this).load(artUri).placeholder(R.drawable.music_note_icon).into(ivArt)

        findViewById<SeekBar>(R.id.sbOverlayProgress).max = 100
        findViewById<SeekBar>(R.id.sbOverlayProgress).progress = 0
        
        val btnPlayPause = findViewById<android.widget.ImageView>(R.id.btnOverlayPlayPause)
        btnPlayPause.setImageResource(R.drawable.stop_icon) // Using stop_icon for active state

        btnPlayPause.setOnClickListener {
            if (isHostLocal) {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.pause()
                    btnPlayPause.setImageResource(R.drawable.play_arrow_icon)
                    val pauseJson = JSONObject().apply { put("type", "music_pause") }
                    sugunaClient.publishData(pauseJson.toString())
                } else {
                    mediaPlayer?.start()
                    btnPlayPause.setImageResource(R.drawable.stop_icon)
                    val resumeJson = JSONObject().apply { put("type", "music_resume") }
                    sugunaClient.publishData(resumeJson.toString())
                }
            }
        }
        
        findViewById<android.widget.ImageView>(R.id.btnOverlayClose).setOnClickListener {
            if (isHostLocal) {
                mediaPlayer?.stop()
                musicSyncRunnable?.let { musicSyncHandler.removeCallbacks(it) }
                val stopJson = JSONObject().apply { put("type", "music_stop") }
                sugunaClient.publishData(stopJson.toString())
            }
            overlay.visibility = View.GONE
        }
    }

    private var animationOverlay: android.widget.FrameLayout? = null
    // Simultaneous animation implementation (No queue)
    private fun processNextGift() { /* No longer used */ }

    private fun startActualAnimation(giftUrl: String, receiverId: String, rootView: android.view.ViewGroup) {
        val urlLower = giftUrl.lowercase()
        val isLottie = urlLower.contains(".json") || urlLower.contains(".lottie")
        val context = this
        
        if (isLottie) {
            val lottieView = com.airbnb.lottie.LottieAnimationView(context).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(500, 500)
                alpha = 0f
            }
            rootView.addView(lottieView)
            lottieView.bringToFront()
            
            com.airbnb.lottie.LottieCompositionFactory.fromUrl(context, giftUrl).addListener { composition: com.airbnb.lottie.LottieComposition ->
                lottieView.setComposition(composition)
                lottieView.playAnimation()
                lottieView.repeatCount = com.airbnb.lottie.LottieDrawable.INFINITE
                runMovementAnimation(lottieView, receiverId, rootView)
            }.addFailureListener { e: Throwable ->
                Log.e("SugunaGift", "Lottie Load Failed: ${e.message}")
            }
        } else {
            val imageView = ImageView(context).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(500, 500)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                alpha = 0f
            }
            rootView.addView(imageView)
            imageView.bringToFront()
            
            com.bumptech.glide.Glide.with(context)
                .load(giftUrl)
                .into(object : com.bumptech.glide.request.target.SimpleTarget<android.graphics.drawable.Drawable>() {
                    override fun onResourceReady(resource: android.graphics.drawable.Drawable, transition: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?) {
                        imageView.setImageDrawable(resource)
                        runMovementAnimation(imageView, receiverId, rootView)
                    }
                })
        }
    }

    private fun runMovementAnimation(giftView: android.view.View, receiverId: String, rootView: android.view.ViewGroup) {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()

        // START FROM BOTTOM CENTER (Above Input Bar)
        giftView.x = (screenWidth / 2) - 250
        giftView.y = screenHeight - 850

        // Phase 1: Appear and Scale at Bottom
        giftView.animate()
            .alpha(1f)
            .scaleX(1.5f)
            .scaleY(1.5f)
            .setDuration(1000)
            .withEndAction {
                // Phase 2: Wait at bottom for 3 seconds as requested
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isFinishing || isDestroyed) return@postDelayed
                    
                    val receiverView = getUserView(receiverId) ?: findViewById<View>(R.id.ivRoomOwner)
                    val targetPos = IntArray(2)
                    receiverView.getLocationInWindow(targetPos)
                    
                    val rootPos = IntArray(2)
                    rootView.getLocationInWindow(rootPos)
                    
                    val endX = (targetPos[0] - rootPos[0]).toFloat() + (receiverView.width / 2) - 250f
                    val endY = (targetPos[1] - rootPos[1]).toFloat() + (receiverView.height / 2) - 250f

                    // Phase 3: Move to Seat
                    val path = android.graphics.Path()
                    path.moveTo(giftView.x, giftView.y)
                    
                    val controlX = (giftView.x + endX) / 2
                    val controlY = java.lang.Math.min(giftView.y, endY) - 500 
                    path.quadTo(controlX, controlY, endX, endY)

                    val animator = android.animation.ObjectAnimator.ofFloat(giftView, View.X, View.Y, path)
                    animator.duration = 1800
                    
                    giftView.animate()
                        .scaleX(0.3f) 
                        .scaleY(0.3f)
                        .alpha(1f) 
                        .setDuration(1800)
                        .start()

                    animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            giftView.animate().alpha(0f).setDuration(200).withEndAction {
                                rootView.removeView(giftView)
                            }.start()
                        }
                    })
                    animator.start()
                }, 1500)
            }
            .start()
    }

    private fun getUserView(userId: String): View? {
        val rv = findViewById<RecyclerView>(R.id.rvSeats) ?: return null
        val lm = rv.layoutManager as? GridLayoutManager ?: return null
        
        // 1. Prioritize searching in the Seats Grid (includes Host at pos 0)
        for (i in 0 until seatAdapter.itemCount) {
             val seatId = seatAdapter.getSeatAt(i)?.id ?: ""
             if (seatId == userId) {
                 val view = lm.findViewByPosition(i)
                 if (view != null) {
                     val profileImg = view.findViewById<View>(R.id.ivParticipantProfile)
                     if (profileImg != null) return profileImg
                     return view
                 }
             }
        }

        // 2. Fallback for Host to Room Header image if seat view is not found/off-screen
        if (userId == roomOwnerId) {
            return findViewById(R.id.ivRoomOwner)
        }
        
        return null
    }

    private fun vibrateDevice() {
        val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        vibrator?.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                it.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                it.vibrate(50)
            }
        }
    }

    private fun playRemoteMusicForAudience(url: String, startPosition: Int = 0) {
        if (isFinishing || isDestroyed) return // Prevent Phantom MediaPlayers after Leaving Room!
        val requestTime = System.currentTimeMillis()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        try {
            mediaPlayer = android.media.MediaPlayer().apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            // CRITICAL: Revert to USAGE_MEDIA for HD Quality Audio bypass
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                } else {
                    setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
                }
                setDataSource(this@SugunaChatRoomActivity, android.net.Uri.parse(url))
                setOnPreparedListener { 
                     // Force extreme volume in MediaPlayer layer
                     it.setVolume(1.0f, 1.0f)
                     
                     // MATHEMATICAL PERFECTION: Calculate exact time lost during preparation and network transit
                     val timeTakenToLoad = (System.currentTimeMillis() - requestTime).toInt()
                     val networkLatency = 300 // Standard latency for realtime data channels
                     val exactPosition = startPosition + timeTakenToLoad + networkLatency
                     
                     if (exactPosition > 0) {
                         it.seekTo(exactPosition)
                     }
                     it.start() 
                }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    data class GiftTask(val senderId: String, val receiverId: String, val giftUrl: String)
}

