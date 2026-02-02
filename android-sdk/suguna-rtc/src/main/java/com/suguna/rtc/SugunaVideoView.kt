package com.suguna.rtc

import android.content.Context
import android.util.AttributeSet
import io.livekit.android.renderer.TextureViewRenderer
import org.webrtc.EglBase

/**
 * SugunaVideoView - A wrapper around the underlying video renderer.
 */
class SugunaVideoView : TextureViewRenderer {
    constructor(context: Context) : super(context) { initRenderer() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { initRenderer() }

    private fun initRenderer() {
        try {
            val eglBase = EglBase.create()
            // We use eglBaseContext which is standard in WebRTC
            init(eglBase.eglBaseContext, null)
        } catch (e: Exception) {
            // Already initialized
        }
    }
}