# PROJECT KNOWLEDGE BASE

**Generated:** 2026-09-15
**Commit:** 7d69a75
**Branch:** master

## OVERVIEW
Android library + sample app: WebGPU-accelerated image viewer (manga-reader style). Kotlin + Compose + JNI C++. Fork of `mpreg-ca/webgpuviewer`, vendored as submodule of komikku-pineapple (`external/`).

## STRUCTURE
```
./
├── library/src/main/java/ca/mpreg/webgpuviewer/
│   ├── renderer/    # GPU pipeline (tiles, mipmaps, upscalers)
│   ├── viewer/      # Composables + gesture/state
│   ├── transition/  # 14 page-transition effects
│   ├── filter/      # GPU filter chain
│   ├── draw/        # small draw primitives
│   ├── ImageView.kt / ImageViewContinuous.kt  # public view entry
│   └── Trim.kt / TrimNative.kt                # JNI bridge
├── library/src/main/cpp/  # libresize.so (resize.cpp, trim.cpp)
├── sample/          # demo app (STALE — does not build, see NOTES)
├── gradle/libs.versions.toml  # single source of versions
└── .github/workflows/build.yml  # CI = publishToMavenCentral only
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Public API | `library/.../ImageView.kt`, `ImageViewContinuous.kt` | `AbstractComposeView` → `ImageViewer` composables |
| Gesture/render loop | `viewer/ImageViewer.kt`, `viewer/ImageViewerState.kt` | `AndroidEmbeddedExternalSurface`, `WebGpuRenderer.dispatcher` |
| GPU device/thread | `renderer/WebGpuRenderer.kt` | single render-thread dispatcher, frame profiling |
| Tiling/masking | `renderer/TileRenderer.kt` | stencil pipeline, tile cache; densest gotcha comments |
| Page model | `viewer/ImagePage.kt` | tile cache vs live-draw decisions |
| Transitions | `transition/Transition*.kt` | caches never stencil-masked |
| Filters | `filter/Filter*.kt` | chain ping-pongs buffers, never self-allocates |
| Native trim/resize | `library/src/main/cpp/`, `TrimNative.kt` | `Java_ca_mpreg_webgpuviewer_TrimNative_*` |
| Versions | `gradle/libs.versions.toml` | never hardcode; use `libs.*` aliases |
| Publishing | `library/build.gradle.kts` (`MergeEmbeddedAarsTask`) | embeds androidx.webgpu AAR; version = tag or `{sha}-SNAPSHOT` |

## CODE MAP
| Symbol | Type | Location | Role |
|--------|------|----------|------|
| `WebGpuRenderer` | class (companion singleton) | `renderer/WebGpuRenderer.kt` | GPU instance/adapter/device, `render()`, `reconfigure()` |
| `ImageViewer` / `ImageViewerContinuous` | @Composable | `viewer/` | gesture handling → `state.init/collect/cleanup` |
| `ImageViewerState` | class | `viewer/ImageViewerState.kt` | page offset, `renderPass`, `captureRenderState` |
| `ImagePage` | sealed hierarchy | `viewer/ImagePage.kt` | `Images` vs `Dummy`; tile-vs-live policy |
| `Filter` / `FilterChain` | class | `filter/` | per-frame GPU passes over shared buffers |
| `Transition` | class hierarchy (14) | `transition/` | animated page effects, own cache |
| `TrimNative` | object (JNI) | `TrimNative.kt` → `trim.cpp` | `findTrim`, `detectBackground` |
| `TileRenderer` | class | `renderer/TileRenderer.kt` | tile grid, stencil writes, prewarm |

## CONVENTIONS
- Kotlin style `kotlin.code.style=official`; Java 17 target; daemon JDK 21, CI JDK 17.
- Deps/plugins only via `gradle/libs.versions.toml` (`libs.*`); modules declare no repos (`FAIL_ON_PROJECT_REPOS`).
- Packages lowercase `ca.mpreg.webgpuviewer.*`; resources dot-named (`app.name`); layouts snake_case.
- C++: C++17, 2-space, `kCamelCase` consts, snake_case fns, `// ----` section separators, internal symbols in anonymous namespace.
- WGSL shaders embedded as Kotlin string constants (no `.wgsl` files).

## ANTI-PATTERNS (THIS PROJECT)
- No `src/test` / `src/androidTest` exists; CI runs zero tests — do not assume coverage.
- Never reference `WebGpuImageView*` (renamed to `ImageView*`); sample layout still uses old name.
- Never add repos inside module `build.gradle.kts` (root policy fails build).
- Never hardcode dep versions; never bypass the version catalog (`ca.mpreg:imagedecoder:6` in sample is the one existing violation — do not copy).
- `buildFeatures`/`compileOptions` belong at `android` level, not inside `defaultConfig` (sample violates — do not copy).

## UNIQUE STYLES
- Module `:webgpuviewer` lives in `library/` dir (mapped in `settings.gradle.kts`).
- Library Kotlin under `src/main/java/` (not `src/main/kotlin/`).
- Custom Maven repo = raw GitHub URL (`mpreg-ca/androidx-webgpu-repo`).
- `MergeEmbeddedAarsTask` fat-shades androidx.webgpu into published AAR.
- NEVER/ALWAYS prose comments document invariants (TileRenderer.kt, ImagePage.kt densest) — read before touching pipeline.

## COMMANDS
```bash
./gradlew :sample:assembleDebug        # sample APK (currently broken, see NOTES)
./gradlew :webgpuviewer:assembleRelease # library AAR
./gradlew :sample:installDebug         # install on device
./gradlew publishToMavenCentral        # CI publish (needs 4 secrets + tag for release version)
./gradlew clean
```

## NOTES
- `sample/` is stale/broken: layout references nonexistent `WebGpuImageViewContinuous`, `MainActivity` opens missing `ref2.png`, `moe/grass/...` dir mismatches `ca.mpreg...` package, nested `defaultConfig` block. `sample/build/` absent (never built here). Fix these before building sample.
- WSL gotcha: `local.properties` has Windows SDK path; use `/mnt/c/Users/Branden/AppData/Local/Android/Sdk`.
- Toolchain is bleeding-edge (AGP 9.3.2, Kotlin 2.4.10, compileSdk 37, webgpu `1.0.0-dev05`).
- Zero `TODO`/`FIXME`/`HACK` markers in source; `toDouble()` / `CompareFunction.Always` / `alwaysAvoidCutout` are grep false positives, not markers.
- `.git` is a gitfile — submodule at `external/webgpuviewer-houri` of komikku-pineapple.
