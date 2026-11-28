package ca.mpreg.webgpuviewer.viewer

import androidx.webgpu.GPUTexture

/**
 * KMK --> What the newest encoded frame actually asked the page path to draw.
 *
 * A diagnostics-only sink, written on the render thread and read once when the reader closes. It
 * answers the question a black-screen dump could not: the page path may run with a decoded page
 * and still issue zero quads (no mipmaps, a placement outside the surface, a page the walk never
 * reached), or issue one quad over a page cube that the surface then shows as black. Without this
 * the difference is invisible: `frames` counts presents, `drawn` counts a walk claim, and neither
 * says whether `renderPage` emitted anything.
 *
 * No Android import and no allocation on the hot path beyond the one description per drawn page:
 * the renderer resets the counts at the start of every encoded frame.
 * KMK <--
 */
object RenderProbe {
    @Volatile
    var pages: Int = 0
        private set

    @Volatile
    var imageQuads: Int = 0
        private set

    @Volatile
    var skippedNoMips: Int = 0
        private set

    @Volatile
    var lastPage: String = "none"
        private set

    @Volatile
    var lastRect: String = "none"
        private set

    // KMK --> One-shot page-texture readback: the first drawn page's centre four quarters are
    // copied to a mappable buffer and logged as RGBA. This is what separates "the source texture
    // is black" from "the texture is fine and the draw/compositor is black". Armed once per
    // session by ImageSingle.renderPage, consumed by WebGpuRenderer.render.
    class TextureSample(val texture: GPUTexture, val x: Int, val y: Int)

    /** Failed maps after which the probe stops trying. See [noteTextureSample]. */
    private const val MAX_TEXTURE_SAMPLE_ATTEMPTS = 3

    @Volatile
    private var textureSampleArmed = false

    @Volatile
    private var textureSampleAttempts = 0

    @Volatile
    private var pendingTexture: GPUTexture? = null

    @Volatile
    private var pendingX = 0

    @Volatile
    private var pendingY = 0

    /** Nanotime the current session opened, so `tex=` can say *when* it resolved. */
    @Volatile
    private var sessionStartedNanos = 0L

    @Volatile
    var textureSample: String = "none"
        private set

    /**
     * Open a fresh probe for a new reader session.
     *
     * The object is process-wide and outlives the activity, so without this every reader opened
     * after the first inherits the previous session's sample - and because the armed flag was
     * never cleared, `tex=` was effectively a once-per-process fact: a second session reported
     * the first session's answer (or `none`) no matter what its own frames drew.
     */
    fun beginSession() {
        textureSampleArmed = false
        textureSampleAttempts = 0
        pendingTexture = null
        pendingX = 0
        pendingY = 0
        textureSample = "none"
        sessionStartedNanos = System.nanoTime()
        pages = 0
        imageQuads = 0
        skippedNoMips = 0
        lastPage = "none"
        lastRect = "none"
    }

    fun armTextureSample(texture: GPUTexture, x: Int, y: Int) {
        if (textureSampleArmed || textureSampleAttempts >= MAX_TEXTURE_SAMPLE_ATTEMPTS) return
        textureSampleArmed = true
        pendingTexture = texture
        pendingX = x
        pendingY = y
    }

    fun takeTextureSample(): TextureSample? {
        val texture = pendingTexture ?: return null
        pendingTexture = null
        return TextureSample(texture, pendingX, pendingY)
    }

    /**
     * Drops an armed-but-not-yet-taken sample after a device rebuild, and lets the replacement
     * device have its own attempts: the sample describes the session's live device, and the one
     * it was armed against is gone.
     */
    fun discardTextureSample() {
        pendingTexture = null
        pendingX = 0
        pendingY = 0
        textureSampleArmed = false
        textureSampleAttempts = 0
    }

    /**
     * The sample resolved (or failed), stamped with the time since [beginSession].
     *
     * A failure re-arms, bounded by [MAX_TEXTURE_SAMPLE_ATTEMPTS]: the first page draw can land
     * in the same frame a device dies, and one failed map must not leave the session report
     * without the texture-vs-composite answer a field report needs. A successful sample stays
     * consumed - it is one readback per session, after the present, and repeating it every frame
     * would be a per-frame map on the render thread.
     */
    fun noteTextureSample(value: String, success: Boolean = true) {
        val elapsedMs = if (sessionStartedNanos == 0L) {
            0L
        } else {
            (System.nanoTime() - sessionStartedNanos) / 1_000_000L
        }
        textureSample = "$value t=${elapsedMs}ms"
        if (success) {
            textureSampleArmed = true
        } else {
            textureSampleAttempts++
            textureSampleArmed = false
        }
    }
    // KMK <--

    fun beginFrame() {
        pages = 0
        imageQuads = 0
        skippedNoMips = 0
    }

    fun notePage(description: String) {
        pages++
        lastPage = description
    }

    fun noteImage(rect: String) {
        imageQuads++
        lastRect = rect
    }

    fun noteSkippedNoMips() {
        skippedNoMips++
    }

    fun summary(): String =
        "pages=$pages quads=$imageQuads skippedNoMips=$skippedNoMips " +
            "last=$lastPage rect=$lastRect tex=$textureSample"
}
