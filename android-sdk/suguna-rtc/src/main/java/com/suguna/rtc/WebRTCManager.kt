package com.suguna.rtc

import android.content.Context
import org.json.JSONObject
import org.webrtc.*

class WebRTCManager(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val events: Events
) {
    private var peerConnectionFactory: PeerConnectionFactory
    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private var localStream: VideoTrack? = null

    interface Events {
        fun onRemoteStream(userId: String, videoTrack: VideoTrack)
        fun onUserLeft(userId: String)
    }

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(null))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(null, true, true))
            .createPeerConnectionFactory()

        setupSignaling()
    }

    private fun setupSignaling() {
        signalingClient.callback = object : SignalingClient.Callback {
            override fun onUserJoined(userId: String) {
                createPeerConnection(userId).createOffer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        peerConnections[userId]?.setLocalDescription(SimpleSdpObserver(), sdp)
                        val offerJson = JSONObject().apply {
                            put("type", sdp.type.canonicalForm())
                            put("sdp", sdp.description)
                        }
                        signalingClient.sendOffer(userId, offerJson)
                    }
                }, MediaConstraints())
            }

            override fun onOfferReceived(fromUserId: String, offer: JSONObject) {
                val pc = createPeerConnection(fromUserId)
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(offer.getString("type")),
                    offer.getString("sdp")
                )
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        pc.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(sdp: SessionDescription) {
                                pc.setLocalDescription(SimpleSdpObserver(), sdp)
                                val answerJson = JSONObject().apply {
                                    put("type", sdp.type.canonicalForm())
                                    put("sdp", sdp.description)
                                }
                                signalingClient.sendAnswer(fromUserId, answerJson)
                            }
                        }, MediaConstraints())
                    }
                }, sdp)
            }

            override fun onAnswerReceived(fromUserId: String, answer: JSONObject) {
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(answer.getString("type")),
                    answer.getString("sdp")
                )
                peerConnections[fromUserId]?.setRemoteDescription(SimpleSdpObserver(), sdp)
            }

            override fun onIceCandidateReceived(fromUserId: String, candidate: JSONObject) {
                val ice = IceCandidate(
                    candidate.getString("sdpMid"),
                    candidate.getInt("sdpMLineIndex"),
                    candidate.getString("candidate")
                )
                peerConnections[fromUserId]?.addIceCandidate(ice)
            }

            override fun onUserLeft(userId: String) {
                peerConnections[userId]?.close()
                peerConnections.remove(userId)
                events.onUserLeft(userId)
            }
        }
    }

    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null

    fun createLocalStream(video: Boolean, audio: Boolean): Pair<VideoTrack?, AudioTrack?> {
        // 1. Audio
        if (audio) {
            val audioConstraints = MediaConstraints()
            audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory.createAudioTrack("ARDAMSa0", audioSource)
        }

        // 2. Video
        if (video) {
            videoCapturer = createVideoCapturer()
            videoCapturer?.let { capturer ->
                videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
                val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", EglBase.create().eglBaseContext)
                capturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
                capturer.startCapture(1280, 720, 30)

                localVideoTrack = peerConnectionFactory.createVideoTrack("ARDAMSv0", videoSource)
            }
        }

        return Pair(localVideoTrack, localAudioTrack)
    }

    // --- Agora-like Controls ---

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun toggleAudio(enable: Boolean) {
        localAudioTrack?.setEnabled(enable)
    }

    fun toggleVideo(enable: Boolean) {
        localVideoTrack?.setEnabled(enable)
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Try to find front facing camera
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }

        // Fallback to back facing
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    fun dispose() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoSource?.dispose()
            audioSource?.dispose()
            peerConnections.values.forEach { it.close() }
            peerConnections.clear()
            peerConnectionFactory.dispose()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
