package ca.mpreg.webgpuviewer.transition

import androidx.compose.ui.geometry.Offset
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPUTexture
import ca.mpreg.webgpuviewer.renderer.TileRenderer
import ca.mpreg.webgpuviewer.viewer.ImagePage

/**
 * No animation: a finger drag still slides both pages side by side, exactly like
 * [TransitionBasic] - the same thing a ViewPager2 with no page transformer does. The "no
 * animation" part lives one layer up instead: programmatic turns (tap zones, buttons) skip the
 * settle animation and cut straight to the new page, so there is never a spring slide on release
 * of a tap - only the drag itself tracks the finger. [frac]/[pos1]/[pos2] pass straight through
 * to [TransitionBasic].
 */
object TransitionNone : Transition() {
    override val isAnimated = false

    override fun render(
        page1: ImagePage,
        page2: ImagePage,
        encoder: GPUCommandEncoder,
        dst: GPUTexture,
        frac: Float,
        pos1: Offset,
        pos2: Offset,
        tiles: TileRenderer,
    ) {
        TransitionBasic.render(page1, page2, encoder, dst, frac, pos1, pos2, tiles)
    }

    /** The same drag-following slide, vertically. */
    object Vertical : Transition() {
        override val isAnimated = false

        override fun render(
            page1: ImagePage,
            page2: ImagePage,
            encoder: GPUCommandEncoder,
            dst: GPUTexture,
            frac: Float,
            pos1: Offset,
            pos2: Offset,
            tiles: TileRenderer,
        ) {
            TransitionBasic.Vertical.render(page1, page2, encoder, dst, frac, pos1, pos2, tiles)
        }
    }
}
