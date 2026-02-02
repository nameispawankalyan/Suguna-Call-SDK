package com.suguna.rtc

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalVideoTrack
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
                
                // Enable media
                room?.localParticipant?.setCameraEnabled(true)
                room?.localParticipant?.setMicrophoneEnabled(true)

                // Wait for publishing to finish
                delay(2000)

                // Correct way to get local video track in LiveKit 2.x
                val publications = room?.localParticipant?.videoTrackPublications
                val videoTrack = publications?.firstOrNull()?.track
                
                videoTrack?.let {
                    eventListener?.onLocalStreamReady(it as Any)
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
