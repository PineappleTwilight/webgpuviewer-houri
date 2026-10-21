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
- Tile cache budget 16-64 MB; `cacheScreens` coerced to 0.5-3.0; `onLowMemory()` shrinks and
  evicts, `restoreMemoryBudget()` puts the pre-shrink value back.
- **`cacheByteBudget` (host-set, 0 = derive from `cacheScreens`) overrides the screens path
  entirely.** At or above ~1440p the 16-64 MB `coerceIn` is what actually sized the atlas, so
  tuning `cacheScreens` there did nothing — which is why a host that knows its memory class now
  supplies bytes instead.
- **A shrink applies immediately, a grow re-cuts the atlas.** `maxTileBytes` takes
  `minOf(allocated, budgetTiles())` so eviction honours a lowered budget without reallocating; the
  atlas itself is one fixed allocation latched in `atlasSide`, so growth arms
  `atlasGrowthPending` and `newFrame` tears it down and re-cuts — the only point in a frame where
  no caller holds a grid.
- **`onLowMemory` scales whichever budget is in force**, not just `cacheScreens` — `budgetTiles`
  ignores screens once a host sets bytes, so shrinking screens there would silently shed nothing.
- **`drawMaskedRect` draws from a frame-counted ring, and `WebGpuRenderer.render()` feeds it.**
  Every background rect per page per frame (the continuous underlay is the reader's most frequent
  one) used to build a fresh `GPUBuffer` + bind group; the ring (`maskedRectSlots`) serves them
  with `Mipmap.tileUniformFor`'s persistent-buffer precedent instead. `RenderPage.beginFrame()`
  is called once per encoded frame — the count resets there, slots are distinct only *within
  one frame* (two `writeBuffer` calls before a submit make every draw the buffer serves read the
  last colour), draws past `MASKED_RECT_RING` fall back to the one-shot pair, and the ring is
  dropped when `deviceGeneration` moves. A second render entry point must call
  `RenderPage.beginFrame()` too.

## ANTI-PATTERNS
- Never touch `TileRenderer` without reading its invariant prose comments (header, `gridPlacement`, `drawCore`). They document why things are the way they are.
- **Do not return `maxTileBytes` to `atlasBudgetTiles` alone.** That figure is the atlas's allocation, not the policy; a lowered `cacheByteBudget` would then only bind after a full surface rebuild.
- **`Display.registerHdrSdrRatioChangedListener` throws `IllegalStateException` unless `isHdrSdrRatioAvailable`.** Gate registration on that flag, not on a non-null display — otherwise every surface creation on an SDR-only panel logs a full stack trace for the display's own correct answer.
- Prewarmed-never-drawn pages blit a stale clip rect: `prewarm`'s tiles leave the wanted range empty, and it once silently missed a step `drawCore`/`availableTileKeys` had. Keep one shared definition (`forEachTile`/`gridPlacement`); never fork a copy.
- Stencil writes 1: `blitPipelineStencilWrite` writes 1 wherever it draws; `RenderPage.samplerVariant` tests `NotEqual` against 1 so a pixel the blit already wrote is skipped, not shaded twice.
- Transition caches bypass stencil: the transition cache-seed pass has no stencil attachment at all. Use `plainVariant` (stencil-free) there; `plainVariantMasked` only declares a no-op stencil so it is valid inside a stencil-attached pass. A stencil-free pass cannot just gain one.
- `TimestampQuery` absent means `generate()` returns null: `timestampsSupported` gates measurement, so `generate(req, scope): Job?` yields null and callers must handle it (`blitAvailableTiles` falls back to `workerScope`, fire-and-forget). Never assume a Job came back.
- `CompareFunction.Always` is code, not a marker: it is the real depth/stencil compare in pipeline descriptors (RenderPage.kt, TileRenderer.kt). Grep false positive, not a TODO.
- Never reuse a single stencil texture across frames; never add a stencil attachment to a pass that has none (transitions).
- **Never let the `drawMaskedRect` ring wrap inside one frame.** Distinct slots only hold between two
  writes to one buffer when a submit separates them, and `writeBuffer` never lands between a
  submitted frame's commands — so the per-frame count (`RenderPage.beginFrame`) is the load-bearing
  reset. A frame that would exceed `MASKED_RECT_RING` already falls back to one-shot
  allocate-and-close; keep that fallback rather than rotating modulo the ring size.
- **`Draw.rect`/`Draw.circle` allocate a buffer per call by design** — their rect count per frame is
  unbounded (transitions, procedural placeholder pages), so they cannot adopt the guard-free ring.
  Transient paths first; only move them onto persistent slots once a per-frame ceiling exists.