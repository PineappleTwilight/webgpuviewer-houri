package ca.mpreg.webgpuviewer.filter

import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUTextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FilterBrightnessContrast(
    brightness: Float = 0f,
    contrast: Float = 1f,
) : FilterFullscreen() {

    @Volatile var brightness: Float = brightness
        set(value) {
            field = value.coerceIn(-1f, 1f)
            uniformsDirty = true
            invalidate()
        }

    @Volatile var contrast: Float = contrast
        set(value) {
            field = value.coerceIn(0f, 3f)
            uniformsDirty = true
            invalidate()
        }

    override val active: Boolean get() = enabled && (brightness != 0f || contrast != 1f)
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
            b.putFloat(brightness)
            b.putFloat(contrast)
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
    brightness: f32,
    contrast: f32,
    _pad0: f32,
    _pad1: f32,
}
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var src: texture_2d<f32>;

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    let texel = textureLoad(src, vec2<i32>(in.position.xy), 0);
    if (texel.a <= 0.0) { return texel; }
    let c = texel.rgb / texel.a;
    let adjusted = (c - 0.5) * params.contrast + 0.5 + params.brightness;
    let clamped = clamp(adjusted, vec3<f32>(0.0), vec3<f32>(1.0));
    return vec4<f32>(clamped * texel.a, texel.a);
}
"""
    }
}
