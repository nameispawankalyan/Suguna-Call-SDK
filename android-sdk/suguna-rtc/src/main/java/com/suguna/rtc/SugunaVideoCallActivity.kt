package com.suguna.rtc

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import io.livekit.android.room.track.VideoTrack
import java.util.Locale

class SugunaVideoCallActivity : AppCompatActivity() {

    private lateinit var sugunaClient: SugunaClient
    private var handler = Handler(Looper.getMainLooper())
    
    // UI Elements
    private lateinit var videoRemote: SugunaVideoView
    private lateinit var videoLocal: SugunaVideoView
    private lateinit var cardLocalVideo: CardView
    private lateinit var uiContainer: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var tvRemoteName: TextView
    private lateinit var tvDuration: TextView
    
    // Controls
    private lateinit var btnMute: ImageButton
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnEndCall: ImageButton
    private lateinit var btnAddCoins: android.widget.LinearLayout
    
    private var localUserId: String = ""
    private var remoteUserId: String = "" 
    
    // Tracks for Swapping
    private var localTrack: VideoTrack? = null
    private var remoteTrack: VideoTrack? = null
    private var isSwapped = false
    
    // UI Hiding
    private val hideUiRunnable = Runnable { 
        if (!isDestroyed && !isFinishing) {
            uiContainer.animate().alpha(0f).setDuration(300).withEndAction {
                uiContainer.visibility = View.GONE
                snapPipToCorner() // Move PIP back to corner when UI auto-hides
            }.start()
        }
    }
    
    // Usage Variables
    private var isMuted = false
    // Video Calls default to Speaker ON
    private var isSpeakerOn = true 
    
    // Timer & Coin Logic
    private var totalSeconds = 0L
    private var coins = 0L
    private var isSender = false
    private var pricePerMin = 60
    private var isViolationDialogShowing = false
    
    private val REQUEST_PERMISSION_CODE = 1002
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_suguna_video_call)
        
        // 🛑 Block Back Press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing
            }
        })
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
             window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
       
       // Volume Control Stream
       volumeControlStream = android.media.AudioManager.STREAM_VOICE_CALL

        // 0. Check Permissions (Audio + Camera)
        val permissions = arrayOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CAMERA
        )
        var allGranted = true
        for (perm in permissions) {
            if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                allGranted = false
                break
            }
        }
        
        if (!allGranted) {
            requestPermissions(permissions, REQUEST_PERMISSION_CODE)
        }

        // 1. Get Data from Intent
        val token = intent.getStringExtra("TOKEN") ?: ""
        val serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        val userName = intent.getStringExtra("USER_NAME") ?: "Unknown"
        val userImage = intent.getStringExtra("USER_IMAGE") ?: ""
        // ... (Keep existing Intent extraction)
        val userId = intent.getStringExtra("USER_ID") ?: ""
        val rName = intent.getStringExtra("REMOTE_NAME") ?: "FriendZone User"
        coins = intent.getLongExtra("COINS", 0L)
        
        localUserId = userId 

        if (token.isEmpty()) {
            Toast.makeText(this, "Error: Invalid Token", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Determine Logic Role
        isSender = intent.getBooleanExtra("IS_SENDER", false)
        
        if (isSender) {
            totalSeconds = if (pricePerMin > 0) (coins / pricePerMin) * 60L else 0
        }

        // 2. Setup UI
        initViews()
        setupClickListeners()
        
        tvRemoteName.text = rName

        // Start Auto-Hide Timer
        resetAutoHideTimer()

        // 3. Initialize SDK
        sugunaClient = SugunaClient(this, serverUrl)
        
        sugunaClient.setEventListener(object : SugunaClient.SugunaEvents {
            override fun onConnected(userId: String) {
                localUserId = userId
                runOnUiThread { forceSpeakerOutput(true) }
            }

            override fun onLocalStreamReady(videoTrack: VideoTrack) {
                if (localTrack == videoTrack) return
                
                android.util.Log.d("SugunaVideoCall", "Local track ready: ${videoTrack.sid}")
                val oldTrack = localTrack
                localTrack = videoTrack
                
                runOnUiThread {
                    // Detach from all views to be sure
                    oldTrack?.removeRenderer(videoLocal)
                    oldTrack?.removeRenderer(videoRemote)
                    
                    if (!isSwapped) {
                        safeAttachTrack(videoTrack, videoLocal, true)
                        cardLocalVideo.visibility = View.VISIBLE
                    } else {
                        safeAttachTrack(videoTrack, videoRemote, true)
                    }
                }
            }

            override fun onRemoteStreamReady(userId: String?, videoTrack: VideoTrack) {
                if (remoteTrack == videoTrack) return
                
                android.util.Log.d("SugunaVideoCall", "Remote track ready: ${videoTrack.sid} from $userId")
                val oldTrack = remoteTrack
                remoteTrack = videoTrack
                
                runOnUiThread {
                    // Detach from all views to be sure
                    oldTrack?.removeRenderer(videoLocal)
                    oldTrack?.removeRenderer(videoRemote)
                    
                    if (!isSwapped) {
                        safeAttachTrack(videoTrack, videoRemote, false)
                    } else {
                        safeAttachTrack(videoTrack, videoLocal, false)
                        cardLocalVideo.visibility = View.VISIBLE
                    }
                }
            }

            override fun onLocalStreamUpdate(videoTrack: VideoTrack, isMuted: Boolean) {
                android.util.Log.d("SugunaVideoCall", "Local track update: Muted=$isMuted")
            }

            override fun onRemoteStreamUpdate(userId: String?, videoTrack: VideoTrack, isMuted: Boolean) {
                android.util.Log.d("SugunaVideoCall", "Remote track update from $userId: Muted=$isMuted")
                if (!isMuted) {
                    // User returned from background! Force re-attach and layout refresh
                    runOnUiThread {
                        if (!isSwapped) {
                            safeAttachTrack(videoTrack, videoRemote, false)
                        } else {
                            safeAttachTrack(videoTrack, videoLocal, false)
                            cardLocalVideo.visibility = View.VISIBLE
                        }
                    }
                }
            }

            override fun onUserJoined(participant: io.livekit.android.room.participant.RemoteParticipant) {
                runOnUiThread {
                    remoteUserId = participant.identity?.value ?: ""
                    val pName = participant.name
                    if (!pName.isNullOrEmpty()) {
                        val isGeneric = pName == "Caller" || pName == "Receiver" || pName == "Unknown" || pName == "null"
                        if (!isGeneric) {
                            tvRemoteName.text = pName
                        }
                    }
                    Toast.makeText(this@SugunaVideoCallActivity, "${tvRemoteName.text} Joined", Toast.LENGTH_SHORT).show()
                }
            }
            // ... (Rest of event listeners)
            override fun onActiveSpeakerChanged(speakers: List<String>) {}
            
            override fun onDataReceived(data: String) {
                 if (data.startsWith("SYNC_TIME:")) {
                    val remoteSeconds = data.substringAfter("SYNC_TIME:").toLongOrNull()
                    if (remoteSeconds != null) {
                        runOnUiThread { updateTimerUI(remoteSeconds) }
                    }
                } else {
                    try {
                        val json = org.json.JSONObject(data)
                        if (json.optString("type") == "SUGUNA_SIGNAL") {
                            val targetId = json.optString("target_id")
                            val isTargetMe = targetId.trim().equals(localUserId.trim(), ignoreCase = true)
                            runOnUiThread { handleAiSignal(json, isTargetMe) }
                        }
                    } catch (e: Exception) {}
                }
            }

            override fun onUserLeft(userId: String?) {
                runOnUiThread {
                    Toast.makeText(this@SugunaVideoCallActivity, "User Disconnected", Toast.LENGTH_SHORT).show()
                    endCall()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@SugunaVideoCallActivity, "Error: $message", Toast.LENGTH_LONG).show()
                }
            }
        })
        
        // 4. Start Video Call
        sugunaClient.initialize(token, SugunaClient.ROLE_HOST, isVideoCall = true, defaultSpeakerOn = true)
        
        startSyncTimer()
        
        handler.postDelayed({
             forceSpeakerOutput(true)
        }, 800)
    }

    private fun initViews() {
        videoRemote = findViewById(R.id.videoRemote)
        videoLocal = findViewById(R.id.videoLocal)
        cardLocalVideo = findViewById(R.id.cardLocalVideo)
        // Ensure PIP is visible (with black bg)
        cardLocalVideo.visibility = View.VISIBLE
        
        // Ensure PIP starts at the correct position (above buttons since UI is visible by default)
        cardLocalVideo.post { snapPipToCorner() }
        
        uiContainer = findViewById(R.id.uiContainer)
        
        tvRemoteName = findViewById(R.id.tvRemoteName)
        tvDuration = findViewById(R.id.tvDuration)
        
        btnMute = findViewById(R.id.btnMute)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnEndCall = findViewById(R.id.btnEndCall)
        
        btnAddCoins = findViewById(R.id.btnAddCoins)
        if (isSender) {
            btnAddCoins.visibility = View.VISIBLE
        } else {
            btnAddCoins.visibility = View.GONE
        }
    }

    private fun setupClickListeners() {
        // Toggle Overlay on Fullscreen Click
        videoRemote.setOnClickListener {
             toggleUiVisibility()
        }
        
        // Reset Timer on any UI interaction
        uiContainer.setOnClickListener { resetAutoHideTimer() }

        // Draggable PIP with Tap-to-Swap
        setupLocalVideoDrag()

        btnMute.setOnClickListener {
            resetAutoHideTimer()
            animateButtonClick(btnMute)
            isMuted = !isMuted
            sugunaClient.setMicrophoneEnabled(!isMuted)
            
            if (isMuted) {
                btnMute.setBackgroundResource(R.drawable.bg_control_danger_glass)
                btnMute.setImageResource(R.drawable.ic_mic_off)
                btnMute.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
            } else {
                btnMute.setBackgroundResource(R.drawable.bg_control_glass)
                btnMute.setImageResource(R.drawable.ic_mic_on)
                btnMute.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
            }
        }

        btnSwitchCamera.setOnClickListener {
            resetAutoHideTimer()
            animateButtonClick(btnSwitchCamera)
            sugunaClient.switchCamera()
        }

        btnEndCall.setOnClickListener {
            animateButtonClick(btnEndCall)
            endCall()
        }
        
        btnAddCoins.setOnClickListener {
            resetAutoHideTimer()
            animateButtonClick(btnAddCoins)
            val intent = Intent("com.suguna.rtc.ACTION_ADD_COINS")
            sendBroadcast(intent)
        }
    }
    
    private fun toggleUiVisibility() {
        if (uiContainer.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideUiRunnable)
            uiContainer.animate().alpha(0f).setDuration(300).withEndAction {
                uiContainer.visibility = View.GONE
                snapPipToCorner() // Move PIP to corner when UI hides
            }.start()
        } else {
            uiContainer.alpha = 0f
            uiContainer.visibility = View.VISIBLE
            uiContainer.animate().alpha(1f).setDuration(300).start()
            // Don't snap immediately, let the user see the overlapping if any, 
            // or we can snap if desired. Let's snap to ensure it's above buttons.
            snapPipToCorner() 
            resetAutoHideTimer()
        }
    }
    
    private fun resetAutoHideTimer() {
        handler.removeCallbacks(hideUiRunnable)
        if (uiContainer.visibility == View.VISIBLE) {
            handler.postDelayed(hideUiRunnable, 5000)
        }
    }
    
    private fun setupLocalVideoDrag() {
        cardLocalVideo.setOnTouchListener(object : View.OnTouchListener {
            private var dX = 0f
            private var dY = 0f
            private var downRawX = 0f
            private var downRawY = 0f
            private val CLICK_DRAG_TOLERANCE = 10f 

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val parent = view.parent as View
                val parentWidth = parent.width
                val parentHeight = parent.height
                
                // Margin in pixels (20dp for more padding vs 16dp)
                val margin = 20 * resources.displayMetrics.density

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = view.x - event.rawX
                        dY = view.y - event.rawY
                        downRawX = event.rawX
                        downRawY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        var newX = event.rawX + dX
                        var newY = event.rawY + dY
                        
                        // Limit to screen bounds
                        newX = newX.coerceIn(0f, (parentWidth - view.width).toFloat())
                        newY = newY.coerceIn(0f, (parentHeight - view.height).toFloat())

                        view.animate().x(newX).y(newY).setDuration(0).start()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val deltaX = kotlin.math.abs(event.rawX - downRawX)
                        val deltaY = kotlin.math.abs(event.rawY - downRawY)
                        
                        // Detect Click
                        if (deltaX < CLICK_DRAG_TOLERANCE && deltaY < CLICK_DRAG_TOLERANCE) {
                             onLocalVideoClick()
                             return true
                        }
                        
                        // Drag Release -> Snap to Corner
                        snapPipToCorner()
                        return true
                    }
                }
                return false
            }
        })
    }
    
    private fun snapPipToCorner() {
        val parent = cardLocalVideo.parent as View
        val parentWidth = parent.width
        val parentHeight = parent.height
        
        val margin = 20 * resources.displayMetrics.density
        
        // Target Y depends on UI visibility
        // If UI is visible, bottom margin should be above the control panel
        val bottomMargin = if (uiContainer.visibility == View.VISIBLE) {
            // controls height + padding + safe margin
            200 * resources.displayMetrics.density
        } else {
            margin + 40 * resources.displayMetrics.density // Bottom padding for status bar/rounded corners
        }
        
        val topMargin = margin + 40 * resources.displayMetrics.density // Header padding
        
        val viewCenterX = cardLocalVideo.x + cardLocalVideo.width / 2
        val viewCenterY = cardLocalVideo.y + cardLocalVideo.height / 2
        
        val targetX = if (viewCenterX < parentWidth / 2) margin else (parentWidth - cardLocalVideo.width - margin)
        
        val targetY = if (viewCenterY < parentHeight / 2) {
            topMargin
        } else {
            parentHeight - cardLocalVideo.height - bottomMargin
        }
        
        cardLocalVideo.animate()
            .x(targetX)
            .y(targetY)
            .setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }
    
    private fun onLocalVideoClick() {
        animateButtonClick(cardLocalVideo)
        isSwapped = !isSwapped
        
        runOnUiThread {
            if (isSwapped) {
                // Swapped: Local on Big (videoRemote), Remote on PIP (videoLocal)
                localTrack?.let { 
                    it.removeRenderer(videoLocal)
                    safeAttachTrack(it, videoRemote, true) 
                }
                remoteTrack?.let { 
                    it.removeRenderer(videoRemote)
                    safeAttachTrack(it, videoLocal, false) 
                }
            } else {
                // Not Swapped: Remote on Big (videoRemote), Local on PIP (videoLocal)
                remoteTrack?.let { 
                    it.removeRenderer(videoLocal)
                    safeAttachTrack(it, videoRemote, false) 
                }
                localTrack?.let { 
                    it.removeRenderer(videoRemote)
                    safeAttachTrack(it, videoLocal, true) 
                }
            }
        }
    }

    private fun safeAttachTrack(track: VideoTrack, view: SugunaVideoView, isLocal: Boolean) {
        runOnUiThread {
            view.visibility = View.VISIBLE
            SugunaClient.attachTrackToView(track, view, isLocal)
            view.requestLayout()
        }
    }

    private fun animateButtonClick(view: View) {
        view.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }
    
    private fun startSyncTimer() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isSender) {
                    if (totalSeconds > 0) {
                        totalSeconds--
                        updateTimerUI(totalSeconds)
                        sugunaClient.publishData("SYNC_TIME:$totalSeconds")
                    } else {
                        sugunaClient.publishData("SYNC_TIME:0")
                        endCall()
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }
    
    private fun updateTimerUI(secondsLeft: Long) {
        val days = secondsLeft / (24 * 3600)
        val hours = (secondsLeft % (24 * 3600)) / 3600
        val minutes = (secondsLeft % 3600) / 60
        val seconds = secondsLeft % 60
        
        val timeString = when {
            days > 0 -> String.format(Locale.US, "%02d:%02d:%02d:%02d", days, hours, minutes, seconds)
            hours > 0 -> String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            else -> String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
        
        tvDuration.text = timeString

        if (secondsLeft == 120L || secondsLeft == 90L || secondsLeft == 60L) {
            triggerVibrationAlert(5)
        }
        
        if (secondsLeft <= 0 && !isSender) {
             Toast.makeText(this, "Time Up!", Toast.LENGTH_SHORT).show()
             endCall()
        }
    }

    private fun triggerVibrationAlert(count: Int) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (vibrator.hasVibrator()) {
                val pattern = LongArray(count * 2)
                for (i in 0 until count) {
                    pattern[i * 2] = 200L 
                    pattern[i * 2 + 1] = 300L
                }
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun forceSpeakerOutput(enable: Boolean) {
        try {
            // 1. Tell ConnectionService (System) to switch route
            val routeIntent = Intent("com.suguna.rtc.ACTION_REQUEST_AUDIO_ROUTE")
            routeIntent.putExtra("IS_SPEAKER", enable)
            routeIntent.setPackage(packageName)
            sendBroadcast(routeIntent)

            // 2. Local AudioManager manipulation (Backup/Overlay)
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            
            if (audioManager.mode != android.media.AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            }
            
            if (enable) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = true
            } else {
                audioManager.isSpeakerphoneOn = false
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
            sugunaClient.setSpeakerphoneEnabled(enable)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun endCall() {
        val roomName = intent.getStringExtra("CALL_ID") ?: ""
        if (roomName.isNotEmpty()) {
            val endCallIntent = Intent("com.suguna.rtc.ACTION_END_CALL")
            endCallIntent.putExtra("ROOM_NAME", roomName)
            sendBroadcast(endCallIntent)
        }
        sugunaClient.leaveRoom()
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        sugunaClient.leaveRoom()
        handler.removeCallbacksAndMessages(null)
        
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                // Permission Granted
                sugunaClient.setMicrophoneEnabled(true)
                sugunaClient.setCameraEnabled(true)
            } else {
                Toast.makeText(this, "Permissions required for video calls", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    companion object {
        fun start(
            context: Context,
            token: String,
            serverUrl: String,
            callId: String,
            userId: String,
            userName: String,
            userImage: String,
            remoteName: String = "",
            remoteImage: String = "",
            coins: Long,
            isSender: Boolean,
            webhookUrl: String
        ) {
            val intent = Intent(context, SugunaVideoCallActivity::class.java).apply {
                putExtra("TOKEN", token)
                putExtra("SERVER_URL", serverUrl)
                putExtra("CALL_ID", callId)
                putExtra("USER_ID", userId)
                putExtra("USER_NAME", userName)
                putExtra("USER_IMAGE", userImage)
                putExtra("REMOTE_NAME", remoteName)
                putExtra("REMOTE_IMAGE", remoteImage)
                putExtra("COINS", coins)
                putExtra("IS_SENDER", isSender)
                putExtra("WEBHOOK_URL", webhookUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
    
     private fun handleAiSignal(json: org.json.JSONObject, isTargetMe: Boolean) {
        val action = json.optString("action")
        val strikes = json.optInt("strike_count", 0)
        val reason = json.optString("reason", "Violation detected")
        val message = json.optString("message", "")

        when (action) {
            "VIOLATION_SIGNAL" -> {
                showViolationDialog(strikes, reason, message, isTargetMe)
            }
        }
    }

    private fun showViolationDialog(strikes: Int, reason: String, message: String, isTargetMe: Boolean) {
        if (isViolationDialogShowing) {
            return
        }
        
        isViolationDialogShowing = true
        
        val builder = androidx.appcompat.app.AlertDialog.Builder(this@SugunaVideoCallActivity)
        builder.setCancelable(false)
        
        val title = if (isTargetMe) "Security Warning" else "Partner Violation Warning"
        val displayMessage = message.ifEmpty { "Violation: $reason. Strike $strikes/3" }

        if (!isTargetMe) {
             builder.setTitle(title)
             builder.setMessage("Your partner violated safety rules ($reason).\nStrike $strikes/3 issued to them.")
             builder.setPositiveButton("OK") { _, _ -> 
                 isViolationDialogShowing = false 
             }
             builder.create().show()
             return
        }

        builder.setTitle(title)
        builder.setMessage(displayMessage)

        when (strikes) {
            1 -> {
                sugunaClient.setMicrophoneEnabled(false)
                isMuted = true
                btnMute.setBackgroundResource(R.drawable.bg_control_danger_glass)
                
                builder.setMessage("$displayMessage\n\nYour microphone is muted for 30 seconds.")
                
                val dialog = builder.create()
                dialog.show()

                val timerTextView = dialog.findViewById<TextView>(android.R.id.message)
                
                object : android.os.CountDownTimer(30000, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        val secondsRemaining = millisUntilFinished / 1000
                        timerTextView?.text = "$displayMessage\n\nMicrophone muted: ${secondsRemaining}s remaining."
                    }

                    override fun onFinish() {
                        sugunaClient.setMicrophoneEnabled(true)
                        isMuted = false
                        btnMute.setBackgroundResource(R.drawable.bg_control_glass)
                        dialog.dismiss()
                        isViolationDialogShowing = false
                        Toast.makeText(this@SugunaVideoCallActivity, "Microphone Restored", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            2 -> {
                builder.setTitle("LAST WARNING")
                builder.setMessage("Violation detected. Call will end in 5 seconds.")
                builder.setPositiveButton("End Call Now") { _, _ -> 
                    isViolationDialogShowing = false
                    endCall() 
                }
                val dialog = builder.create()
                dialog.show()
                
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (dialog.isShowing) {
                        dialog.dismiss()
                        isViolationDialogShowing = false
                        endCall()
                    }
                }, 5000)
            }
            3 -> {
                builder.setTitle("ACCOUNT BANNED")
                builder.setMessage("Your account has been permanently banned. Call ending...")
                builder.setPositiveButton("Exit") { _, _ -> 
                    isViolationDialogShowing = false
                    endCall() 
                }
                val dialog = builder.create()
                dialog.show()
                
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (dialog.isShowing) {
                        dialog.dismiss()
                        isViolationDialogShowing = false
                        endCall()
                    }
                }, 5000)
            }
        }
    }
}
