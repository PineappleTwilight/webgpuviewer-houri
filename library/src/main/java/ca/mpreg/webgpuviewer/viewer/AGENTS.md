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
- `pageOffset` setter clamps NaN/Inf and walks whole-page deltas with a 20-iteration guard.

## ANTI-PATTERNS
- Do NOT clear inside `renderLoaded` (shared continuous texture) - clear once up front in `ImageViewerContinuousState` instead.
- Do NOT route animated frames through the tile cache.
- Do NOT add a stencil attachment to transition cache passes.
- Do NOT let the viewer fetch images; keep `Image` supply app-side.
- Do NOT assume a rotated `getCurrentTexture()` buffer is blank.
- Do NOT trim spread inner edges (seam).