# Upstream port ledger (2026-11)

Our fork branched from `mpreg-ca/webgpuviewer` at `7efb710` (2026-09-03) and has since carried
111 commits of its own. Upstream has 76 commits we do not (by patch id), most of them a rewrite of
the tile/render pipeline (`TileRenderer`, `Image`, `ImagePage`, `ImageViewer*`, WGSL shader
network, `ComposablePageHost`). This file records what has been **ported**, what was already
answered by our own work, and what cannot be ported without that rewrite — so a later pass does
not have to re-derive it.

Method: for each missing commit, the lines it *removes* were matched against the same file in our
tree (`git cherry` for the set, then a verbatim-line ratio per file). A ratio near 1.0 means the
code it patches still exists here and the fix is portable; near 0 means the target was rewritten.

## Ported from upstream

| Commit | Subject | Where |
|---|---|---|
| `ec1d88b` | Fix tap navigating from cancelled target | `ImageViewer.kt` — a tap resumes the target the interrupted animation was heading for |
| `76780cf` | Fix double tap zoom clamp | `ImageViewerContinuous.kt` — both zoom animations clamp `offsetX` as they go |
| `d7f2af8` | Time strip flings from the lift, not their first frame | `ImageViewerContinuous.kt` — `DecayAnimation` timed from lift, like RecyclerView |

## Already answered in this fork (do not re-port)

- `acf9d1c` *Trigger redraw when cameOnScreen* — `ImagePage.cameOnScreen()` calls `onInvalidate`.
- `92be54d` *Only update progress tracking when scrolling* — `isPanning` + `onViewport` gate exist.
- `803fda0` *embed the render surface via TextureView* (pre-branch base) — `ViewerSurface.kt`
  already splits API 34+ (`AndroidExternalSurface`) from below (`AndroidEmbeddedExternalSurface`),
  and adds a `surface.onDestroyed` hook upstream does not have.
- Device-loss reporting (`c57cfe0`, `78cd00f`) — `DeviceLostListener`, `WebGpuHealth`,
  `surfaceParkCount` and the app-side recovery ladder cover this; our shape differs by design.
- `afe727c` *core-features-and-limits + degenerate surface sizes* — ours (KMK), not upstream.

## Blocked on the upstream rewrite (evaluated, not portable)

- `70fabb4` *TransientAttachment for tiles* — a one-line `TextureUsage` change. **Deliberately
  not applied**: our stencil texture is written by a blit pass and read by later
  masked `RenderPage` passes in the same frame; `TransientAttachment` makes the attachment
  memoryless with contents only guaranteed inside one render pass, so it would be a
  device-dependent correctness risk. Do not port unless the passes are merged into one.
- `0224cf1`, `50be284`, `217f72f`, `cf86e34`, `9b6c00f`, `0325eef`, `d37a3da`, `6551b16`,
  `1f04a58` — tile pacing, prewarm, staged rescaler and shader-filter work. Our `TileRenderer`
  keeps its own atlas/budget/pacing model (`atlasGrowthPending`, `budgetTiles`, per-frame
  prewarm), so these patches' context no longer exists.
- `f146c31` *Fix cutout height at startup* — depends on upstream's cutout refactor
  (`WindowInsetsCompat` + `cutoutHeight` contract). Ours gates on `avoidCutout`/`cutoutTopPx`
  by design; port only together with that refactor.
- `5aac203`, `7e3a75f`, `b502b8b`, `7b877c9`, `12e6b94`, `70a2d69`, `ee72b15` — draw/crop/rect
  and shader-network rework (`Image`, `Mipmap`, WGSL). Not a fix patch; the file model differs.
- Everything from `95ab288` *Composable pages* onward that touches `ComposePageHost` — that file
  does not exist here.

## Low-end devices

The app-side answer is in the app, not the library: `WebGpuHealth` records a strike when a
session ends with the device dead, parked, or (added 2026-11) having decoded pages while never
drawing one, and the provider stops offering WebGPU after two such sessions. The provider-level
RAM gate was removed on 2026-11-19: the renderer is offered wherever `WebGpuRenderer.isAvailable`
is true, and the reader's own memory budget sizes its decoded pages and tiles to the device. A
device with no headroom for GPU-visible memory is caught by the health strikes at runtime.
