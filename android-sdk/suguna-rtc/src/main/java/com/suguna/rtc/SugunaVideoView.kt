package com.suguna.rtc

import android.content.Context
import android.util.AttributeSet
import io.livekit.android.renderer.TextureViewRenderer
import livekit.org.webrtc.EglBase

class SugunaVideoView : TextureViewRenderer {

    var isRendererInitialized = false
        private set

    // When created from code
    constructor(context: Context) : super(context)

    // When created from XML
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    fun safeInit(eglContext: EglBase.Context) {
        if (!isRendererInitialized) {
            init(eglContext, null)
            isRendererInitialized = true
        }
    }
}
