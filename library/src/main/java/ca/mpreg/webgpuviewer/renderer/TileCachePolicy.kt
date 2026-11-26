package ca.mpreg.webgpuviewer.renderer

/**
 * Whether the high-quality tile *cache* may run on an adapter.
 *
 * Deliberately separate from texture tiling: every page is still uploaded as a grid of [Mipmap]
 * tiles, because the device's texture limit makes that the only way a tall page can exist at all.
 * This policy only decides whether [TileRenderer] builds its second, screen-space copy of those
 * pages in an atlas. With the cache off, pages are drawn directly from their own mip chain.
 *
 * PowerVR Rogue is pinned off regardless of the host's preference. The 2026-11-26 PowerVR field
 * report kept losing the Vulkan device under `VK_ERROR_OUT_OF_DEVICE_MEMORY` while the tile atlas
 * was live; the same build with the cache disabled reads normally. The cache is an optimisation,
 * so a device that cannot afford it loses sharpening, never the page.
 *
 * Pure and Android-free so the rule can be asserted without a device - see
 * `scripts/kotlinc-check/harness/TileCachePolicyHarness.kt`.
 */
internal object TileCachePolicy {

    /**
     * Adapter descriptions that mean "Imagination PowerVR Rogue".
     *
     * Dawn reports the vendor as `img-tec` on the PowerVR field devices, while the device string
     * carries `PowerVR Rogue`. Matching the architecture too catches reports that omit the
     * marketing name but still say `arch=rogue`.
     */
    private val POWERVRS = listOf("powervr", "img-tec", "imagination", "rogue")

    fun isPowerVr(adapterDescription: String): Boolean {
        val description = adapterDescription.lowercase()
        return POWERVRS.any { description.contains(it) }
    }

    /**
     * The effective cache switch: the host's choice, unless the adapter is a known-unaffordable
     * one. A host can never turn the cache on for PowerVR; it can only turn it off elsewhere.
     */
    fun isEnabled(requested: Boolean, adapterDescription: String): Boolean =
        requested && !isPowerVr(adapterDescription)
}
