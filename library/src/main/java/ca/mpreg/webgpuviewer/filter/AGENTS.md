# AGENTS.md - filter package

## OVERVIEW
Post-processing chain between the viewer's offscreen draw and the swapchain: each enabled Filter runs over the previous result, the last one writes the swapchain directly.

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Filter contract | Filter.kt | `run(src -> dst)`; chain owns both textures. `outputFormat`, `usesCompute`, `outputWidth/Height`, `active`, `label` |
| Chain wiring | FilterChain.kt | `beginFrame`/`endFrame`; texture pool, ping-pong, tail blit, tiny-surface guard |
| Fragment-pass base | FilterFullscreen.kt | `code` (fs_main) + `entries()`; bind-group cache ring keyed by source handle |
| Per-pixel filters | FilterBrightnessContrast.kt, FilterHlg.kt | 16-byte uniform buffer, dirty-flag upload in `prepare()` |
| 3D LUT filter | FilterLut3d.kt | 3D texture, trilinear, premultiplied-alpha undo/redo, half-float upload |
| LUT parsing | Lut3d.kt | `.cube` and madVR `.3dlut` parsers; streaming, resampling |

## CONVENTIONS
- WGSL in Kotlin string constants; `Fullscreen.VERTEX` prepended, so `fs_main(in: VertexOutput)` with `in.uv`/`in.position` in scope.
- 1:1 passes use `textureLoad` (no sampler); only resampling/LUT passes sample.
- Settings: `@Volatile` var, setter coerces range, sets dirty flag, calls `invalidate()`. `active = enabled && configured` (non-default params, `lut != null`).
- Uniforms: 16-byte buffer, written on render thread in `prepare()` when dirty.
- `cleanup()` calls `rebind()` after destroying own resources.
- No blend state: pass replaces every pixel.
- Compute filters must set `usesCompute`; chain gives offscreen dst and blits (swapchain has no storage binding).

## ANTI-PATTERNS
- Never allocate own input/output; take `chain.scratch()` and `chain.release()` before `run()` returns.
- Never cache bind groups keyed only by the last texture: chain rotates textures each frame, so cache a ring (4) keyed by native handle; `rebind()` when own bindings change (new LUT size).
- Never hand slots back from a throwing frame: `releaseAll()` at `beginFrame`; pool lives within one frame only.
- Never `textureSample` under non-uniform control flow (LUT early return): use `textureSampleLevel(..., 0.0)`.
- Never load a 96MB madVR file whole: stream, skip, resample to <=128^3 (default 64, ~6MB touched).
- Never assume RGBA8Unorm is storable: compute passes use RGBA16Float.
- Never let a filter write the swapchain when it resamples or needs headroom: `direct` path requires RGBA8Unorm + same size + fragment.
- Don't skip the tiny-surface guard (<8px): Adreno gralloc 0x3b fails on intermediate textures.