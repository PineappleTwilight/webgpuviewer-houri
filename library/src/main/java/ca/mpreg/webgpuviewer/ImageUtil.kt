package ca.mpreg.webgpuviewer

import ca.mpreg.webgpuviewer.ImageUtil.applyGainmapNative
import ca.mpreg.webgpuviewer.ImageUtil.resize
import ca.mpreg.webgpuviewer.ImageUtil.resizeLinearAreaNative
import ca.mpreg.webgpuviewer.ImageUtil.rotateQuarterNative
import ca.mpreg.webgpuviewer.ImageUtil.toneMapToSdrNative
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

    /**
     * [resizeLinearAreaNative] over extended-sRGB RGBA half-float, for an HDR image's mipmaps.
     *
     * Same half-resolution box filter in linear light, but without the clamp to 1.0 the 8-bit
     * path ends on - that clamp is exactly what would flatten every highlight.
     */
    external fun resizeLinearAreaNativeF16(
        pixels: ByteBuffer,
        dstPixels: ByteBuffer,
        width: Int,
        height: Int
    )

    /**
     * For showing an HDR image on an SDR swapchain. The image's own brightest pixel lands on
     * white and the roll-off starts as late as that peak allows, so a dim HDR frame keeps
     * nearly all of its SDR range.
     */
    external fun toneMapToSdrNative(
        pixels: ByteBuffer,
        dstPixels: ByteBuffer,
        width: Int,
        height: Int,
    )

    /**
     * Combine an SDR base with its unapplied gain map into extended-sRGB half-float.
     *
     * Deliberately here rather than in the decoder: how much of a gain map to apply is a display
     * question. It also lets the arithmetic follow libultrahdr's `applyGain` exactly - the map
     * value is used *raw*, with no transfer function undone, which is where libvips's own
     * three-channel path goes wrong and tints the whole picture.
     *
     * [weight] scales the gain in log space for a display with less headroom than the content
     * carries; 1.0 applies the map in full.
     */
    external fun applyGainmapNative(
        base: ByteBuffer,
        gain: ByteBuffer,
        dstPixels: ByteBuffer,
        width: Int,
        height: Int,
        gainWidth: Int,
        gainHeight: Int,
        gainChannels: Int,
        gamma: FloatArray,
        minContentBoost: FloatArray,
        maxContentBoost: FloatArray,
        offsetSdr: FloatArray,
        offsetHdr: FloatArray,
        weight: Float,
    )

    /**
     * Scale extended-sRGB half-float [pixels] in place so the image's own brightest pixel lands
     * on [targetPeak]. Returns the peak it ends up with.
     *
     * For PQ and HLG. The peak is measured from the pixels rather than taken from the format:
     * PQ can encode 10000 nits and HLG 1000, but almost no real image comes near either, and
     * compressing from the format's ceiling left everything about five stops too dim.
     *
     * Values at or below SDR white are untouched, and an image already peaking under the target
     * is left alone rather than brightened to fill the headroom.
     */
    external fun scaleHdrPeakNative(
        pixels: ByteBuffer,
        width: Int,
        height: Int,
        targetPeak: Float,
    ): Float

    /**
     * Sized in [Long] and range-checked because the product overflows [Int] for a large enough
     * image, and an overflow landing on a small positive number is worse than one that throws:
     * the allocation succeeds undersized and the native pass writes past its end.
     */
    private fun directBuffer(width: Int, height: Int, bytesPerPixel: Int): ByteBuffer {
        val bytes = width.toLong() * height.toLong() * bytesPerPixel
        require(width > 0 && height > 0 && bytes in 1..Int.MAX_VALUE.toLong()) {
            "Cannot allocate ${width}x$height at $bytesPerPixel B/px"
        }
        return ByteBuffer.allocateDirect(bytes.toInt())
    }

    fun resize(source: ByteBuffer, width: Int, height: Int): ByteBuffer {
        requireAvailable()
        require(width >= 8 && height >= 8) { "resize: dimensions must be >=8, got ${width}x$height (avoids gralloc 0x3b)" }
        // 16384 per side, the native contract (resize.cpp refuses past it) and the same bound
        // Image.invoke validates a whole page against and Mipmap.create uploads under. The old
        // 8192 cap was stricter than every caller, so the first mipmap step of a long strip
        // (an 800x16000 webtoon segment) threw "dimensions too large" and the chain stopped at
        // level 0 - every zoomed-out frame then sampled the full-res texture through the tile
        // grid, which is the stutter the long-strip reader showed.
        require(width <= 16384 && height <= 16384) { "resize: dimensions too large ${width}x$height" }
        require(source.isDirect) { "resize: source must be direct ByteBuffer" }
        val srcNeeded = width.toLong() * height * 4L
        require(srcNeeded >= 0) { "resize: src overflow ${width}x$height" }
        require(source.capacity().toLong() >= srcNeeded) { "resize: source capacity ${source.capacity()} < needed $srcNeeded" }
        // The 64MP area Image.invoke accepts a whole page at, in bytes. The source is the
        // caller's own buffer - nothing is allocated for it here - so this is the contract
        // check, not an allocation guard; the only allocation is the destination, bounded a
        // quarter of it below. The old 64MB byte cap rejected any strip wider than ~1600px.
        require(srcNeeded <= 256L * 1024 * 1024) { "resize: src too large ${width}x$height" }
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

    /** [resize] for extended-sRGB half-float pixels, which are twice as wide. */
    fun resizeF16(source: ByteBuffer, width: Int, height: Int): ByteBuffer {
        // Half by half at 8 B/px, so two bytes per source pixel.
        val output = directBuffer(width, height, 2)
        resizeLinearAreaNativeF16(source, output, width, height)
        return output
    }

    /**
     * [applyGainmapNative] into a freshly allocated RGBA16F buffer.
     *
     * [gainmap] is `ImageDecoder.DecodeResult.gainmap`, passed through as its parts so this
     * module doesn't have to depend on the decoder's types.
     */
    fun applyGainmap(
        base: ByteBuffer,
        width: Int,
        height: Int,
        gain: ByteBuffer,
        gainWidth: Int,
        gainHeight: Int,
        gainChannels: Int,
        gamma: FloatArray,
        minContentBoost: FloatArray,
        maxContentBoost: FloatArray,
        offsetSdr: FloatArray,
        offsetHdr: FloatArray,
        weight: Float = 1f,
    ): ByteBuffer {
        val output = directBuffer(width, height, 8)
        applyGainmapNative(
            base, gain, output, width, height, gainWidth, gainHeight, gainChannels,
            gamma, minContentBoost, maxContentBoost, offsetSdr, offsetHdr, weight,
        )
        return output
    }

    /** [toneMapToSdrNative] into a freshly allocated RGBA8 buffer. */
    fun toneMapToSdr(source: ByteBuffer, width: Int, height: Int): ByteBuffer {
        val output = directBuffer(width, height, 4)
        toneMapToSdrNative(source, output, width, height)
        return output
    }
}
