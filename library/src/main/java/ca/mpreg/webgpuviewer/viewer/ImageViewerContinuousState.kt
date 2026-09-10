package ca.mpreg.webgpuviewer.viewer

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.ui.util.fastCoerceIn
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPUTexture
import ca.mpreg.webgpuviewer.draw.Draw
import ca.mpreg.webgpuviewer.draw.clear
import ca.mpreg.webgpuviewer.renderer.RenderPage
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import ca.mpreg.webgpuviewer.renderer.solveImagePlacement
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

class ImageViewerContinuousState : ImageViewerState(isVertical = true) {
    companion object {
        const val MAX_VISIBLE_PAGES = 4
        const val MAX_PAGE_WALK = 64
    }

    data class ContinuousPosition(
        val documentY: Float,
        val scale: Float = 1f,
        val offsetX: Float = 0f,
        val pageIndexHint: Int = -1,
        val fractionWithinPage: Float = 0f,
        val timestamp: Long = System.currentTimeMillis(),
    )

    var scale = 1f
        set(value) {
            val v = if (!value.isNaN() && !value.isInfinite()) value else 1f
            val clamped = v.fastCoerceIn(minScale, maxScale)
            if (clamped == field) return
            field = clamped
            invalidate()
        }

    var offsetX = 0f
        set(value) {
            val v = if (!value.isNaN() && !value.isInfinite()) value else 0f
            val maxOffsetX = max(0f, (scale - 1f) / (2f * scale))
            val clamped = v.fastCoerceIn(-maxOffsetX, maxOffsetX)
            if (clamped == field) return
            field = clamped
            invalidate()
        }

    var minZoomWidthFraction: Float = 1f
        set(value) {
            val clamped = value.fastCoerceIn(0.01f, 1f)
            if (clamped == field) return
            field = clamped
            if (scale < clamped) scale = clamped
            invalidate()
        }

    val minScale: Float get() = minZoomWidthFraction
    val doubleTapScale: Float get() = minScale * 2f
    val maxScale: Float get() = max(doubleTapScale * 2f, 4f)

    @Volatile
    var isScaleAnimating: Boolean = false

    @Volatile
    var isFlinging: Boolean = false

    @Volatile
    private var isRestoring: Boolean = false

    private val scrollLock = Any()

    private var scrollYInternal: Double = 0.0
    private var anchorDocYInternal: Double = 0.0
    private var lastWidth: Int = -1

    var scrollY: Float
        get() = synchronized(scrollLock) { scrollYInternal.toFloat() }
        private set(value) {
            synchronized(scrollLock) { scrollYInternal = value.toDouble() }
        }

    private var slideOffset = 0f

    fun getPageHeight(page: ImagePage): Float {
        if (page !is ImagePage.ImageSingle) return page.height.toFloat()
        val pageWidth = page.width
        if (pageWidth <= 0) return page.height.toFloat()
        if (width <= 0) return page.height.toFloat()
        return page.height * (width.toFloat() / pageWidth)
    }

    private var currentPageHeight: Float? = null
    private var pendingRestore: ContinuousPosition? = null

    var onPageScrolledThrough: ((ImagePage) -> Unit)? = null
    private var lastScrolledThrough: ImagePage? = null

    @Volatile
    var pagesBelow: Int = 0
        private set

    @Volatile
    var pagesAbove: Int = 0
        private set

    private var anchorDocY: Float
        get() = synchronized(scrollLock) { anchorDocYInternal.toFloat() }
        set(value) { synchronized(scrollLock) { anchorDocYInternal = value.toDouble() } }

    fun scrollBy(deltaPixels: Float) {
        if (!deltaPixels.isFinite()) return
        val delta = deltaPixels.toDouble()
        if (abs(delta) < 0.001) return
        synchronized(scrollLock) {
            getPage(0) ?: return
            slideOffset = 0f

            scrollYInternal += delta

            var guard = 0
            while (scrollYInternal < 0 && guard++ < MAX_PAGE_WALK) {
                if (getPage(-1) == null) {
                    scrollYInternal = 0.0
                    break
                }
                if (!isRestoring) {
                    try { onPageChange?.invoke(-1) } catch (_: Throwable) {}
                }
                val newPage = getPage(0) ?: return
                val newHeight = getPageHeight(newPage).toDouble()
                anchorDocYInternal -= newHeight
                currentPageHeight = newHeight.toFloat()
                if (newHeight <= 0.0) {
                    scrollYInternal = 0.0
                    break
                }
                scrollYInternal += newHeight
            }

            guard = 0
            while (guard++ < MAX_PAGE_WALK) {
                val page = getPage(0) ?: return
                val pageHeight = getPageHeight(page).toDouble()
                if (scrollYInternal <= pageHeight || pageHeight <= 0.0) break
                if (getPage(1) == null) {
                    scrollYInternal = pageHeight
                    break
                }
                if (!isRestoring) {
                    try { onPageChange?.invoke(1) } catch (_: Throwable) {}
                }
                anchorDocYInternal += pageHeight
                val newPage = getPage(0) ?: return
                currentPageHeight = getPageHeight(newPage).toFloat()
                scrollYInternal -= pageHeight
            }

            clampToDocumentEndLocked()
            if (scrollYInternal.isNaN() || scrollYInternal.isInfinite()) scrollYInternal = 0.0
            if (anchorDocYInternal.isNaN() || anchorDocYInternal.isInfinite()) anchorDocYInternal = 0.0
        }
    }

    private fun maxScrollYLocked(): Double? {
        val viewportHeight = if (scale.isFinite() && scale > 0f) height / scale.toDouble() else height.toDouble()
        var bottom = 0.0
        for (i in 0..MAX_VISIBLE_PAGES) {
            val page = getPage(i) ?: return bottom - viewportHeight
            val pageHeight = getPageHeight(page).toDouble()
            if (pageHeight <= 0.0) break
            bottom += pageHeight
            if (bottom - viewportHeight > scrollYInternal) break
        }
        return null
    }

    private fun clampToDocumentEndLocked() {
        var guard = 0
        while (guard++ < MAX_PAGE_WALK) {
            val max = maxScrollYLocked() ?: return
            if (scrollYInternal <= max) return
            if (max >= 0.0) {
                scrollYInternal = max
                return
            }
            if (getPage(-1) == null) {
                scrollYInternal = 0.0
                return
            }
            if (!isRestoring) {
                try { onPageChange?.invoke(-1) } catch (_: Throwable) {}
            }
            val newPage = getPage(0) ?: return
            val newHeight = getPageHeight(newPage).toDouble()
            anchorDocYInternal -= newHeight
            currentPageHeight = newHeight.toFloat()
            if (newHeight <= 0.0) {
                scrollYInternal = 0.0
                return
            }
            scrollYInternal = max + newHeight
        }
    }

    val documentY: Float get() = synchronized(scrollLock) { (anchorDocYInternal + scrollYInternal).toFloat() }

    val documentYDouble: Double get() = synchronized(scrollLock) { anchorDocYInternal + scrollYInternal }

    fun scrollTo(docY: Float) {
        if (!docY.isFinite()) return
        synchronized(scrollLock) { scrollBy((docY.toDouble() - (anchorDocYInternal + scrollYInternal)).toFloat()) }
    }

    fun scrollToDouble(docY: Double) {
        if (!docY.isFinite()) return
        synchronized(scrollLock) { scrollBy((docY - (anchorDocYInternal + scrollYInternal)).toFloat()) }
    }

    fun savePosition(): ContinuousPosition = synchronized(scrollLock) {
        val docY = (anchorDocYInternal + scrollYInternal).toFloat()
        val page = getPage(0)
        val pageHeight = page?.let { getPageHeight(it) } ?: 0f
        val fraction = if (pageHeight > 0f) (scrollYInternal / pageHeight).toFloat().coerceIn(0f, 1f) else 0f
        val hint = try { getPageIndexForDocumentYLocked(docY) } catch (_: Throwable) { -1 }
        ContinuousPosition(
            documentY = docY,
            scale = scale,
            offsetX = offsetX,
            pageIndexHint = hint,
            fractionWithinPage = fraction,
        )
    }

    fun restorePosition(pos: ContinuousPosition, animate: Boolean = false) {
        if (!pos.documentY.isFinite() || !pos.scale.isFinite() || !pos.offsetX.isFinite()) return
        synchronized(scrollLock) {
            if (getPage(0) == null) {
                pendingRestore = pos
                return
            }
            applyRestoreLocked(pos, animate)
        }
    }

    private fun applyRestoreLocked(pos: ContinuousPosition, animate: Boolean) {
        isRestoring = true
        try {
            val targetDocY = resolveDocumentYForRestore(pos)
            if (animate) {
                val delta = (targetDocY - (anchorDocYInternal + scrollYInternal)).toFloat()
                // launch animation outside lock
                val jobScale = pos.scale.fastCoerceIn(minScale, maxScale)
                val jobOffsetX = pos.offsetX
                synchronized(scrollLock) { pendingRestore = null }
                animateScroll(delta)
                scale = jobScale
                offsetX = jobOffsetX
            } else {
                scrollBy((targetDocY - (anchorDocYInternal + scrollYInternal)).toFloat())
                scale = pos.scale.fastCoerceIn(minScale, maxScale)
                val maxOffsetX = max(0f, (scale - 1f) / (2f * scale))
                offsetX = pos.offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                pendingRestore = null
            }
        } finally {
            isRestoring = false
        }
        invalidate()
    }

    private fun resolveDocumentYForRestore(pos: ContinuousPosition): Double {
        if (pos.pageIndexHint >= 0) {
            val byPage = documentYForPageIndex(pos.pageIndexHint, pos.fractionWithinPage)
            if (byPage != null && byPage.isFinite()) return byPage
        }
        return pos.documentY.toDouble().coerceIn(-1e9, 1e9)
    }

    private fun getPageIndexForDocumentYLocked(docY: Float): Int {
        var y = anchorDocYInternal
        var idx = 0
        var guard = 0
        while (guard++ < MAX_PAGE_WALK) {
            val page = getPage(idx) ?: break
            val h = getPageHeight(page).toDouble()
            if (h <= 0.0) break
            if (docY < y + h) return idx
            y += h
            idx++
        }
        // search backward
        y = anchorDocYInternal
        idx = 0
        guard = 0
        while (guard++ < MAX_PAGE_WALK) {
            if (docY >= y) return idx
            val prev = getPage(idx - 1) ?: break
            val h = getPageHeight(prev).toDouble()
            if (h <= 0.0) break
            y -= h
            idx--
        }
        return 0
    }

    fun documentYForPageIndex(pageIndex: Int, fraction: Float = 0f): Double? {
        val clampedFraction = fraction.coerceIn(0f, 1f)
        synchronized(scrollLock) {
            val currentIdx = getCurrentPageIndexLocked() ?: return null
            val deltaPages = pageIndex - currentIdx
            if (deltaPages == 0) {
                val h = getPage(0)?.let { getPageHeight(it).toDouble() } ?: return null
                return anchorDocYInternal + h * clampedFraction
            }
            var docY = anchorDocYInternal
            if (deltaPages > 0) {
                for (i in 0 until deltaPages) {
                    val p = getPage(i) ?: return null
                    docY += getPageHeight(p).toDouble()
                }
                val targetPage = getPage(deltaPages) ?: return null
                docY += getPageHeight(targetPage).toDouble() * clampedFraction
            } else {
                for (i in deltaPages until 0) {
                    val p = getPage(i) ?: return null
                    docY += getPageHeight(p).toDouble()
                }
                val targetPage = getPage(deltaPages) ?: return null
                val h = getPageHeight(targetPage).toDouble()
                docY += h * clampedFraction
                // Adjust because anchor is top of page 0, not target
                // We already summed heights from anchor, so docY is correct
            }
            return docY
        }
    }

    fun getCurrentPageIndexLocked(): Int? {
        var y = anchorDocYInternal
        val docY = anchorDocYInternal + scrollYInternal
        var idx = 0
        var guard = 0
        while (guard++ < MAX_PAGE_WALK) {
            val page = getPage(idx) ?: break
            val h = getPageHeight(page).toDouble()
            if (h <= 0.0) break
            if (docY < y + h) return idx
            y += h
            idx++
        }
        return 0
    }

    fun getCurrentPageIndex(): Int = synchronized(scrollLock) { getCurrentPageIndexLocked() ?: 0 }

    fun getFractionWithinPage(): Float = synchronized(scrollLock) {
        val page = getPage(0) ?: return 0f
        val h = getPageHeight(page)
        if (h <= 0f) return 0f
        return (scrollYInternal / h).toFloat().coerceIn(0f, 1f)
    }

    fun scrollToPage(pageIndex: Int, fraction: Float = 0f) {
        val target = documentYForPageIndex(pageIndex, fraction) ?: return
        scrollToDouble(target)
    }

    fun resetScroll() {
        synchronized(scrollLock) {
            scrollYInternal = 0.0
            currentPageHeight = null
            pendingRestore = null
        }
    }

    fun animateSlideIn(direction: Int) {
        animationJob?.cancel()
        animationJob = scope?.launch {
            try {
                animate(
                    direction * height / 2f, 0f, animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.5f
                    )
                ) { value, _ ->
                    slideOffset = value
                    invalidate()
                }
            } finally {
                if (animationJob === coroutineContext[Job]) {
                    slideOffset = 0f
                    invalidate()
                }
            }
        }
    }

    fun animateScroll(deltaPixels: Float) {
        if (!deltaPixels.isFinite()) return
        animationJob?.cancel()
        animationJob = scope?.launch {
            var lastValue = 0f
            animate(
                0f, deltaPixels, animationSpec = spring(
                    stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.002f
                )
            ) { value, _ ->
                scrollBy(value - lastValue)
                lastValue = value
                invalidate()
            }
        }
    }

    private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()
    private fun Double.isFinite(): Boolean = !isNaN() && !isInfinite()

    private class VisiblePage(val page: ImagePage, val docTop: Float, val pageHeight: Float)

    private class ContinuousRenderSnapshot(
        val pages: List<VisiblePage>,
        val scale: Float,
        val offsetX: Float,
        val cameraDocY: Float,
        val suppressGeneration: Boolean,
    )

    override fun captureRenderState(): Any = synchronized(scrollLock) {
        val screenH = height.toFloat()
        val page0 = getPage(0)

        if (lastWidth != width) {
            if (lastWidth > 0 && page0 != null && currentPageHeight != null && currentPageHeight!! > 0f) {
                val oldH = currentPageHeight!!
                val newH = getPageHeight(page0)
                if (newH > 0f && oldH > 0f) {
                    val fraction = (scrollYInternal / oldH).coerceIn(0.0, 1.0)
                    scrollYInternal = fraction * newH
                }
            }
            lastWidth = width
        }

        pendingRestore?.let { pending ->
            val canRestore = getPage(0) != null && width > 0 && height > 0
            if (canRestore) {
                applyRestoreLocked(pending, animate = false)
            }
        }

        if (page0 != null) {
            val pageHeight = getPageHeight(page0)
            currentPageHeight?.let { h -> if (h > 0f && pageHeight > 0f) scrollYInternal *= pageHeight / h }
            if (pageHeight > 0f) currentPageHeight = pageHeight
            clampToDocumentEndLocked()
        }

        val y0 = if (page0 != null) (-scrollYInternal + slideOffset).toFloat() else 0f
        val cameraDocY = (anchorDocYInternal - y0 + 0.5 * screenH).toFloat()

        val pages = mutableListOf<VisiblePage>()
        val visTop = 0.5f * screenH - screenH / (2f * scale)
        val screenBot = 0.5f * screenH + screenH / (2f * scale)
        val visBot = screenBot + tiles.preferredTileSize / scale

        fun isScrolledThrough(top: Float, pageHeight: Float) =
            pageHeight > 0f && (top + pageHeight <= screenBot || top < visTop)

        var scrolledThrough: ImagePage? = null

        var yTop = y0
        var iBack = -1
        var docTopBack = anchorDocYInternal
        var above = 0
        while (yTop > visTop && iBack >= -MAX_VISIBLE_PAGES) {
            val page = getPage(iBack) ?: break
            above = -iBack
            val pageHeight = getPageHeight(page)
            docTopBack -= pageHeight.toDouble()
            yTop -= pageHeight
            if (scrolledThrough == null && isScrolledThrough(yTop, pageHeight)) scrolledThrough = page
            if (page.isDecoded) {
                pages.add(0, VisiblePage(page, docTopBack.toFloat(), pageHeight))
            }
            if (pageHeight <= 0f) break
            iBack--
        }

        var y = y0
        var i = 0
        var docTop = anchorDocYInternal
        var prevHeight = 0f
        var hasPrev = false
        var below = 0
        while (y < visBot && i <= MAX_VISIBLE_PAGES) {
            val page = getPage(i) ?: break
            below = i
            if (hasPrev) docTop += prevHeight.toDouble()
            hasPrev = true
            val pageHeight = getPageHeight(page)
            if (isScrolledThrough(y, pageHeight)) scrolledThrough = page
            if (y + pageHeight > visTop && page.isDecoded) {
                pages.add(VisiblePage(page, docTop.toFloat(), pageHeight))
            }
            if (pageHeight <= 0f) break
            prevHeight = pageHeight
            y += pageHeight
            i++
        }

        onScreenPages = pages.map { it.page }
        pagesBelow = below
        pagesAbove = above

        scrolledThrough?.takeIf { it !== lastScrolledThrough }?.let {
            lastScrolledThrough = it
            try { onPageScrolledThrough?.invoke(it) } catch (_: Throwable) {}
        }

        ContinuousRenderSnapshot(pages, scale, offsetX, cameraDocY, isScaleAnimating || isFlinging)
    }

    override suspend fun renderSnapshot(
        encoder: GPUCommandEncoder, texture: GPUTexture, snapshot: Any
    ) {
        val s = snapshot as ContinuousRenderSnapshot
        tiles.newFrame()
        if (s.pages.isEmpty()) return

        val hasImagePage = s.pages.any { it.page is ImagePage.ImageSingle }

        val dstW = texture.width.toFloat()
        val dstH = texture.height.toFloat()
        val anchorX = dstW / 2f + s.scale * (s.offsetX * dstW + WebGpuRenderer.offsetX * dstW)
        val anchorY = dstH / 2f - s.scale * s.cameraDocY + s.scale * WebGpuRenderer.offsetY * dstH

        if (hasImagePage) {
            renderPass(encoder, texture) { pass ->
                s.pages.forEach { vp ->
                    val page = vp.page as? ImagePage.ImageSingle ?: return@forEach
                    if (page.destroyed || !page.isDecoded || page.width <= 0) return@forEach

                    val pageScale = dstW / page.width

                    val covered = !page.isAnimated && tiles.draw(
                        pass,
                        page,
                        texture,
                        s.cameraDocY,
                        vp.docTop,
                        s.offsetX,
                        s.scale,
                        s.suppressGeneration
                    )
                    if (!covered) {
                        val imageScale = pageScale * s.scale
                        page.forEachImage { image, srcOffsetX ->
                            if (image.mipmaps.isEmpty()) return@forEachImage
                            val docCenterX = pageScale * (srcOffsetX + image.x)
                            val docCenterY = vp.docTop + 0.5f * vp.pageHeight + pageScale * image.y
                            val targetX = anchorX + s.scale * docCenterX
                            val targetY = anchorY + s.scale * docCenterY
                            val (x, y) = solveImagePlacement(
                                targetX, targetY, imageScale, image, dstW, dstH
                            )
                            if (page.isAnimated || page.highQuality) {
                                RenderPage.renderFast(pass, image, texture, x, y, imageScale)
                            } else {
                                RenderPage.renderFast(
                                    pass, image, texture, x, y, imageScale, linear = false
                                )
                            }
                        }
                    }

                    if (page.fade < 1f) {
                        val top = anchorY + s.scale * vp.docTop
                        page.drawFade(
                            pass,
                            (anchorX - s.scale * dstW / 2f) / dstW,
                            top / dstH,
                            (anchorX + s.scale * dstW / 2f) / dstW,
                            (top + s.scale * vp.pageHeight) / dstH
                        )
                    }
                }
            }
        } else {
            Draw.clear(encoder, texture, 0)
        }

        s.pages.forEach { vp ->
            if (vp.page is ImagePage.ImageSingle) return@forEach
            val page = vp.page as ImagePage.Render
            if (page.destroyed) return@forEach

            val renderScale = s.scale * page.scale
            val targetX = anchorX
            val targetY = anchorY + s.scale * (vp.docTop + 0.5f * vp.pageHeight)

            val x = (targetX - dstW / 2f) / (renderScale * dstW)
            val y = (targetY - dstH / 2f) / (renderScale * dstH)
            page.renderLoaded(encoder, x, y, renderScale, texture)
        }
    }
}
