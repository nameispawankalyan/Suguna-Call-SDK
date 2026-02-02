package com.suguna.rtc

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SugunaClient - Powered by LiveKit
 * Unified Architecture for 1-vs-1 and Live Streaming
 */
class SugunaClient(
    private val context: Context,
    private val serverUrl: String 
) {
    private var room: Room? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    interface SugunaEvents {
        fun onLocalStreamReady(videoTrack: Any)
        // Pass userId as nullable to avoid crashes if identity is missing
        fun onRemoteStreamReady(userId: String?, videoTrack: Any)
        fun onUserLeft(userId: String?)
        fun onError(message: String)
    }

    companion object {
        const val ROLE_HOST = "host"
        const val ROLE_AUDIENCE = "audience"
        
        fun attachTrackToView(track: Any, view: SugunaVideoView) {
            if (track is VideoTrack) {
                track.addRenderer(view)
            }
        }
    }

    private var eventListener: SugunaEvents? = null

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

                // Wait a bit for track to be ready
                kotlinx.coroutines.delay(1000)

                // Try to find the local video track
                room?.localParticipant?.videoTrackPublications?.firstOrNull()?.videoTrack?.let { videoTrack ->
                    eventListener?.onLocalStreamReady(videoTrack as Any)
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
                        val participant = event.participant
                        
                        if (track is VideoTrack) {
                            // Ensure identity is treated as String (it usually is 'Identity' type which acts as String)
                            eventListener?.onRemoteStreamReady(participant.identity?.toString(), track)
                        }
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        eventListener?.onUserLeft(event.participant.identity?.toString())
                    }
                    is RoomEvent.Disconnected -> {
                        eventListener?.onUserLeft("local_user")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun startLocalStream() {
        scope.launch {
            // Suspend calls
            room?.localParticipant?.setCameraEnabled(true)
            room?.localParticipant?.setMicrophoneEnabled(true)

            // Local Video Track logic:
            // Usually setting camera enabled automatically publishes the track.
            // We can listen for 'LocalTrackPublished' event if needed, but for now we assume auto-publish.
            // If we need to show local preview, we can grab the track from room.localParticipant.videoTrackPublications
        }
    }

    fun joinRoom() {
        // No-op
    }

    fun leaveRoom() {
        scope.launch {
            room?.disconnect()
            room?.release()
            room = null
        }
    }

    fun switchCamera() {
        scope.launch {
             val track = room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? io.livekit.android.room.track.LocalVideoTrack
             track?.switchCamera()
        }
    }

    fun muteLocalAudio(muted: Boolean) {
        scope.launch {
            room?.localParticipant?.setMicrophoneEnabled(!muted)
        }
    }

    fun muteLocalVideo(muted: Boolean) {
        scope.launch {
            room?.localParticipant?.setCameraEnabled(!muted)
        }
    }
}
