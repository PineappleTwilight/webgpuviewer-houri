package ca.mpreg.webgpuviewer.viewer

import android.content.res.Resources
import android.util.Log
import android.view.Surface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.webgpu.GPUColor
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDepthStencilAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPURenderPassEncoder
import androidx.webgpu.GPUTexture
import androidx.webgpu.LoadOp
import androidx.webgpu.StoreOp
import ca.mpreg.webgpuviewer.filter.FilterChain
import ca.mpreg.webgpuviewer.reader.OnReaderStateChanged
import ca.mpreg.webgpuviewer.reader.PageAnchor
import ca.mpreg.webgpuviewer.reader.ReaderState
import ca.mpreg.webgpuviewer.renderer.Downscaler
import ca.mpreg.webgpuviewer.renderer.DownscalerBox
import ca.mpreg.webgpuviewer.renderer.FrameResult
import ca.mpreg.webgpuviewer.renderer.Hdr
import ca.mpreg.webgpuviewer.renderer.Rescaler
import ca.mpreg.webgpuviewer.renderer.TileRenderer
import ca.mpreg.webgpuviewer.renderer.Upscaler
import ca.mpreg.webgpuviewer.renderer.UpscalerArtCnn
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.Companion.dispatcher
import ca.mpreg.webgpuviewer.renderer.endAndRelease
import ca.mpreg.webgpuviewer.transition.Transition
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

open class ImageViewerState(var isVertical: Boolean = false, var isReversed: Boolean = false) {
    val renderer = WebGpuRenderer()

    internal val tiles = TileRenderer { invalidate() }

    /**
     * How a high-quality tile that magnifies the page is resized - see [Rescaler]. Assigning
     * drops the tiles already generated. [UpscalerArtCnn] doubles through a network first.
     */
    var upscaler: Upscaler
        get() = tiles.upscaler
        set(value) {
            tiles.upscaler = value
        }

    /** How a high-quality tile that shrinks the page is resized. [DownscalerBox] is the only one. */
    var downscaler: Downscaler
        get() = tiles.downscaler
        set(value) {
            tiles.downscaler = value
        }

    /**
     * Post-processing over the finished frame - assign [FilterChain.filters] to run some. Wired
     * to [invalidate] here, so changing a filter's settings redraws by itself.
     */
    val filters: FilterChain = renderer.filters.also { chain ->
        chain.onInvalidate = { invalidate() }
    }

    var scope: CoroutineScope? = null

    var animationJob: Job? = null

    val width get() = renderer.width
    val height get() = renderer.height

    /** Top padding clearing the display cutout, in pixels. Set by ImageViewer while [avoidCutout]. */
    var cutoutTopPx: Float = 0f

    val viewportHeight: Float
        get() = height - if (avoidCutout) cutoutTopPx else 0f

    var dpi = Resources.getSystem().displayMetrics.densityDpi / 100f
    var density: Density =
        Density(density = Resources.getSystem().displayMetrics.density, fontScale = 1f)

    /** When true, images will be positioned/scaled to avoid the display cutout. */
    var avoidCutout: Boolean by mutableStateOf(false)

    /** Shift below the cutout always, rather than only when the image would overlap it. */
    var alwaysAvoidCutout: Boolean by mutableStateOf(false)

    private var suppressPageChange = false

    var pageOffset = 0f
        set(value) {
            var v = if (!value.isNaN() && !value.isInfinite()) value else 0f
            v = v.coerceIn(-10f, 10f)
            var pageDelta = 0

            if (!suppressPageChange) {
                var guard = 0
                while (v >= 1f && haveNext && guard++ < 20) {
                    pageDelta += 1
                    v -= 1f
                }
                guard = 0
                while (v <= -1f && havePrev && guard++ < 20) {
                    pageDelta -= 1
                    v += 1f
                }
            }

            // Numeric test first: haveNext/havePrev reach fetchPage, which resolves a page and
            // may trim a cache to do it, and every animation frame sets this with |v| under 1.
            if (v > 1f && !haveNext) v = 1f
            if (v < -1f && !havePrev) v = -1f

            val settling = field != 0f && v == 0f

            field = v

            if (pageDelta != 0) {
                notifyPageChange(if (isReversed) -pageDelta else pageDelta)
            }

            // Rotate rather than invalidate: onPageChange already moved what backs getPage, so a
            // slot often holds a valid render of the new current page.
            if (settling) {
                val current = try { getPage(0) } catch (_: Throwable) { null }
                if (current != null) try { Transition.rotateCacheOnPageChange(current) } catch (_: Throwable) {} else try { Transition.invalidateCache() } catch (_: Throwable) {}
            }
        }

    private fun setPageOffsetDirect(value: Float) {
        suppressPageChange = true
        pageOffset = value
        suppressPageChange = false
    }

    private var turn = 0

    fun animatePageTurn(direction: Int) {
        animationJob?.cancel()
        if (!transition.isAnimated) {
            // No-animation transition: cut straight to the new page - the caller already
            // swapped it in, so there is nothing to slide from and transitionFromPage is moot.
            transitionFromPage = null
            invalidate()
            return
        }
        // cancel() doesn't wait: a turn started while the last one unwinds would otherwise have
        // its own transitionFromPage - set just before this call - cleared by that finally.
        val id = ++turn
        animationJob = scope?.launch {
            setPageOffsetDirect(direction.toFloat())
            invalidate()
            try {
                Animatable(direction.toFloat()).animateTo(
                    0f, animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.002f
                    )
                ) {
                    setPageOffsetDirect(value)
                    invalidate()
                }
            } finally {
                // Cancelled or not: getPage provides the right page from here.
                if (turn == id) transitionFromPage = null
            }
            // Outside the try - a cancelled turn leaves the offset to its successor.
            setPageOffsetDirect(0f)
            invalidate()
        }
    }

    val havePrev get() = getPage(if (isReversed) 1 else -1) != null
    val haveNext get() = getPage(if (isReversed) -1 else 1) != null

    var fetchPage: ((Int) -> ImagePage?)? = null

    /** Once per outage, on the render dispatcher - see [FrameResult.Unavailable]. */
    var onRenderUnavailable: ((String?) -> Unit)? = null

    var onPageChange: ((Int) -> Unit)? = null
    var onTap: ((Offset) -> Unit)? = null
    var onLongTap: ((Offset) -> Unit)? = null

    /**
     * Absolute page index for [captureAnchor]/[applyAnchor]. Seeded app-side via
     * [seedPageIndex] (the host owns chapter page identity), then advanced by the
     * signed deltas passed to [notifyPageChange]. [PageAnchor.UNSET_INDEX] until seeded.
     */
    @Volatile
    var absolutePageIndex: Int = PageAnchor.UNSET_INDEX
        private set

    /** Host hook: last visible position after each coalesced [emitReaderState]. */
    @Volatile
    var onReaderStateChanged: OnReaderStateChanged? = null

    @Volatile
    private var lastReadyAtMs = 0L

    /** Adopt the host's absolute index for the page now current (or about to be reported). */
    fun seedPageIndex(index: Int) {
        if (index < 0) return
        absolutePageIndex = index
    }

    /**
     * Single path for every whole-page crossing: keeps [absolutePageIndex] in lockstep
     * with the host's chain, invokes [onPageChange], then force-emits a [ReaderState.Ready].
     * Paged already resolves isReversed before calling; continuous passes raw scroll sign.
     */
    protected fun notifyPageChange(signedDelta: Int) {
        if (absolutePageIndex != PageAnchor.UNSET_INDEX) {
            absolutePageIndex = (absolutePageIndex + signedDelta).coerceAtLeast(0)
        }
        try {
            onPageChange?.invoke(signedDelta)
        } catch (e: Throwable) {
            android.util.Log.w("ImageViewerState", "onPageChange failed", e)
        }
        emitReaderState(force = true)
    }

    /**
     * Coalesced Ready emission: at most one per [READER_STATE_THROTTLE_MS] unless [force]
     * (a page crossing always forces so display/progress never lags a turn). Safe under
     * continuous's scrollLock - [captureAnchor] re-enters the same monitor on one thread.
     */
    fun emitReaderState(force: Boolean = false) {
        val listener = onReaderStateChanged ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastReadyAtMs < READER_STATE_THROTTLE_MS) return
        lastReadyAtMs = now
        try {
            listener.invoke(ReaderState.Ready(captureAnchor()))
        } catch (e: Throwable) {
            android.util.Log.w("ImageViewerState", "onReaderStateChanged failed", e)
        }
    }

    /**
     * Mode-agnostic reading-position snapshot. Base (paged): absolute index plus the
     * current page's zoom/pan; document scroll fields stay at defaults. Continuous
     * overrides with documentY/fraction under scrollLock.
     */
    open fun captureAnchor(): PageAnchor {
        val page = try { getPage(0) } catch (_: Throwable) { null }
        return PageAnchor(
            pageIndex = absolutePageIndex.coerceAtLeast(0),
            documentY = 0f,
            offsetX = page?.x ?: 0f,
            scale = page?.scale ?: 1f,
            fraction = 0f,
        ).sanitized()
    }

    /**
     * Apply a prior [captureAnchor] to the surface now live. Base seeds the index and
     * restores zoom/pan on the current page (page navigation stays host-owned).
     * Continuous overrides with a full document restore. Returns false when no page
     * is available yet (nothing applied).
     */
    open fun applyAnchor(anchor: PageAnchor): Boolean {
        if (anchor.pageIndex >= 0) seedPageIndex(anchor.pageIndex)
        val a = anchor.sanitized()
        val page = try { getPage(0) } catch (_: Throwable) { null } ?: return false
        return try {
            page.scale = a.scale.coerceIn(page.minScale, page.maxScale)
            val minX = page.minX(page.scale)
            val maxX = page.maxX(page.scale)
            page.x = a.offsetX.coerceIn(minX, maxX)
            invalidate()
            emitReaderState(force = true)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * When false, a double tap (and double-tap-drag) does not zoom: the first tap
     * resolves as a single tap immediately instead of waiting out the pair window.
     * Default true preserves built-in behavior.
     */
    var doubleTapZoomEnabled: Boolean = true

    /** Override for the "from" page during far navigation animation */
    var transitionFromPage: ImagePage? = null

    // One instance for this state's lifetime, so [cleanup] can tell its own from a successor's.
    private val invalidateCallback: () -> Unit = { invalidate() }

    /**
     * The page [index] steps from current. [isReversed] plays no part here - [fetchPage] and
     * [onPageChange] are what decide what a step actually means.
     */
    fun getPage(index: Int): ImagePage? {
        return fetchPage?.invoke(index)?.also { it.attach(this, scope, invalidateCallback) }
    }

    /**
     * What the last [captureRenderState] drew with, and so what [ImagePage.isOnScreen] answers
     * from. Off the last frame, not re-fetched: changing which pages show draws its own frame.
     */
    @Volatile
    protected var onScreenPages: List<ImagePage> = emptyList()

    internal fun isOnScreen(page: ImagePage): Boolean = onScreenPages.any { it.covers(page) }

    suspend fun prepareForSurface() {
        tiles.awaitCleanup()
    }

    @Synchronized
    fun init(scope: CoroutineScope, surface: Surface, width: Int, height: Int) {
        try {
            onReaderStateChanged?.invoke(ReaderState.Idle)
        } catch (_: Throwable) {}
        this.renderer.init(scope, surface, width, height)
        this.scope = scope
        Hdr.requestFrame = invalidateCallback

        // On [dispatcher] and drained under the lock, as [post] itself would have run them.
        scope.launch(dispatcher) {
            val pending = synchronized(this@ImageViewerState) {
                _postInit.toList().also { _postInit.clear() }
            }
            pending.forEach { it() }
        }
    }

    @Synchronized
    fun resize(width: Int, height: Int) {
        renderer.resize(width, height)
        invalidate()
    }

    var firstPos = Offset.Zero
    var currentPos = Offset.Zero

    var transition: Transition = if (isVertical) TransitionBasic.Vertical else TransitionBasic

    // Anything changed since the last frame, however many invalidates said so.
    private val dirty = AtomicBoolean(true)

    @Volatile
    private var reportedUnavailable = false

    // Wakes [collect] when there is nothing to draw. Buffered, so no send races [dirty]'s check.
    private val renderWake = Channel<Unit>(Channel.CONFLATED)

    fun invalidate() {
        dirty.set(true)
        renderWake.trySend(Unit)
    }

    /**
     * Draw at most one frame per display frame, for as long as anything is [dirty].
     *
     * A frame loop, not a pass per invalidate: a fling step, a fade step, a decode and the tile
     * worker all land in one frame. [renderWake] carries no count, so a draw clearing [dirty]
     * doesn't leave that frame's remaining invalidates to wait out a frame each.
     *
     * Nothing may be awaited between the frame wait and the capture, the draw included: this
     * registers as an awaiter mid-draw to land in the next frame's batch, behind the animation,
     * and registering after that batch has gone out costs a frame every frame.
     */
    suspend fun collect() = coroutineScope {
        val frameClock = currentCoroutineContext()[MonotonicFrameClock]
        var drawing: Job? = null
        while (true) {
            // Paced either way: called without a clock, the loop would spin on whatever is dirty.
            if (frameClock != null) frameClock.withFrameNanos { } else delay(8)
            // Still drawing: leave [dirty] set for the next frame.
            if (drawing?.isActive == true) continue
            // Anything invalidating from here belongs to the next frame.
            if (!dirty.getAndSet(false)) {
                renderWake.receive()
                continue
            }
            // Drop the wake this frame's invalidate left, or going idle costs a spurious one.
            renderWake.tryReceive()
            // Capture render state on main thread before any thread switching
            val snapshot = captureRenderState()
            if (snapshot == null) {
                // No page to draw yet: back off instead of spinning a vsync-rate
                // capture loop. Coalescing is preserved - dirty was already cleared
                // above, and any invalidate during the delay re-sets it with a
                // buffered wake, so nothing is dropped.
                delay(IDLE_BACKOFF_MS)
                continue
            }
            // Now render on GPU thread with captured state
            drawing = launch(dispatcher) {
                when (renderer.render { encoder, texture ->
                    renderSnapshot(encoder, texture, snapshot)
                }) {
                    FrameResult.Drawn -> reportedUnavailable = false

                    FrameResult.Retry -> invalidate()

                    // No invalidate: leaving [dirty] clear parks the loop on [renderWake] rather
                    // than spinning the frame clock, and a later resize still wakes it.
                    FrameResult.Unavailable -> if (!reportedUnavailable) {
                        reportedUnavailable = true
                        val reason = renderer.unavailableReason ?: "no render surface"
                        Log.e("ImageViewerState", "Nothing can be drawn: $reason")
                        onRenderUnavailable?.runCatching { invoke(reason) }
                    }
                }
            }
        }
    }

    protected open fun captureRenderState(): Any? {
        val currentPage = getPage(0) ?: return null
        val offset = pageOffset
        val adjacentPage = when {
            offset == 0f -> null
            // Use override if set (for far navigation)
            transitionFromPage != null -> transitionFromPage
            offset > 0f -> getPage(if (isReversed) -1 else 1)
            else -> getPage(if (isReversed) 1 else -1)
        }
        // Only used to pre-warm the transition cache while at rest (see renderSnapshot), so
        // there's no need to look it up while a turn is already in progress. Both neighbors:
        // turning back shows the previous page's tiles just as live as turning forward.
        val nextPage = if (offset == 0f) getPage(1) else null
        val prevPage = if (offset == 0f) getPage(-1) else null
        onScreenPages = listOfNotNull(currentPage, adjacentPage)
        return RenderSnapshot(
            currentPage, adjacentPage, nextPage, prevPage, offset, transition, firstPos, currentPos
        )
    }

    private class RenderSnapshot(
        val currentPage: ImagePage,
        val adjacentPage: ImagePage?,
        val nextPage: ImagePage?,
        val prevPage: ImagePage?,
        val offset: Float,
        val transition: Transition,
        val firstPos: Offset,
        val currentPos: Offset,
    )

    /**
     * Run [block] against a render pass over [texture], ending it afterwards either way - a draw
     * that throws still closes the pass, and [WebGpuRenderer.render] makes it a dropped frame.
     * Owned here because only the whole frame's contents decide where a pass starts and ends.
     *
     * Always clears: `getCurrentTexture` rotates buffers, so loading would show stale content.
     */
    protected fun renderPass(
        encoder: GPUCommandEncoder,
        texture: GPUTexture,
        clearColor: Int = 0,
        block: (GPURenderPassEncoder) -> Unit
    ) {
        val targetView = texture.createView()
        val pass = encoder.beginRenderPass(
            GPURenderPassDescriptor(
                colorAttachments = arrayOf(
                    GPURenderPassColorAttachment(
                        view = targetView,
                        loadOp = LoadOp.Clear,
                        storeOp = StoreOp.Store,
                        clearValue = GPUColor(
                            ((clearColor shr 16) and 0xFF) / 255.0,
                            ((clearColor shr 8) and 0xFF) / 255.0,
                            (clearColor and 0xFF) / 255.0,
                            ((clearColor ushr 24) and 0xFF) / 255.0,
                        )
                    )
                ),
                // Fresh each frame: the tile blit marks what it covered so masked draws skip
                // re-shading it. Discarded afterward - nothing reads it across frames.
                depthStencilAttachment = GPURenderPassDepthStencilAttachment(
                    view = tiles.stencilViewFor(texture),
                    stencilLoadOp = LoadOp.Clear,
                    stencilStoreOp = StoreOp.Discard,
                    stencilClearValue = 0,
                )
            )
        )
        try {
            block(pass)
        } finally {
            pass.endAndRelease(targetView)
        }
    }

    protected open suspend fun renderSnapshot(
        encoder: GPUCommandEncoder, texture: GPUTexture, snapshot: Any
    ) {
        val s = snapshot as RenderSnapshot
        tiles.newFrame()
        val page = s.currentPage

        if (s.adjacentPage != null && s.offset != 0f) {
            s.transition.render(
                page, s.adjacentPage, encoder, texture, s.offset, s.firstPos, s.currentPos, tiles
            )
            return
        }

        val covered = page.drawLive(encoder, texture, tiles)

        // Opportunistic: once the current page's tiles settle, prewarm both neighbors'
        // too, so a transition into either starts already mostly sharp
        // (Transition.getCachedTexture seeds itself and layers tiles in live, so this no
        // longer needs to be complete first). Warming the previous page as well is what
        // keeps scrolling back from flashing half-generated tiles. Gated on atHome since
        // the cache is keyed by (x, y, scale).
        if (covered && page is ImagePage.ImageSingle && page.atHome) {
            listOfNotNull(s.nextPage, s.prevPage).forEach { neighbor ->
                val single = neighbor as? ImagePage.ImageSingle
                if (single != null && single.highQuality && !single.isAnimated && single.atHome) {
                    tiles.prewarm(single, texture)
                }
            }
        }
    }

    private val _postInit = mutableListOf<(suspend () -> Unit)>()

    var showTileHud by mutableStateOf(false)

    /** In viewer-Box coordinates (TileHud's boundsInParent) - for gesture hit-testing. */
    @Volatile
    var tileHudBounds: Rect? = null

    fun hitsTileHud(pos: Offset): Boolean =
        showTileHud && tileHudBounds?.contains(pos) == true

    internal suspend fun residencySnapshot(): List<TileRenderer.GridResidency> =
        withContext(dispatcher) { tiles.residency() }

    internal suspend fun gridTraceSnapshot(): List<TileRenderer.GridChangeEvent> =
        withContext(dispatcher) { tiles.changeTrace() }

    fun dumpGridTrace() {
        CoroutineScope(dispatcher).launch { tiles.dumpTrace() }
    }

    @Synchronized
    fun post(fn: suspend () -> Unit) {
        val activeScope = scope
        if (activeScope?.isActive == true) {
            activeScope.launch(dispatcher) {
                fn()
            }
        } else {
            _postInit.add(fn)
        }
    }

    /**
     * Recreate the device and discard all device-bound viewer state before the next frame.
     * Returns true when the replacement device is available, even if the platform surface must
     * wait for the next surface callback.
     */
    suspend fun recoverFromDeviceLoss(): Boolean {
        tiles.recoverFromDeviceLoss()
        if (!renderer.recoverFromDeviceLoss()) return false
        invalidate()
        return true
    }

    fun cleanup() {
        animationJob?.cancel()
        // Held by an object that outlives this state, so it has to be dropped by hand - but only
        // if it is still ours: a replacement viewer inits before the one it replaces cleans up.
        if (Hdr.requestFrame === invalidateCallback) Hdr.requestFrame = null
        tiles.cleanup()
        renderer.cleanup()
        try {
            onReaderStateChanged?.invoke(ReaderState.Released)
        } catch (_: Throwable) {}
    }

    companion object {
        /** Idle pause when collect() has no page to draw - keeps it off a vsync-rate spin. */
        const val IDLE_BACKOFF_MS = 32L

        /** Coalescing window for non-forced [emitReaderState] (scroll settles). */
        const val READER_STATE_THROTTLE_MS = 500L
    }
}