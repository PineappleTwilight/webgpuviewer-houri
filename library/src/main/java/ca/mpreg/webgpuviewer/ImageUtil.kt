package ca.mpreg.webgpuviewer

import java.nio.ByteBuffer

object ImageUtil {
    @Volatile
    private var libraryLoaded = false
    @Volatile
    private var libraryLoadError: Throwable? = null

    init {
        try {
            System.loadLibrary("resize")
            libraryLoaded = true
        } catch (e: Throwable) {
            libraryLoadError = e
            android.util.Log.e("ImageUtil", "Failed to load resize library", e)
        }
    }

    fun isAvailable(): Boolean = libraryLoaded && libraryLoadError == null

    fun requireAvailable() {
        check(isAvailable()) { "resize native library not loaded: $libraryLoadError" }
    }

    external fun resizeLinearAreaNative(
        pixels: ByteBuffer,
        dstPixels: ByteBuffer,
        width: Int,
        height: Int
    )

    fun resize(source: ByteBuffer, width: Int, height: Int): ByteBuffer {
        requireAvailable()
        require(width >= 8 && height >= 8) { "resize: dimensions must be >=8, got ${width}x$height (avoids gralloc 0x3b)" }
        require(width <= 8192 && height <= 8192) { "resize: dimensions too large ${width}x$height" }
        require(width in 8..16384 && height in 8..16384) { "resize: non-finite dimensions ${width}x$height" }
        require(source.isDirect) { "resize: source must be direct ByteBuffer" }
        val srcNeeded = width.toLong() * height * 4L
        require(srcNeeded >= 0) { "resize: src overflow ${width}x$height" }
        require(source.capacity().toLong() >= srcNeeded) { "resize: source capacity ${source.capacity()} < needed $srcNeeded" }
        if (srcNeeded > 64L * 1024 * 1024) throw IllegalArgumentException("resize: src too large ${width}x$height")
        val dstWidth = (width / 2).coerceAtLeast(8)
        val dstHeight = (height / 2).coerceAtLeast(8)
        if (dstWidth < 8 || dstHeight < 8) throw IllegalArgumentException("resize: dst too small ${dstWidth}x$dstHeight")
        val dstNeeded = dstWidth.toLong() * dstHeight * 4L
        require(dstNeeded >= 0) { "resize: dst overflow ${dstWidth}x$dstHeight" }
        require(dstNeeded <= 16L * 1024 * 1024) { "resize: dst too large ${dstWidth}x$dstHeight" }
        val output = try {
            ByteBuffer.allocateDirect(dstNeeded.toInt())
        } catch (e: OutOfMemoryError) {
            System.gc()
            try { ByteBuffer.allocateDirect(dstNeeded.toInt()) } catch (_: Throwable) { throw e }
        }
        try {
            resizeLinearAreaNative(source, output, width, height)
        } catch (e: OutOfMemoryError) {
            System.gc()
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("resizeLinearAreaNative failed for ${width}x$height -> ${dstWidth}x$dstHeight: ${e.message}", e)
        }
        if (output.capacity() < dstNeeded) throw IllegalStateException("resize: output capacity mismatch")
        return output
    }


}
