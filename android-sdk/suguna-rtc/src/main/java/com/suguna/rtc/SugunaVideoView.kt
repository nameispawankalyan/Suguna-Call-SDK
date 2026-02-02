package com.suguna.rtc

import android.content.Context
import android.util.AttributeSet
import io.livekit.android.renderer.TextureViewRenderer

/**
 * SugunaVideoView - A wrapper around the underlying video renderer.
 * This ensures the consumer app doesn't need to know about LiveKit specifically.
 */
import org.webrtc.EglBase

class SugunaVideoView : TextureViewRenderer {
    constructor(context: Context) : super(context) { initRenderer() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { initRenderer() }

    private fun initRenderer() {
        val eglBase = EglBase.create()
        init(eglBase.eglContext, null)
    }
}