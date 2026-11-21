package ca.mpreg.webgpuviewer.filter

import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUTextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FilterHlg(
    exposure: Float = 0f,
    gamma: Float = 1f,
) : FilterFullscreen() {
    @Volatile var exposure: Float = exposure
        set(value) {
            field = value.coerceIn(-4f, 4f)
            uniformsDirty = true
            invalidate()
        }

    @Volatile var gamma: Float = gamma
        set(value) {
            field = value.coerceIn(0.5f, 3f)
            uniformsDirty = true
            invalidate()
        }

    override val active: Boolean get() = enabled && (exposure != 0f || gamma != 1f)
    override val code: String get() = FRAGMENT
    @Volatile private var uniformsDirty = true
    private val uniforms: GPUBuffer by lazy {
        device.createBuffer(GPUBufferDescriptor(label = label, size = 16, usage = BufferUsage.Uniform or BufferUsage.CopyDst))
    }
    private val uniformBytes: ByteBuffer by lazy { ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()) }

    override fun prepare(srcWidth: Int, srcHeight: Int) {
        if (uniformsDirty) {
            uniformsDirty = false
            val b = uniformBytes
            b.clear()
            b.putFloat(exposure)
            b.putFloat(gamma)
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

    override fun cleanup() { rebind() }

    companion object {
        const val FRAGMENT = """
struct Params { exposure: f32, gamma: f32, _pad0: f32, _pad1: f32, }
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var src: texture_2d<f32>;
@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    let texel = textureLoad(src, vec2<i32>(in.position.xy), 0);
    if (texel.a <= 0.0) { return texel; }
    let c = texel.rgb / texel.a;
    let ev = pow(2.0, params.exposure);
    let lin = c * ev;
    let oetf = pow(clamp(lin, vec3<f32>(0.0), vec3<f32>(10.0)) / 12.0, vec3<f32>(1.0 / params.gamma));
    let hlg = clamp(oetf, vec3<f32>(0.0), vec3<f32>(1.0));
    return vec4<f32>(hlg * texel.a, texel.a);
}
"""
    }
}
