package ca.mpreg.webgpuviewer.renderer

import kotlin.math.log2
import kotlin.math.round

/**
 * Mip-chain sizing and selection for the direct (tile-cache-free) page draw.
 *
 * The tile cache used to hide a gap here: it re-rendered the visible page into screen-space tiles
 * with a box filter, so the direct path's minification quality was never tested. Without the
 * cache, the direct path does the minifying itself, and two things decide whether halftone
 * screentones moiré:
 *
 * - the chain has to actually exist at the scale being drawn. The old builder stopped at 2048 px,
 *   so an ordinary 960x1376 page had exactly one level and a 0.6x draw sampled the full-resolution
 *   texture through one bilinear tap.
 * - the level has to be chosen for the draw scale, not rounded down. `floor(log2(1/scale))` keeps
 *   level 0 all the way down to 0.5x, which is precisely the aliasing band.
 *
 * Pure, so both rules are asserted offline by `ImageMipPolicyHarness`.
 */
internal object ImageMipPolicy {

    /** Smallest mip dimension the GPU uploader accepts (gralloc 0x3b below this). */
    const val MIN_MIP_DIMENSION = 8

    /** Whether another half-size level is worth building from [width] x [height]. */
    fun shouldBuildNextLevel(width: Int, height: Int): Boolean =
        width >= MIN_MIP_DIMENSION * 2 && height >= MIN_MIP_DIMENSION * 2

    /**
     * The mip level a draw at [scale] should sample, given [levels] resident levels.
     *
     * Nearest level rather than floor: at 0.6x that picks level 1, whose texels land at 1.2 screen
     * pixels each. Floor picks level 0 (1.67 screen pixels per texel) and aliases. A non-finite or
     * non-positive scale falls back to level 0 rather than throwing on the render path.
     */
    fun levelForScale(scale: Float, levels: Int): Int {
        if (levels <= 1 || !scale.isFinite() || scale <= 0f) return 0
        val level = round(log2(1f / scale)).toInt()
        return level.coerceIn(0, levels - 1)
    }
}
