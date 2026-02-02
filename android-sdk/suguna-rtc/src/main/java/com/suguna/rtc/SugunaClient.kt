package com.suguna.rtc

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.media.AudioManager
import android.media.AudioDeviceInfo
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.EglBase
import android.widget.Toast

class SugunaClient(private val context: Context, private val serverUrl: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var room: Room? = null
    private var eventListener: SugunaEvents? = null
    
    // Audio Manager
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    companion object {
        const val ROLE_HOST = "host"
        const val ROLE_AUDIENCE = "audience"
        
        private val eglBase by lazy { EglBase.create() }

        @JvmStatic
        fun attachTrackToView(
            track: VideoTrack,
            view: SugunaVideoView,
            isLocal: Boolean
        ) {
            view.post {
                System.out.println("DEBUG: Attaching Track. Enabled=${track.enabled}")
                Toast.makeText(view.context, "Render: $isLocal", Toast.LENGTH_SHORT).show()
                
                view.safeInit(eglBase.eglBaseContext)
                view.setMirror(isLocal)
                view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                view.setEnableHardwareScaler(true) /* Force Hardware Scaler */

                track.removeRenderer(view)
                track.addRenderer(view)
                
                view.requestLayout()
                view.invalidate()
            }
        }
    }

    interface SugunaEvents {
        fun onLocalStreamReady(videoTrack: VideoTrack)
        fun onRemoteStreamReady(userId: String?, videoTrack: VideoTrack)
        fun onUserLeft(userId: String?)
        fun onError(message: String)
    }

    fun setEventListener(listener: SugunaEvents) {
        this.eventListener = listener
    }

    fun initialize(token: String, role: String, isVideoCall: Boolean = true, defaultSpeakerOn: Boolean? = null) {
        scope.launch {
            try {
                // Initialize Room
                room = LiveKit.create(context)
                setupRoomListeners()

                // Connect
                room?.connect(serverUrl, token)
                
                // Configure Audio Mode
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                
                // Default Speaker Logic: If manual setting provided, use it. Else, Video=Speaker, Audio=Earpiece.
                val initialSpeakerState = defaultSpeakerOn ?: isVideoCall 
                setSpeakerphoneEnabled(initialSpeakerState)
                
                if (role == ROLE_HOST) {
                    // Check Permission First - Audio is mandatory for calls
                    if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                         eventListener?.onError("Mic Permission Missing!")
                         return@launch
                    }
                    
                    // Check Camera Permission only if doing Video Call
                    if (isVideoCall && context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                         eventListener?.onError("Camera Permission Missing!")
                         return@launch
                    }

                    // Enable mic (always for host)
                    room?.localParticipant?.setMicrophoneEnabled(true)

                    // Enable camera only if Video Call
                    if (isVideoCall) {
                        room?.localParticipant?.setCameraEnabled(true)
                    }
                }
            } catch (e: Exception) {
                eventListener?.onError("SDK Init Error: ${e.message}")
            }
        }
    }
    
    fun setSpeakerphoneEnabled(enable: Boolean) {
        var isHeadsetConnected = false
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            val type = device.type
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || 
                type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || 
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                isHeadsetConnected = true
                break
            }
        }

        if (isHeadsetConnected) {
            // Priority: Bluetooth/Headset (Disable Speakerphone)
            audioManager.isSpeakerphoneOn = false
        } else {
            // Toggle Speakerphone
            audioManager.isSpeakerphoneOn = enable
        }
    }

    private fun setupRoomListeners() {
        scope.launch {
            room?.events?.collect { event ->
                when (event) {
                    // ✅ LOCAL VIDEO
                    is RoomEvent.TrackPublished -> {
                        if (event.participant is LocalParticipant) {
                            val track = event.publication.track
                            if (track is VideoTrack) {
                                eventListener?.onLocalStreamReady(track)
                            }
                        }
                    }

                    // ✅ REMOTE VIDEO
                    is RoomEvent.TrackSubscribed -> {
                        val track = event.track
                        if (track is VideoTrack) {
                            eventListener?.onRemoteStreamReady(event.participant.identity?.value, track)
                        }
                    }

                    is RoomEvent.ParticipantDisconnected -> {
                        eventListener?.onUserLeft(event.participant.identity?.value)
                    }
                    else -> {}
                }
            }
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        scope.launch { room?.localParticipant?.setMicrophoneEnabled(enabled) }
    }

    fun setCameraEnabled(enabled: Boolean) {
        scope.launch { room?.localParticipant?.setCameraEnabled(enabled) }
    }

    fun leaveRoom() {
        room?.disconnect()
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
