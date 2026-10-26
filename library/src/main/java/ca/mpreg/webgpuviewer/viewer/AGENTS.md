# AGENTS.md - viewer package

## OVERVIEW
Gesture handling + page model for the WebGPU viewer: composables turn touch into state, state drives `WebGpuRenderer.dispatcher`; public `ImageView.kt`/`ImageViewContinuous.kt` (parent dir) delegate here.

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Gesture handling | `ImageViewer.kt` (534 lines) | `pointerInput` + `awaitEachGesture`, fling `Animatable`, cutout insets, `AndroidEmbeddedExternalSurface`; calls `state.init/collect/cleanup` |
| Continuous (vertical) viewer | `ImageViewerContinuous.kt` | same gesture core, vertical layout |
| Page offset / render pass | `ImageViewerState.kt` | `pageOffset` (coerced ±10, page-change walk), `renderPass`, `captureRenderState`, `tiles`, `upscaler`/`downscaler`, `filters` (wired to `invalidate`) |
| Continuous state | `ImageViewerContinuousState.kt` | `MAX_VISIBLE_PAGES=4`, `MAX_PAGE_WALK=64`, shared screen texture, `ContinuousPosition` |
| Page model | `ImagePage.kt` (~1724 lines) | sealed hierarchy: `ImageSingle`, `ImageSpread`, `Dummy`, `Render`, `Progress`; tile-vs-live policy; densest gotcha comments in repo |

## CONVENTIONS
- Pages are dumb: viewer never fetches/decodes images itself, app supplies `Image` objects.
- `ImageSpread` composes two `ImageSingle` sides WITHOUT taking ownership of their images; the builder owns cleanup. `ImageSingle` with an image owns it and cleans up in `cleanup()`.
- `Render` pages draw via `renderWith` (own pass, clears dst) or `renderLoaded` (loads dst, no clear: continuous mode shares one screen texture across pages, clearing would blank neighbors).
- `ImageSingle.highQuality=false` skips `TileRenderer` cache entirely, fast path renders `linear=false`.
- Animated frames always take the fast path, never the tile cache (cache would churn every frame).
- Transition cache textures are opened with `LoadOp.Clear`, NO stencil attachment: never masked.
- `getCurrentTexture()` rotates buffers: always clear before loading, never assume previous contents.
- Seam is never trimmed: inner edges of a spread are ignored for trim; `Render` sides have no trim at all.
- **The underlay runs before every early return in `renderSnapshot`'s image walk** (KMK island in
  `ImageViewerContinuousState.kt`). A decoded page sits briefly between its `Image` object existing
  (`isDecoded == true`) and its first mip upload (`mipmaps.isEmpty()`), and the walk's early return
  used to skip the underlay for that state too — a transparent sliver bounded by two hard edges at
  the page's slot, which showed as a dark seam for exactly the frames the first upload takes. Rule:
  keep the background pass before the has-content checks; the background's extent is the *slot*
  (`vp.docTop` + `vp.pageHeight`), never the page's own height.
- **Every extent in `renderSnapshot` must hang off the same SNAPPED camera anchor the tiles do**
  (KMK island in `ImageViewerContinuousState.renderSnapshot`). TileRenderer's continuous placement
  rounds the shared anchor (`snapX/snapY`) and hangs each grid's clip rect off it; the underlay,
  `renderFast`'s quad, a `Render` page's slot fill and the fade veil all used to hang off the raw,
  unrounded anchor instead. Two paths deriving the *same page boundary* from *two anchors that
  differ by up to half a pixel* is the page-boundary seam itself: the pixel-center rule hands the
  boundary row to neither page whenever a pixel centre falls in the disagreement window, and the
  underlay's colour (the reader background, whenever "automatic background" is not selected) shows
  as a thin line. It only appeared for a *mixed* boundary pair — tiled against fast content while a
  neighbour was still decoding, or decoded against placeholder — which is why each earlier fix
  only made it rarer. Rule: round the anchor once at the top of `renderSnapshot`, with the same
  `kotlin.math.round` over the same expression `continuousAnchor` uses, and never place continuous
  content from the raw anchor.
- **`doubleTapZoomEnabled` is the only host-facing gate; `doubleTapGesturesEnabled` follows it**
  (KMK island in `ImageViewerState.kt`; port of upstream `34fb12b`). The host sets one flag and the
  setter keeps the gesture flag tied, so "double-tap zoom off" means both "no second-tap zoom" and
  "a tap fires on release" — paged `waitForDown` (`ImageViewer.kt`) and the continuous
  early-return gate (`ImageViewerContinuous.kt`) both key on the pair. Setting
  `doubleTapGesturesEnabled` directly would desynchronise the pair; do not.
- **Continuous read-through only advances on real scrolling** (KMK islands; port of upstream
  `92be54d`). `onPageScrolledThrough`'s ratchet computes from walk geometry, and that geometry
  moves for the camera too, not just for content: a pinch zoom-out or a placeholder slot
  shrinking would otherwise mark pages read that no scroll touched. Gate the ratchet on
  `isFlinging || isPanning`; `animateScroll` sets `isFlinging` (a tap navigation is real scroll),
  the single-finger drag sets `isPanning`, and a second pointer joining the drag clears it (a
  pinch can move the read boundary without scrolling). Clearing `isPanning` outside those three
  sites would either starve read-through or reopen the zoom-out corruption.
- **The continuous on-screen set is every page in the walked band, decoded or not — and it goes
  through `publishOnScreen`, never a bare assign** (KMK island; port of upstream `27f3f6c` +
  `92be54d`). `[pages]` in the walk stays decoded-only because that is the *drawn* list, but
  `onScreenPages` feeds `[ImagePage.isOnScreen]` and is what makes a page that invalidated while
  off screen get woken when it finally joins (`cameOnScreen`). The continuous walk used to
  bare-assign decoded pages only, so undecoded on-screen pages never joined, their decode's
  invalidate could go unanswered while parked, and the app's tile/prewarm visibility checks
  (`containsKey`-gated `visible` logic in `TileRenderer`) saw them as off screen. Keep the
  published set through `publishOnScreen(visible)`; bare-assigning silently drops the wake.
- `pageOffset` setter clamps NaN/Inf and walks whole-page deltas with a 20-iteration guard.

## ANTI-PATTERNS
- Do NOT clear inside `renderLoaded` (shared continuous texture) - clear once up front in `ImageViewerContinuousState` instead.
- Do NOT route animated frames through the tile cache.
- Do NOT add a stencil attachment to transition cache passes.
- Do NOT let the viewer fetch images; keep `Image` supply app-side.
- Do NOT assume a rotated `getCurrentTexture()` buffer is blank.
- Do NOT trim spread inner edges (seam).