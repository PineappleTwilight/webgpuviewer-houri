package ca.mpreg.webgpuviewer.draw

import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPURequestCallback
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer

object Draw {
    internal val device get() = WebGpuRenderer.device

    /**
     * Buffers recorded during the frame being built, released once the GPU is finished with them.
     *
     * A recorded draw does not touch its buffer. The command buffer that references it is handed to
     * the queue only at submit, and the work runs after that, so the buffer has to outlive the
     * submit - never mind the recording.
     *
     * Destroying in the frame that records the draw frees memory the queue has not read yet, which
     * Dawn rejects as "Buffer used in submit while destroyed" and then segfaults on. Destroying
     * straight after submit is the same hazard and only looks safer: the queue has been handed the
     * work, not finished it.
     *
     * Per-thread because recording happens on the render thread while a texture's own decode work
     * can allocate on another.
     */
    private val frameBuffers = ThreadLocal.withInitial { mutableListOf<GPUBuffer>() }

    fun submit(block: Draw.(GPUCommandEncoder) -> Unit) {
        val encoder = device.createCommandEncoder()
        block.invoke(this, encoder)
        device.queue.submit(arrayOf(encoder.finish()))
        onFrameSubmitted()
    }

    internal fun createBuffer(size: Long, usage: Int): GPUBuffer {
        return device.createBuffer(GPUBufferDescriptor(size = size, usage = usage)).also {
            frameBuffers.get()!!.add(it)
        }
    }

    /**
     * Release the current frame's buffers, and only once the submitted work that reads them has
     * actually completed.
     *
     * Call after every queue.submit that can contain these primitives. The render loop's single
     * per-frame submit is that point; anything recorded into an encoder submitted elsewhere rides
     * the next one, which only delays the release.
     *
     * The fence resolves on a worker thread and the buffers are destroyed there. That is safe - a
     * destroyed buffer is a no-op afterwards - and it keeps the render thread off the fence's
     * completion, which is the whole point of using one instead of counting frames: a frame-count
     * heuristic frees early whenever the GPU falls behind, which is exactly the case that matters.
     */
    fun onFrameSubmitted() {
        val buffers = frameBuffers.get()!!
        if (buffers.isEmpty()) return
        val snapshot = ArrayList<GPUBuffer>(buffers)
        buffers.clear()

        device.queue.onSubmittedWorkDone(
            { it.run() },
            object : GPURequestCallback<Unit> {
                override fun onResult(result: Unit) {
                    snapshot.forEach { buffer -> runCatching { buffer.destroy() } }
                }

                override fun onError(exception: Exception) {
                    // A lost device has already released everything it owned, and destroying a
                    // buffer whose device is gone is at best redundant and at worst another fault.
                }
            },
        )
    }
}