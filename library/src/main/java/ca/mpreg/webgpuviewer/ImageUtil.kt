package ca.mpreg.webgpuviewer

import java.nio.ByteBuffer

object ImageUtil {
    init {
        System.loadLibrary("resize")
    }

    external fun resizeLinearAreaNative(
        pixels: ByteBuffer,
        dstPixels: ByteBuffer,
        width: Int,
        height: Int
    )

    fun resize(source: ByteBuffer, width: Int, height: Int): ByteBuffer {
        require(width > 0 && height > 0) { "resize: dimensions must be positive, got ${width}x$height" }
        require(width <= 16384 && height <= 16384) { "resize: dimensions too large ${width}x$height" }
        require(source.isDirect) { "resize: source must be direct ByteBuffer" }
        val srcNeeded = width.toLong() * height * 4L
        require(source.capacity().toLong() >= srcNeeded) { "resize: source capacity ${source.capacity()} < needed $srcNeeded" }
        val dstWidth = width / 2
        val dstHeight = height / 2
        require(dstWidth > 0 && dstHeight > 0) { "resize: dst dimensions must be positive" }
        val dstNeeded = dstWidth.toLong() * dstHeight * 4L
        require(dstNeeded <= Int.MAX_VALUE) { "resize: dst too large" }
        val output = ByteBuffer.allocateDirect(dstNeeded.toInt())
        resizeLinearAreaNative(source, output, width, height)
        return output
    }
}
