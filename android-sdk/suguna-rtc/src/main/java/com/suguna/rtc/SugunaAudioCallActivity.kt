package com.suguna.rtc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_suguna_audio_call)
        
        // Prevent Screenshots/Screen Recording
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)

        // 1. Get Data from Intent
        val token = intent.getStringExtra("TOKEN") ?: ""
        val serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        val userName = intent.getStringExtra("USER_NAME") ?: "Unknown"
        val userImage = intent.getStringExtra("USER_IMAGE") ?: ""
        val userId = intent.getStringExtra("USER_ID") ?: ""
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

        // 3. Initialize SDK
        sugunaClient = SugunaClient(this, serverUrl)
        
        sugunaClient.setEventListener(object : SugunaClient.SugunaEvents {
            override fun onConnected(userId: String) {
                // Update Local ID with the authentic one from LiveKit
                localUserId = userId
            }

            override fun onLocalStreamReady(videoTrack: VideoTrack) {}

            override fun onRemoteStreamReady(userId: String?, videoTrack: VideoTrack) {}

            override fun onUserJoined(participant: io.livekit.android.room.participant.RemoteParticipant) {
                runOnUiThread {
                    val name = if (participant.name.isNullOrEmpty()) participant.identity?.value ?: "Unknown" else participant.name
                    remoteUserId = participant.identity?.value ?: "" // Capture remote ID
                    
                    tvRemoteName.text = name
                    val imageUrl = "https://ui-avatars.com/api/?name=$name&background=random&size=200"
                    Glide.with(this@SugunaAudioCallActivity).load(imageUrl).placeholder(R.drawable.circle_outline_white_20).into(ivRemoteProfile)
                    Toast.makeText(this@SugunaAudioCallActivity, "$name Joined", Toast.LENGTH_SHORT).show()
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
        
        if (secondsLeft <= 0 && !isSender) {
             Toast.makeText(this, "Time Up!", Toast.LENGTH_SHORT).show()
             endCall()
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

    private fun endCall() {
        sugunaClient.leaveRoom()
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        sugunaClient.leaveRoom()
        handler.removeCallbacksAndMessages(null)
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
                putExtra("COINS", coins)
                putExtra("IS_SENDER", isSender)
                putExtra("WEBHOOK_URL", webhookUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
