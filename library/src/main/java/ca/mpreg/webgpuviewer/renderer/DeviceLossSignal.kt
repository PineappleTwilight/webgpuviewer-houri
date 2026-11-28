package ca.mpreg.webgpuviewer.renderer

/**
 * KMK --> Whether a failed native call is telling us the GPU device itself is gone.
 *
 * The device-loss callback is the designed signal, and it is not enough on its own. The
 * 2026-11-27 PowerVR report (Multilaser M8_4G, Android 11, TINY) presented 350 frames into a
 * black reader with `deviceLosses=0`, `recoveries=0` and `gen=0`; the only proof that the device
 * was dead was the once-per-session page-texture readback failing with
 * `4: [Device] is lost.`. The callback never reached the app, so nothing rebuilt the device and
 * the render loop kept drawing into a dead one for the rest of the session. Any native answer
 * that says the device is lost means what the callback means, whatever path it took to get here.
 *
 * Deliberately narrow. The same readback also fails with "Buffer was destroyed before mapping
 * was resolved.", which is a buffer-lifetime bug, not a dead device - treating it as a loss would
 * throw away a healthy device and reset every decoded page for nothing. Likewise a *surface*
 * that is lost is not a device loss; only the device's own answer counts.
 * KMK <--
 */
object DeviceLossSignal {
    /**
     * A reason code no driver reports, for a loss observed from a native answer rather than the
     * driver's callback. Hosts that record why a session degraded can tell the two apart.
     */
    const val REASON_OBSERVED: Int = -2

    /**
     * Whether [message] is the device-loss answer.
     *
     * Dawn's shape is `"<status>: [Device] is lost."` - the status prefix varies (it was `4` in
     * the 2026-11-27 report), so only the tail is matched. Case-insensitive because the text
     * comes from the driver.
     */
    fun isLostDeviceMessage(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val m = message.lowercase()
        if (m.contains("buffer was destroyed")) return false
        return m.contains("[device] is lost") ||
            m.contains("device is lost") ||
            m.contains("device was destroyed")
    }

    /**
     * Whether a device loss should move the next recovery onto Dawn's GLES backend.
     *
     * Only PowerVR, and only for a Vulkan-shaped fault. The field failures on that driver are
     * Vulkan-specific - `VK_ERROR_OUT_OF_DEVICE_MEMORY` from `vkQueueSubmit` and the 2026-11-27
     * loss whose only evidence was `[Device] is lost.` - while the same devices read through
     * Dawn's GLES backend, which the swapchain ladder already proves in the field
     * (`swapchain recovered after switching to the OpenGLES backend`). Rebuilding the same
     * Vulkan path that just died buys one more black session; moving the session to GLES does
     * not share that driver's transfer/memory paths at all.
     *
     * A callback loss that names no fault (backgrounding, surface teardown) keeps the default
     * backend: switching a healthy session's backend is not free, and a phone drops its GPU
     * device on backgrounding as a matter of course.
     */
    fun shouldSwitchToGles(
        powerVr: Boolean,
        alreadyOnGles: Boolean,
        reason: Int,
        message: String,
    ): Boolean {
        if (!powerVr || alreadyOnGles) return false
        return reason == REASON_OBSERVED ||
            message.contains("OUT_OF_DEVICE_MEMORY", ignoreCase = true) ||
            message.contains("DEVICE_LOST", ignoreCase = true)
    }
}
