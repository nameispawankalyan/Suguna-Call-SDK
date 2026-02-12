package com.suguna.rtc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.DisconnectCause
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import io.livekit.android.room.track.VideoTrack
import java.util.Locale

class SugunaAudioCallActivity : AppCompatActivity() {

    private lateinit var sugunaClient: SugunaClient
    private var handler = Handler(Looper.getMainLooper())
    
    // UI Elements
    private lateinit var tvLocalName: TextView
    private lateinit var tvRemoteName: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnMute: ImageButton
    private lateinit var btnSpeaker: ImageButton
    private lateinit var btnEndCall: ImageButton
    private lateinit var btnAddCoins: android.widget.LinearLayout
    private lateinit var ivLocalProfile: ImageView
    private lateinit var ivRemoteProfile: ImageView

    // Ripple Views (3 Layers Each)
    private lateinit var viewLocalRipple1: View
    private lateinit var viewLocalRipple2: View
    private lateinit var viewLocalRipple3: View
    
    private lateinit var viewRemoteRipple1: View
    private lateinit var viewRemoteRipple2: View
    private lateinit var viewRemoteRipple3: View

    private var localUserId: String = ""
    private var remoteUserId: String = "" 
    
    // Usage Variables
    private var isMuted = false
    private var isSpeakerOn = false
    
    // Timer & Coin Logic
    private var totalSeconds = 0L
    private var coins = 0L
    private var isSender = false
    private var pricePerMin = 20

    private val REQUEST_PERMISSION_CODE = 1001
    
    // Track if violation dialog is currently showing
    private var isViolationDialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_suguna_audio_call)
        
        // 🛑 Block Back Press: Ensures both gesture navigation and buttons are disabled
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing: User must click "End Call" to exit
            }
        })
        
        
        // Prevent Screenshots/Screen Recording and Keep Screen On
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
       // window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
       
       // Ensure Volume Buttons control Voice Call volume
       volumeControlStream = android.media.AudioManager.STREAM_VOICE_CALL

        // 0. Check Permissions
        if (checkSelfPermission(android.Manifest.`permission`.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.`permission`.RECORD_AUDIO), REQUEST_PERMISSION_CODE)
        }

        // 1. Get Data from Intent
        val token = intent.getStringExtra("TOKEN") ?: ""
        val serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        val userName = intent.getStringExtra("USER_NAME") ?: "Unknown"
        val userImage = intent.getStringExtra("USER_IMAGE") ?: ""
        val userId = intent.getStringExtra("USER_ID") ?: ""
        val rName = intent.getStringExtra("REMOTE_NAME") ?: "FriendZone User"
        val rImage = intent.getStringExtra("REMOTE_IMAGE") ?: ""
        coins = intent.getLongExtra("COINS", 0L)
        
        localUserId = userId 

        if (token.isEmpty()) {
            Toast.makeText(this, "Error: Invalid Token", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Determine Logic Role: Sender (Payer) vs Receiver
        isSender = intent.getBooleanExtra("IS_SENDER", false)
        
        if (isSender) {
            totalSeconds = if (pricePerMin > 0) (coins / pricePerMin) * 60L else 0
        }

        // 2. Setup UI
        initViews()
        setupClickListeners()
        
        tvLocalName.text = userName
        if (userImage.isNotEmpty()) {
            Glide.with(this).load(userImage).placeholder(R.drawable.circle_outline_white_20).into(ivLocalProfile)
        }
        
        tvRemoteName.text = rName
        if (rImage.isNotEmpty()) {
            Glide.with(this).load(rImage).placeholder(R.drawable.circle_outline_white_20).into(ivRemoteProfile)
        }

        // 3. Initialize SDK
        sugunaClient = SugunaClient(this, serverUrl)
        
        sugunaClient.setEventListener(object : SugunaClient.SugunaEvents {
            override fun onConnected(userId: String) {
                // Update Local ID with the authentic one from LiveKit
                localUserId = userId
                runOnUiThread { forceSpeakerOutput(isSpeakerOn) }
            }

            override fun onLocalStreamReady(videoTrack: VideoTrack) {}

            override fun onRemoteStreamReady(userId: String?, videoTrack: VideoTrack) {}

            override fun onUserJoined(participant: io.livekit.android.room.participant.RemoteParticipant) {
                runOnUiThread {
                    remoteUserId = participant.identity?.value ?: "" // Capture remote ID
                    
                    // 🛡️ Safety: Only update name if it's a REAL name (not generic SDK labels)
                    val pName = participant.name
                    if (!pName.isNullOrEmpty()) {
                        val isGeneric = pName == "Caller" || pName == "Receiver" || pName == "Unknown" || pName == "null"
                        if (!isGeneric) {
                            tvRemoteName.text = pName
                        }
                    }
                    
                    Toast.makeText(this@SugunaAudioCallActivity, "${tvRemoteName.text} Joined", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onActiveSpeakerChanged(speakers: List<String>) {
                runOnUiThread {
                    // Check if Local User is Speaking
                    if (speakers.contains(localUserId)) {
                        startRippleAnimation(viewLocalRipple1, viewLocalRipple2, viewLocalRipple3)
                    } else {
                        stopRippleAnimation(viewLocalRipple1, viewLocalRipple2, viewLocalRipple3)
                    }

                    // Check if Remote User is Speaking
                    if (speakers.contains(remoteUserId)) {
                         startRippleAnimation(viewRemoteRipple1, viewRemoteRipple2, viewRemoteRipple3)
                    } else {
                         stopRippleAnimation(viewRemoteRipple1, viewRemoteRipple2, viewRemoteRipple3)
                    }
                }
            }
            
            override fun onDataReceived(data: String) {
                if (data.startsWith("SYNC_TIME:")) {
                    val remoteSeconds = data.substringAfter("SYNC_TIME:").toLongOrNull()
                    if (remoteSeconds != null) {
                        runOnUiThread {
                            updateTimerUI(remoteSeconds)
                        }
                    }
                } else {
                    // Handle AI Signals
                    try {
                        val json = org.json.JSONObject(data)
                        if (json.optString("type") == "SUGUNA_SIGNAL") {
                            val action = json.optString("action")
                            val targetId = json.optString("target_id")
                            
                            val isTargetMe = targetId.trim().equals(localUserId.trim(), ignoreCase = true)
                            runOnUiThread {
                                handleAiSignal(json, isTargetMe)
                            }
                        }
                    } catch (e: Exception) {}
                }
            }

            override fun onUserLeft(userId: String?) {
                runOnUiThread {
                    Toast.makeText(this@SugunaAudioCallActivity, "User Disconnected", Toast.LENGTH_SHORT).show()
                    endCall()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@SugunaAudioCallActivity, "Error: $message", Toast.LENGTH_LONG).show()
                }
            }
        })
        
        // 4. Start Call
        sugunaClient.initialize(token, SugunaClient.ROLE_HOST, isVideoCall = false, defaultSpeakerOn = true)
        
        // Start Sync Timer
        startSyncTimer()
        
        // Visual
        handler.postDelayed({
            tvDuration.visibility = View.VISIBLE
        }, 1000)

        // 🔥 Force Audio Routing on Start
        handler.postDelayed({
             forceSpeakerOutput(true)
        }, 800)
    }

    private fun initViews() {
        tvLocalName = findViewById(R.id.tvLocalName)
        tvRemoteName = findViewById(R.id.tvRemoteName)
        ivLocalProfile = findViewById(R.id.ivLocalProfile)
        ivRemoteProfile = findViewById(R.id.ivRemoteProfile)
        
        // Local Ripples
        viewLocalRipple1 = findViewById(R.id.viewLocalRipple1)
        viewLocalRipple2 = findViewById(R.id.viewLocalRipple2)
        viewLocalRipple3 = findViewById(R.id.viewLocalRipple3)
        
        // Remote Ripples
        viewRemoteRipple1 = findViewById(R.id.viewRemoteRipple1)
        viewRemoteRipple2 = findViewById(R.id.viewRemoteRipple2)
        viewRemoteRipple3 = findViewById(R.id.viewRemoteRipple3)
        
        tvDuration = findViewById(R.id.tvDuration)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnEndCall = findViewById(R.id.btnEndCall)
        
        // Set Default Speaker State (ON) UI
        isSpeakerOn = true
        btnSpeaker.setBackgroundResource(R.drawable.bg_control_active_white)
        btnSpeaker.setColorFilter(android.graphics.Color.BLACK) // Active = Black Icon
        
        btnAddCoins = findViewById(R.id.btnAddCoins)
        if (isSender) {
            btnAddCoins.visibility = View.VISIBLE
        } else {
            btnAddCoins.visibility = View.GONE
        }
    }

    private fun setupClickListeners() {
        btnMute.setOnClickListener {
            animateButtonClick(btnMute)
            isMuted = !isMuted
            sugunaClient.setMicrophoneEnabled(!isMuted)
            
            // UI Update: Muted (Mic Off) = Red, Unmuted (Mic On) = Glass
            if (isMuted) {
                btnMute.setBackgroundResource(R.drawable.bg_control_danger_glass)
                btnMute.setImageResource(R.drawable.ic_mic_off)
                btnMute.setColorFilter(android.graphics.Color.WHITE)
            } else {
                btnMute.setBackgroundResource(R.drawable.bg_control_glass)
                btnMute.setImageResource(R.drawable.ic_mic_on)
                btnMute.setColorFilter(android.graphics.Color.WHITE)
            }
        }

        btnSpeaker.setOnClickListener {
            animateButtonClick(btnSpeaker)
            isSpeakerOn = !isSpeakerOn
            sugunaClient.setSpeakerphoneEnabled(isSpeakerOn)
            
            // Force Audio Manager Routing
            forceSpeakerOutput(isSpeakerOn)
            
            // UI Update: Speaker ON = White BG + Black Icon, OFF = Glass BG + White Icon
            if (isSpeakerOn) {
                btnSpeaker.setBackgroundResource(R.drawable.bg_control_active_white)
                btnSpeaker.setColorFilter(android.graphics.Color.BLACK)
                btnSpeaker.alpha = 1.0f
            } else {
                btnSpeaker.setBackgroundResource(R.drawable.bg_control_glass)
                btnSpeaker.setColorFilter(android.graphics.Color.WHITE)
                btnSpeaker.alpha = 1.0f 
            }
            Toast.makeText(this, "Speaker ${if(isSpeakerOn) "On" else "Off"}", Toast.LENGTH_SHORT).show()
        }

        btnEndCall.setOnClickListener {
            animateButtonClick(btnEndCall)
            endCall()
        }
        
        btnAddCoins.setOnClickListener {
            animateButtonClick(btnAddCoins)
            // Notify Client via Broadcast (Client handles UI/Logic)
            val intent = Intent("com.suguna.rtc.ACTION_ADD_COINS")
            sendBroadcast(intent)
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
                    // SENDER Logic: Decrease time and Broadcast
                    if (totalSeconds > 0) {
                        totalSeconds--
                        updateTimerUI(totalSeconds)
                        // Broadcast to Receiver
                        sugunaClient.publishData("SYNC_TIME:$totalSeconds")
                    } else {
                        // Time Up!
                        sugunaClient.publishData("SYNC_TIME:0")
                        endCall()
                    }
                }
                // RECEIVER Logic: Does nothing here, waits for onDataReceived

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

        // 🔥 Milestone Vibration Alerts (2 min, 1.5 min, 1 min)
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
                // Short buzzes: 300ms on, 200ms off
                val pattern = LongArray(count * 2)
                for (i in 0 until count) {
                    pattern[i * 2] = 200L // Off
                    pattern[i * 2 + 1] = 300L // On
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

    private fun startRippleAnimation(v1: View, v2: View, v3: View) {
        if (v1.visibility == View.VISIBLE) return // Already animating

        v1.visibility = View.VISIBLE
        v2.visibility = View.VISIBLE
        v3.visibility = View.VISIBLE

        val anim1 = AnimationUtils.loadAnimation(this, R.anim.ripple_pulse)
        val anim2 = AnimationUtils.loadAnimation(this, R.anim.ripple_pulse)
        val anim3 = AnimationUtils.loadAnimation(this, R.anim.ripple_pulse)

        anim2.startOffset = 300 // Delay for 2nd wave
        anim3.startOffset = 600 // Delay for 3rd wave

        v1.startAnimation(anim1)
        v2.startAnimation(anim2)
        v3.startAnimation(anim3)
    }

    private fun stopRippleAnimation(v1: View, v2: View, v3: View) {
        if (v1.visibility == View.INVISIBLE) return

        v1.clearAnimation()
        v2.clearAnimation()
        v3.clearAnimation()

        v1.visibility = View.INVISIBLE
        v2.visibility = View.INVISIBLE
        v3.visibility = View.INVISIBLE
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
            
            // Ensure Communication Mode
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
            
            // Sync with SDK
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
        
        // Reset Audio Routing
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
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Permission Granted! Enable Mic
                sugunaClient.setMicrophoneEnabled(true)
            } else {
                Toast.makeText(this, "Microphone Permission is required for calls", Toast.LENGTH_LONG).show()
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
            val intent = Intent(context, SugunaAudioCallActivity::class.java).apply {
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
        // Prevent multiple dialogs from showing at once
        if (isViolationDialogShowing) {
            android.util.Log.d("SugunaCall", "Violation dialog already showing, ignoring new signal")
            return
        }
        
        isViolationDialogShowing = true
        
        val builder = androidx.appcompat.app.AlertDialog.Builder(this@SugunaAudioCallActivity)
        builder.setCancelable(false)
        
        val title = if (isTargetMe) "Security Warning" else "Partner Violation Warning"
        val displayMessage = message.ifEmpty { "Violation: $reason. Strike $strikes/3" }

        // Logic for Non-Target User (Sender) - Only Warning
        if (!isTargetMe) {
             builder.setTitle(title)
             builder.setMessage("Your partner violated safety rules ($reason).\nStrike $strikes/3 issued to them.")
             builder.setPositiveButton("OK") { _, _ -> 
                 isViolationDialogShowing = false 
             }
             builder.create().show()
             return
        }

        // --- Logic for Target (Receiver) - Penalties Apply ---
        builder.setTitle(title)
        builder.setMessage(displayMessage)

        when (strikes) {
            1 -> {
                // Strike 1: 30 Seconds Mute + Countdown
                sugunaClient.setMicrophoneEnabled(false)
                isMuted = true
                btnMute.setBackgroundResource(R.drawable.bg_control_danger_glass)
                btnMute.setImageResource(R.drawable.ic_mic_off)
                
                builder.setMessage("$displayMessage\n\nYour microphone is muted for 30 seconds.")
                
                val dialog = builder.create()
                dialog.show()

                // Countdown Timer for the Dialog & Unmute
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
                        btnMute.setImageResource(R.drawable.ic_mic_on)
                        dialog.dismiss()
                        isViolationDialogShowing = false // Reset flag
                        Toast.makeText(this@SugunaAudioCallActivity, "Microphone Restored", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            2 -> {
                // Strike 2: Last Warning -> End Call
                builder.setTitle("LAST WARNING")
                builder.setMessage("Violation detected. Call will end in 5 seconds.")
                builder.setPositiveButton("End Call Now") { _, _ -> 
                    isViolationDialogShowing = false
                    endCall() 
                }
                val dialog = builder.create()
                dialog.show()
                
                // Auto-end call after 5 seconds
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (dialog.isShowing) {
                        dialog.dismiss()
                        isViolationDialogShowing = false
                        endCall()
                    }
                }, 5000)
            }
            3 -> {
                // Strike 3: Banned -> End Call
                builder.setTitle("ACCOUNT BANNED")
                builder.setMessage("Your account has been permanently banned. Call ending...")
                builder.setPositiveButton("Exit") { _, _ -> 
                    isViolationDialogShowing = false
                    endCall() 
                }
                val dialog = builder.create()
                dialog.show()
                
                // Auto-end call after 5 seconds
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

    override fun onBackPressed() {
        super.onBackPressed()
        // 🛑 Block Back Press: User must click "End Call" button to exit.
        // This ensures the call logic (cleanup, credits, etc.) is handled via endCall().
    }
}
