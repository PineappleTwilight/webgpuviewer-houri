package ca.mpreg.webgpuviewer.renderer

/**
 * KMK --> What the renderer does about a consecutive run of `getCurrentTexture` failures.
 *
 * Pure on purpose: no Android import and no Dawn type, so `SwapchainRecoveryHarness` can drive the
 * real table offline. [WebGpuRenderer.onSwapchainError] applies it and owns the side effects; this
 * file owns only the order and the boundaries.
 *
 * The order is the point. A surface that stays wedged after its own rebuild is not a surface
 * problem any more - Dawn's WSI object is born broken and a fresh swapchain on the same device is
 * born broken too, which the 2026-11-22 PowerVR dump shows directly (`recreating the surface` at
 * 30 and at 120 frames, `Error` on every frame between). So the rungs escalate past the surface:
 * rebuild the device (a new logical device and a new `VkSurfaceKHR`), then move the adapter to
 * Dawn's OpenGLES backend, whose presentation path is EGL rather than `VkSwapchainKHR`. Only when
 * all three have been given their window does the frame loop park.
 *
 * A [Step.RETRY] between the boundaries is not a rung passing - it is the frames inside that
 * window, where the renderer has already asked for the reset and is waiting for it to land.
 * KMK <--
 */
internal enum class SwapchainRecoveryStep {
    /** Keep retrying; the frame clock paces it. */
    RETRY,

    /** Close and rebuild the WebGPU surface on the same window/device. */
    RECREATE_SURFACE,

    /** Rebuild the device (fresh adapter and swapchain, same backend). */
    REBUILD_DEVICE,

    /** Rebuild on Dawn's OpenGLES backend - a different WSI implementation. */
    SWITCH_BACKEND,

    /** Every rung failed: park until something else invalidates the reader. */
    PARK,
}

internal object SwapchainRecoveryPolicy {
    /**
     * The boundaries, in failed frames at ~60 fps. 30 is ~0.5 s - long enough for a genuinely
     * transient present failure to ride through, short enough to notice a wedge in a breath. Each
     * later rung gets another 30 frames, so a wedged surface reaches the backend switch in ~1.5 s
     * and parks at ~2 s. Do not compress them: a device/backend rebuild tears down every decoded
     * page, and a one-frame hiccup must never pay for that.
     */
    const val RECREATE_SURFACE_AT = 30
    const val REBUILD_DEVICE_AT = 60
    const val SWITCH_BACKEND_AT = 90
    const val GIVE_UP_AT = 120

    /**
     * The step for a streak of [streak] consecutive failures (1 after the first failed frame).
     *
     * Equality on the rungs, not `>=`: a streak that jumps over a boundary (a paused loop resuming
     * straight at 60) must still take that rung, but the lower rungs must not re-fire every frame
     * above their boundary. [GIVE_UP_AT] is the one cumulative ceiling - once reached, no later
     * frame has anything left to try for this streak.
     */
    fun stepFor(streak: Int): SwapchainRecoveryStep = when {
        streak >= GIVE_UP_AT -> SwapchainRecoveryStep.PARK
        streak == RECREATE_SURFACE_AT -> SwapchainRecoveryStep.RECREATE_SURFACE
        streak == REBUILD_DEVICE_AT -> SwapchainRecoveryStep.REBUILD_DEVICE
        streak == SWITCH_BACKEND_AT -> SwapchainRecoveryStep.SWITCH_BACKEND
        else -> SwapchainRecoveryStep.RETRY
    }
}
