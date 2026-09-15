# TRANSITION PACKAGE KNOWLEDGE BASE

## OVERVIEW
14 page-turn effects + base class: each transition renders two `ImagePage`s into `dst` at fraction `frac`, sampling a shared 2-slot page cache that is never stencil-masked.

## FILES (15)
`Transition.kt` `TransitionBasic.kt` `TransitionFade.kt` `TransitionFadeWhite.kt` `TransitionFlip.kt` `TransitionFlipLeft.kt` `TransitionFlipRight.kt` `TransitionCube.kt` `TransitionCubeOuter.kt` `TransitionSphere.kt` `TransitionStackUp.kt` `TransitionStackDown.kt` `TransitionStackLeft.kt` `TransitionStackRight.kt` `TransitionNone.kt`

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Base contract + cache | `Transition.kt` | abstract `render(page1, page2, encoder, dst, frac, pos1, pos2, tiles)`; `premultipliedOutput` flag |
| Cache machinery | `Transition.kt` companion | 2 slots (`texture1/2`), `cacheLock`, `getCachedTexture`, `blitCached`, `blitCachedRegion`, `beginClearedPass`, `rotateCacheOnPageChange`, `invalidateCache` |
| Shared blit pipelines | `Transition.kt` companion | `blitPipeline` (full blit), `regionPipeline` (sub-rect blit); both use `BlendFactor.One` src |
| Background blend | `Transition.kt` | `blendBackgroundColor` lerps in linear space, matches `TransitionFade` shader pace |
| Fade family | `TransitionFade.kt`, `TransitionFadeWhite.kt` | shader mix un-premultiplies → linear → re-premultiplies |
| Flip family | `TransitionFlip.kt`, `TransitionFlipLeft.kt`, `TransitionFlipRight.kt` | |
| Cube family | `TransitionCube.kt`, `TransitionCubeOuter.kt` | Cube never covers whole surface → clear pass required first |
| Stack family | `TransitionStackUp/Down/Left/Right.kt` | |
| Others | `TransitionBasic.kt`, `TransitionSphere.kt`, `TransitionNone.kt` | |

## CONVENTIONS
- Every transition: open ONE `beginClearedPass` on `dst`, draw background, blit cached pages into it, close. Never open per-draw passes.
- Cache opens `LoadOp.Clear` on identity change, `LoadOp.Load` for incremental tile blits; never stencil-masked.
- `premultipliedOutput = true` when the shader samples a cached page texture (cache is premultiplied; re-multiplying by alpha darkens edges).
- Animated pages never hit the cache: `getCachedTexture` forces fresh `Clear` + `renderCacheSeed` every call.
- Cache validity tracked by blitted tile-key sets vs `TileRenderer.availableTileKeys`, not a "done" boolean.
- `rotateCacheOnPageChange` swaps slot 2 into slot 1 on page settle instead of wiping.
- Cache textures clamped to 8..8192; destroyed via `pendingDestroy` on resize.

## ANTI-PATTERNS
- Do not stencil-mask cache writes or open cache passes with anything but Clear/Load as above.
- Do not force tile generation in `getCachedTexture`; missing tiles fill in async on the background worker.
- Do not assume a cache hit for animated pages, or for pages that never get tiles (`newlyAvailableTileKeys` null).
- Do not use `SrcAlpha` src factor when blitting cached textures; cached data is already premultiplied.
- Do not open a separate pass per draw call; one shared cleared pass per frame is the invariant.