import android.util.AttributeSet
import io.livekit.android.renderer.TextureViewRenderer
import org.webrtc.EglBase

/**
 * SugunaVideoView - A wrapper around the underlying video renderer.
 */
class SugunaVideoView : TextureViewRenderer {
    constructor(context: Context) : super(context) { initRenderer() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs ?: throw IllegalArgumentException("Attrs null")) { initRenderer() }

    private fun initRenderer() {
        try {
            val eglBase = EglBase.create()
            init(eglBase.eglContext, null)
        } catch (e: Exception) {
            // Already initialized or failed
        }
    }
}