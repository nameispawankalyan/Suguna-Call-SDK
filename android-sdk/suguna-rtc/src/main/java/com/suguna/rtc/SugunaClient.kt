package com.suguna.rtc

import android.content.Context
import org.webrtc.*

/**
 * SugunaClient - The main entry point for the Suguna Calling SDK.
 */
class SugunaClient(
    private val context: Context,
    private val serverUrl: String
) {
    private var webRTCManager: WebRTCManager? = null
    private var signalingClient: SignalingClient? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null

    interface SugunaEvents {
        fun onLocalStreamReady(videoTrack: VideoTrack)
        fun onRemoteStreamReady(userId: String, videoTrack: VideoTrack)
        fun onUserLeft(userId: String)
        fun onError(message: String)
    }

    private var eventListener: SugunaEvents? = null

    fun setEventListener(listener: SugunaEvents) {
        this.eventListener = listener
    }

    fun initialize(roomId: String, rtcToken: String) {
        signalingClient = SignalingClient(serverUrl, roomId, rtcToken)
        webRTCManager = WebRTCManager(context, signalingClient!!, object : WebRTCManager.Events {
            override fun onRemoteStream(userId: String, videoTrack: VideoTrack) {
                eventListener?.onRemoteStreamReady(userId, videoTrack)
            }

            override fun onUserLeft(userId: String) {
                eventListener?.onUserLeft(userId)
            }
        })

        signalingClient?.connect()
    }

    fun startLocalStream(videoEnabled: Boolean = true, audioEnabled: Boolean = true) {
        val tracks = webRTCManager?.createLocalStream(videoEnabled, audioEnabled)
        localVideoTrack = tracks?.first as? VideoTrack
        localAudioTrack = tracks?.second as? AudioTrack
        
        localVideoTrack?.let {
            eventListener?.onLocalStreamReady(it)
        }
    }

    fun joinRoom() {
        signalingClient?.joinRoom()
    }

    fun leaveRoom() {
        signalingClient?.disconnect()
        webRTCManager?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
    }

    // --- Media Controls ---

    fun switchCamera() {
        webRTCManager?.switchCamera()
    }

    fun muteLocalAudio(muted: Boolean) {
        webRTCManager?.toggleAudio(!muted)
    }

    fun muteLocalVideo(muted: Boolean) {
        webRTCManager?.toggleVideo(!muted)
    }
}
