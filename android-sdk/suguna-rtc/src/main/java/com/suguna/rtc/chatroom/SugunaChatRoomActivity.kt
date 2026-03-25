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
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SugunaChatRoomActivity : AppCompatActivity() {

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
    private val seatedUsers = mutableMapOf<Int, SeatParticipant>() // seatId -> User
    private val hostMutedUsers = mutableSetOf<String>()
    private val selfMutedUsers = mutableSetOf<String>() // Added for self mutes
    private val chatHistory = mutableListOf<ChatMessage>()
    private var isRequestSent = false // Track request sent state
    
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
            }

            override fun onLocalStreamReady(videoTrack: VideoTrack) {}
            override fun onRemoteStreamReady(userId: String?, videoTrack: VideoTrack) {}
            override fun onLocalStreamUpdate(videoTrack: VideoTrack, isMuted: Boolean) {}
            override fun onRemoteStreamUpdate(userId: String?, videoTrack: VideoTrack, isMuted: Boolean) {}

            override fun onUserJoined(participant: io.livekit.android.room.participant.RemoteParticipant) {
                runOnUiThread {
                    val pId = participant.identity?.value ?: ""
                    val isHost = pId == roomOwnerId
                    
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
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            broadcastChatHistory()
                        }, 1000)
                    } else {
                        try {
                             val j = JSONObject().apply { put("type", "chat_history_request") }
                             sugunaClient.publishData(j.toString())
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
                                }
                                "seat_remove" -> {
                                    val targetId = json.getString("target_id")
                                    if (localUserId == targetId) {
                                        isRequestSent = false
                                        isMuted = true
                                        setupControls() // Refresh button
                                        sugunaClient.setMicrophoneEnabled(false)
                                        updateSeats()
                                    }
                                }
                                 "seat_confirm" -> {
                                      if (isHostLocal) {
                                           val sId = json.getInt("seat_id")
                                           val u = SeatParticipant(
                                                json.getString("user_id"),
                                                json.getString("name"),
                                                json.optString("image")
                                           )
                                           seatedUsers[sId] = u
                                           updateSeats()
                                           broadcastSeatState()
                                      }
                                 }
                                 "seat_state" -> {
                                     val sArray = json.getJSONArray("seats")
                                     val mySeatEntry = seatedUsers.entries.find { it.value.id == localUserId }
                                     val mySeatId = mySeatEntry?.key ?: -1
                                     val mySeatUser = mySeatEntry?.value

                                     seatedUsers.clear()
                                     if (mySeatId != -1 && mySeatUser != null) {
                                          seatedUsers[mySeatId] = mySeatUser
                                     }
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
                                     } else if (isSeatedLocal) {
                                         val isSelfMuted = selfMutedUsers.contains(localUserId)
                                         if (!isSelfMuted && isMuted) {
                                              isMuted = false
                                              sugunaClient.setMicrophoneEnabled(true)
                                         }
                                     } else if (!isHostLocal && !isSeatedLocal) {
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
                                      seatedUsers.values.removeIf { it.id == targetId }
                                      updateSeats()
                                      if (isHostLocal) broadcastSeatState()
                                 }
                                 "user_left_room" -> {
                                      val targetId = json.getString("user_id")
                                      listParticipants.removeAll { it.id == targetId }
                                      if (isHostLocal) {
                                           requestList.removeAll { it.id == targetId }
                                           updateRequestCount()
                                           val removed = seatedUsers.values.removeIf { it.id == targetId }
                                           if (removed) {
                                                broadcastSeatState()
                                           }
                                      }
                                      updateSeats()
                                      updateOnlineCount()
                                      setupControls()
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
                    
                    if (isHostLocal) {
                        requestList.removeAll { it.id == userId }
                        updateRequestCount()
                        updateSeats() // Update seat label representation
                        val removed = seatedUsers.values.removeIf { it.id == userId }
                        if (removed) {
                             broadcastSeatState()
                        }
                    }

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
        val isHostOnline = listParticipants.any { it.id == roomOwnerId } || isHostLocal
        if (!isHostOnline) {
             Toast.makeText(this, "Host is offline!", Toast.LENGTH_SHORT).show()
             return
        }
        val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        val json = JSONObject().apply {
            put("type", "chat")
            put("sender_id", localUserId)
            put("name", localName)
            put("image", localImage)
            put("msg", text)
            put("time", currentTime)
        }
        sugunaClient.publishData(json.toString())
        
        // Also add locally
        val msg = ChatMessage(localUserId, localName, localImage, text, currentTime)
        messageAdapter.addMessage(msg)
        chatHistory.add(msg) // Add to history so it’s included when broadcasting to rejoinees
        findViewById<RecyclerView>(R.id.rvMessages).scrollToPosition(messageAdapter.itemCount - 1)
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
        OnlineUsersBottomSheet(
            this, listParticipants, isHostLocal, localUserId, localName, localImage, roomOwnerId, seatedUsers
        ) { targetUser: SeatParticipant ->
            val inviteJson = JSONObject().apply {
                put("type", "seat_invite")
                put("target_id", targetUser.id)
                put("host_name", localName)
            }
            sugunaClient.publishData(inviteJson.toString())
            Toast.makeText(this, "Invitation sent!", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun showInviteDialog(hostName: String) {
        // Find host image from list
        val host = listParticipants.find { it.id == roomOwnerId }
        val hostImage = host?.image ?: ""

        SeatInviteDialog(
            this, hostName, hostImage,
            onAccept = {
                val responseJson = JSONObject().apply {
                    put("type", "seat_invite_accept")
                    put("sender_id", localUserId)
                    put("name", localName)
                    put("image", localImage)
                }
                sugunaClient.publishData(responseJson.toString())
            },
            onReject = {}
        ).show()
    }

    private fun updateSeats() {
        // FORCE EXACTLY 7 audience seats yielding exactly 8 absolute grid nodes (1 Host + 7 placeholders)
        seatManager.generateSeats(7)
        val seats = seatManager.getSeats().toMutableList()
        
        // 1. Position 0 is ALWAYS the HOST (Room Owner)
        val hostFromList = listParticipants.find { it.id == roomOwnerId }
        
        if (isHostLocal) {
             seats[0] = seats[0].copy(id = localUserId, name = localName, image = localImage, isSpeaking = speakerIds.contains(localUserId))
        } else if (hostFromList != null) {
             seats[0] = seats[0].copy(id = hostFromList.id, name = hostFromList.name, image = hostFromList.image, isSpeaking = speakerIds.contains(roomOwnerId))
        } else {
             seats[0] = seats[0].copy(name = "$roomOwnerName (Offline)")
        }

        // 2. Map explicitly seated audience items from synchronized memory state
        var foundEmpty = false
        for (i in 1 until seats.size) {
            val seatedUser = seatedUsers[i]
            if (seatedUser != null) {
                 seats[i] = seats[i].copy(
                     id = seatedUser.id,
                     name = seatedUser.name,
                     image = seatedUser.image ?: "",
                     isSpeaking = speakerIds.contains(seatedUser.id)
                 )
            } else if (!foundEmpty) {
                 foundEmpty = true
                 if (isHostLocal) {
                     seats[i] = seats[i].copy(
                         id = "request_seat",
                         name = "Requests (${requestList.size})",
                         image = ""
                     )
                 } else {
                     val isHostOnline = listParticipants.any { it.id == roomOwnerId } || isHostLocal
                     val isSeatedLocal = seatedUsers.values.any { it.id == localUserId }
                     if (!isSeatedLocal && isHostOnline) {
                          seats[i] = seats[i].copy(
                              id = "audience_request_$i",
                              name = "Request Seat",
                              image = if (isRequestSent) "SENT" else ""
                          )
                     }
                 }
            }
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
        val json = JSONObject().apply {
            put("type", "seat_request")
            put("sender_id", localUserId)
            put("name", localName)
            put("image", localImage)
        }
        sugunaClient.publishData(json.toString())
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
        // Clear from request list if they were pending
        requestList.removeIf { it.id == req.id }
        updateRequestCount()

        // Find next empty seat sequential
        val maxSeats = seatManager.getSeats().size
        var nextSeat = -1
        for (i in 1 until maxSeats) {
            if (!seatedUsers.containsKey(i)) {
                nextSeat = i
                break
            }
        }

        if (nextSeat == -1) {
            Toast.makeText(this, "No empty seats available!", Toast.LENGTH_SHORT).show()
            return
        }

        // Add to map
        seatedUsers[nextSeat] = req
        updateSeats()

        // Promote Participant dynamically on Server to grant Publish permissions 
        val promoteUrl = "https://call.suguna.co/api/promote-participant"
        val promoteJson = JSONObject().apply {
            put("roomName", intent.getStringExtra("ROOM_ID") ?: "")
            put("userId", req.id)
        }

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = promoteJson.toString().toRequestBody(mediaType)

        val request = okhttp3.Request.Builder()
            .url(promoteUrl)
            .post(body)
            .build()

        okhttp3.OkHttpClient().newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread { android.widget.Toast.makeText(this@SugunaChatRoomActivity, "Promote Fail: ${e.message}", android.widget.Toast.LENGTH_SHORT).show() }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
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
                        android.widget.Toast.makeText(this@SugunaChatRoomActivity, "Promote Fail: ${response.code}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun broadcastSeatState() {
        val json = JSONObject()
        json.put("type", "seat_state")
        
        val arr = org.json.JSONArray()
        for ((idx, u) in seatedUsers) {
            val obj = JSONObject()
            obj.put("seat_id", idx)
            obj.put("user_id", u.id)
            obj.put("name", u.name)
            obj.put("image", u.image)
            arr.put(obj)
        }
        json.put("seats", arr)

        val muteArr = org.json.JSONArray()
        hostMutedUsers.forEach { muteArr.put(it) }
        json.put("host_muted_ids", muteArr)

        val selfMuteArr = org.json.JSONArray()
        selfMutedUsers.forEach { selfMuteArr.put(it) }
        json.put("self_muted_ids", selfMuteArr)

        sugunaClient.publishData(json.toString())
    }

    private fun broadcastChatHistory() {
        val json = JSONObject()
        json.put("type", "chat_history")
        val arr = org.json.JSONArray()
        for (msg in chatHistory.takeLast(20)) {
             val obj = JSONObject().apply {
                 put("sender_id", msg.senderId)
                 put("name", msg.name)
                 put("image", msg.image)
                 put("msg", msg.message)
                 put("time", msg.timestamp)
             }
             arr.put(obj)
        }
        json.put("messages", arr)
        sugunaClient.publishData(json.toString())
    }

    private fun showSeatControlBottomSheet(position: Int, seat: SeatParticipant) {
        if (seat.id.startsWith("host_") || seat.id.startsWith("id_") || seat.id.isEmpty()) return // Blank placeholder

        SeatControlsBottomSheet(
            this,
            seat,
            isHostLocal,
            localUserId,
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
                broadcastSeatState()
            },
            onRemoveClick = {
                seatedUsers.values.removeIf { it.id == seat.id }
                hostMutedUsers.remove(seat.id)
                selfMutedUsers.remove(seat.id)
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
        val json = JSONObject().apply {
            put("type", "seat_leave")
            put("user_id", localUserId)
        }
        sugunaClient.publishData(json.toString())
        sugunaClient.setMicrophoneEnabled(false)
        isMuted = true
        isRequestSent = false // Reset seat requested flag

        // Remove from local list to trigger button visibility correctly
        seatedUsers.values.removeIf { it.id == localUserId }
        
        setupControls() // Refresh Visibility
        updateSeats()   // Refresh UI
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
             val token = intent.getStringExtra("TOKEN") ?: ""
             val serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
             startRtc(serverUrl, token)
        }
    }
}
