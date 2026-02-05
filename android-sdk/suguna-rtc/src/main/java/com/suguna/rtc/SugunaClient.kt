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
        fun onConnected(userId: String) // ✅ Added to get authoritative Local ID
        fun onLocalStreamReady(videoTrack: VideoTrack)
        fun onRemoteStreamReady(userId: String?, videoTrack: VideoTrack)
        fun onUserJoined(participant: io.livekit.android.room.participant.RemoteParticipant)
        fun onUserLeft(userId: String?)
        // New Event: Active Speaker
        fun onActiveSpeakerChanged(speakers: List<String>) 
        fun onDataReceived(data: String)
        fun onError(message: String)
    }

    fun setEventListener(listener: SugunaEvents) {
        this.eventListener = listener
    }

    fun initialize(token: String, role: String, isVideoCall: Boolean = true, defaultSpeakerOn: Boolean? = null) {
        scope.launch {
            try {
                // Initialize Room (Standard VoIP for Earpiece/Bluetooth support)
                room = LiveKit.create(context)
                setupRoomListeners()

                // Connect
                room?.connect(serverUrl, token)
                
                // ✅ Notify Activity of the Actual Local ID
                val myIdentity = room?.localParticipant?.identity?.value ?: ""
                eventListener?.onConnected(myIdentity)
                
                // Trigger for already connected participants (if any)
                room?.remoteParticipants?.values?.forEach { participant ->
                    eventListener?.onUserJoined(participant)
                }
                
                // Configure Audio Mode - Must be COMMUNICATION for Earpiece/BT Routing
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                
                // Default Speaker Logic
                val initialSpeakerState = defaultSpeakerOn ?: isVideoCall 
                setSpeakerphoneEnabled(initialSpeakerState)
                
                if (role == ROLE_HOST) {
                    // Check Permission First
                    if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                         eventListener?.onError("Mic Permission Missing!")
                         return@launch
                    }
                    
                    if (isVideoCall && context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                         eventListener?.onError("Camera Permission Missing!")
                         return@launch
                    }

                    room?.localParticipant?.setMicrophoneEnabled(true)
                    if (isVideoCall) {
                        room?.localParticipant?.setCameraEnabled(true)
                    }
                }
            } catch (e: Exception) {
                eventListener?.onError("SDK Init Error: ${e.message}")
            }
        }
    }
    
    fun publishData(message: String) {
        scope.launch {
            try {
                val data = message.toByteArray(Charsets.UTF_8)
                room?.localParticipant?.publishData(data)
            } catch (e: Exception) {
                // Log error
            }
        }
    }
    
    fun setSpeakerphoneEnabled(enable: Boolean) {
        // Enforce Communication Mode for correct routing
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        
        if (enable) {
            // Speaker Requested: Turn off BT SCO, Turn ON Speaker
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = true
        } else {
            // Earpiece/BT Requested: Turn OFF Speaker, Enable BT SCO if available
            audioManager.isSpeakerphoneOn = false
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
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
                    
                    // ✅ USER JOINED
                    is RoomEvent.ParticipantConnected -> {
                         eventListener?.onUserJoined(event.participant)
                    }
                    
                    // ✅ ACTIVE SPEAKERS (For Ripple Effect)
                    is RoomEvent.ActiveSpeakersChanged -> {
                        val activeSpeakerIds = event.speakers.map { it.identity?.value ?: "" }
                        eventListener?.onActiveSpeakerChanged(activeSpeakerIds)
                    }
                    
                    // ✅ DATA RECEIVED (For Timer Sync)
                    is RoomEvent.DataReceived -> {
                        val message = String(event.data, Charsets.UTF_8)
                        eventListener?.onDataReceived(message)
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
