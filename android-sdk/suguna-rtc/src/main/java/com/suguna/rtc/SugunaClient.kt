package com.suguna.rtc

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.media.AudioManager
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.EglBase
import io.livekit.android.LiveKitOverrides
import io.livekit.android.room.track.LocalVideoTrack

class SugunaClient(private val context: Context, private val serverUrl: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var room: Room? = null
    private var eventListener: SugunaEvents? = null
    
    // Audio Manager
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    companion object {
        const val ROLE_HOST = "host"
        const val ROLE_AUDIENCE = "audience"
        
        internal val eglBase by lazy { EglBase.create() }

        @JvmStatic
        fun attachTrackToView(
            track: VideoTrack,
            view: SugunaVideoView,
            isLocal: Boolean
        ) {
            view.post {
                android.util.Log.d("SugunaClient", "Attaching track: ${track.sid}, isLocal: $isLocal, enabled: ${track.enabled}")
                
                view.safeInit(eglBase.eglBaseContext)
                view.setMirror(isLocal)
                view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                
                track.removeRenderer(view)
                track.addRenderer(view)
                
                view.requestLayout()
                view.invalidate()
            }
        }
    }

    interface SugunaEvents {
        fun onConnected(userId: String) 
        fun onLocalStreamReady(videoTrack: VideoTrack)
        fun onRemoteStreamReady(userId: String?, videoTrack: VideoTrack)
        fun onLocalStreamUpdate(videoTrack: VideoTrack, isMuted: Boolean) // Added for Mute/Unmute
        fun onRemoteStreamUpdate(userId: String?, videoTrack: VideoTrack, isMuted: Boolean) // Added for Mute/Unmute
        fun onUserJoined(participant: io.livekit.android.room.participant.RemoteParticipant)
        fun onUserLeft(userId: String?)
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
                // Initialize Room with Shared EglBase
                val overrides = LiveKitOverrides(
                    eglBase = eglBase
                )
                
                room = LiveKit.create(
                    appContext = context,
                    overrides = overrides
                )
                
                setupRoomListeners()

                // Connect
                room?.connect(serverUrl, token)
                
                val myIdentity = room?.localParticipant?.identity?.value ?: ""
                android.util.Log.d("SugunaClient", "Connected to room as: $myIdentity")
                eventListener?.onConnected(myIdentity)
                
                // Trigger for already connected participants (if any)
                room?.remoteParticipants?.values?.forEach { participant ->
                    eventListener?.onUserJoined(participant)
                }
                
                // Configure Audio Mode
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                
                // Default Speaker Logic
                val initialSpeakerState = defaultSpeakerOn ?: isVideoCall 
                setSpeakerphoneEnabled(initialSpeakerState)
                
                if (role == ROLE_HOST) {
                    if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        (isVideoCall && context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)) {
                         eventListener?.onError("Permissions Missing!")
                         return@launch
                    }

                    room?.localParticipant?.setMicrophoneEnabled(true)
                    if (isVideoCall) {
                        android.util.Log.d("SugunaClient", "Enabling Camera for Local Participant")
                        room?.localParticipant?.setCameraEnabled(true)
                        
                        // Reliability: Explicitly poll for the local camera track
                        scope.launch {
                            android.util.Log.d("SugunaClient", "Starting local track polling...")
                            repeat(50) { // Check for 10 seconds
                                val publications = room?.localParticipant?.videoTrackPublications
                                val cameraPub = publications?.find { it.first.source == Track.Source.CAMERA }
                                val track = cameraPub?.second as? VideoTrack
                                
                                if (track != null) {
                                    android.util.Log.d("SugunaClient", "Local Camera Track Found via polling. Enabled=${track.enabled}")
                                    if (!track.enabled) {
                                        android.util.Log.d("SugunaClient", "Forcing track enable")
                                        track.enabled = true
                                    }
                                    
                                    // Start capture if it's a local video track
                                    if (track is LocalVideoTrack) {
                                        track.startCapture()
                                    }
                                    
                                    eventListener?.onLocalStreamReady(track)
                                    return@launch
                                }
                                kotlinx.coroutines.delay(200)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SugunaClient", "Connect Error", e)
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
        android.util.Log.d("SugunaClient", "Setting speakerphone enabled: $enable")
        try {
            // Enforce Communication Mode for correct routing
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            
            if (enable) {
                // Speaker Requested: Turn off BT SCO, Turn ON Speaker
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = true
                android.util.Log.d("SugunaClient", "Audio routed to SPEAKER")
            } else {
                // Earpiece/BT Requested: Turn OFF Speaker
                audioManager.isSpeakerphoneOn = false
                
                // Only start Bluetooth SCO if a headset is actually connected
                val isBluetoothConnected = audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoAvailableOffCall
                if (isBluetoothConnected) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    android.util.Log.d("SugunaClient", "Audio routed to BLUETOOTH")
                } else {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    android.util.Log.d("SugunaClient", "Audio routed to EARPIECE")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SugunaClient", "Error setting audio route", e)
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

                    // ✅ TRACK MUTE/UNMUTE (Crucial for Background/Foreground Resume)
                    is RoomEvent.TrackMuted -> {
                        val track = event.publication.track
                        if (track is VideoTrack) {
                            if (event.participant is LocalParticipant) {
                                eventListener?.onLocalStreamUpdate(track, true)
                            } else {
                                eventListener?.onRemoteStreamUpdate(event.participant.identity?.value, track, true)
                            }
                        }
                    }
                    is RoomEvent.TrackUnmuted -> {
                        val track = event.publication.track
                        if (track is VideoTrack) {
                            if (event.participant is LocalParticipant) {
                                eventListener?.onLocalStreamUpdate(track, false)
                            } else {
                                eventListener?.onRemoteStreamUpdate(event.participant.identity?.value, track, false)
                            }
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

    fun switchCamera() {
        val videoPub = room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)
        val videoTrack = videoPub?.track as? LocalVideoTrack
        val capturer = videoTrack?.capturer as? livekit.org.webrtc.CameraVideoCapturer
        capturer?.switchCamera(null)
    }

    fun leaveRoom() {
        room?.disconnect()
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
