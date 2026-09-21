package ca.mpreg.webgpuviewer.filter

import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUTextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Desaturates the frame towards grayscale - a saturation control for e-ink-style or
 * low-distraction reading. Follows [FilterBrightnessContrast]: same 16-byte uniform plumbing,
 * same premultiplied-alpha handling, inactive at the identity setting so it costs nothing.
 */
class FilterGrayscale(
    saturation: Float = 1f,
) : FilterFullscreen() {

    /** 1 is the unfiltered image, 0 is fully gray. */
    @Volatile var saturation: Float = saturation
        set(value) {
            field = value.coerceIn(0f, 1f)
            uniformsDirty = true
            invalidate()
        }

    override val active: Boolean get() = enabled && saturation != 1f
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
            b.putFloat(saturation)
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
    saturation: f32,
    _pad0: f32,
    _pad1: f32,
    _pad2: f32,
}
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var src: texture_2d<f32>;

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    let texel = textureLoad(src, vec2<i32>(in.position.xy), 0);
    if (texel.a <= 0.0) { return texel; }
    let c = texel.rgb / texel.a;
    let luma = dot(c, vec3<f32>(0.2126, 0.7152, 0.0722));
    let mixed = mix(vec3<f32>(luma), c, params.saturation);
    return vec4<f32>(mixed * texel.a, texel.a);
}
"""
    }
}
