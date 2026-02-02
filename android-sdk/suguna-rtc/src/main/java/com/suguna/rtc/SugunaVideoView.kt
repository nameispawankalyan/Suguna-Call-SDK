package com.suguna.rtc

import android.content.Context
import android.util.AttributeSet
import io.livekit.android.renderer.TextureViewRenderer

/**
 * SugunaVideoView - A wrapper around LiveKit's TextureViewRenderer.
 * LiveKit handles the initialization of this view when a track is added.
 */
class SugunaVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureViewRenderer(context, attrs)