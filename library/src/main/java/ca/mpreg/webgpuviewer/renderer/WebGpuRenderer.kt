package ca.mpreg.webgpuviewer.renderer

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.webgpu.BackendType
import androidx.webgpu.BufferUsage
import androidx.webgpu.DeviceLostCallback
import androidx.webgpu.FeatureLevel
import androidx.webgpu.FeatureName
import androidx.webgpu.GPU.createInstance
import androidx.webgpu.GPUAdapter
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUDeviceDescriptor
import androidx.webgpu.GPUExtent3D
import androidx.webgpu.GPUInstance
import androidx.webgpu.GPUInstanceDescriptor
import androidx.webgpu.GPULimits
import androidx.webgpu.GPUOrigin3D
import androidx.webgpu.GPURequestAdapterOptions
import androidx.webgpu.GPUSurface
import androidx.webgpu.GPUSurfaceConfiguration
import androidx.webgpu.GPUSurfaceDescriptor
import androidx.webgpu.GPUSurfaceSourceAndroidNativeWindow
import androidx.webgpu.GPUTexelCopyBufferInfo
import androidx.webgpu.GPUTexelCopyBufferLayout
import androidx.webgpu.GPUTexelCopyTextureInfo
import androidx.webgpu.GPUTexture
import androidx.webgpu.MapMode
import androidx.webgpu.SurfaceGetCurrentTextureStatus
import androidx.webgpu.TextureAspect
import androidx.webgpu.TextureUsage
import androidx.webgpu.UncapturedErrorCallback
import androidx.webgpu.helper.Util.windowFromSurface
import androidx.webgpu.helper.initLibrary
import ca.mpreg.webgpuviewer.filter.FilterChain
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.Companion.mutex
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.Companion.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class FrameResult {
    Drawn,
    Retry,
    Unavailable,
}

class WebGpuRenderer {
    companion object {
        const val MIN_SURFACE_DIMENSION = 8

        const val MAX_SURFACE_DIMENSION = 8192

        /**
         * The surface-teardown pump: see [pumpSurfaceTeardown]. Eight short pumps cover a
         * driver that defers the `vkDestroySurfaceKHR` disconnect by a frame or three; the
         * alternative is the replacement surface failing with
         * `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR` and losing the device built to recover onto.
         */
        private const val SURFACE_TEARDOWN_PUMPS = 8
        private const val SURFACE_TEARDOWN_PUMP_MS = 2L

        /**
         * How long [detachSurface] waits for the frame in flight before giving up and letting the
         * teardown proceed: the common case is a few milliseconds, since the frame only has to
         * run out its last native calls, but a frame wedged past every deadline must not turn the
         * crash this prevents into a hang of the surface callback.
         */
        private const val FRAME_DRAIN_TIMEOUT_MS = 250L

        /**
         * The recovery ladder: see [recoverFromDeviceLoss]. A recovery must have produced a
         * device that stayed alive this long before consecutive-failure counting resets, and
         * this many consecutive failures stop the self-retries until a fresh loss event.
         */
        private const val RECOVERY_STABLE_MS = 15_000L
        private const val RECOVERY_GIVE_UP_ATTEMPTS = 12

        // KMK --> Swapchain-error escalation thresholds. The table itself is
        // [SwapchainRecoveryPolicy] - pure, no Android import, asserted offline by
        // SwapchainRecoveryHarness. The renderer owns only the side effects.
        // KMK <--

        /**
         * Consecutive frames whose draw threw before the frame loop is asked to park. A thrown
         * draw used to fall through to `FrameResult.Drawn` - the submit was skipped, so nothing
         * reached the display, yet the viewer reset its streak and kept treating the session as
         * healthy. A deterministic pipeline failure then presented as an eternal black screen
         * with no park, no retry and no strike. ~0.5 s at 60fps gives a transient allocation
         * failure a few chances before the failure is reported.
         */
        private const val DRAW_FAILURE_GIVE_UP_STREAK = 30
        // KMK <--

        /**
         * The ceiling a *surface* dimension may be configured at: the historical [MAX_SURFACE_DIMENSION]
         * constant narrowed by [maxTextureDimension2D], because the swapchain's canvas reads back as a
         * texture of exactly that size. Before the device exists so does the Compatibility 4096 floor;
         * everywhere else [maxTextureDimension2D] tracks whichever device is live - a fresh adapter can
         * report different limits (see [recreateDeviceLocked]) - so this is read per call, not cached.
         *
         * [init]/[resize] clamp through here: a display whose dimension exceeds what the device can
         * texture must configure at the device's own limit instead of throwing out of `configure`.
         */
        fun surfaceDimensionCap(): Int = minOf(MAX_SURFACE_DIMENSION, maxTextureDimension2D)

        lateinit var instance: GPUInstance
        lateinit var adapter: GPUAdapter
        lateinit var device: GPUDevice

        /**
         * KMK --> One line naming the adapter as the driver reports it. The backend is the fact
         * every field report needs and no current log carries: Dawn on Android may run Vulkan or
         * fall back to OpenGLES, and a shader/pipeline defect can be backend-specific (PowerVR's
         * Vulkan driver is the one with the documented mip/dynamic-rendering bugs; its GLES
         * driver is a different compiler with different failures). Written at init and after
         * every device recreation, read by the app's session header and diagnostics.
         * KMK <--
         */
        @Volatile
        var adapterDescription: String = "unknown"
            private set

        /**
         * KMK --> Whether the tile cache may be used on the live adapter.
         *
         * It used to be false on PowerVR Rogue: the 2026-11-23 dump presented every frame with
         * pages decoded and no image, and the direct `renderWith` path was kept as a
         * device-keyed fallback. That diagnosis was wrong - the black frames were device-bound
         * pipelines and font atlases surviving a device rebuild (see
         * `recreateDeviceLocked`) - and the direct path has two visible costs the tile path does
         * not: it minifies with one bilinear tap and no mip chain, which moires on halftone
         * screentones at the ~0.6 scale a phone reader uses, and it bypasses the tile atlas that
         * page transitions seed their caches from, so transitions do not run.
         *
         * The tile path is the quality path on every adapter again. If it ever fails, the
         * existing fallbacks still apply: `ImageSingle.drawLive` paints the page directly under
         * the tiles and a tile-pipeline exception drops the page to the fast path.
         * KMK <--
         */
        val tileCacheUsable: Boolean
            get() = true

        private fun describeAdapter(adapter: GPUAdapter): String = runCatching {
            val info = adapter.getInfo()
            "vendor=${info.vendor} arch=${info.architecture} device=${info.device} " +
                "desc=${info.description} backend=${BackendType.toString(info.backendType)}"
        }.getOrElse { "info unavailable: ${it::class.simpleName}" }

        /**
         * KMK --> Which Dawn backend the live adapter came from, and which one a rebuild asks
         * for first. Both start [BackendType.Undefined] ("whatever Dawn picks"). The swapchain
         * ladder sets [preferBackend] to OpenGLES after a device rebuild failed to clear a
         * wedged Vulkan swapchain - the GLES backend presents through EGL rather than
         * `VkSwapchainKHR`, so it does not inherit that WSI state. A backend that turns out to
         * be unavailable is dropped back to the default instead of poisoning the renderer.
         *
         * [activeBackend] is what the live adapter reports, not what was asked for: Dawn may
         * fall back to another backend when the preference cannot be honoured, and a recovery
         * that keeps naming the wrong one would mislead the next rung of the ladder.
         * KMK <--
         */
        @Volatile
        private var activeBackend: Int = BackendType.Undefined

        @Volatile
        private var preferBackend: Int = BackendType.Undefined

        /**
         * KMK --> Host hook that runs before [scheduleSwapchainReset] replaces the device.
         *
         * The renderer does not own the host's device-bound caches - the tile atlas, mip chains
         * and filter pool live in [ImageViewerState]/[TileRenderer] - and those hold handles from
         * the device about to be retired. A device-loss recovery has always asked the host to
         * drop them first (`ImageViewerState.recoverFromDeviceLoss`); the swapchain ladder runs
         * from inside the renderer, so it needs the same chance or the next frame paints from a
         * destroyed atlas. Suspending on purpose: the rebuild must not start until the host has
         * actually let go of the old handles.
         * KMK <--
         */
        fun interface DeviceRebuildListener {
            suspend fun onBeforeDeviceRebuild()
        }

        private val deviceRebuildListeners = CopyOnWriteArrayList<DeviceRebuildListener>()

        @JvmStatic
        fun addDeviceRebuildListener(listener: DeviceRebuildListener) {
            deviceRebuildListeners.addIfAbsent(listener)
        }

        @JvmStatic
        fun removeDeviceRebuildListener(listener: DeviceRebuildListener) {
            deviceRebuildListeners.remove(listener)
        }

        private suspend fun notifyBeforeDeviceRebuild() {
            for (listener in deviceRebuildListeners) {
                try {
                    listener.onBeforeDeviceRebuild()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w("WebGpuRenderer", "device-rebuild listener failed", e)
                }
            }
        }

        private fun adapterOptions(backend: Int) = GPURequestAdapterOptions(
            featureLevel = FeatureLevel.Compatibility,
            backendType = backend,
        )

        private suspend fun requestAdapterFor(backend: Int): GPUAdapter {
            return try {
                instance.requestAdapter(adapterOptions(backend))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (backend == BackendType.Undefined) throw e
                // A backend this build of Dawn was compiled without, or one the driver refuses
                // to surface: asking for it must not take the renderer down with it.
                Log.w(
                    "WebGpuRenderer",
                    "adapter on ${BackendType.toString(backend)} unavailable; falling back to the default backend",
                    e,
                )
                preferBackend = BackendType.Undefined
                instance.requestAdapter(adapterOptions(BackendType.Undefined))
            }
        }

        private fun noteActiveBackend(adapter: GPUAdapter) {
            activeBackend = runCatching { adapter.getInfo().backendType }
                .getOrDefault(BackendType.Undefined)
        }

        /**
         * The device's real texture size ceiling - requested at creation, since Compatibility mode
         * otherwise caps it at 4096 whatever the hardware can do.
         */
        var maxTextureDimension2D = 4096
            private set
        private val mutex = Mutex()

        var offsetX: Float = 0f
        var offsetY: Float = 0f

        /** Set if [init]'s adapter/device request throws, so a callsite can fail soft instead. */
        @Volatile
        var initError: Throwable? = null
            private set

        @Volatile
        private var initialized = false

        @Volatile
        private var deviceLost = false

        /**
         * Why the device was lost, plus the last uncaptured error before it. Carried in every
         * later "device lost" message, since the log line from the loss itself is easily pruned.
         */
        @Volatile
        private var deviceLostDetail = ""

        @Volatile
        private var lastUncapturedError: String? = null

        /**
         * Called once, on whichever thread the driver reports it from, when the device is lost.
         * Nothing renders after that for the life of the process, so a host should move to a
         * viewer that does not need WebGPU.
         */
        @Volatile
        var onDeviceLost: (() -> Unit)? = null

        /**
         * Notified on the device-lost callback thread when the device is lost, with the reason -
         * listeners typically call [recoverFromDeviceLoss] to try for a fresh device.
         */
        fun interface DeviceLostListener {
            fun onDeviceLost(reason: Int, message: String)
        }

        private val deviceLostListeners = CopyOnWriteArrayList<DeviceLostListener>()

        fun addDeviceLostListener(listener: DeviceLostListener) {
            deviceLostListeners.add(listener)
        }

        fun removeDeviceLostListener(listener: DeviceLostListener) {
            deviceLostListeners.remove(listener)
        }

        /**
         * Called on the render thread when a recovery produced a live device - including the
         * self-healing retries a loss callback never announces (the loss event fires once, on
         * the loss itself; the retries that later succeed are silent). A host rebuilds
         * everything device-bound here: a page decoded before the loss was reset by the loss
         * handling, but a page that failed to decode *while the device was dead* carries an
         * ErrorPage and a latched `wantedByRender` that only a fresh reset clears.
         */
        fun interface DeviceRecoveredListener {
            fun onDeviceRecovered()
        }

        private val deviceRecoveredListeners = CopyOnWriteArrayList<DeviceRecoveredListener>()

        fun addDeviceRecoveredListener(listener: DeviceRecoveredListener) {
            deviceRecoveredListeners.add(listener)
        }

        fun removeDeviceRecoveredListener(listener: DeviceRecoveredListener) {
            deviceRecoveredListeners.remove(listener)
        }

        private fun notifyDeviceRecovered() {
            for (listener in deviceRecoveredListeners) {
                try {
                    listener.onDeviceRecovered()
                } catch (e: Throwable) {
                    Log.w("WebGpuRenderer", "device-recovered listener failed", e)
                }
            }
        }

        private fun notifyDeviceLost(reason: Int, message: String) {
            for (listener in deviceLostListeners) {
                try {
                    listener.onDeviceLost(reason, message)
                } catch (e: Throwable) {
                    Log.w("WebGpuRenderer", "device-lost listener failed", e)
                }
            }
        }

        fun isOnRenderThread() = Thread.currentThread().name == "WebGPU-Render-Thread"

        /** The companion's device object, or null before first init - teardown paths use this. */
        fun deviceOrNull(): GPUDevice? = if (::device.isInitialized) device else null

        /** The companion's adapter object, or null before first init - teardown paths use this. */
        fun adapterOrNull(): GPUAdapter? = if (::adapter.isInitialized) adapter else null

        val isAvailable: Boolean
            get() = initError == null && !deviceLost &&
                    ::instance.isInitialized && ::adapter.isInitialized && ::device.isInitialized

        val unavailableReason: String?
            get() = when {
                initError != null -> "WebGPU failed to initialize: ${initError?.message}"
                deviceLost -> "WebGPU device lost$deviceLostDetail"
                !::instance.isInitialized || !::adapter.isInitialized ||
                        !::device.isInitialized -> "WebGPU never initialized"

                else -> null
            }

        fun requireAvailable() {
            check(isAvailable) {
                "WebGPU not available" + (initError?.let { ": ${it.message}" }
                    ?: if (deviceLost) ": device lost$deviceLostDetail" else "")
            }
        }

        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "WebGPU-Render-Thread")
        }.asCoroutineDispatcher()

        // Frame time profiling
        var profilingEnabled = false

        /**
         * Bumped on every successful device creation after the first: filters and other
         * device-bound caches compare against it to detect a recreation and rebuild
         * (see [ca.mpreg.webgpuviewer.filter.Filter.syncDevice]). Starts 0, so instances built
         * before any loss never spuriously reset.
         */
        var deviceGeneration = 0L
            private set

        /**
         * KMK --> Process-wide count of uncaptured WebGPU errors. Dawn validation rejects a
         * command buffer wholesale; the submit itself still returns normally, so without this
         * counter a stale-device cache reads as a healthy session that presents black frames
         * (2026-11-23 Mali-G76 dump). [render] compares it around the submit and reports the
         * frame as failed instead of drawn.
         * KMK <--
         */
        @Volatile
        var validationErrors = 0L
            private set

        /** Guards every field below - written on the render thread, read from wherever polls them. */
        private val profilingLock = Any()
        private var frameCount = 0L
        private var totalFrameTimeNs = 0L
        private var minFrameTimeNs = Long.MAX_VALUE
        private var maxFrameTimeNs = 0L
        private var lastFrameTimeNs = 0L
        private val recentFrameTimes = LongArray(60)
        private var recentFrameIndex = 0

        val lastFrameTimeMs: Float get() = synchronized(profilingLock) { lastFrameTimeNs / 1_000_000f }
        val avgFrameTimeMs: Float
            get() = synchronized(profilingLock) {
                if (frameCount > 0) totalFrameTimeNs / frameCount / 1_000_000f else 0f
            }
        val minFrameTimeMs: Float
            get() = synchronized(profilingLock) {
                if (minFrameTimeNs == Long.MAX_VALUE) 0f else minFrameTimeNs / 1_000_000f
            }
        val maxFrameTimeMs: Float get() = synchronized(profilingLock) { maxFrameTimeNs / 1_000_000f }
        val recentAvgFrameTimeMs: Float
            get() = synchronized(profilingLock) {
                val count = minOf(frameCount.toInt(), 60)
                if (count == 0) return@synchronized 0f
                var sum = 0L
                for (i in 0 until count) {
                    sum += recentFrameTimes[i]
                }
                sum.toFloat() / count / 1_000_000f
            }
        val estimatedFps: Float
            get() = synchronized(profilingLock) {
                if (lastFrameTimeNs > 0) 1_000_000_000f / lastFrameTimeNs else 0f
            }

        fun resetProfiling() = synchronized(profilingLock) {
            frameCount = 0
            totalFrameTimeNs = 0
            minFrameTimeNs = Long.MAX_VALUE
            maxFrameTimeNs = 0
            lastFrameTimeNs = 0
            recentFrameIndex = 0
            recentFrameTimes.fill(0)
        }

        private fun hasFeature(adapter: GPUAdapter, feature: Int): Boolean =
            runCatching { adapter.hasFeature(feature) }.getOrDefault(false)

        private fun deviceDescriptor(includeCore: Boolean = true) = GPUDeviceDescriptor(
            deviceLostCallback = DeviceLostCallback { lost, reason, message ->
                // A retired device's still-pending loss record can dispatch long after recovery
                // parked it (see [destroyRetired] for why its wrapper must stay alive for that).
                // Firing the ladder for a device the companion no longer names would churn
                // generations: every such dispatch would start another recovery. Ignore them.
                if (lost !== deviceOrNull()) {
                    Log.w(
                        "WebGpuRenderer",
                        "stale device-lost callback for a retired device (reason=$reason: $message)"
                    )
                    return@DeviceLostCallback
                }
                val first = !deviceLost
                if (first) {
                    deviceLostDetail = " (reason=$reason: $message" +
                            (lastUncapturedError?.let { "; last error: $it" } ?: "") + ")"
                }
                deviceLost = true
                Log.e(
                    "WebGpuRenderer",
                    "WebGPU device lost reason=$reason: $message device=$lost"
                )
                notifyDeviceLost(reason, message)
            },
            deviceLostCallbackExecutor = Executor(Runnable::run),
            uncapturedErrorCallback = UncapturedErrorCallback { _, type, message ->
                validationErrors++
                lastUncapturedError = "type=$type: $message"
                Log.e(
                    "WebGpuRenderer",
                    "Uncaptured WebGPU error type=$type: $message"
                )
            },
            uncapturedErrorCallbackExecutor = Executor(Runnable::run),
            requiredFeatures = requiredFeaturesFor(adapter, includeCore),
            // Requested at creation, since Compatibility mode otherwise caps it at 4096
            // whatever the hardware can do.
            requiredLimits = GPULimits(
                maxTextureDimension2D = adapter.getLimits().maxTextureDimension2D,
            ),
        )

        private fun requiredFeaturesFor(adapter: GPUAdapter, includeCore: Boolean): IntArray {
            val features = mutableListOf<Int>()
            // Both field-reported adapters (Adreno 6xx, Mali bifrost) expose
            // core-features-and-limits. Requesting it upgrades the device from Compatibility
            // mode to core WebGPU, lifting the compatibility-mode restrictions that are
            // otherwise silently in effect (128-invocation compute workgroups, 16384-byte
            // uniform buffers, 15 inter-stage variables, storage buffers barred from the
            // vertex stage, storage textures barred from it too, 16 sampler combinations
            // per stage). Without this a pipeline or filter that exceeds any of those
            // fails validation with no exception on the draw path - a frame that draws
            // nothing, i.e. a black screen. Devices without the feature keep the
            // compatibility behaviour unchanged.
            if (includeCore && hasFeature(adapter, FeatureName.CoreFeaturesAndLimits)) {
                features += FeatureName.CoreFeaturesAndLimits
            }
            if (hasFeature(adapter, FeatureName.TimestampQuery)) {
                features += FeatureName.TimestampQuery
            }
            return features.toIntArray()
        }

        /**
         * Request the device from [adapter], upgrading it to core WebGPU when the adapter
         * offers `core-features-and-limits` (see [requiredFeaturesFor]). A driver that lists
         * the feature but then rejects the request loses nothing but the upgrade: the retry
         * asks for the plain compatibility device instead of latching [initError] for the
         * life of the process.
         */
        private suspend fun requestDeviceBestEffort(): GPUDevice =
            try {
                adapter.requestDevice(deviceDescriptor(includeCore = true))
            } catch (e: Throwable) {
                if (!hasFeature(adapter, FeatureName.CoreFeaturesAndLimits)) throw e
                Log.w(
                    "WebGpuRenderer",
                    "requestDevice with core-features-and-limits failed; retrying compatibility-only",
                    e
                )
                adapter.requestDevice(deviceDescriptor(includeCore = false))
            }

        internal fun recordFrameTime(timeNs: Long) {
            if (!profilingEnabled) return
            synchronized(profilingLock) {
                frameCount++
                totalFrameTimeNs += timeNs
                lastFrameTimeNs = timeNs
                if (timeNs < minFrameTimeNs) minFrameTimeNs = timeNs
                if (timeNs > maxFrameTimeNs) maxFrameTimeNs = timeNs
                recentFrameTimes[recentFrameIndex] = timeNs
                recentFrameIndex = (recentFrameIndex + 1) % 60
            }
        }

        /**
         * The soname Dawn's JNI entry points live in. `GPU.createInstance` and every other
         * `Java_androidx_webgpu_*` symbol are bound to class/method names, so the library has to
         * be resident in this process *before* the first native call.
         */
        private const val WEBGPU_NATIVE_LIBRARY = "webgpu_c_bundled"

        /**
         * Load the native library directly first, then through [initLibrary], then by reflection.
         *
         * Direct first is deliberate: the helper is a static method in an AAR class, and a minified
         * consumer that loses that call leaves `createInstance` throwing "No implementation found"
         * even though the `.so` is packaged. `System.loadLibrary` is idempotent, so doing it here
         * costs nothing when the helper already loaded the same library. The other two paths stay
         * as fallbacks for packaging that names the helper class differently.
         */
        private fun safeInitLibrary() {
            try {
                System.loadLibrary(WEBGPU_NATIVE_LIBRARY)
                return
            } catch (e: Throwable) {
                Log.w("WebGpuRenderer", "Direct load of $WEBGPU_NATIVE_LIBRARY failed, trying helper", e)
            }
            try {
                initLibrary()
                return
            } catch (e: Throwable) {
                Log.w("WebGpuRenderer", "Util.initLibrary failed, trying WebGpuUtils fallback", e)
            }
            try {
                val cls = Class.forName("androidx.webgpu.helper.WebGpuUtils")
                val m = cls.getMethod("initLibrary")
                m.invoke(null)
            } catch (e: Throwable) {
                Log.e("WebGpuRenderer", "Fallback WebGpuUtils.initLibrary also failed", e)
                throw e
            }
        }

        /**
         * [createInstance] is a native method. If the library is not resident when it is first
         * called, ART throws `UnsatisfiedLinkError: No implementation found` and the companion
         * latches that as a permanent `initError` - the renderer is then "unavailable" for the
         * rest of the process even though the `.so` is packaged. Load once and retry once; a
         * second failure is a real load/driver problem and falls through to the caller's catch.
         */
        private fun createInstanceWithLibrary(): GPUInstance =
            try {
                createInstance(GPUInstanceDescriptor())
            } catch (e: UnsatisfiedLinkError) {
                Log.w(
                    "WebGpuRenderer",
                    "GPU.createInstance had no native implementation; loading $WEBGPU_NATIVE_LIBRARY and retrying",
                    e,
                )
                System.loadLibrary(WEBGPU_NATIVE_LIBRARY)
                createInstance(GPUInstanceDescriptor())
            }

        private fun safeWindowFromSurface(surface: Surface): Long {
            try {
                return windowFromSurface(surface)
            } catch (e: Throwable) {
                Log.w("WebGpuRenderer", "Util.windowFromSurface failed, trying WebGpuUtils", e)
                try {
                    val cls = Class.forName("androidx.webgpu.helper.WebGpuUtils")
                    val m = cls.getMethod("windowFromSurface", Surface::class.java)
                    return m.invoke(null, surface) as Long
                } catch (e2: Throwable) {
                    Log.e("WebGpuRenderer", "Fallback windowFromSurface also failed", e2)
                    throw e2
                }
            }
        }

        init {
            runBlocking {
                try {
                    safeInitLibrary()

                    instance = createInstanceWithLibrary()

                    adapter = requestAdapterFor(BackendType.Undefined)
                    noteActiveBackend(adapter)
                    adapterDescription = describeAdapter(adapter)

                    val adapterLimits = adapter.getLimits()

                    device = requestDeviceBestEffort()
                    initialized = true
                    deviceLost = false
                    maxTextureDimension2D = device.getLimits().maxTextureDimension2D
                    val core = runCatching {
                        device.getFeatures().features.contains(FeatureName.CoreFeaturesAndLimits)
                    }.getOrDefault(false)
                    Log.i(
                        "WebGpuRenderer",
                        "adapter $adapterDescription; maxTextureDimension2D adapter=${adapterLimits.maxTextureDimension2D} device=$maxTextureDimension2D core=$core"
                    )
                } catch (e: Throwable) {
                    // Fails soft via initError - a driver init failure shouldn't poison the whole class.
                    Log.e("WebGpuRenderer", "Failed to initialize WebGPU", e)
                    initError = e
                }
            }
        }

        @JvmStatic
        suspend fun <R> withContext(block: suspend CoroutineScope.(GPUDevice) -> R): R {
            requireAvailable()
            return withContext(dispatcher) {
                mutex.withLock {
                    block(this, device)
                }
            }
        }

        /**
         * Run [block] on the GPU thread *without* taking the render mutex.
         *
         * For long resource work that yields as it goes. [withContext] would defeat that: a
         * [render] call woken by the yield would just block on the mutex and hand the thread
         * straight back, so the work would still run to completion before the next frame. Without
         * the mutex the yield actually lets a frame through.
         *
         * Only safe for work that either owns its resources outright (an image still being built
         * and not yet reachable from a page) or that cannot be observed mid-flight. Anything that
         * has to appear atomically to the renderer belongs in [withContext].
         */
        @JvmStatic
        suspend fun <R> onDispatcher(block: suspend CoroutineScope.(GPUDevice) -> R): R {
            requireAvailable()
            return withContext(dispatcher) {
                block(this, device)
            }
        }
    }

    @Volatile
    private var surface: GPUSurface? = null

    /** The window reference [windowFromSurface] took for [surface], released in [cleanup]; see [NativeWindow]. */
    private var window = 0L

    /**
     * The format the swapchain is currently configured as, so [render] can notice when
     * [Hdr.frameFormat] has moved and rebuild it.
     */
    private var configuredFormat: Int = 0

    /**
     * Post-processing over the finished frame - see [FilterChain]. Empty by default, in which
     * case [render] hands the swapchain texture straight to its caller as it always did.
     */
    val filters = FilterChain()

    var width: Int = 0
    var height: Int = 0

    /**
     * KMK --> The instance facts a field report needs and no existing line carried: the canvas it
     * is presenting at, the format it configured versus the one frames are drawn in, the device
     * generation and the surface's advertised formats. Read by the app's session header and the
     * close-out diagnostics; no rendering effect.
     * KMK <--
     */
    val debugSummary: String
        get() = "surface=${width}x$height configured=$configuredFormat frame=${Hdr.frameFormat} " +
            "gen=$deviceGeneration maxTex=$maxTextureDimension2D " +
            "errs=$validationErrors ${Hdr.surfaceSummary()}"

    private var scope: CoroutineScope? = null

    private var pendingSurface: Surface? = null

    /**
     * Retained so [recoverFromDeviceLoss] can rebuild the swapchain without waiting for the app
     * to hand over a new surface (no new surface callback fires on device loss).
     */
    private var platformSurface: Surface? = null

    /**
     * The device and adapter retired by the previous recovery, freed at the start of the next
     * one - see [destroyRetired] in [recoverFromDeviceLoss] for why the wait, and why the free.
     */
    private var retiredDevice: GPUDevice? = null
    private var retiredAdapter: GPUAdapter? = null

    /** Consecutive failed recoveries - the escalation dial of the recovery ladder. */
    @Volatile
    private var recoveryFailures = 0

    /**
     * Uptime before which [recoverFromDeviceLoss] fast-fails without building a device; each
     * failure pushes it out, up to the ladder's ceiling - see [recoveryBackoffMs].
     */
    @Volatile
    private var recoveryCooldownUntil = 0L

    /**
     * When the last recovery that produced a device happened - a device that survives
     * [RECOVERY_STABLE_MS] resets the failure dial in [noteRecoverySuccess].
     */
    @Volatile
    private var lastRecoverySuccessAt = 0L

    /** Guards [scheduleRecoveryRetry]: one self-healing retry in flight at a time. */
    private val recoveryRetryScheduled = AtomicBoolean(false)

    @Volatile
    private var resizePending = false

    /**
     * Set by [detachSurface] when the platform surface is going away. The device is fine and the
     * [GPUSurface] still exists, but the framework dismantles the window - its BufferQueue and
     * every gralloc buffer behind the swapchain images - as soon as the surfaceDestroyed
     * callbacks return, so a frame that starts or resumes into this flag drops instead of
     * driving a window that is being torn out from under it.
     */
    @Volatile
    private var surfaceDetached = false

    /**
     * KMK --> Consecutive [androidx.webgpu.SurfaceGetCurrentTextureStatus.Error] frames on the
     * live surface. PowerVR field evidence (Multilaser M8_4G, Android 11): the swapchain can be
     * born wedged - `getCurrentTexture` returns `Error` on every frame from the first one, while
     * `configure` keeps succeeding - and [reconfigure] can never clear it, because configure only
     * updates the descriptor on a surface whose WSI object is already broken. Counted here so the
     * error path in [render] can escalate instead of retrying forever. Render-thread only.
     *
     * The escalation the field log asked for: ~0.5s of consecutive Errors tears the WebGPU surface
     * down and builds a fresh one from the same window (the one reset that gives the driver a new
     * swapchain); the worker's live tail saw 10s of Error spam and a black screen otherwise.
     * KMK <--
     */
    private var surfaceErrorStreak = 0

    /**
     * KMK --> Consecutive frames whose draw threw - see [DRAW_FAILURE_GIVE_UP_STREAK]. A frame
     * that draws resets it; it is not the swapchain streak, because the surface is fine and
     * recreating it would not help a pipeline or validation failure.
     * KMK <--
     */
    private var drawFailureStreak = 0

    /**
     * KMK --> How many times this surface has *parked*: hit [SwapchainRecoveryPolicy.GIVE_UP_AT]
     * consecutive swapchain `Error`s and gone [FrameResult.Unavailable] until something else
     * invalidates - the PowerVR black-screen shape where `isAvailable` stays true the whole
     * time, so the device-loss path never fires. Written on the render thread only; hosts
     * snapshot it at viewer construction and again at session end and diff the two reads to
     * detect a session that spent its life parked. Monotonic per renderer instance, never
     * reset - the caller's own snapshot scopes it to one session. Volatile: read from host
     * threads.
     * KMK <--
     */
    @Volatile
    var surfaceParkCount = 0
        private set

    /**
     * KMK --> How many frames actually reached `present()`: encoded, submitted to the queue and
     * handed to the surface. Monotonic per renderer instance; hosts snapshot it at viewer
     * construction and again at session end and diff the two reads, exactly like
     * [surfaceParkCount].
     *
     * This is the only counter that can tell "the display was never written" from "the display
     * was written and the page was invisible", and no earlier field log could distinguish them:
     * the app's `anyPageDrawn` is set by the render *walk* before the frame is submitted, so a
     * session that never presented a single frame still reported `drawn=true`. A black screen
     * with `frames=0` is a renderer/surface defect; the same screen with `frames>0` is a draw or
     * compositing defect. Render thread only; volatile because hosts read it.
     * KMK <--
     */
    @Volatile
    var presentedFrames = 0L
        private set

    /**
     * KMK --> Whether a [scheduleSurfaceRecreate] pass is queued or running, so [render] treats a
     * momentarily absent surface as Retry (the recreate finishes within a few frames) instead of
     * reporting an outage for a reset it asked for itself.
     * KMK <--
     */
    private val surfaceRecreateScheduled = AtomicBoolean(false)

    /**
     * KMK --> Whether a [scheduleSwapchainReset] pass is queued or running. That pass rebuilds
     * the device (and, on its second rung, the adapter's backend), which takes the surface away
     * for longer than a swapchain recreate; [render] must answer Retry for the same reason it
     * does while [surfaceRecreateScheduled] is set, or the viewer sees a one-off outage and
     * parks its loop before the recovery it asked for can finish.
     * KMK <--
     */
    private val swapchainResetScheduled = AtomicBoolean(false)

    @Synchronized
    fun init(scope: CoroutineScope, surface: Surface, width: Int, height: Int) {
        if (!isAvailable) {
            Log.w("WebGpuRenderer", "init called but WebGPU not available: $unavailableReason")
            return
        }
        this.scope = scope
        this.pendingSurface = surface
        this.platformSurface = surface
        // A surface the framework destroyed between its create callback and this call (the
        // surface block may have suspended before init ran) must not clear the detach that its
        // own destroy callback set.
        if (surface.isValid) surfaceDetached = false
        // KMK --> A fresh attach is a fresh swapchain: whatever the previous surface was doing,
        // its error streak says nothing about this one.
        // KMK <--
        surfaceErrorStreak = 0
        // KMK --> A surface can be created before its view is laid out, or mid-teardown, and
        // report 0x0 (field: e-ink readers, which tear the surface down on refresh-mode
        // switches). Storing those zeroes is what latches the degenerate 0.01 home scale into
        // every page attached while they stand - the page then draws at a few dozen pixels
        // until it is re-decoded. Keep the previous dimensions instead; a real resize callback
        // follows and moves them. KMK <--
        if (width >= MIN_SURFACE_DIMENSION && height >= MIN_SURFACE_DIMENSION) {
            this.width = width.coerceAtMost(surfaceDimensionCap())
            this.height = height.coerceAtMost(surfaceDimensionCap())
        } else {
            Log.w("WebGpuRenderer", "init called with degenerate size ${width}x$height - keeping ${this.width}x${this.height}")
        }
        createSurface()
    }

    @Synchronized
    fun resize(width: Int, height: Int) {
        if (!isAvailable || surfaceDetached) return
        // KMK --> Same degenerate-size rule as init: a relayout can report 0x0, and storing it
        // zeroes the dims every home-scale computation reads. KMK <--
        if (width < MIN_SURFACE_DIMENSION || height < MIN_SURFACE_DIMENSION) return
        val w = width.coerceAtMost(surfaceDimensionCap())
        val h = height.coerceAtMost(surfaceDimensionCap())
        if (w == this.width && h == this.height && surface != null) return
        this.width = w
        this.height = h

        if (surface == null) {
            createSurface()
        } else {
            // Not here: [render] holds [mutex] across a suspending draw, so blocking on it from
            // this thread deadlocks against the frame that owns it.
            resizePending = true
        }
    }

    /**
     * The platform surface is going away: stop driving it and drain the frame in flight.
     *
     * Called from the surface-destroyed callback, on the main thread. That callback is the
     * framework's last wait before teardown: a SurfaceView's window - its BufferQueue and the
     * gralloc buffers behind the swapchain images - is dismantled only once the surfaceDestroyed
     * callbacks return. Compose cancels the render job there but does not wait for it, and a
     * cancellation only lands at a suspension point, so a frame that is already past its last
     * suspension (the fast tile path suspends only while generating) would run its whole
     * [render] tail - submit and present - against a window whose buffers are being freed
     * underneath it. That is the rotation crash: a barrage of gralloc allocation failures as the
     * BufferQueue goes down, then an abort inside `GPUQueue_submit`. Waiting here keeps the
     * teardown behind the last frame, and the [surfaceDetached] gate makes every frame that
     * starts or resumes after this drop instead of touching the dying window.
     */
    fun detachSurface() {
        surfaceDetached = true
        if (isOnRenderThread()) return
        try {
            runBlocking {
                withTimeoutOrNull(FRAME_DRAIN_TIMEOUT_MS) {
                    mutex.withLock { }
                }
            }
        } catch (_: Throwable) {
            // A drain that cannot run must not take the surface callback (and with it the
            // framework's teardown wait) down with it.
        }
    }

    /**
     * KMK --> Tear the WebGPU surface down and build a fresh one from the same window: the one
     * reset that can clear a swapchain wedged in Dawn's error state, where [reconfigure] only
     * rewrites the descriptor on a surface whose WSI object is already broken. Scheduled from
     * [render]'s error-streak path rather than run inline, because the teardown pump suspends and
     * the error path is inside the render mutex. Runs on the render dispatcher under the render
     * mutex - the same discipline [recoverFromDeviceLoss]'s surface half follows - and no-ops
     * when the surface detached (its own destroy callback and the next [init] own that case).
     * KMK <--
     */
    private fun scheduleSurfaceRecreate() {
        if (!surfaceRecreateScheduled.compareAndSet(false, true)) return
        CoroutineScope(dispatcher).launch {
            try {
                mutex.withLock {
                    if (surfaceDetached || deviceLost) return@withLock
                    val previous = surface
                    surface = null
                    configuredFormat = 0
                    try {
                        previous?.close()
                    } catch (_: Throwable) {
                    }
                    // The same leak discipline as the recovery path: the window reference is
                    // ours, so ours has to go before createSurface() takes a fresh one.
                    if (window != 0L) {
                        NativeWindow.release(window)
                        window = 0L
                    }
                    pumpSurfaceTeardown()
                    createSurface()
                    Log.i("WebGpuRenderer", "surface recreated after a swapchain error streak")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // KMK --> A surface that cannot be rebuilt at all is one rung past the swapchain:
                // escalate to the device rebuild instead of leaving `surface` null, which would
                // answer Unavailable on every frame and park the viewer before the ladder ran.
                // KMK <--
                Log.e("WebGpuRenderer", "surface recreation failed", e)
                scheduleSwapchainReset(switchBackend = false)
            } finally {
                surfaceRecreateScheduled.set(false)
            }
        }
    }

    /**
     * KMK --> When the surface rebuild was not enough: rebuild the whole GPU device, and on the
     * next rung ask for Dawn's OpenGLES backend.
     *
     * Field evidence (2026-11-22, Multilaser M8_4G / PowerVR GE8322): the second reader session
     * in a process was born with `getCurrentTexture` returning `Error` on every frame while
     * `configure` succeeded, and a fresh WebGPU surface on the same device reproduced it. That
     * is one level above the swapchain: Dawn's Vulkan WSI object is wedged, and only a new
     * device - or a genuinely different WSI implementation - can clear it. The first forced pass
     * also replaces the Dawn instance (`freshInstance = true`): a new VkInstance is the closest
     * reset to the process restart that always cleared the field case, and the previous instance
     * is left open rather than closed under retired wrappers. The GLES switch is the second rung
     * because its presentation path is EGL, not `vkCreateAndroidSurfaceKHR`/`VkSwapchainKHR`, so
     * it cannot inherit the wedged object.
     *
     * Runs on the render dispatcher and suspends inside the teardown pump, so it must not be
     * called inline from [render] (which holds [mutex]). [swapchainResetScheduled] keeps the
     * frame loop in Retry while the surface is away, exactly like [surfaceRecreateScheduled].
     * KMK <--
     */
    private fun scheduleSwapchainReset(switchBackend: Boolean) {
        if (!swapchainResetScheduled.compareAndSet(false, true)) return
        if (switchBackend) preferBackend = BackendType.OpenGLES
        CoroutineScope(dispatcher).launch {
            try {
                // The host owns the tile atlas and other device-bound caches; it must drop them
                // before the device they were allocated on is retired (see the listener's note).
                notifyBeforeDeviceRebuild()
                // KMK --> The device rung also replaces the Dawn instance: the field wedge lives
                // in the WSI, and a new VkInstance is the closest thing to the process restart
                // that always cleared it. The backend rung reuses that fresh instance and moves
                // the adapter to GLES.
                // KMK <--
                val ok = recoverFromDeviceLoss(force = true, freshInstance = !switchBackend)
                if (ok) {
                    // KMK --> Do NOT reset surfaceErrorStreak here. `ok` means a device and a
                    // surface were created; it does not mean a frame reached the display. The
                    // field case this ladder exists for (PowerVR) rebuilds cleanly and still gets
                    // `getCurrentTexture` Error on the first frame, so resetting here restarted
                    // the count at 0 and the loop never reached SWITCH_BACKEND_AT - it just kept
                    // re-running the 30/60 rungs. Only a Drawn frame resets the streak (render()).
                    // KMK <--
                    Log.i(
                        "WebGpuRenderer",
                        if (switchBackend) {
                            "swapchain recovered after switching to the ${BackendType.toString(activeBackend)} backend"
                        } else {
                            "swapchain recovered after a device rebuild"
                        },
                    )
                } else {
                    Log.e(
                        "WebGpuRenderer",
                        if (switchBackend) {
                            "swapchain still wedged after the backend switch"
                        } else {
                            "swapchain still wedged after a device rebuild"
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("WebGpuRenderer", "swapchain reset failed", e)
            } finally {
                swapchainResetScheduled.set(false)
            }
        }
    }

    /**
     * Full per-instance recovery after a device loss: recreates the shared device, drops this
     * instance's device-bound caches (filter pool, stale swapchain) and rebuilds the swapchain
     * from the retained platform surface. True when the device is usable afterwards; a missing
     * surface waits for the next surface callback.
     *
     * KMK --> [force] is the swapchain ladder's entry point. The device is alive there - only its
     * swapchain is wedged - so [recreateDeviceLocked]'s "already available" short-circuit would
     * skip the rebuild. Forcing it also re-requests the adapter, which is what lets
     * [scheduleSwapchainReset] move the session onto the GLES backend ([preferBackend]).
     * A forced call ignores the failure cooldown: it is not a loss-driven retry, it is the one
     * escalation that has not been tried for this surface yet.
     * KMK <--
     *
     * Failures are laddered, not retried at the caller's pace. A recovery that fails at surface
     * creation has lost the device it just built, whose own loss callback starts the next
     * recovery - unthrottled, that loop recreated a device every second for the rest of the
     * session (90 generations in the field log, one whole leaked device each). Early failures
     * run at the caller's own pace; each further one buys a longer cooldown; enough consecutive
     * failures stop the self-retries until a fresh loss event. A recovery only resets the dial
     * once its device has stayed alive [RECOVERY_STABLE_MS] - a device that dies again
     * immediately belongs to the same pressure era, not to a new one.
     */
    suspend fun recoverFromDeviceLoss(
        force: Boolean = false,
        freshInstance: Boolean = false,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        if (!force && now < recoveryCooldownUntil) {
            Log.i(
                "WebGpuRenderer",
                "recovery deferred ${(recoveryCooldownUntil - now) / 1000.0}s (consecutive failures: $recoveryFailures)"
            )
            return false
        }
        // A forced reset racing the surface teardown has no window to build on; the next init()
        // owns that case, and reporting failure here would count against the recovery ladder.
        if (force && surfaceDetached) return true
        return try {
            withContext(dispatcher) {
                mutex.withLock {
                    // The device retired by the previous recovery is a full recovery cycle old
                    // by now: continuations that were mid-flight when it died read `device`
                    // from the companion when they resume (see [onDispatcher]), so nothing
                    // references the retired handles any more and this is the safe point to
                    // free them. Reassigning instead leaked a whole Dawn device per recovery -
                    // the wrapper has no finalizer (see Release.kt) - and the loss loop was
                    // compounding the very memory pressure that had started it.
                    destroyRetired()
                    val deadDevice = deviceOrNull()
                    val deadAdapter = adapterOrNull()
                    val backendBefore = activeBackend

                    // Drop the swapchain and the native window BEFORE the new device exists.
                    // Dawn can defer vkDestroySurfaceKHR past close(), and a second createSurface
                    // on the same window while the old swapchain is still connected to it fails
                    // with VK_ERROR_NATIVE_WINDOW_IN_USE_KHR - which loses the device again.
                    val previous = surface
                    surface = null
                    try {
                        previous?.close()
                    } catch (_: Throwable) {
                    }
                    // cleanup() releases this after closing the surface, for the same reason: the
                    // window reference is ours, so ours has to be the one that goes.
                    if (window != 0L) {
                        NativeWindow.release(window)
                        window = 0L
                    }
                    // Releasing our references does not disconnect the window - the deferred
                    // vkDestroySurfaceKHR does, and it runs only when the instance's event
                    // loop is pumped. Nothing here had pumped it, so the old surface was
                    // still connected when createSurface() built the replacement: the fresh
                    // device was lost at birth and its loss callback started the next
                    // recovery. Give the deferred work wall-clock time to run.
                    pumpSurfaceTeardown()

                    if (!recreateDeviceLocked(force = force, freshInstance = freshInstance)) {
                        noteRecoveryFailure()
                        return@withLock false
                    }
                    // A forced rebuild may have changed the WSI implementation (Vulkan -> GLES).
                    // Its surface capabilities are a different list, and the old answer would
                    // make the renderer configure a float or RGBA swapchain this backend cannot
                    // present. Safe under the render mutex: no frame is between a latch and an
                    // allocation here.
                    if (activeBackend != backendBefore || freshInstance) {
                        Hdr.resetSurfaceSupportForBackendChange()
                        Log.i(
                            "WebGpuRenderer",
                            "backend changed ${BackendType.toString(backendBefore)} -> " +
                                "${BackendType.toString(activeBackend)}" +
                                if (freshInstance) " (fresh instance); surface formats reset" else "; surface formats reset",
                        )
                    }
                    // The companion no longer names the dead device, so nothing drives it
                    // after this lock ends; free it one recovery cycle from now (above).
                    retiredDevice = deadDevice
                    retiredAdapter = deadAdapter
                    try {
                        filters.cleanup()
                    } catch (e: Throwable) {
                        Log.w("WebGpuRenderer", "filter cleanup during recovery failed", e)
                    }
                    val platform = platformSurface
                    if (platform == null || !platform.isValid || surfaceDetached) {
                        platformSurface = null
                        Log.w("WebGpuRenderer", "recovery has no live platform surface; next init() rebuilds it")
                        noteRecoverySuccess()
                        return@withLock true
                    }
                    createSurface()
                    // A failed surface build loses the device it was created on - synchronously,
                    // on the loss-callback executor - so a fresh `deviceLost` here means this
                    // recovery never produced a usable device. Reporting success would have
                    // reset the ladder and hidden the loop for the rest of the session.
                    if (deviceLost) {
                        noteRecoveryFailure()
                        return@withLock false
                    }
                    noteRecoverySuccess()
                    true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("WebGpuRenderer", "recoverFromDeviceLoss failed", e)
            noteRecoveryFailure()
            false
        }
    }

    /**
     * Gives Dawn's deferred surface destruction wall-clock time to run: [GPUSurface.close] only
     * drops our reference, and the `vkDestroySurfaceKHR` that actually disconnects the window is
     * dispatched onto the instance's event loop. Called wherever a surface has just been closed
     * and a new one may be built on the same window; a failed build loses the device it was
     * created on, so this pump is what stands between recovery and the loop it used to feed.
     */
    private suspend fun pumpSurfaceTeardown() {
        repeat(SURFACE_TEARDOWN_PUMPS) {
            instance.processEvents()
            delay(SURFACE_TEARDOWN_PUMP_MS)
        }
    }

    /**
     * Frees the device and adapter retired by the previous recovery. Holds [mutex]; runs on the
     * render thread.
     *
     * The device gets [GPUDevice.destroy] and **never [GPUDevice.close]**. `close()` is the only
     * thing in the whole pipeline that deletes the global refs the androidx.webgpu glue holds for
     * a wrapper, and the glue constructs its callback runnables *at dispatch time* from those
     * refs: `DeviceLostCallbackRunnable` / `UncapturedErrorCallbackRunnable` are built with
     * `(callback, GPUDevice, …)` when Dawn flushes a pending record - which can be long after we
     * parked the device, because a loss record queued but never dispatched (nothing drives a
     * parked device) rides until some later command's processing flushes it. `close()` on a
     * retired device deleted those globals out from under a still-pending record, and the next
     * frame's `finish()` - which pumps the callback queue - aborted the process inside
     * `NewObjectV` with ART's `JNI ERROR (app bug): accessed deleted Global`. Field evidence:
     * Multilaser M8_4G, Android 11, double device loss in one pressure era (tombstone:
     * `finish$default` → `submitAndRelease` → `NewObjectV`). So `destroy()` frees the GPU memory
     * - the part that fed the loss loop - while the wrapper's global stays alive and any late
     * record dispatches harmlessly (the stale-fire guard in [deviceDescriptor] keeps it from
     * reaching the recovery ladder). The residue is the destroyed device's shell plus one Java
     * wrapper per recovery: kilobytes, and recoveries are rare.
     */
    private fun destroyRetired() {
        retiredDevice?.let { d ->
            try {
                d.destroy()
            } catch (_: Throwable) {
            }
        }
        retiredDevice = null
        retiredAdapter?.let { a ->
            try {
                a.close()
            } catch (_: Throwable) {
            }
        }
        retiredAdapter = null
    }

    /** Cooldown bought by [failures] consecutive failed recoveries - see [recoverFromDeviceLoss]. */
    private fun recoveryBackoffMs(failures: Int): Long =
        if (failures <= 3) {
            // Inside one loss-driven chain's own pacing (250/500/750 ms), so a chain's later
            // attempts still get through.
            300L * failures
        } else {
            minOf(1_500L shl (failures - 4), 30_000L)
        }

    /** Called under [mutex] when a recovery attempt did not produce a usable device. */
    private fun noteRecoveryFailure() {
        recoveryFailures++
        val backoff = recoveryBackoffMs(recoveryFailures)
        recoveryCooldownUntil = SystemClock.uptimeMillis() + backoff
        Log.w("WebGpuRenderer", "recovery failed (consecutive: $recoveryFailures, next attempt in ${backoff}ms)")
        if (recoveryFailures < RECOVERY_GIVE_UP_ATTEMPTS) {
            scheduleRecoveryRetry(backoff)
        } else if (recoveryFailures == RECOVERY_GIVE_UP_ATTEMPTS) {
            Log.e(
                "WebGpuRenderer",
                "recovery gave up after $recoveryFailures consecutive failures; a later device-loss " +
                    "event restarts the ladder"
            )
        }
    }

    /** Called under [mutex] when a recovery attempt produced a device that is alive at return. */
    private fun noteRecoverySuccess() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRecoverySuccessAt >= RECOVERY_STABLE_MS) {
            recoveryFailures = 0
        }
        lastRecoverySuccessAt = now
        notifyDeviceRecovered()
    }

    /**
     * One self-healing retry after a failed recovery. The loss-driven chain that started the
     * recovery gives up after its own attempts, and a failure inside the cooldown produces no
     * new loss callback - without this the reader stayed dead until the user recreated it, even
     * once the pressure that broke it had passed. The cooldown gate in [recoverFromDeviceLoss]
     * keeps overlapping chains and retries from piling up; the ladder's give-up stops them.
     */
    private fun scheduleRecoveryRetry(delayMs: Long) {
        if (!recoveryRetryScheduled.compareAndSet(false, true)) return
        CoroutineScope(dispatcher).launch {
            try {
                delay(delayMs)
                if (deviceLost || initError != null) recoverFromDeviceLoss()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            } finally {
                recoveryRetryScheduled.set(false)
            }
        }
    }

    /**
     * Retry acquiring the device after [deviceLost] (or a failed init) instead of staying
     * latched dead. Suspends on the render dispatcher under the render mutex, so it never runs
     * against an in-flight frame. True when the device is usable afterwards.
     */
    suspend fun reinit(): Boolean {
        if (isAvailable) return true
        return try {
            withContext(dispatcher) {
                mutex.withLock {
                    recreateDeviceLocked()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("WebGpuRenderer", "reinit failed", e)
            false
        }
    }

    /** Device-only recreation shared by [reinit] and [recoverFromDeviceLoss]. Must hold [mutex]. */
    internal suspend fun recreateDeviceLocked(
        force: Boolean = false,
        freshInstance: Boolean = false,
    ): Boolean {
        if (isAvailable && !force) return true
        return try {
            // KMK --> A fresh Dawn instance is the strongest reset available without restarting
            // the process: a new VkInstance means a new WSI connection, not merely a new logical
            // device on the one whose surface state is suspect. The previous instance is left
            // open on purpose - closing it while any retired wrapper still names an object from
            // it is the same class of JNI hazard as closing a retired device - and the OS frees
            // it at process exit. Only the swapchain ladder asks for this; a normal loss keeps
            // the instance.
            // KMK <--
            if (freshInstance) {
                Log.i(
                    "WebGpuRenderer",
                    "recreating the Dawn instance (previous one left for process exit)",
                )
                instance = createInstanceWithLibrary()
                activeBackend = BackendType.Undefined
                Hdr.resetSurfaceSupportForBackendChange()
            }
            // An adapter is consumed by the first requestDevice that succeeds on it, so the one
            // that built the lost device can never build another - Dawn rejects it as "consumed",
            // and with nothing re-requesting an adapter the renderer stayed unavailable for good.
            //
            // KMK --> The requested backend is the session's preference: Undefined on a normal
            // recovery (Dawn's own choice), OpenGLES after the swapchain ladder proved the Vulkan
            // WSI wedged. requestAdapterFor drops an unavailable preference rather than failing.
            // KMK <--
            adapter = requestAdapterFor(preferBackend)
            noteActiveBackend(adapter)
            adapterDescription = describeAdapter(adapter)
            device = requestDeviceBestEffort()
            initialized = true
            deviceLost = false
            initError = null
            deviceGeneration++
            // KMK --> Every pipeline and bind group cached by a painter belongs to the device
            // that built it; a stale one makes Dawn reject the whole command buffer and the
            // surface presents nothing (2026-11-23 Mali-G76 field dump). Drop them before any
            // frame can use the new device.
            RenderPage.resetForDeviceRebuild()
            // KMK --> The draw primitives cache pipelines too (the page background is Draw.rect,
            // page numbers are Draw.text). A stale one from these is the same
            // "[RenderPipeline] is associated with [Device]" rejection the page pipelines had.
            ca.mpreg.webgpuviewer.draw.resetRectPipelinesForDeviceRebuild()
            ca.mpreg.webgpuviewer.draw.resetCirclePipelinesForDeviceRebuild()
            ca.mpreg.webgpuviewer.draw.resetLinePipelinesForDeviceRebuild()
            ca.mpreg.webgpuviewer.draw.resetTextPipelinesForDeviceRebuild()
            ca.mpreg.webgpuviewer.draw.resetFontCacheForDeviceRebuild()
            // KMK <--
            // KMK <--
            // A fresh adapter can report different limits, and this is read live by the upload
            // paths, so it has to move with the device rather than keep the previous one's value.
            maxTextureDimension2D = device.getLimits().maxTextureDimension2D
            val core = runCatching {
                device.getFeatures().features.contains(FeatureName.CoreFeaturesAndLimits)
            }.getOrDefault(false)
            Log.i(
                "WebGpuRenderer",
                "recreated the device (generation $deviceGeneration, maxTextureDimension2D=$maxTextureDimension2D core=$core, adapter $adapterDescription)"
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("WebGpuRenderer", "recreateDevice requestDevice failed", e)
            initError = e
            false
        }
    }

    /** Non-suspending best-effort [reinit] for callbacks that cannot suspend. */
    fun requestReinit() {
        try {
            runBlocking(dispatcher) {
                reinit()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("WebGpuRenderer", "requestReinit dispatch failed", e)
        }
    }

    /** Dispatches only when not already on the render thread, which would deadlock. */
    private fun onRenderThread(block: () -> Unit) {
        if (Thread.currentThread().name == "WebGPU-Render-Thread") {
            block()
        } else {
            runBlocking(dispatcher) { block() }
        }
    }

    private fun createSurface() {
        if (surface != null) return
        val pending = pendingSurface ?: return

        // A transient layout pass can hand over a near-zero size; [resize] picks it up later.
        if (width < MIN_SURFACE_DIMENSION || height < MIN_SURFACE_DIMENSION) {
            Log.w("WebGpuRenderer", "surface deferred at undersized ${width}x${height}")
            return
        }

        val initSurface = initSurface@{
            // A cleanup queued first let [pending] go; a surface from it would leak.
            if (surface != null || pendingSurface !== pending) return@initSurface
            // KMK --> Field reports cannot see the platform window's state from the app side, and
            // an invalid/abandoned Surface here is one of the few ways a swapchain is born unusable.
            // KMK <--
            Log.i(
                "WebGpuRenderer",
                "creating surface ${this@WebGpuRenderer.width}x${this@WebGpuRenderer.height} " +
                    "valid=${pending.isValid}",
            )
            val created = instance.createSurface(
                GPUSurfaceDescriptor(
                    surfaceSourceAndroidNativeWindow = GPUSurfaceSourceAndroidNativeWindow(
                        safeWindowFromSurface(pending).also { acquired -> window = acquired }
                    )
                )
            )
            try {
                created.apply {
                    // Before the first latch, so the swapchain is configured knowing
                    // whether float is even available.
                    Hdr.resolve(this, adapter)
                    // No images exist yet, so a previous session's stranded retains can go
                    // without discarding a live one.
                    Hdr.resetContent()
                    Hdr.latchFrameFormat()
                    configure(
                        GPUSurfaceConfiguration(
                            device,
                            this@WebGpuRenderer.width,
                            this@WebGpuRenderer.height,
                            Hdr.frameFormat,
                            TextureUsage.RenderAttachment
                        )
                    )
                    this@WebGpuRenderer.configuredFormat = Hdr.frameFormat
                }
            } catch (e: Throwable) {
                // Unassigned, so cleanup would never free either.
                created.close()
                if (window != 0L) {
                    NativeWindow.release(window)
                    window = 0L
                }
                throw e
            }
            this@WebGpuRenderer.surface = created
        }

        onRenderThread(initSurface)
    }

    suspend fun render(fn: suspend (GPUCommandEncoder, GPUTexture) -> Unit): FrameResult {
        if (!isAvailable) return FrameResult.Unavailable
        val startTime = if (profilingEnabled) System.nanoTime() else 0L

        mutex.withLock {
            // KMK --> The swapchain ladder's device/backend rebuild owns the surface and every
            // device-bound cache for its whole duration. Answer Retry until it finishes rather
            // than drawing one frame into a half-torn-down device.
            // KMK <--
            if (swapchainResetScheduled.get()) return FrameResult.Retry
            // Only [init]/[resize] can build one, so a redraw alone accomplishes nothing.
            // KMK --> A surface a scheduled recreation/reset just closed is a pause, not an
            // outage: both land within a few frames (they pump the teardown and rebuild on this
            // dispatcher), so Retry keeps the walk alive without reporting a failure the viewer
            // asked for itself. KMK <--
            val surface = surface ?: if (surfaceRecreateScheduled.get() || swapchainResetScheduled.get()) {
                return FrameResult.Retry
            } else {
                return FrameResult.Unavailable
            }
            // [detachSurface] ran: the platform window is going away, so nothing may start a
            // frame against it - the framework tears it down once the destroy callbacks return.
            if (surfaceDetached) return FrameResult.Unavailable
            // A stale surface from before a resize shrunk below this would otherwise still reach
            // getCurrentTexture - [createSurface]/[reconfigure] refuse to configure one this small.
            if (width < MIN_SURFACE_DIMENSION || height < MIN_SURFACE_DIMENSION) {
                return FrameResult.Unavailable
            }

            // Between frames and under the lock - the only place a rebuild is safe.
            if (resizePending) {
                resizePending = false
                reconfigure(surface)
                // KMK --> Let the new swapchain settle for one full frame before acquiring from
                // it. A layout change lands right at viewer open in continuous mode (the column
                // width is applied after the first surface callback), and configuring and then
                // acquiring inside the same frame is the shortest possible window for a driver to
                // build and use a fresh swapchain. The frame loop re-arms on Retry.
                // KMK <--
                return FrameResult.Retry
            }

            // An HDR image arriving, or the last one leaving, changes what the swapchain should
            // be. Here rather than at the decode: the format can only change between frames, and
            // this is the one place guaranteed to be between them - hence the latch too.
            try {
                Hdr.latchFrameFormat()
                if (Hdr.frameFormat != configuredFormat) {
                    reconfigure(surface)
                    Hdr.syncPresentation()
                    // Same rule as a resize reconfigure: use the new swapchain next frame.
                    return FrameResult.Retry
                } else if (Hdr.consumePresentationDirty()) {
                    // Same format, but a brighter image arrived (or the brightest was freed), so
                    // the headroom asked of the display has moved.
                    Hdr.syncPresentation()
                }
            } catch (e: Exception) {
                // Escaping would end the frame loop.
                Log.e("WebGpuRenderer", "HDR presentation update failed", e)
            }

            val current = try {
                surface.getCurrentTexture()
            } catch (e: Exception) {
                // KMK --> A throw here is the same wedge as a non-success status, and it used to
                // return Retry without ever touching the streak: the escalation below could not
                // fire, and with the viewer's own retry cap now gone that would have been an
                // unbounded rearm instead of a recovery. Count it. Do *not* reconfigure: Dawn's
                // configure only rewrites the descriptor on a WSI object that is already broken,
                // and doing it on every failed frame built a fresh swapchain per frame on the
                // very driver this recovery exists for.
                // KMK <--
                Log.w("WebGpuRenderer", "Failed to get current texture", e)
                return onSwapchainError()
            }

            // A non-success status hands back a null texture, and every GPUTexture read goes
            // straight through its handle - so one segfaults rather than throws.
            val texture = current.texture
            if (!current.status.isSurfaceSuccess() || texture.handle == 0L) {
                if (texture.handle != 0L) texture.close()
                when (current.status) {
                    // A window resize under a frame already in flight, or the compositor
                    // withholding buffers while backgrounded. Both are normal and self-correct
                    // once the descriptor follows the window.
                    SurfaceGetCurrentTextureStatus.Outdated -> {
                        reconfigure(surface)
                        return FrameResult.Retry
                    }

                    SurfaceGetCurrentTextureStatus.Timeout -> return FrameResult.Retry

                    // Lost needs a new surface: park until one arrives.
                    SurfaceGetCurrentTextureStatus.Lost -> {
                        surfaceErrorStreak = 0
                        return FrameResult.Unavailable
                    }
                }
                // KMK --> A swapchain stuck in Dawn's error state never clears through
                // [reconfigure] - configure only updates the descriptor on a surface whose WSI
                // object is already broken. The escalation lives in [onSwapchainError]: surface
                // rebuild, then device rebuild, then the GLES backend, then park.
                // KMK <--
                if (surfaceErrorStreak == 0) {
                    Log.w(
                        "WebGpuRenderer",
                        "No surface texture: ${SurfaceGetCurrentTextureStatus.toString(current.status)}" +
                            (lastUncapturedError?.let { " last=$it" } ?: ""),
                    )
                }
                return onSwapchainError()
            }
            surfaceErrorStreak = 0

            var encoder: GPUCommandEncoder? = null
            var sampleBuffer: GPUBuffer? = null
            try {
                encoder = device.createCommandEncoder()
                // KMK --> Both rings belong to this encoded frame: the masked-rect slots and the
                // diagnostics probe. A frame that throws after this point leaves the probe with
                // whatever it actually asked for before the throw, which is what the dump wants.
                RenderPage.beginFrame()
                ca.mpreg.webgpuviewer.viewer.RenderProbe.beginFrame()
                // KMK <--
                // Draws into an offscreen texture when filters are enabled; endFrame runs them
                // over it and lands the result on the swapchain.
                fn(encoder, filters.beginFrame(texture))
                filters.endFrame(encoder, texture)
                // KMK --> One-shot page-texture readback, armed by ImageSingle.renderPage. The
                // copy goes into this frame's encoder; the map is read after the present and the
                // result lands in the session report's `tex=`. This is the fact that separates
                // "the decoded texture is black" from "the texture is fine and the draw or the
                // compositor is black" - the two shapes every 2026-11 black-screen dump so far
                // could not tell apart.
                ca.mpreg.webgpuviewer.viewer.RenderProbe.takeTextureSample()?.let { sample ->
                    try {
                        val buffer = device.createBuffer(
                            GPUBufferDescriptor(
                                usage = BufferUsage.MapRead or BufferUsage.CopyDst,
                                size = 256,
                                label = "page-readback",
                            )
                        )
                        encoder.copyTextureToBuffer(
                            GPUTexelCopyTextureInfo(
                                sample.texture, 0,
                                GPUOrigin3D(sample.x, sample.y, 0),
                                TextureAspect.All,
                            ),
                            GPUTexelCopyBufferInfo(
                                buffer, GPUTexelCopyBufferLayout(0, 256, 1)
                            ),
                            GPUExtent3D(1, 1, 1),
                        )
                        sampleBuffer = buffer
                    } catch (e: Throwable) {
                        ca.mpreg.webgpuviewer.viewer.RenderProbe.noteTextureSample(
                            "copy failed ${e.message}"
                        )
                    }
                }
                // KMK <--
                // A device loss landing while [fn] above suspends (tile generation waits inside
                // it) invalidates the native queue before this submit. The Kotlin deviceLost flag
                // only flips when the loss callback runs, so the entry isAvailable gate cannot
                // cover the gap - and submitting on the dead handle is a null-deref SEGV inside
                // libwebgpu_c_bundled (Java_androidx_webgpu_GPUQueue_submit) that kills the
                // process with no exception to catch. cleanup() holds the same rule: once the
                // device is lost there is nothing to drive. Drop the frame; the app-side loss
                // listener rebuilds and re-drives. UNVERIFIED: the residual window between this
                // recheck and the JNI call cannot be closed from Kotlin.
                //
                // The platform surface has the same exposure, with one difference: [detachSurface]
                // sets [surfaceDetached] from the surface-destroyed callback and then drains this
                // very frame before returning - the framework holds the teardown until that
                // callback returns - so this check, not the drain, is what actually stops a frame
                // that was mid-draw when the surface went away. The isValid probe is a last
                // backstop for teardowns that bypass the callback.
                if (!isAvailable || surfaceDetached || platformSurface?.isValid == false) {
                    val why = when {
                        surfaceDetached -> "Surface detached mid-frame"
                        platformSurface?.isValid == false -> "Platform surface invalid mid-frame"
                        else -> "Device lost mid-frame"
                    }
                    Log.w("WebGpuRenderer", "$why; dropping frame")
                    // Non-local return, like the other FrameResult exits above: the withLock
                    // block's own value is discarded, so a return@withLock would fall through
                    // to the FrameResult.Drawn tail.
                    return FrameResult.Unavailable
                }
                val errorsBefore = validationErrors
                device.queue.submitAndRelease(encoder)
                encoder = null
                // KMK --> An uncaptured validation error invalidates the command buffer Dawn was
                // handed, so this frame draws nothing even though submit returned. Report it as a
                // failed frame (bounded by onDrawError) instead of presenting black and counting
                // it as drawn.
                if (validationErrors != errorsBefore) {
                    Log.e(
                        "WebGpuRenderer",
                        "dropping frame: ${validationErrors - errorsBefore} WebGPU validation error(s) " +
                            "last=${lastUncapturedError.orEmpty()}"
                    )
                    return onDrawError()
                }
                // KMK <--
                surface.present()
                // KMK --> Counted only after the present returns, so the counter means "the
                // surface was handed this frame". See the field's own note for why a session
                // report needs it.
                // KMK <--
                presentedFrames++
                // KMK --> Resolve the one-shot readback now that the GPU has the copy.
                // KMK --> Device-lost guard: mapAndAwait on a lost device is a native fault
                // (SIGILL) that kills the render thread before any Kotlin catch can run, so
                // check the flag first and skip the readback entirely on loss. The catch below
                // stays for genuine read errors that surface through an exception.
                // KMK <--
                sampleBuffer?.let { buffer ->
                    if (deviceLost) {
                        Log.w("WebGpuRenderer", "Skipping texture readback: device lost")
                        drawFailureStreak++
                        return FrameResult.Unavailable
                    }
                    try {
                        buffer.mapAndAwait(MapMode.Read, 0, 4)
                        val bytes = ByteArray(4)
                        buffer.getMappedRange(0, 4).get(bytes)
                        ca.mpreg.webgpuviewer.viewer.RenderProbe.noteTextureSample(
                            "rgba=${bytes[0].toInt() and 0xFF},${bytes[1].toInt() and 0xFF}," +
                                "${bytes[2].toInt() and 0xFF},${bytes[3].toInt() and 0xFF}"
                        )
                    } catch (e: Throwable) {
                        ca.mpreg.webgpuviewer.viewer.RenderProbe.noteTextureSample(
                            "map failed ${e.message}"
                        )
                        if (e.message?.contains("[Device]") == true || e.message?.contains("is lost") == true) {
                            Log.w("WebGpuRenderer", "Device lost detected during texture readback: $e.message")
                            drawFailureStreak++
                            return FrameResult.Unavailable
                        }
                    } finally {
                        try { buffer.unmap() } catch (_: Throwable) {}
                        try { buffer.destroy() } catch (_: Throwable) {}
                        try { buffer.close() } catch (_: Throwable) {}
                    }
                }
                sampleBuffer = null
                // KMK <--
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // KMK --> Do not fall through to Drawn: the submit did not happen, so reporting a
                // drawn frame reset the viewer's streak and kept a deterministic pipeline failure
                // invisible forever. Count it and let the bounded draw-failure ladder answer.
                // KMK <--
                Log.e("WebGpuRenderer", "Render error", e)
                return onDrawError()
            } catch (e: OutOfMemoryError) {
                Log.e("WebGpuRenderer", "Out of memory rendering", e)
                System.gc()
                return onDrawError()
            } finally {
                // Unsubmitted if the frame threw.
                encoder?.close()
                // A readback armed above but never resolved (a dropped frame, a device loss)
                // would otherwise keep its buffer.
                sampleBuffer?.let {
                    try { it.destroy() } catch (_: Throwable) {}
                    try { it.close() } catch (_: Throwable) {}
                }
                // getCurrentTexture hands out a new reference every frame - see [endAndRelease].
                texture.close()
            }
            // A frame that reached here submitted and presented - the draw-failure dial resets
            // under the same lock that reads it.
            drawFailureStreak = 0
        }

        if (profilingEnabled) {
            val frameTime = System.nanoTime() - startTime
            recordFrameTime(frameTime)
            Log.d(
                "WebGpuRenderer", "Frame: %.2fms | Avg: %.2fms | FPS: %.1f".format(
                    frameTime / 1_000_000f, recentAvgFrameTimeMs, estimatedFps
                )
            )
        }

        return FrameResult.Drawn
    }

    /**
     * KMK --> One frame whose draw threw, and what the loop should do about it.
     *
     * Bounded separately from [onSwapchainError] because the surface is healthy here: a retry
     * can succeed after a transient allocation failure, but a deterministic pipeline or shader
     * failure repeats forever. Returning [FrameResult.Unavailable] after
     * [DRAW_FAILURE_GIVE_UP_STREAK] parks the loop with one reported reason instead of a
     * vsync-rate exception storm the user sees only as a black page.
     *
     * Render thread, under [mutex].
     * KMK <--
     */
    private fun onDrawError(): FrameResult {
        drawFailureStreak++
        if (drawFailureStreak >= DRAW_FAILURE_GIVE_UP_STREAK) {
            Log.e(
                "WebGpuRenderer",
                "draw failure streak $drawFailureStreak frames; parking until the next invalidation"
            )
            drawFailureStreak = 0
            return FrameResult.Unavailable
        }
        return FrameResult.Retry
    }

    /**
     * KMK --> Count one failed swapchain frame and answer what the frame should return.
     *
     * The escalation dials live together here because they all put the renderer on the path to
     * recovery: [SwapchainRecoveryPolicy.RECREATE_SURFACE_AT] replaces the WebGPU surface,
     * [SwapchainRecoveryPolicy.REBUILD_DEVICE_AT] rebuilds the device (and with it Dawn's WSI object),
     * [SwapchainRecoveryPolicy.SWITCH_BACKEND_AT] re-requests the adapter on Dawn's OpenGLES backend
     * (EGL presentation, a different WSI implementation from `VkSwapchainKHR`), and
     * [SwapchainRecoveryPolicy.GIVE_UP_AT] parks the loop with [FrameResult.Unavailable] once all three
     * are proved futile. A [FrameResult.Drawn] frame resets [surfaceErrorStreak] in [render].
     *
     * The rungs matter because they fail in a fixed order on the field device (PowerVR GE8322,
     * Android 11): a fresh surface on the same device reproduces the wedge, so the first rung
     * only proves the swapchain object is not the whole problem. The 2026-11-22 dump shows the
     * surface recreated at 30 and 120 frames with `getCurrentTexture` still Error, which is why
     * the device and backend rungs exist.
     *
     * One caller is a non-success `getCurrentTexture` status, the other a throw from the same
     * call. **Both must come through here**: the viewer re-arms on every [FrameResult.Retry], so
     * a Retry that does not increment this streak is an unbounded frame-rate retry with no
     * recovery, which is exactly how a wedged PowerVR swapchain used to present as a healthy
     * session that simply never drew.
     *
     * Render thread, under [mutex].
     * KMK <--
     */
    private fun onSwapchainError(): FrameResult {
        surfaceErrorStreak++
        when (SwapchainRecoveryPolicy.stepFor(surfaceErrorStreak)) {
            SwapchainRecoveryStep.PARK -> {
                Log.e(
                    "WebGpuRenderer",
                    "swapchain error streak $surfaceErrorStreak frames; parking until the surface changes"
                )
                surfaceParkCount++
                surfaceErrorStreak = 0
                // The park is final: every rung has been tried for this streak, and rebuilding yet
                // another surface here only fed an Error loop the parking decision exists to stop.
                // The next page decode, resize or user action re-arms the ladder from zero.
                return FrameResult.Unavailable
            }

            SwapchainRecoveryStep.RECREATE_SURFACE -> {
                Log.w(
                    "WebGpuRenderer",
                    "swapchain error streak $surfaceErrorStreak frames; recreating the surface"
                )
                scheduleSurfaceRecreate()
            }

            SwapchainRecoveryStep.REBUILD_DEVICE -> {
                Log.w(
                    "WebGpuRenderer",
                    "swapchain error streak $surfaceErrorStreak frames; " +
                        "recreating the surface did not help - rebuilding the GPU device"
                )
                scheduleSwapchainReset(switchBackend = false)
            }

            SwapchainRecoveryStep.SWITCH_BACKEND -> {
                Log.w(
                    "WebGpuRenderer",
                    "swapchain error streak $surfaceErrorStreak frames; " +
                        "device rebuild did not help - switching to the OpenGLES backend"
                )
                scheduleSwapchainReset(switchBackend = true)
            }

            SwapchainRecoveryStep.RETRY -> Unit
        }
        return FrameResult.Retry
    }

    /** Rebuild the swapchain at the size [init] was last given. Must hold [mutex]. */
    private fun reconfigure(surface: GPUSurface) {
        if (width < MIN_SURFACE_DIMENSION || height < MIN_SURFACE_DIMENSION) return
        try {
            surface.configure(
                GPUSurfaceConfiguration(
                    device, width, height, Hdr.frameFormat, TextureUsage.RenderAttachment
                )
            )
            configuredFormat = Hdr.frameFormat
        } catch (e: Exception) {
            Log.w("WebGpuRenderer", "Failed to reconfigure surface", e)
        }
    }

    fun cleanup() {
        // Check if already on dispatcher thread to avoid deadlock
        val isOnDispatcherThread = Thread.currentThread().name == "WebGPU-Render-Thread"

        val doCleanup: suspend () -> Unit = {
            mutex.withLock {
                // Once the device is lost there is nothing to free, and calling into its objects
                // crashed in native code.
                if (!deviceLost) {
                    filters.cleanup()
                    surface?.close()
                }
                surface = null
                // After the surface: Vulkan holds its own reference while its surface lives, so ours is the last.
                if (window != 0L) {
                    NativeWindow.release(window)
                    window = 0L
                }
                pendingSurface = null
                resizePending = false
                // The same deferral as in [recoverFromDeviceLoss]: a viewer recreated right
                // after this one (a reading-mode switch) inits on a window the closed
                // surface may still own until the deferred destroy actually runs.
                pumpSurfaceTeardown()
            }
        }

        if (isOnDispatcherThread && mutex.isLocked) {
            // The mutex holder is on this thread: blocking would deadlock.
            CoroutineScope(dispatcher).launch { doCleanup() }
        } else if (isOnDispatcherThread) {
            // Already on dispatcher, run synchronously
            runBlocking {
                doCleanup()
            }
        } else {
            runBlocking(dispatcher) {
                doCleanup()
            }
        }
    }
}

/** Suboptimal still draws - it only asks to be reconfigured eventually. */
private fun Int.isSurfaceSuccess(): Boolean =
    this == SurfaceGetCurrentTextureStatus.SuccessOptimal ||
            this == SurfaceGetCurrentTextureStatus.SuccessSuboptimal
