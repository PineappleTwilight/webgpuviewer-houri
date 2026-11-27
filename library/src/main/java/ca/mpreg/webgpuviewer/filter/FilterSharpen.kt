package ca.mpreg.webgpuviewer.filter

import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUTextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unsharp-mask sharpening for scans that arrive soft or were resized down by a source.
 *
 * One fragment pass over the frame: a 3x3 box blur of the neighbourhood is subtracted from the
 * centre pixel, scaled by [amount], and added back. Edges and line art get crisper without the
 * halo a fixed convolution has at this radius, and `amount = 0` is the identity so the pass is
 * not attached at all.
 *
 * The frame arrives premultiplied. Each neighbour's colour is divided back out by its alpha
 * before averaging and the result is multiplied in again, so transparent letterboxing does not
 * bleed black into the page edge.
 */
class FilterSharpen(
    amount: Float = 0f,
) : FilterFullscreen() {

    /** 0 is off, 1 is a moderate sharpen. Above that the halo becomes visible. */
    @Volatile var amount: Float = amount
        set(value) {
            field = value.coerceIn(0f, 2f)
            uniformsDirty = true
            invalidate()
        }

    override val active: Boolean get() = enabled && amount > 0f
    override val code: String get() = FRAGMENT

    @Volatile private var uniformsDirty = true

    private val uniforms: GPUBuffer by lazy {
        device.createBuffer(
            GPUBufferDescriptor(label = label, size = 16, usage = BufferUsage.Uniform or BufferUsage.CopyDst)
        )
    }

    private val uniformBytes: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
    }

    override fun prepare(srcWidth: Int, srcHeight: Int) {
        if (uniformsDirty) {
            uniformsDirty = false
            val b = uniformBytes
            b.clear()
            b.putFloat(amount)
            b.putFloat(0f)
            b.putFloat(0f)
            b.putFloat(0f)
            b.flip()
            device.queue.writeBuffer(uniforms, 0, b)
        }
    }

    override fun entries(src: GPUTextureView): Array<GPUBindGroupEntry> = arrayOf(
        GPUBindGroupEntry(0, buffer = uniforms),
        GPUBindGroupEntry(1, textureView = src),
    )

    override fun cleanup() {
        rebind()
    }

    companion object {
        const val FRAGMENT = """
struct Params {
    amount: f32,
    _pad0: f32,
    _pad1: f32,
    _pad2: f32,
}
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var src: texture_2d<f32>;

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    let dims = vec2<i32>(textureDimensions(src));
    let p = vec2<i32>(in.position.xy);
    let centre = textureLoad(src, p, 0);
    if (centre.a <= 0.0) { return centre; }

    var sum = vec3<f32>(0.0);
    var weight = 0.0;
    for (var dy = -1; dy <= 1; dy = dy + 1) {
        for (var dx = -1; dx <= 1; dx = dx + 1) {
            if (dx == 0 && dy == 0) { continue; }
            let q = clamp(p + vec2<i32>(dx, dy), vec2<i32>(0), dims - vec2<i32>(1));
            let t = textureLoad(src, q, 0);
            let a = max(t.a, 0.0001);
            sum = sum + t.rgb / a;
            weight = weight + 1.0;
        }
    }

    let base = centre.rgb / max(centre.a, 0.0001);
    let blurred = sum / weight;
    let sharpened = clamp(base + params.amount * (base - blurred), vec3<f32>(0.0), vec3<f32>(1.0));
    return vec4<f32>(sharpened * centre.a, centre.a);
}
"""
    }
}
