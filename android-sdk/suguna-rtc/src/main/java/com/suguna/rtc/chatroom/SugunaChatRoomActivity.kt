package com.suguna.rtc.chatroom

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.suguna.rtc.R
import com.suguna.rtc.SugunaClient
import com.suguna.rtc.chatroom.dialogs.OnlineUsersBottomSheet
import com.suguna.rtc.chatroom.dialogs.RequestsBottomSheet
import com.suguna.rtc.chatroom.dialogs.SeatControlsBottomSheet
import com.suguna.rtc.chatroom.dialogs.SeatInviteDialog
import io.livekit.android.room.track.VideoTrack
import io.socket.client.Socket
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var listParticipants = mutableListOf<SeatParticipant>()
    
    private val requestList = mutableListOf<SeatParticipant>()
    private val seatedUsers = java.util.TreeMap<Int, SeatParticipant>() // TreeMap enforces seat-id order
    private val hostMutedUsers = mutableSetOf<String>()
    private val selfMutedUsers = mutableSetOf<String>() // Added for self mutes
    private val invitedUsers = mutableSetOf<String>()
    private val chatHistory = mutableListOf<ChatMessage>()
    private var isRequestSent = false // Track request sent state
    private var isInviteDialogShowing = false
    
    private val promoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var promoRunnable: Runnable? = null
    
    private val closeChatRoomReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.suguna.rtc.ACTION_CLOSE_CHATROOM_SEAT") {
                android.util.Log.d("SugunaChatRoom", "Received Close ChatRoom signal due to direct call start")
                if (!isHostLocal) {
                    handleLeaveSeat() // Leave seat to notify host
                }
                finish()
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

        val token = intent.getStringExtra("TOKEN") ?: ""
        val serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        localUserId = intent.getStringExtra("USER_ID") ?: "user_${System.currentTimeMillis()}"
        localName = intent.getStringExtra("USER_NAME") ?: "User"
        localImage = intent.getStringExtra("USER_IMAGE") ?: ""
        isHostLocal = intent.getBooleanExtra("isHost", false)
        roomLanguage = intent.getStringExtra("ROOM_LANGUAGE") ?: "English"
        roomOwnerId = intent.getStringExtra("ROOM_OWNER_ID") ?: ""
        roomOwnerName = intent.getStringExtra("ROOM_OWNER_NAME") ?: "Host"
        val roomOwnerImage = intent.getStringExtra("ROOM_OWNER_IMAGE") ?: if (isHostLocal) localImage else ""
        roomLevel = intent.getIntExtra("roomLevel", 8)

        seatManager = SeatManager()
        seatManager.generateSeats(roomLevel)

        if (isHostLocal) {
             try {
                  val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("frienzone")
                  db.collection("BestieRooms").document(localUserId).update("status", "Active")
             } catch (e: Exception) {}
        }

        // Start Lifecycle Monitoring Service for Clear triggers
        val startServiceIntent = android.content.Intent(this, com.suguna.rtc.chatroom.SugunaChatRoomService::class.java).apply {
            putExtra("USER_ID", localUserId)
            putExtra("isHost", isHostLocal)
        }
        startService(startServiceIntent)



        val cFilter = android.content.IntentFilter("com.suguna.rtc.ACTION_CLOSE_CHATROOM_SEAT")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(closeChatRoomReceiver, cFilter, 2) // 2 = RECEIVER_NOT_EXPORTED
        } else {
            registerReceiver(closeChatRoomReceiver, cFilter)
        }

        if (token.isEmpty()) {
            Toast.makeText(this, "Error: Invalid Token", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.tvRoomId).text = "ID: ${intent.getStringExtra("ROOM_ID") ?: "--"}"
        findViewById<TextView>(R.id.tvRoomName).text = intent.getStringExtra("ROOM_NAME") ?: "Suguna Chat Room"

        val ivRoomOwner = findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivRoomOwner)
        if (roomOwnerImage.isNotEmpty()) {
             com.bumptech.glide.Glide.with(this).load(roomOwnerImage).into(ivRoomOwner)
        }

        setupSeatsRecyclerView()
        setupMessagesRecyclerView()
        setupMessageSender()
        setupControls()

        // Request Audio Permission upfront for dynamic seating
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
             requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
        } else {
             startRtc(serverUrl, token)
        }
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
                                
                                // Selective Update: Only clear if we actually have data to replace it with
                                // IMPORTANT: Host MUST be online for state sync to be considered valid for automatic seating
                                val isHostOnline = listParticipants.any { it.id == roomOwnerId } || isHostLocal
                                
                                if (newSeatedUsers.isNotEmpty() || (isHostLocal && seatedUsers.isEmpty())) {
                                    // If host is offline, audiences should NOT be placed on seats by sync
                                    // unless they are already active in RTC and were seated before.
                                    if (!isHostLocal && !isHostOnline) {
                                         // Host offline -> Keep current state if user is already seated
                                         val wasSeated = seatedUsers.values.any { it.id == localUserId }
                                         val isNowSeated = newSeatedUsers.values.any { it.id == localUserId }
                                         
                                         if (!wasSeated && isNowSeated) {
                                              // Don't auto-seat if host is gone
                                              return@runOnUiThread
                                         }
                                    }

                                    seatedUsers.clear()
                                    seatedUsers.putAll(newSeatedUsers)
                                    updateSeats()
                                }
                            }
                        }
                    }
                    
                    sock?.on("cr_handle_request") { args: Array<Any>? ->
                        runOnUiThread {
                            val reqJson = args?.getOrNull(0) as? org.json.JSONObject
                            if (reqJson != null) {
                                val reqU = SeatParticipant(reqJson.getString("userId"), reqJson.getString("name"), reqJson.optString("image"))
                                if (!requestList.any { it.id == reqU.id }) {
                                    requestList.add(reqU)
                                    updateRequestCount()
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
                                 val msg = ChatMessage(
                                     senderId = data.getString("userId"),
                                     name = data.getString("name"),
                                     image = data.optString("image"),
                                     message = data.getString("msg"),
                                     timestamp = data.optString("time", "")
                                 )
                                 messageAdapter.addMessage(msg)
                                 findViewById<RecyclerView>(R.id.rvMessages).scrollToPosition(messageAdapter.itemCount - 1)
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
                    updateSeats()
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
                                    val msg = ChatMessage(
                                        senderId = json.getString("sender_id"),
                                        name = json.getString("name"),
                                        image = json.optString("image"),
                                        message = json.getString("msg"),
                                        timestamp = json.getString("time")
                                    )
                                    messageAdapter.addMessage(msg)
                                    chatHistory.add(msg) // Add to history
                                    findViewById<RecyclerView>(R.id.rvMessages).scrollToPosition(messageAdapter.itemCount - 1)
                                }
                                "chat_history" -> {
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
                                }
                                "seat_state_request" -> {
                                     if (isHostLocal) {
                                          broadcastSeatState()
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
                                         val image = json.optString("image")
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
                                     val isHostOnline = listParticipants.any { it.id == roomOwnerId } || isHostLocal

                                     if (hostMutedUsers.contains(localUserId)) {
                                         if (!isMuted) {
                                             isMuted = true
                                             sugunaClient.setMicrophoneEnabled(false)
                                         }
                                      } else if (isHostLocal || (isSeatedLocal && isHostOnline)) {
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
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        dialogView.findViewById<TextView>(R.id.tvDialogTitle).text = findViewById<TextView>(R.id.tvRoomName).text.toString()
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnKeep).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLeave).setOnClickListener { 
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

    private fun setupMessageSender() {
        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnSend = findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.btnSendMessage)

        etMessage.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                btnSend.visibility = if (!s.isNullOrBlank()) View.VISIBLE else View.GONE
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
             com.bumptech.glide.Glide.with(this).load(host.image).into(ivRoomOwner)
        }
    }

    private fun setupControls() {
        findViewById<android.widget.LinearLayout>(R.id.btnOnlineCountSub).setOnClickListener {
            showOnlineUsersDialog()
        }

        val btnViewRequests = findViewById<android.widget.LinearLayout>(R.id.btnViewRequests)
        val btnRequestSeat = findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.btnRequestSeat)

        btnViewRequests.visibility = View.GONE
        btnRequestSeat.visibility = View.GONE

        val isHostOnline = listParticipants.any { it.id == roomOwnerId } || isHostLocal

        if (isHostLocal) {
            btnViewRequests.visibility = View.VISIBLE
            btnViewRequests.setOnClickListener { showRequestsBottomSheet() }
            updateRequestCount()
        } else {
            val isSeatedLocal = seatedUsers.values.any { it.id == localUserId }
            
            if (!isHostOnline || isSeatedLocal || isRequestSent) {
                btnRequestSeat.visibility = View.GONE
            } else {
                btnRequestSeat.visibility = View.VISIBLE
            }

            btnRequestSeat.setOnClickListener { 
                sendSeatRequest() 
                isRequestSent = true
                btnRequestSeat.visibility = View.GONE
                updateSeats()
            }
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
                  val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("frienzone")
                  db.collection("BestieRooms").document(localUserId).update(
                      mapOf("onlineCount" to count, "status" to "Active")
                  )
             } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        promoRunnable?.let { promoHandler.removeCallbacks(it) }

        val rId = intent.getStringExtra("ROOM_ID") ?: ""
        if (rId.isNotEmpty()) {
            com.suguna.rtc.utils.SocketManager.crLeave(rId, localUserId)
        }

        if (isHostLocal) {
             try {
                  val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("frienzone")
                  db.collection("BestieRooms").document(localUserId).update("status", "Offline")
             } catch (e: Exception) {}
        }
        try {
            unregisterReceiver(closeChatRoomReceiver)
        } catch (e: Exception) {}
        
        try {
            val stopServiceIntent = android.content.Intent(this, com.suguna.rtc.chatroom.SugunaChatRoomService::class.java)
            stopService(stopServiceIntent)
        } catch (e: Exception) {}

        if (::sugunaClient.isInitialized) {
            sugunaClient.leaveRoom()
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
                     if (hostMutedUsers.contains(seat.id)) hostMutedUsers.remove(seat.id) else hostMutedUsers.add(seat.id)
                } else {
                     isMuted = !isMuted
                     sugunaClient.setMicrophoneEnabled(!isMuted)
                     if (isMuted) selfMutedUsers.add(localUserId) else selfMutedUsers.remove(localUserId)
                }
                updateSeats()
                broadcastSeatState()
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            registerReceiver(callStatusReceiver, android.content.IntentFilter("com.suguna.rtc.ACTION_CALL_SUCCESS"), android.content.Context.RECEIVER_EXPORTED)
            registerReceiver(callStatusReceiver, android.content.IntentFilter("com.suguna.rtc.ACTION_CALL_FAILED"), android.content.Context.RECEIVER_EXPORTED)
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
}
