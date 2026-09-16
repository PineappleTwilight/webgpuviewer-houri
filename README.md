# WebGPUViewer

WebGPU-accelerated image viewer for Android — manga-reader style paged and continuous (webtoon) views. Kotlin + Jetpack Compose + JNI C++ (`libresize.so`), with WGSL shaders embedded as Kotlin strings.

Fork of `mpreg-ca/webgpuviewer`, vendored as a submodule of `komikku-pineapple` (`external/`). Upstream is published to Maven Central as `ca.mpreg:webgpuviewer`; this fork is **not** published — consume it locally (see Installation).

## Features

- **Two viewers**
  - `ImageViewer` — paged (horizontal page turns by default, vertical with `isVertical = true`)
  - `ImageViewerContinuous` — continuous vertical scroll (webtoon style)
  - XML wrappers `ImageView` / `ImageViewContinuous` (`AbstractComposeView`) for View-based layouts
- **GPU pipeline** (`renderer/`)
  - Single render-thread dispatcher in `WebGpuRenderer` (instance/adapter/device, `render()`, `reconfigure()`, frame profiling)
  - `TileRenderer` — stencil tile grid + tile cache + prewarm (read its NEVER/ALWAYS comments before touching)
  - `Mipmap`, `Rescaler`, `Fullscreen`, `RenderPage`
  - Upscalers: `UpscalerCatmullRom`, `UpscalerArtCnn`; downscaler: `DownscalerBox`
- **Page model** (`viewer/ImagePage.kt`)
  - `ImageSingle(image)` — decoded image page; `ImageSingle(frames)` for animation
  - `ImageSpread(left, right)` — dual-page spread (does not own sides' images)
  - `Dummy(width, height)` — placeholder; `Render(width, height)` — app-drawn content
- **14 page-transition effects** (`transition/`) — `Basic` (+ `Vertical`), `None`, `Fade`, `FadeWhite`, `Flip`, `FlipLeft`, `FlipRight`, `Cube`, `CubeOuter`, `Sphere`, `StackUp`, `StackDown`, `StackLeft`, `StackRight`. Transition caches are never stencil-masked.
- **GPU filter chain** (`filter/`) — `FilterChain` ping-pongs shared buffers (never self-allocates); filters: `FilterBrightnessContrast`, `FilterHlg`, `FilterLut3d` (+ `Lut3d`), `FilterFullscreen`
- **Draw primitives** (`draw/`) — `Draw`, `Clear`, `Rect`, `Circle`, `Line`, `Text` (used by `ImagePage.Render`)
- **Native trim / resize** (`library/src/main/cpp/`, `TrimNative.kt`, `ImageUtil.kt`)
  - `libresize.so` from `resize.cpp` + `trim.cpp` (C++17, `-O3 -flto`, links only `jnigraphics` + `log`)
  - `ImageUtil_resizeLinearAreaNative` — half-area downscale, ARM NEON, sRGB↔linear LUTs
  - `TrimNative_findTrim` — per-color foreground bounding box, threaded band scan (≤8 threads)
  - `TrimNative_detectBackground` — edge-sampled background as `0xAARRGGBB`
- **Gesture / render loop** (`viewer/ImageViewer.kt`, `ImageViewerState.kt`) — `AndroidEmbeddedExternalSurface`, `WebGpuRenderer.dispatcher`, `state.init / collect / cleanup`

## Requirements

| Component | Version | Source |
|---|---|---|
| Gradle | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.3.2 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.10 | `gradle/libs.versions.toml` |
| compileSdk | 37 | `library/build.gradle.kts` |
| minSdk | 24 | `library/build.gradle.kts` |
| Java target | 17 | `library/build.gradle.kts` |
| CMake | 3.22.1, C++17 | `library/build.gradle.kts`, `CMakeLists.txt` |
| androidx.webgpu | 1.0.0-dev05 | `gradle/libs.versions.toml` |
| Compose BOM | 2026.08.00 | `gradle/libs.versions.toml` |
| compose-foundation / animation-core | 1.12.0 | `gradle/libs.versions.toml` |
| activity-compose | 1.13.0 | `gradle/libs.versions.toml` |
| core / annotation | 1.19.0 / 1.10.0 | `gradle/libs.versions.toml` |
| vanniktech maven-publish | 0.37.0 | `library/build.gradle.kts` |

Toolchain is bleeding-edge (AGP 9.3.2, Kotlin 2.4.10, compileSdk 37). Daemon JDK 21, CI JDK 17 (Temurin). Kotlin style `kotlin.code.style=official`.

## Installation

Upstream (Maven Central) — for the original `mpreg-ca/webgpuviewer`, not this fork:

```kotlin
implementation("ca.mpreg:webgpuviewer:<version>")
```

This fork (`PineappleTwilight/webgpuviewer-houri`) is **not on Maven**. Use it locally:

- As vendored here: consumed as a submodule under `external/` — reference `:webgpuviewer` (`projectDir = file("library")`) directly, or
- Build the AAR yourself: `./gradlew :webgpuviewer:assembleRelease` and depend on the output (e.g. `files(...)`, `mavenLocal()`, or a composite build).

Notes (apply to both paths):

- **No custom repo needed by consumers.** The build-only repo `https://raw.githubusercontent.com/mpreg-ca/androidx-webgpu-repo/main` (see `settings.gradle.kts`) resolves `androidx.webgpu` at build time, and `MergeEmbeddedAarsTask` fat-shades its `classes.jar`, `jni/` libs, and assets into the published AAR.
- **No manifest changes.** Library `AndroidManifest.xml` is empty (`<manifest />`) — no permissions, features, or activities.
- `minSdk 24`. Consumer ProGuard rules keep `ca.mpreg.**` and `androidx.webgpu.**`.
- Deps/plugins only via the version catalog (`libs.*`); modules declare no repos (`FAIL_ON_PROJECT_REPOS`).

## Usage

### Paged reader

```kotlin
@Composable
fun MangaReader(images: List<Image>) {
    val state = remember { ImageViewerState() }
    var current by remember { mutableIntStateOf(0) }

    // Index is relative to current page: 0 = current, -1 = prev, 1 = next.
    // Return null when there is no page in that direction.
    state.fetchPage = { index ->
        images.getOrNull(current + index)?.let { ImagePage.ImageSingle(it) }
    }
    state.onPageChange = { delta -> current += delta }

    ImageViewer(state = state)
}
```

### Continuous (webtoon) reader

```kotlin
@Composable
fun WebtoonReader(images: List<Image>) {
    val state = remember { ImageViewerContinuousState() }
    var current by remember { mutableIntStateOf(0) }

    state.fetchPage = { index ->
        images.getOrNull(current + index)?.let { ImagePage.ImageSingle(it) }
    }
    state.onPageChange = { delta -> current += delta }

    ImageViewerContinuous(state = state)
}
```

### Decoding to a GPU image

```kotlin
// Suspend — call from any coroutine. RGBA direct ByteBuffer, >=8x8, <=16384x16384, <=64MP.
val image = Image(pixels = rgbaBuffer, width = w, height = h)
```

`Image(...)` does CPU trim/mipmap work on `Dispatchers.Default` and uploads via `WebGpuRenderer.onDispatcher` internally.

### State knobs

`ImageViewerState(isVertical = false, isReversed = false)` / `ImageViewerContinuousState()`:

- `fetchPage: ((Int) -> ImagePage?)?` — **required**, nothing draws without it
- `onPageChange: ((Int) -> Unit)?` — signed page delta (`isReversed` flips sign)
- `onTap`, `onLongTap`, `upscaler`, `downscaler`, `filters`, `transition`, `avoidCutout`, `dpi`, `pageGap` (continuous), `homeScale`
- Continuous extras: `scrollBy` / `scrollTo` / `scrollToPage`, `savePosition` / `restorePosition`, `onPageScrolledThrough`
- Lifecycle (`init` → `invalidate` → `collect` → `cleanup`) is owned by the composable's `AndroidEmbeddedExternalSurface.onSurface` — the app never calls it.

### XML views

```kotlin
open class ImageView(context, attrs, isVertical = false, isReversed = false) : AbstractComposeView
// open val state: ImageViewerState — the public hook
```

`ImageViewContinuous` mirrors it for continuous mode.

## API reference

| Area | Symbols | Location |
|---|---|---|
| Entry | `ImageViewer`, `ImageViewerContinuous` (`@Composable`) | `viewer/` |
| State | `ImageViewerState`, `ImageViewerContinuousState` | `viewer/` |
| Pages | `ImagePage.ImageSingle / ImageSpread / Dummy / Render` | `viewer/ImagePage.kt` |
| Device | `WebGpuRenderer` (companion singleton) | `renderer/WebGpuRenderer.kt` |
| Tiling | `TileRenderer` | `renderer/TileRenderer.kt` |
| Images | `Image`, `Mipmap`, `Rescaler`, `Fullscreen`, `RenderPage` | `renderer/` |
| Upscale | `UpscalerCatmullRom`, `UpscalerArtCnn`, `DownscalerBox` | `renderer/` |
| Transitions | 14 effects (see Features) | `transition/` |
| Filters | `Filter`, `FilterChain`, `FilterFullscreen`, `FilterBrightnessContrast`, `FilterHlg`, `FilterLut3d`, `Lut3d` | `filter/` |
| Draw | `Draw`, `Clear`, `Rect`, `Circle`, `Line`, `Text` | `draw/` |
| Native | `TrimNative` (`findTrim`, `detectBackground`), `ImageUtil` (`resizeLinearAreaNative`), `Trim` (GPU path) | `TrimNative.kt`, `ImageUtil.kt`, `Trim.kt` → `trim.cpp`, `resize.cpp` |

Packages are lowercase `ca.mpreg.webgpuviewer.*`. WGSL shaders live as Kotlin string constants (no `.wgsl` files). C++ uses 2-space indent, `kCamelCase` consts, `snake_case` fns, `// ----` separators, internal symbols in anonymous namespaces.

## Building

```bash
./gradlew :webgpuviewer:assembleRelease # library AAR (works) — how this fork is consumed
./gradlew :sample:assembleDebug         # sample APK — currently BROKEN (see below)
./gradlew :sample:installDebug         # install on device
./gradlew publishToMavenCentral        # inherited upstream publish config — NOT used by this fork
./gradlew clean
```

- Module `:webgpuviewer` maps to `library/` (`settings.gradle.kts`); library Kotlin is under `src/main/java/` (not `src/main/kotlin/`).
- Upstream versioning (for reference): git tag name when `GITHUB_REF_TYPE=tag`, else `{short-sha}-SNAPSHOT`, coordinates `ca.mpreg:webgpuviewer:<tag>`. This fork publishes nothing.
- CI (`.github/workflows/build.yml`): inherited `publishToMavenCentral` workflow (`ubuntu-latest`, JDK 17 Temurin, 4 Maven Central secrets). CI runs zero tests — no `src/test` / `src/androidTest` exists.
- WSL gotcha: `local.properties` holds a Windows SDK path; under WSL use `/mnt/c/Users/Branden/AppData/Local/Android/Sdk`.

## Sample app status

`sample/` is **stale and does not build**. Do not treat it as a working reference:

- Layout references nonexistent `WebGpuImageViewContinuous` (renamed to `ImageViewContinuous`; same for `WebGpuImageView` → `ImageView`)
- `MainActivity` opens missing `ref2.png` (only `ref.png` exists in `sample/assets/`)
- Source dir `moe/grass/...` mismatches `ca.mpreg...` package / namespace
- Nested `defaultConfig` block (`buildFeatures`/`compileOptions` belong at `android` level)
- Uses removed APIs: `ImagePage.Images` (now `ImageSingle`), `haveNext`/`havePrev` setters (now computed `val`s), `state.render()` (loop is `collect()`-driven)
- Hardcodes `ca.mpreg:imagedecoder:6` (the one version-catalog violation — do not copy)

The `fetchPage` mapping shape in `MainActivity` is still the right pattern; only the names above need fixing.

## Project structure

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
├── sample/          # demo app (STALE — does not build)
├── gradle/libs.versions.toml  # single source of versions
└── .github/workflows/build.yml  # CI = publishToMavenCentral only
```

## Native details

- `libresize.so` via `library/src/main/cpp/CMakeLists.txt` (C++17, `jnigraphics` + `log` only).
- Loaded with `System.loadLibrary("resize")` in `TrimNative` and `ImageUtil` (each exposes `isAvailable()`).
- JNI: `Java_ca_mpreg_webgpuviewer_ImageUtil_resizeLinearAreaNative`, `Java_ca_mpreg_webgpuviewer_TrimNative_findTrim`, `Java_ca_mpreg_webgpuviewer_TrimNative_detectBackground`.
- Contract: RGBA8 row-major tightly packed direct `ByteBuffer`s, dims 1..16384, capacity ≥ `w*h*4`; worker threads never touch JNI; no exceptions/RTTI.

## License

MIT — see [LICENSE](LICENSE).
