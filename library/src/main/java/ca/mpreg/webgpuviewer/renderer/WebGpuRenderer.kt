package ca.mpreg.webgpuviewer.renderer

import android.util.Log
import android.view.Surface
import androidx.webgpu.DeviceLostCallback
import androidx.webgpu.DeviceLostException
import androidx.webgpu.FeatureLevel
import androidx.webgpu.FeatureName
import androidx.webgpu.GPU.createInstance
import androidx.webgpu.GPUAdapter
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUDeviceDescriptor
import androidx.webgpu.GPUInstance
import androidx.webgpu.GPUInstanceDescriptor
import androidx.webgpu.GPURequestAdapterOptions
import androidx.webgpu.GPUSurface
import androidx.webgpu.GPUSurfaceConfiguration
import androidx.webgpu.GPUSurfaceDescriptor
import androidx.webgpu.GPUSurfaceSourceAndroidNativeWindow
import androidx.webgpu.GPUTexture
import androidx.webgpu.SurfaceGetCurrentTextureStatus
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import androidx.webgpu.UncapturedErrorCallback
import androidx.webgpu.WebGpuRuntimeException
import androidx.webgpu.helper.Util as WebGpuUtilHelper
import androidx.webgpu.helper.initLibrary as webgpuInitLibrary
import ca.mpreg.webgpuviewer.filter.FilterChain
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.Companion.mutex
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.Companion.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class WebGpuRenderer {
    companion object {
        lateinit var instance: GPUInstance
        lateinit var adapter: GPUAdapter
        lateinit var device: GPUDevice
        private val mutex = Mutex()

        @Volatile
        var offsetX: Float = 0f
            set(value) { field = if (!value.isNaN() && !value.isInfinite()) value else 0f }
        @Volatile
        var offsetY: Float = 0f
            set(value) { field = if (!value.isNaN() && !value.isInfinite()) value else 0f }

        private var renderThread: Thread? = null
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "WebGPU-Render-Thread").also { renderThread = it }
        }.asCoroutineDispatcher()
        internal fun isOnRenderThread(): Boolean = Thread.currentThread() === renderThread

        // Frame time profiling - guarded by synchronized(profilingLock) for cross-thread reads
        @Volatile
        var profilingEnabled = false
        private val profilingLock = Any()
        private var frameCount = 0L
        private var totalFrameTimeNs = 0L
        private var minFrameTimeNs = Long.MAX_VALUE
        private var maxFrameTimeNs = 0L
        private var lastFrameTimeNs = 0L
        private val recentFrameTimes = LongArray(60)
        private var recentFrameIndex = 0

        val lastFrameTimeMs: Float get() = synchronized(profilingLock) { lastFrameTimeNs / 1_000_000f }
        val avgFrameTimeMs: Float get() = synchronized(profilingLock) {
            if (frameCount > 0) totalFrameTimeNs / frameCount / 1_000_000f else 0f
        }
        val minFrameTimeMs: Float get() = synchronized(profilingLock) {
            if (minFrameTimeNs == Long.MAX_VALUE) 0f else minFrameTimeNs / 1_000_000f
        }
        val maxFrameTimeMs: Float get() = synchronized(profilingLock) { maxFrameTimeNs / 1_000_000f }
        val recentAvgFrameTimeMs: Float
            get() = synchronized(profilingLock) {
                val count = minOf(frameCount.toInt(), 60)
                if (count == 0) return 0f
                var sum = 0L
                for (i in 0 until count) {
                    sum += recentFrameTimes[i]
                }
                return sum.toFloat() / count / 1_000_000f
            }
        val estimatedFps: Float get() = synchronized(profilingLock) {
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

        internal fun recordFrameTime(timeNs: Long) {
            if (!profilingEnabled) return
            if (timeNs < 0) return
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

        @Volatile
        private var initialized = false

        @Volatile
        var initError: Throwable? = null
            private set

        @Volatile
        private var deviceLost = false

        val isAvailable: Boolean get() = initialized && !deviceLost && initError == null &&
            ::instance.isInitialized && ::adapter.isInitialized && ::device.isInitialized

        fun requireAvailable() {
            check(isAvailable) {
                val cause = initError?.let { ": ${it.message}" } ?: if (deviceLost) ": device lost" else ""
                "WebGPU not available$cause"
            }
        }

        private fun safeInitLibrary() {
            try {
                webgpuInitLibrary()
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

        private fun safeWindowFromSurface(surface: Surface): Long {
            try {
                return WebGpuUtilHelper.windowFromSurface(surface)
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

                    instance = createInstance(GPUInstanceDescriptor())

                    val gotAdapter = try {
                        instance.requestAdapter(GPURequestAdapterOptions(featureLevel = FeatureLevel.Compatibility))
                    } catch (e: Throwable) {
                        Log.e("WebGpuRenderer", "requestAdapter failed", e)
                        throw e
                    }
                    @Suppress("SENSELESS_COMPARISON")
                    if (gotAdapter == null as Any?) {
                        throw IllegalStateException("requestAdapter returned null")
                    }
                    adapter = gotAdapter

                    val requiredFeatures =
                        if (runCatching { adapter.hasFeature(FeatureName.TimestampQuery) }.getOrDefault(false)) {
                            intArrayOf(FeatureName.TimestampQuery)
                        } else {
                            intArrayOf()
                        }

                    device = adapter.requestDevice(
                        GPUDeviceDescriptor(
                            deviceLostCallback = DeviceLostCallback { lostDevice, reason, message ->
                                deviceLost = true
                                Log.e("WebGpuRenderer", "WebGPU device lost reason=$reason: $message device=$lostDevice")
                            },
                            deviceLostCallbackExecutor = Executor(Runnable::run),
                            uncapturedErrorCallback = defaultUncapturedErrorCallback,
                            uncapturedErrorCallbackExecutor = Executor(Runnable::run),
                            requiredFeatures = requiredFeatures,
                        )
                    )
                    initialized = true
                    deviceLost = false
                    initError = null
                } catch (e: Throwable) {
                    Log.e("WebGpuRenderer", "Failed to initialize WebGPU", e)
                    initialized = false
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

        fun tryRecoverNote(): String =
            when {
                !initialized -> "not initialized: $initError"
                deviceLost -> "device lost"
                initError != null -> "init error: $initError"
                else -> "unknown"
            }
    }

    @Volatile
    private var surface: GPUSurface? = null

    /**
     * Post-processing over the finished frame - see [FilterChain]. Empty by default, in which
     * case [render] hands the swapchain texture straight to its caller as it always did.
     */
    val filters = FilterChain()

    var width: Int = 0
    var height: Int = 0

    private var scope: CoroutineScope? = null

    @Synchronized
    fun init(scope: CoroutineScope, surface: Surface, width: Int, height: Int) {
        if (!isAvailable) {
            Log.w("WebGpuRenderer", "init called but WebGPU not available: ${tryRecoverNote()}")
            return
        }
        if (!surface.isValid) {
            Log.w("WebGpuRenderer", "init skipped for invalid surface")
            this.scope = scope
            this.width = width.coerceAtLeast(0)
            this.height = height.coerceAtLeast(0)
            return
        }
        if (width < 0 || height < 0) {
            Log.w("WebGpuRenderer", "init skipped for negative surface ${width}x$height")
            return
        }
        if (width < 8 || height < 8) {
            Log.w("WebGpuRenderer", "init skipped for tiny surface ${width}x$height (<8), deferring until laid out")
            this.scope = scope
            this.width = width.coerceAtLeast(0)
            this.height = height.coerceAtLeast(0)
            return
        }
        if (width > 8192 || height > 8192) {
            Log.w("WebGpuRenderer", "init clamped oversized surface ${width}x$height to 8192")
            this.scope = scope
            this.width = width.coerceIn(8, 8192)
            this.height = height.coerceIn(8, 8192)
            return
        }
        this.scope = scope
        this.width = width
        this.height = height

        val isOnDispatcherThread = isOnRenderThread()

        val initSurface = {
            try {
                if (!surface.isValid) throw IllegalStateException("Surface became invalid before createSurface")
                val current = this@WebGpuRenderer.surface
                try { current?.close() } catch (_: Throwable) {}
                this@WebGpuRenderer.surface = surface.let {
                    instance.createSurface(
                        GPUSurfaceDescriptor(
                            surfaceSourceAndroidNativeWindow = GPUSurfaceSourceAndroidNativeWindow(
                                safeWindowFromSurface(it)
                            )
                        )
                    ).apply {
                        configure(
                            GPUSurfaceConfiguration(
                                device,
                                width,
                                height,
                                TextureFormat.RGBA8Unorm,
                                TextureUsage.RenderAttachment
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("WebGpuRenderer", "Failed to create surface ${width}x$height", e)
                try { this@WebGpuRenderer.surface?.close() } catch (_: Throwable) {}
                this@WebGpuRenderer.surface = null
            }
        }

        if (isOnDispatcherThread) {
            initSurface()
        } else {
            try {
                runBlocking(dispatcher) {
                    initSurface()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("WebGpuRenderer", "init surface dispatch failed", e)
            }
        }
    }



    /** Draws one frame. False when the swapchain had no texture: nothing drawn, retry next frame. */
    suspend fun render(fn: suspend (GPUCommandEncoder, GPUTexture) -> Unit): Boolean {
        if (!isAvailable) return false
        if (deviceLost) return false
        if (width < 8 || height < 8) {
            Log.w("WebGpuRenderer", "render skipped for tiny surface ${width}x$height")
            return false
        }
        val startTime = if (profilingEnabled) System.nanoTime() else 0L

        mutex.withLock {
            val surface = surface ?: return false

            val current = try {
                surface.getCurrentTexture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("WebGpuRenderer", "Failed to get current texture", e)
                return false
            }
            val texW = try { current.texture.width } catch (_: Throwable) { 0 }
            val texH = try { current.texture.height } catch (_: Throwable) { 0 }
            if (texW < 8 || texH < 8) {
                Log.w("WebGpuRenderer", "render skipped for tiny swapchain ${texW}x$texH")
                return false
            }

            // A non-success status hands back a null texture, and every GPUTexture read goes
            // straight through its handle - so one segfaults rather than throws. Outdated means
            // a window resize under a frame already in flight.
            val texture = current.texture
            if (!current.status.isSurfaceSuccess() || texture.handle == 0L) {
                Log.w(
                    "WebGpuRenderer",
                    "No surface texture: ${SurfaceGetCurrentTextureStatus.toString(current.status)}"
                )
                // Lost needs a whole new surface, which only the app can hand over.
                if (current.status != SurfaceGetCurrentTextureStatus.Lost) reconfigure(surface)
                return false
            }

            try {
                val encoder = device.createCommandEncoder()
                // Draws into an offscreen texture when filters are enabled; endFrame runs them
                // over it and lands the result on the swapchain.
                fn(encoder, filters.beginFrame(texture))
                filters.endFrame(encoder, texture)
                device.queue.submit(arrayOf(encoder.finish()))
                surface.present()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WebGpuRenderer", "Render error", e)
                // Don't rethrow - allow the app to continue rendering next frame
            }
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

        return true
    }

    /** Rebuild the swapchain at the size [init] was last given. Must hold [mutex]. */
    private fun reconfigure(surface: GPUSurface) {
        if (width < 8 || height < 8) {
            Log.w("WebGpuRenderer", "reconfigure skipped for tiny ${width}x$height")
            return
        }
        if (width > 8192 || height > 8192) {
            Log.w("WebGpuRenderer", "reconfigure clamped oversized ${width}x$height")
            width = width.coerceIn(8, 8192)
            height = height.coerceIn(8, 8192)
        }
        try {
            surface.configure(
                GPUSurfaceConfiguration(
                    device, width, height, TextureFormat.RGBA8Unorm, TextureUsage.RenderAttachment
                )
            )
        } catch (e: Exception) {
            Log.w("WebGpuRenderer", "Failed to reconfigure surface", e)
        }
    }

    fun onLowMemory() {
        try { filters.onLowMemory() } catch (_: Exception) {}
        // TileRenderer owns its atlas; clear when host signals trim
        // WebGpuRenderer is the owner that can reach it via viewers; viewers call tiles.onLowMemory() themselves
    }

    fun cleanup() {
        val isOnDispatcherThread = isOnRenderThread()

        val doCleanup: suspend () -> Unit = {
            mutex.withLock {
                try { filters.cleanup() } catch (e: Throwable) { Log.w("WebGpuRenderer", "filter cleanup failed", e) }
                val s = surface
                surface = null
                if (s != null) {
                    try { s.close() } catch (e: Throwable) { Log.w("WebGpuRenderer", "surface close failed", e) }
                }
                width = 0
                height = 0
                scope = null
            }
        }

        try {
            if (isOnDispatcherThread) {
                runBlocking {
                    doCleanup()
                }
            } else {
                runBlocking(dispatcher) {
                    doCleanup()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("WebGpuRenderer", "cleanup dispatch failed", e)
            try { surface?.close() } catch (_: Throwable) {}
            surface = null
        }
    }
}

/** Suboptimal still draws - it only asks to be reconfigured eventually. */
private fun Int.isSurfaceSuccess(): Boolean =
    this == SurfaceGetCurrentTextureStatus.SuccessOptimal ||
            this == SurfaceGetCurrentTextureStatus.SuccessSuboptimal

private val defaultUncapturedErrorCallback
    get(): UncapturedErrorCallback {
        return UncapturedErrorCallback { _, type, message ->
            Log.e("WebGpuRenderer", "Uncaptured WebGPU error type=$type: $message")
        }
    }

private val defaultDeviceLostCallback
    get(): DeviceLostCallback {
        return DeviceLostCallback { device, reason, message ->
            Log.e("WebGpuRenderer", "WebGPU device lost reason=$reason: $message device=$device")
        }
    }
