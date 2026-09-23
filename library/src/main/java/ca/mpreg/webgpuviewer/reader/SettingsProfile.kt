package ca.mpreg.webgpuviewer.reader

/**
 * Immutable snapshot of reader settings the WebGPU viewer acts on. Apps build a
 * fresh profile when preferences change, then [diff] against the previous one
 * and apply the returned [SettingsDiff] through a single [OnSettingsChanged]
 * callback (replacing the separate property/state/split/double-tap listeners).
 *
 * Defaults match the library's built-in behavior so an empty profile is valid.
 */
data class SettingsProfile(
    // Gesture / zoom — STATE (policy re-applied to cached pages)
    val doubleTapZoom: Boolean = true,
    val disableZoomIn: Boolean = false,
    val zoomOutDisabled: Boolean = false,
    val landscapeZoom: Boolean = false,

    // Decode-affecting geometry — REDECODE / REBUILD
    val imageScaleType: Int = 1,
    val imageZoomType: Int = 0,
    val imageCropBorders: Boolean = false,
    val dualPageSplit: Boolean = false,
    val doublePages: Boolean = false,
    val autoDoublePages: Boolean = false,
    val invertDoublePages: Boolean = false,
    val matchDoublePageHeights: Boolean = false,
    val shiftDoublePage: Boolean = false,
    val theme: Int = 1,

    // State-only layout / runtime — STATE
    val transitionAnimation: Int = 1,
    val transitionAnimationDual: Int = 1,
    val cutoutMode: Int = 0,
    val cutoutModeDual: Int = 0,
    val continuousMinWidth: Int = 100,
    val continuousGap: Int = 10,
    val pageOffset: Int = 0,
    val preloadAhead: Int = 3,
    val preloadBehind: Int = 1,
    val artCnnUpscaler: Boolean = false,
    val fastRender: Boolean = false,

    // Color / filter uniforms — LIVE
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val hlgEnabled: Boolean = false,
    val hlgExposure: Float = 1f,
    val lutPreset: String = "",
    val lutCustomPath: String = "",
    val lutIntensity: Float = 0f,
    val webgpuDarkMode: Boolean = false,
    val webgpuDarkModeAmoled: Boolean = false,
    val darkModeTolerance: Float = 0.5f,
    val darkModeChunkRange: Float = 0.5f,
    val compareTranslation: Boolean = false,
    val perfHud: Boolean = false,
    val einkPreset: Boolean = false,
) {
    /**
     * Field-by-field comparison against [old]; [impacts] is the set of tiers that
     * must run (empty = no visible change). Callers apply [SettingsDiff.highest]
     * (or each impact) once per preference emission.
     */
    fun diff(old: SettingsProfile): SettingsDiff {
        val impacts = mutableSetOf<SettingsImpact>()

        fun flag(changed: Boolean, impact: SettingsImpact) {
            if (changed) impacts += impact
        }

        flag(doubleTapZoom != old.doubleTapZoom, SettingsImpact.STATE)
        flag(disableZoomIn != old.disableZoomIn, SettingsImpact.STATE)
        flag(zoomOutDisabled != old.zoomOutDisabled, SettingsImpact.STATE)
        flag(landscapeZoom != old.landscapeZoom, SettingsImpact.REDECODE)

        flag(imageScaleType != old.imageScaleType, SettingsImpact.REDECODE)
        flag(imageZoomType != old.imageZoomType, SettingsImpact.REBUILD)
        flag(imageCropBorders != old.imageCropBorders, SettingsImpact.REDECODE)
        flag(matchDoublePageHeights != old.matchDoublePageHeights, SettingsImpact.REDECODE)

        flag(dualPageSplit != old.dualPageSplit, SettingsImpact.REBUILD)
        flag(doublePages != old.doublePages, SettingsImpact.REBUILD)
        flag(autoDoublePages != old.autoDoublePages, SettingsImpact.REBUILD)
        flag(invertDoublePages != old.invertDoublePages, SettingsImpact.REBUILD)
        flag(shiftDoublePage != old.shiftDoublePage, SettingsImpact.REBUILD)
        flag(theme != old.theme, SettingsImpact.REBUILD)

        flag(transitionAnimation != old.transitionAnimation, SettingsImpact.STATE)
        flag(transitionAnimationDual != old.transitionAnimationDual, SettingsImpact.STATE)
        flag(cutoutMode != old.cutoutMode, SettingsImpact.STATE)
        flag(cutoutModeDual != old.cutoutModeDual, SettingsImpact.STATE)
        flag(continuousMinWidth != old.continuousMinWidth, SettingsImpact.STATE)
        flag(continuousGap != old.continuousGap, SettingsImpact.STATE)
        flag(pageOffset != old.pageOffset, SettingsImpact.STATE)
        flag(preloadAhead != old.preloadAhead, SettingsImpact.STATE)
        flag(preloadBehind != old.preloadBehind, SettingsImpact.STATE)
        flag(artCnnUpscaler != old.artCnnUpscaler, SettingsImpact.STATE)
        flag(fastRender != old.fastRender, SettingsImpact.STATE)

        flag(brightness != old.brightness, SettingsImpact.LIVE)
        flag(contrast != old.contrast, SettingsImpact.LIVE)
        flag(hlgEnabled != old.hlgEnabled, SettingsImpact.LIVE)
        flag(hlgExposure != old.hlgExposure, SettingsImpact.LIVE)
        flag(lutPreset != old.lutPreset, SettingsImpact.LIVE)
        flag(lutCustomPath != old.lutCustomPath, SettingsImpact.LIVE)
        flag(lutIntensity != old.lutIntensity, SettingsImpact.LIVE)
        flag(webgpuDarkMode != old.webgpuDarkMode, SettingsImpact.LIVE)
        flag(webgpuDarkModeAmoled != old.webgpuDarkModeAmoled, SettingsImpact.LIVE)
        flag(darkModeTolerance != old.darkModeTolerance, SettingsImpact.LIVE)
        flag(darkModeChunkRange != old.darkModeChunkRange, SettingsImpact.LIVE)
        flag(compareTranslation != old.compareTranslation, SettingsImpact.LIVE)
        flag(perfHud != old.perfHud, SettingsImpact.LIVE)
        flag(einkPreset != old.einkPreset, SettingsImpact.LIVE)

        return SettingsDiff(previous = old, current = this, impacts = impacts)
    }
}
