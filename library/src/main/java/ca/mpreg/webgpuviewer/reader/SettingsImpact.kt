package ca.mpreg.webgpuviewer.reader

/**
 * How much work a settings change requires. Ordered by severity: combining
 * impacts yields the max (ordinal).
 *
 * - [LIVE] — uniform/filter update only; no invalidation of decoded pages.
 * - [STATE] — viewer state + invalidate; decoded pages keep their bitmaps.
 * - [REDECODE] — page images must be re-decoded (crop/scale/theme bake change);
 *   page shells and cache structure stay.
 * - [REBUILD] — full page-cache clear and re-fetch (layout/pairing/split change).
 */
enum class SettingsImpact {
    LIVE,
    STATE,
    REDECODE,
    REBUILD,
    ;

    infix fun merge(other: SettingsImpact): SettingsImpact = maxOf(this, other)
}

/** Result of [SettingsProfile.diff]: what changed and the worst-case work. */
data class SettingsDiff(
    val previous: SettingsProfile,
    val current: SettingsProfile,
    val impacts: Set<SettingsImpact>,
) {
    val highest: SettingsImpact
        get() = impacts.maxOrNull() ?: SettingsImpact.LIVE

    val isEmpty: Boolean get() = impacts.isEmpty()
}

/** Typed replacement for the untyped `() -> Unit` config-change listeners. */
fun interface OnSettingsChanged {
    fun invoke(diff: SettingsDiff)
}
