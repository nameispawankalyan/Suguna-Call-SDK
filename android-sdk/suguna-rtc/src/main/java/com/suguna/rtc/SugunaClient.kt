package com.suguna.rtc

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
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
                room = LiveKit.create(context)
                setupRoomListeners()
                room?.connect(serverUrl, token)
                
                if (role == ROLE_HOST) {
                    room?.localParticipant?.setCameraEnabled(true)
                    room?.localParticipant?.setMicrophoneEnabled(true)

                    // Give some time for publication
                    delay(2000)

                    // Find video track using a more resilient method
                    val localParticipant = room?.localParticipant
                    val videoPublication = localParticipant?.videoTrackPublications?.firstOrNull()
                    
                    // Use reflection-like safe access or check both track/videoTrack
                    // In LiveKit Android, LocalTrackPublication usually has 'track'
                    val track = videoPublication?.javaClass?.getMethod("getTrack")?.invoke(videoPublication)
                                ?: videoPublication?.javaClass?.getMethod("getVideoTrack")?.invoke(videoPublication)

                    if (track != null) {
                        eventListener?.onLocalStreamReady(track as Any)
                    } else {
                        // One last try: just find any VideoTrack in the participant's tracks
                        localParticipant?.videoTrackPublications?.forEach { pub ->
                            pub.track?.let {
                                eventListener?.onLocalStreamReady(it as Any)
                                return@launch
                            }
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
