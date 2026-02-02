package com.suguna.rtc

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class SugunaClient(private val context: Context, private val serverUrl: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var room: Room? = null
    private var eventListener: SugunaEvents? = null

    companion object {
        const val ROLE_HOST = "host"
        const val ROLE_AUDIENCE = "audience"

        @JvmStatic
        fun attachTrackToView(track: Any, view: SugunaVideoView) {
            if (track is VideoTrack) {
                track.addRenderer(view)
            }
        }
    }

    interface SugunaEvents {
        fun onLocalStreamReady(videoTrack: Any)
        fun onRemoteStreamReady(userId: String?, videoTrack: Any)
        fun onUserLeft(userId: String?)
        fun onError(message: String)
    }

    fun setEventListener(listener: SugunaEvents) {
        this.eventListener = listener
    }

    fun initialize(roomName: String, token: String, role: String) {
        scope.launch {
            try {
                // Initialize Room
                room = LiveKit.create(context)
                setupRoomListeners()

                // Connect
                room?.connect(serverUrl, token)
                
                // For Host, enable camera and mic
                if (role == ROLE_HOST) {
                    room?.localParticipant?.setCameraEnabled(true)
                    room?.localParticipant?.setMicrophoneEnabled(true)

                    // Wait for the track to be published and ready
                    delay(2000)

                    // Get the camera track publication
                    val cameraPub = room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)
                    val videoTrack = cameraPub?.track
                    
                    if (videoTrack != null) {
                        eventListener?.onLocalStreamReady(videoTrack as Any)
                    } else {
                        // Fallback: try first video track publication
                        room?.localParticipant?.videoTrackPublications?.firstOrNull()?.track?.let {
                            eventListener?.onLocalStreamReady(it as Any)
                        }
                    }
                }

            } catch (e: Exception) {
                eventListener?.onError("SDK Error: ${e.message}")
            }
        }
    }

    private fun setupRoomListeners() {
        scope.launch {
            room?.events?.collect { event ->
                when (event) {
                    is RoomEvent.TrackSubscribed -> {
                        val track = event.track
                        if (track is VideoTrack) {
                            eventListener?.onRemoteStreamReady(event.participant.identity?.value, track as Any)
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
    }
}
