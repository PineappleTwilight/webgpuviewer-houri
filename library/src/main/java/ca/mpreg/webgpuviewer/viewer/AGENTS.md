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
- **A page boundary is ONE float, chained — never re-derived per page** (KMK island in
  `ImageViewerContinuousState.captureRenderState` + `TileRenderer.ClipEdges`). Even with the snapped
  anchor shared, each page's own clip edges were still that page's separate float expression over
  the same document boundary (`snapY + centerYOffset + halfB` for the upper page vs the next page's
  `snapY + centerYOffset - halfT`): mathematically one value, float-adjacent in practice — a few
  ULPs apart. Whenever the boundary value landed within that window of a pixel CENTRE, the blit
  shader's pixel-centre rule handed the row to neither grid and the underlay showed through — the
  same thin line, now rare, which is exactly how it kept appearing after every earlier fix.
  `captureRenderState` now chains the device-space edges through the walked band — each page's top
  edge is the previous page's bottom edge plus the device-space gap, all walks anchored on one
  shared `chainTopPage0` (the same snapped anchor expression `renderSnapshot` uses, over `height`,
  which equals the swapchain texture's height) — and hands the pair to the tile path as
  `TileRenderer.ClipEdges`, which replaces the derived clipT/clipB for uncropped pages (a crop
  keeps its derived inset: its content edge is mid-slot and shares no boundary). The `Render` page
  slot fill and its `renderLoaded` centre derive from the same chained edges. Rule: adjacent pages'
  clip edges must be *assigned* from one another, not computed from neighbours' inputs twice —
  `round(a) + b` isn't `round(a + b)` and `x + y + z` isn't `x + (y + z)`; the only value that
  cannot disagree is the one both sides share.
- **A tile-pipeline failure falls back to the fast page path, it does not fail the page.**
  `ImageSingle.drawLive` calls `tiles.draw`, then `renderPage` only when the tile path reports
  it covered nothing. A driver that throws out of the tile path (bad atlas allocation, rejected
  pipeline) used to take the whole frame down before the direct draw could run — every frame,
  for the life of the page, on a device where the simple `renderPage` pipeline was fine. The
  call is now wrapped: on an exception the page sets `highQuality = false` and the direct draw
  paints it. The pass itself is wrapped too (`beginLivePass`): a device that cannot provide the
  **Stencil8 attachment** throws one line earlier, and the fast path is the only render path in
  this library that opens no stencil pass at all. Do not remove either catch; "the tiles and the
  mask are only an optimization" is precisely the invariant they protect. **And the other half of
  that invariant is what `tiles.draw` is allowed to answer**: it may return covered only when it
  blitted this frame. A grid that answered `true` after painting nothing (an empty placement, or
  no instance buffer) skipped `renderPage` and left the page invisible with no error — the 2026-11-22
  black-page shape. `TileRenderer.drawCore` now requires an actual blit; if you add a "nothing to
  draw" early return, it must answer `false`, never `true`.
- **The first on-screen page is often still a placeholder — `page=` must follow the adopted image.**
  `resolveFetchedPage(0)` marks a page as drawn on the first frame `imagePage.isOnScreen` answers
  true, which is usually the `ProgressPage` the shell starts with; recording geometry there reported
  `img=null scale=null` for a session that had decoded three pages (2026-11-23 dump). The recorder
  now overwrites the record as soon as the on-screen page is an `ImageSingle`, so the line describes
  the image, not the spinner.
- **A page the renderer cannot place is refit when it is drawn.** `ImageSingle.renderPage` refits a
  page whose own `scale` is the parentless 0.01 sentinel or non-finite, or whose *home* placement
  lies entirely outside the surface, instead of drawing nothing. This is the geometry half of a
  black frame the diagnostics can otherwise only describe; it never overrides a user's pan/zoom
  (the guard fires at home or on the sentinel only). The refit is logged as
  `ImagePage: Refitting page ...` and appears in `page=`.
- **The page is painted *under* the tiles, every frame — the tile cache is an overlay, never a
  substitute.** `ImageSingle.drawLive` paints the page with `renderPage` unconditionally and then
  blits the tile cache over it. It used to paint the page only when the grid answered "not
  covered", which made the page's visibility a function of the tile path's honesty: any wrong
  answer (an empty placement, no instance buffer, a count over capacity) or any blit whose pixels
  did not land left a black page with no error — the 2026-11-22 report. The cost is one textured
  page quad per frame per page, which is what the pre-tile renderer did and what every non-WebGPU
  viewer does. **Do not reintroduce the skip**: `covered` may decide prewarming and nothing else.
- **Device-bound pipeline caches do not survive a rebuild, and a rejected frame is not drawn.**
  `RenderPage`'s `Variant`/`FormatKeyed` caches (sampler, sampler-unmasked, plain, plain-masked,
  masked-rect) are built against one `GPUDevice`; nothing cleared them when `recreateDeviceLocked`
  replaced it. The 2026-11-23 Mali-G76 dump has the signature: `recoveries=1`, `frames=110`,
  `drawn=true`, endless `Uncaptured WebGPU error [BindGroupLayout] is associated with [Device]`
  → `Invalid CommandBuffer` → black screen. A validation error invalidates the submitted command
  buffer while `submit` still returns, so `render` compares `WebGpuRenderer.validationErrors`
  across the submit and reports a failed frame (`onDrawError`) instead of a drawn one.
  `RenderPage.resetForDeviceRebuild()` runs inside `recreateDeviceLocked`, under the render mutex,
  after `deviceGeneration++`. **Rule: a painter that caches a pipeline, bind group or buffer keyed
  by device owes a reset at device recreation — resetting pages does not reach an object cache.**
- **The direct page path draws with `linear = true`; never select the plain pipeline for it.**
  `ImageSingle.renderWith` (the fallback used by `drawLive` when the tile grid is skipped, which is
  every PowerVR page) used to call `renderPage(..., linear = false, masked = false)` and so ran the
  `plainVariant` shader. On the PowerVR GE8322 that pipeline draws nothing while the same page on
  the Mali-G76, drawn through `samplerVariant`, is visible with identical geometry and mipmaps
  (2026-11-23 M8_4G dump: `draw=pages=1 quads=1 rect=0.0,0.276,1.0,0.724`, no validation errors,
  black screen). The direct path now uses `linear = true, masked = false` - the linear-light shader
  without the stencil test, valid in the stencil-free `renderWith` pass. Keep the cost argument out
  of this: it is one page quad per frame, and a device that cannot draw the plain variant cannot
  afford it.
- **`settings.gradle.kts` pins `ca.mpreg:webgpuviewer` to `:webgpuviewer` in the included build.**
  The fork's coordinates (`group = "ca.mpreg"`, `version = "0.0.0"`) happen to match the published
  artifact the app depends on, so the composite build would substitute it even without an explicit
  rule - but any future version bump on either side would silently switch the reader back to
  mpreg's published AAR. The `dependencySubstitution` block makes the fork the only source.
- **`doubleTapZoomEnabled` is the only host-facing gate; `doubleTapGesturesEnabled` follows it**
  (KMK island in `ImageViewerState.kt`; port of upstream `34fb12b`). The host sets one flag and the
  setter keeps the gesture flag tied, so "double-tap zoom off" means both "no second-tap zoom" and
  "a tap fires on release" — paged `waitForDown` (`ImageViewer.kt`) and the continuous
  early-return gate (`ImageViewerContinuous.kt`) both key on the pair. Setting
  `doubleTapGesturesEnabled` directly would desynchronise the pair; do not.
- **"Disable zoom in" is `ImagePage.maxScaleAtHome`, and `maxScale` is enforced during gestures,
  not only on release.** `maxScaleAtHome` makes the `maxScale` getter resolve `homeScale` against
  the page's live parent, which is what lets a host request the clamp at decode time — before the
  page is attached, `homeScale` is the parentless 0.01 sentinel, so storing it would freeze the
  page. `ImageViewer.kt` clamps both the pinch scale and the double-tap-drag scale to
  `page.maxScale` while the fingers are down; the old release-only clamp let a gesture zoom
  visibly and spring back, which reads as the setting doing nothing. Do not fold double-tap zoom
  into this clamp: it is a separate gate, and coupling them killed pinch zoom on hosts that only
  wanted double-tap off.
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
- **`pageOffset` setter clamps NaN/Inf and walks whole-page deltas with a 20-iteration guard.**
- **A page attached while the surface is 0x0 defers its home init to a later `attach`, never
  latches the 0.01 sentinel** (KMK islands in `ImagePage.kt`). The `parent` setter's
  initialize-to-home block used to run unconditionally: `homeScale` for an unsized parent
  answers 0.01, and a page attached in a teardown/relayout window (e-ink refresh-mode switches
  are the field case) kept that scale for the life of the shell — "the page renders at a few
  dozen pixels". The setter now leaves `homeInitPending` when `width`/`height` are 0, and
  `attach` applies home the first time it sees real dimensions with the page still pristine
  (x=0, y=0, scale=1). The pristine condition is what makes this safe against a user's own
  zoom: once anything has positioned the page, the pending init does nothing. Never simplify
  the two-site dance back into the setter — `attach` is idempotent (`this.parent !== parent`),
  so a skipped init would otherwise never re-run.
- **`ViewerSurface` owns the `Surface.onDestroyed` registration** (before the host block's first
  suspension, so nothing can destroy a surface ahead of it) and forwards it to
  `ImageViewerState.onSurfaceDestroyed` → `WebGpuRenderer.detachSurface`: the framework
  dismantles the window once the surfaceDestroyed callbacks return, so the in-flight frame is
  drained and gated *inside* that callback, on the main thread, before Compose cancels the
  render job. Do not add a surface consumer that bypasses `ViewerSurface` or drops the
  `onSurfaceDestroyed` wiring - see `WebGpuRenderer.detachSurface` for the rotation crash it
  prevents.

## ANTI-PATTERNS
- Do NOT clear inside `renderLoaded` (shared continuous texture) - clear once up front in `ImageViewerContinuousState` instead.
- Do NOT route animated frames through the tile cache.
- Do NOT add a stencil attachment to transition cache passes.
- Do NOT let the viewer fetch images; keep `Image` supply app-side.
- Do NOT assume a rotated `getCurrentTexture()` buffer is blank.
- Do NOT trim spread inner edges (seam).