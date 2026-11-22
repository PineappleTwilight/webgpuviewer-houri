// KMK -->
package ca.mpreg.webgpuviewer.renderer

/**
 * The one rule that decides whether the tile pass may tell its caller the page is fully painted.
 *
 * It gates `TileRenderer.draw`'s return value, which `ImagePage.ImageSingle.drawLive` reads as
 * "the tiles have painted this page, so there is nothing left to draw" - and which still gates
 * prewarming the neighbouring pages. The 2026-11-22 black-screen report was this answer being
 * `true` with no pixels written: an empty placement ("nothing to draw here" treated as "painted
 * everything") and a frame whose instance buffer was missing both answered covered, so the direct
 * page draw was skipped for the life of the page while the session logged nothing wrong.
 *
 * Extracted into its own Android-free file, like the app's pure policy files, so
 * `scripts/kotlinc-check/harness/TileCoverageHarness.kt` can compile and assert the real function
 * on a plain JVM rather than a copy of it.
 *
 * @param placementEmpty the grid's wanted rect has no area, so no tile can be on screen.
 * @param blitted this frame actually handed the pass an instance list (see `drawInstanced`).
 * @param missingVisible a wanted tile that overlaps the viewport has not been generated yet.
 */
internal fun tileCoverage(
    placementEmpty: Boolean,
    blitted: Boolean,
    missingVisible: Boolean,
): Boolean = !placementEmpty && blitted && !missingVisible
// KMK <--
