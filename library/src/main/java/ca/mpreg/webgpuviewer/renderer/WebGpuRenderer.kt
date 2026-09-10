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
        @Volatile
        var offsetY: Float = 0f

        private var renderThread: Thread? = null
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "WebGPU-Render-Thread").also { renderThread = it }
        }.asCoroutineDispatcher()
        internal fun isOnRenderThread(): Boolean = Thread.currentThread() === renderThread

        // Frame time profiling
        @Volatile
        var profilingEnabled = false
        private var frameCount = 0L
        private var totalFrameTimeNs = 0L
        private var minFrameTimeNs = Long.MAX_VALUE
        private var maxFrameTimeNs = 0L
        private var lastFrameTimeNs = 0L
        private val recentFrameTimes = LongArray(60)
        private var recentFrameIndex = 0

        val lastFrameTimeMs: Float get() = lastFrameTimeNs / 1_000_000f
        val avgFrameTimeMs: Float get() = if (frameCount > 0) totalFrameTimeNs / frameCount / 1_000_000f else 0f
        val minFrameTimeMs: Float get() = if (minFrameTimeNs == Long.MAX_VALUE) 0f else minFrameTimeNs / 1_000_000f
        val maxFrameTimeMs: Float get() = maxFrameTimeNs / 1_000_000f
        val recentAvgFrameTimeMs: Float
            get() {
                val count = minOf(frameCount.toInt(), 60)
                if (count == 0) return 0f
                var sum = 0L
                for (i in 0 until count) {
                    sum += recentFrameTimes[i]
                }
                return sum.toFloat() / count / 1_000_000f
            }
        val estimatedFps: Float get() = if (lastFrameTimeNs > 0) 1_000_000_000f / lastFrameTimeNs else 0f

        fun resetProfiling() {
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
            frameCount++
            totalFrameTimeNs += timeNs
            lastFrameTimeNs = timeNs
            if (timeNs < minFrameTimeNs) minFrameTimeNs = timeNs
            if (timeNs > maxFrameTimeNs) maxFrameTimeNs = timeNs
            recentFrameTimes[recentFrameIndex] = timeNs
            recentFrameIndex = (recentFrameIndex + 1) % 60
        }

        @Volatile
        private var initialized = false

        @Volatile
        var initError: Throwable? = null
            private set

        val isAvailable: Boolean get() = initialized && initError == null && ::instance.isInitialized && ::adapter.isInitialized && ::device.isInitialized

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

                    adapter =
                        instance.requestAdapter(GPURequestAdapterOptions(featureLevel = FeatureLevel.Compatibility))

                    val requiredFeatures =
                        if (adapter.hasFeature(FeatureName.TimestampQuery)) {
                            intArrayOf(FeatureName.TimestampQuery)
                        } else {
                            intArrayOf()
                        }

                    device = adapter.requestDevice(
                        GPUDeviceDescriptor(
                            deviceLostCallback = defaultDeviceLostCallback,
                            deviceLostCallbackExecutor = Executor(Runnable::run),
                            uncapturedErrorCallback = defaultUncapturedErrorCallback,
                            uncapturedErrorCallbackExecutor = Executor(Runnable::run),
                            requiredFeatures = requiredFeatures,
                        )
                    )
                    initialized = true
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
            check(isAvailable) { "WebGPU not available: $initError" }
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
            check(isAvailable) { "WebGPU not available: $initError" }
            return withContext(dispatcher) {
                block(this, device)
            }
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
            Log.w("WebGpuRenderer", "init called but WebGPU not available: $initError")
            return
        }
        // Guard tiny/zero surfaces that trigger qdgralloc 0x3b (ASTC/Stencil8) failures on Adreno.
        // A 4x4 swapchain (seen in log: 4x4 format 59) is not drawable and spams gralloc.
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
            } catch (e: Throwable) {
                Log.e("WebGpuRenderer", "Failed to create surface ${width}x$height", e)
                this@WebGpuRenderer.surface = null
            }
        }

        if (isOnDispatcherThread) {
            initSurface()
        } else {
            runBlocking(dispatcher) {
                initSurface()
            }
        }
    }

    /** Draws one frame. False when the swapchain had no texture: nothing drawn, retry next frame. */
    suspend fun render(fn: suspend (GPUCommandEncoder, GPUTexture) -> Unit): Boolean {
        // Harden: skip tiny surfaces that would trigger 4x4 Stencil8 gralloc 0x3b and spam logcat.
        if (width < 8 || height < 8) {
            Log.w("WebGpuRenderer", "render skipped for tiny surface ${width}x$height")
            return false
        }
        val startTime = if (profilingEnabled) System.nanoTime() else 0L

        mutex.withLock {
            val surface = surface ?: return false

            val current = try {
                surface.getCurrentTexture()
            } catch (e: Exception) {
                Log.w("WebGpuRenderer", "Failed to get current texture", e)
                return false
            }
            // Harden: tiny swapchain texture (4x4) from surface - skip to avoid Stencil8 alloc.
            if (current.texture.width < 8 || current.texture.height < 8) {
                Log.w("WebGpuRenderer", "render skipped for tiny swapchain ${current.texture.width}x${current.texture.height}")
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
                filters.cleanup()
                surface?.close()
                surface = null
            }
        }

        if (isOnDispatcherThread) {
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
