# AGENTS.md - library/src/main/cpp

## OVERVIEW
JNI native lib `libresize.so`: CPU fallbacks for trim/background-detect and area resize, bridging to `TrimNative.kt` / `ImageUtil.kt`.

## WHERE TO LOOK
| Task | File | Notes |
|------|------|-------|
| Trim bounds + bg detect | `trim.cpp` | `findTrim`, `detectBackground`; `std::thread` band scan |
| Area resize (half) | `resize.cpp` | ARM NEON sRGB/linear, LUTs via `std::call_once` |
| Build config | `CMakeLists.txt` | C++17 required, links `jnigraphics log`; `-O3 -flto` set in `library/build.gradle.kts` |
| Kotlin bridge | `../java/ca/mpreg/webgpuviewer/TrimNative.kt` | JNI decls must match exports exactly |

## CONVENTIONS
- 2-space indent; C++17; no exceptions/RTTI.
- Consts `kCamelCase` (`kChannels`, `kMinCoverage`, `kWhite`); fns snake_case (`scanBand`, `classifyEdge`); structs PascalCase (`ColorTest`, `Bounds`, `EdgeLine`).
- Internals in anonymous namespace; `// ----` section separators.
- Exports: `extern "C" JNIEXPORT ... JNICALL Java_ca_mpreg_webgpuviewer_<Class>_<method>`.
- Pixel layout RGBA8 row-major tightly packed. `trim.cpp` reads bytes individually (endianness-independent); `resize.cpp` uses a `uint32_t` view (alignment validated).
- Validate every JNI arg: nulls, `ExceptionCheck` clear, dims 1..16384, direct-buffer capacity >= width*height*4.
- Worker threads only read the direct buffer and their own band results; never touch JNI, need no `JNIEnv`.
- Degenerate results must match the GPU path (`Bounds` seeded min=extent, max=0).
- `trim.cpp` math mirrors the WGSL trim shaders: alpha-multiplied diff, kept in 0..255 units.
- Thread count: skip threads under ~quarter-megapixel; cap 8; bands >= 64 rows.

## ANTI-PATTERNS
- Never call JNI from `std::thread` workers.
- Never read trim pixels through `uint32_t` (channel order breaks on big-endian).
- Never drop capacity / `ExceptionCheck` guards.
- Never add an export without a matching Kotlin `external fun` in `TrimNative.kt` / `ImageUtil.kt`.
- Never change RGBA8 layout or the 16384 cap without touching Kotlin and the WGSL shaders.
- No new deps in `CMakeLists.txt` (`jnigraphics log` only).