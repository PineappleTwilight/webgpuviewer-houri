package ca.mpreg.webgpuviewer.draw

import androidx.webgpu.BlendFactor
import androidx.webgpu.BlendOperation
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBlendComponent
import androidx.webgpu.GPUBlendState
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUExtent3D
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUPrimitiveState
import androidx.webgpu.GPURenderPassEncoder
import androidx.webgpu.GPURenderPipeline
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUTexelCopyBufferLayout
import androidx.webgpu.GPUTexelCopyTextureInfo
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureDescriptor
import androidx.webgpu.GPUTextureView
import androidx.webgpu.GPUVertexState
import androidx.webgpu.PrimitiveTopology
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val device get() = WebGpuRenderer.device

private val pipeline: GPURenderPipeline by lazy {
    val shaderModule = device.createShaderModule(
        GPUShaderModuleDescriptor(
            shaderSourceWGSL = GPUShaderSourceWGSL(SPRITE_SHADER)
        )
    )
    device.createRenderPipeline(
        GPURenderPipelineDescriptor(
            vertex = GPUVertexState(module = shaderModule, entryPoint = "vs_main"),
            fragment = GPUFragmentState(
                module = shaderModule, entryPoint = "fs_main", targets = arrayOf(
                    GPUColorTargetState(
                        format = TextureFormat.RGBA8Unorm, blend = GPUBlendState(
                            color = GPUBlendComponent(
                                srcFactor = BlendFactor.SrcAlpha,
                                dstFactor = BlendFactor.OneMinusSrcAlpha,
                                operation = BlendOperation.Add
                            ), alpha = GPUBlendComponent(
                                srcFactor = BlendFactor.One,
                                dstFactor = BlendFactor.OneMinusSrcAlpha,
                                operation = BlendOperation.Add
                            )
                        )
                    )
                )
            ),
            primitive = GPUPrimitiveState(topology = PrimitiveTopology.TriangleList)
        )
    )
}

// Vertex shader rotates the quad's corners around its center by `angle`, so the caller
// spins the sprite by advancing one uniform instead of re-uploading geometry. Fragment
// shader samples with the same manual bilinear tap as the tile plain variant (no sampler
// object needed), tints the texel, and premultiplies - matching this target's blend state.
private const val SPRITE_SHADER = """
struct SpriteUniforms {
    center: vec2<f32>,  // sprite center, destination pixels
    size: vec2<f32>,    // sprite size, destination pixels
    dst_size: vec2<f32>,  // destination size, pixels
    angle: f32,         // rotation, radians, clockwise-positive on screen
    _pad: f32,          // vec4 alignment: tint must start at a 16-byte offset
    tint: vec4<f32>,    // multiplies the sampled texel
}

@group(0) @binding(0) var<uniform> params: SpriteUniforms;
@group(0) @binding(1) var src_tex: texture_2d<f32>;

struct SpriteVertexOutput {
    @builtin(position) position: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

@vertex
fn vs_main(@builtin(vertex_index) vertex_index: u32) -> SpriteVertexOutput {
    var corners = array<vec2<f32>, 6>(
        vec2<f32>(-0.5, -0.5),
        vec2<f32>(-0.5, 0.5),
        vec2<f32>(0.5, -0.5),
        vec2<f32>(0.5, -0.5),
        vec2<f32>(-0.5, 0.5),
        vec2<f32>(0.5, 0.5)
    );
    var uvs = array<vec2<f32>, 6>(
        vec2<f32>(0.0, 0.0),
        vec2<f32>(0.0, 1.0),
        vec2<f32>(1.0, 0.0),
        vec2<f32>(1.0, 0.0),
        vec2<f32>(0.0, 1.0),
        vec2<f32>(1.0, 1.0)
    );

    let corner = corners[vertex_index];
    let c = cos(params.angle);
    let s = sin(params.angle);
    let rotated = vec2<f32>(
        corner.x * c - corner.y * s,
        corner.x * s + corner.y * c
    );
    let pixel_pos = params.center + rotated * params.size;

    var out: SpriteVertexOutput;
    out.position = vec4<f32>(
        (pixel_pos.x / params.dst_size.x) * 2.0 - 1.0,
        1.0 - (pixel_pos.y / params.dst_size.y) * 2.0,
        0.0, 1.0
    );
    out.uv = uvs[vertex_index];
    return out;
}

@fragment
fn fs_main(in: SpriteVertexOutput) -> @location(0) vec4<f32> {
    let size = vec2<f32>(textureDimensions(src_tex));
    let pos = in.uv * size;
    let p = pos - 0.5;
    let base = floor(p);

    let max_coord = vec2<i32>(size) - 1;
    let i0 = clamp(vec2<i32>(base), vec2<i32>(0), max_coord);
    let i1 = clamp(vec2<i32>(base) + 1, vec2<i32>(0), max_coord);
    let f = p - base;

    let c00 = textureLoad(src_tex, vec2<i32>(i0.x, i0.y), 0);
    let c10 = textureLoad(src_tex, vec2<i32>(i1.x, i0.y), 0);
    let c01 = textureLoad(src_tex, vec2<i32>(i0.x, i1.y), 0);
    let c11 = textureLoad(src_tex, vec2<i32>(i1.x, i1.y), 0);

    let col = mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y) * params.tint;
    return vec4<f32>(col.rgb * col.a, col.a);
}
"""

// Thread-local ByteBuffer to avoid allocation per call
private val byteBufferLocal = ThreadLocal.withInitial {
    ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder())
}

/**
 * Draws [view] as a billboarded sprite into an existing render pass, so it can share a pass
 * with other draws. Sets its own pipeline, so the caller must set theirs again before drawing
 * something else.
 *
 * [cx]/[cy] is the sprite center in destination pixels, [sizePx] its width and height,
 * [angleRadians] its rotation. [tint] multiplies the sampled texels - a white source takes
 * any color this way. A fresh uniform buffer is allocated per call, as with [rect].
 */
fun Draw.sprite(
    pass: GPURenderPassEncoder,
    view: GPUTextureView,
    cx: Float,
    cy: Float,
    sizePx: Float,
    dstWidth: Float,
    dstHeight: Float,
    angleRadians: Float,
    tint: Int,
) {
    val r = ((tint shr 16) and 0xFF) / 255f
    val g = ((tint shr 8) and 0xFF) / 255f
    val b = (tint and 0xFF) / 255f
    val a = ((tint ushr 24) and 0xFF) / 255f

    val byteBuffer = byteBufferLocal.get()!!
    byteBuffer.clear()
    byteBuffer.putFloat(cx)
    byteBuffer.putFloat(cy)
    byteBuffer.putFloat(sizePx)
    byteBuffer.putFloat(sizePx)
    byteBuffer.putFloat(dstWidth)
    byteBuffer.putFloat(dstHeight)
    byteBuffer.putFloat(angleRadians)
    byteBuffer.putFloat(0f) // _pad: keeps tint 16-byte aligned (see Circle)
    byteBuffer.putFloat(r)
    byteBuffer.putFloat(g)
    byteBuffer.putFloat(b)
    byteBuffer.putFloat(a)
    byteBuffer.flip()

    val uniformBuffer = device.createBuffer(
        GPUBufferDescriptor(size = 48L, usage = BufferUsage.Uniform or BufferUsage.CopyDst)
    )
    device.queue.writeBuffer(uniformBuffer, 0, byteBuffer)

    pass.setPipeline(pipeline)
    pass.setBindGroup(
        0, device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = pipeline.getBindGroupLayout(0), entries = arrayOf(
                    GPUBindGroupEntry(0, buffer = uniformBuffer),
                    GPUBindGroupEntry(1, textureView = view),
                )
            )
        )
    )
    pass.draw(6)
}

/**
 * Uploads RGBA [pixels] ([width] x [height], 4 bytes per pixel) into a sampled texture.
 * For small static art (icons, spinners) - bulk image uploads go through [Mipmap] instead.
 */
fun Draw.uploadTexture(width: Int, height: Int, pixels: ByteBuffer): GPUTexture {
    val texture = device.createTexture(
        GPUTextureDescriptor(
            size = GPUExtent3D(width, height),
            format = TextureFormat.RGBA8Unorm,
            usage = TextureUsage.TextureBinding or TextureUsage.CopyDst,
        )
    )
    device.queue.writeTexture(
        dataLayout = GPUTexelCopyBufferLayout(
            offset = 0L,
            bytesPerRow = width * Int.SIZE_BYTES,
            rowsPerImage = height,
        ),
        data = pixels,
        destination = GPUTexelCopyTextureInfo(texture = texture),
        writeSize = GPUExtent3D(width, height),
    )
    return texture
}
