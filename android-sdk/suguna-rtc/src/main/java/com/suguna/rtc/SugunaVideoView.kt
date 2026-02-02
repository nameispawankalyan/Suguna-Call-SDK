package com.suguna.rtc

import android.content.Context
import android.util.AttributeSet
import io.livekit.android.renderer.TextureViewRenderer

/**
 * SugunaVideoView - A wrapper around the underlying video renderer.
 * This ensures the consumer app doesn't need to know about LiveKit specifically.
 */
class SugunaVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureViewRenderer(context, attrs) {
    init {
        // Initialize the renderer with a shared EglBase context
        val eglBase = io.livekit.android.renderer.TextureViewRenderer.createEglBase()
        init(eglBase.eglContext, null)
    }
}