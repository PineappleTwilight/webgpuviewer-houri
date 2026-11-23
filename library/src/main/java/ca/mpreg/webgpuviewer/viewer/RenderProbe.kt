package ca.mpreg.webgpuviewer.viewer

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
            "last=$lastPage rect=$lastRect"
}
