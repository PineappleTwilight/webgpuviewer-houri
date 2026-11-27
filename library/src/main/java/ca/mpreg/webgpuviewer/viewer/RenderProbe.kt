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

    @Volatile
    private var textureSampleArmed = false

    @Volatile
    private var pendingTexture: GPUTexture? = null

    @Volatile
    private var pendingX = 0

    @Volatile
    private var pendingY = 0

    @Volatile
    var textureSample: String = "none"
        private set

    fun armTextureSample(texture: GPUTexture, x: Int, y: Int) {
        if (textureSampleArmed) return
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
     * Drops an armed-but-not-yet-taken sample after a device rebuild. Its texture belongs to the
     * retired device, and copying from it on the replacement would only add a validation error
     * (or, on a driver that faults on dead handles, worse). The probe stays armed: it is a
     * once-per-session diagnostic, not one per device generation.
     */
    fun discardTextureSample() {
        pendingTexture = null
        pendingX = 0
        pendingY = 0
    }

    fun noteTextureSample(value: String) {
        textureSample = value
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
