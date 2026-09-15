# RENDERER PACKAGE KNOWLEDGE BASE

## OVERVIEW
GPU pipeline for the WebGPU image viewer: device/dispatcher singleton, tiled filtered-render cache with stencil masking, live page draw, mipmaps, rescalers, fullscreen pass.

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Device/adapter/instance, render thread, frame profiling | `WebGpuRenderer.kt` | companion singleton; `dispatcher` = single-thread "WebGPU-Render-Thread"; `isOnRenderThread()`; `mutex`/`withContext` gate GPU work |
| Tile cache, stencil, prewarm, generation | `TileRenderer.kt` | ~2100 lines; densest invariant prose comments in the repo. Read header + `gridPlacement`/`drawCore` docs before touching |
| Live page draw (fast + filtered) | `RenderPage.kt` | 3 shader variants: `samplerVariant` (stencil-tested), `plainVariantMasked` (no-op stencil), `plainVariant` (stencil-free) |
| Image upload / placement math | `Image.kt` | `prepareForRender` placement; `solveImagePlacement` (TileRenderer.kt) inverts it |
| Mipmap chain | `Mipmap.kt` | |
| Tile resize chain | `Rescaler.kt` | `Upscaler`/`Downscaler` base; 4 calls per tile, in order, on tile worker |
| Magnify upscaler | `UpscalerCatmullRom.kt` | also the fallback for zoom other upscalers leave over |
| ArtCNN upscaler | `UpscalerArtCnn.kt` | 9 compute passes, luma only, 8x8 workgroups, premultiplied output |
| Shrink downscaler | `DownscalerBox.kt` | |
| Fullscreen triangle | `Fullscreen.kt` | shared with `filter` package |

## CONVENTIONS (differs from parent)
- Everything here is touched only on the render thread; `cleanup` may be called from any thread and posts its work there.
- Stencil is a ring buffer of 3 textures (`STENCIL_BUFFER_COUNT`), never one shared texture: a rotated stencil must not still be in flight from a prior frame.
- Grid snaps to the nearest screen pixel so every blit is an exact 1:1 texel copy; tile bind groups/uniforms are created once at generation, only the anchor/clip uniform is rewritten.
- Generation runs on the render thread but outside the render mutex, a few tiles at a time with a suspend between batches; pending tiles pull on-screen-first, centre-out.
- One grid per whole `ImagePage.ImageSingle`, not per image: a spread's seam bakes into whichever tile straddles it.
- Rescaler applies only when `factor > 1 && supported && appliesAt(scale) && fits(tileSize)`; what it declines resolves in one step.
- Tile cache budget 16-64 MB; `cacheScreens` coerced to 0.5-3.0; `onLowMemory()` drops to 0.8 and evicts.

## ANTI-PATTERNS
- Never touch `TileRenderer` without reading its invariant prose comments (header, `gridPlacement`, `drawCore`). They document why things are the way they are.
- Prewarmed-never-drawn pages blit a stale clip rect: `prewarm`'s tiles leave the wanted range empty, and it once silently missed a step `drawCore`/`availableTileKeys` had. Keep one shared definition (`forEachTile`/`gridPlacement`); never fork a copy.
- Stencil writes 1: `blitPipelineStencilWrite` writes 1 wherever it draws; `RenderPage.samplerVariant` tests `NotEqual` against 1 so a pixel the blit already wrote is skipped, not shaded twice.
- Transition caches bypass stencil: the transition cache-seed pass has no stencil attachment at all. Use `plainVariant` (stencil-free) there; `plainVariantMasked` only declares a no-op stencil so it is valid inside a stencil-attached pass. A stencil-free pass cannot just gain one.
- `TimestampQuery` absent means `generate()` returns null: `timestampsSupported` gates measurement, so `generate(req, scope): Job?` yields null and callers must handle it (`blitAvailableTiles` falls back to `workerScope`, fire-and-forget). Never assume a Job came back.
- `CompareFunction.Always` is code, not a marker: it is the real depth/stencil compare in pipeline descriptors (RenderPage.kt, TileRenderer.kt). Grep false positive, not a TODO.
- Never reuse a single stencil texture across frames; never add a stencil attachment to a pass that has none (transitions).