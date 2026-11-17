package ca.mpreg.webgpuviewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

/** The activity this context belongs to, through any wrappers. */
internal fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context != null) {
        if (context is Activity) return context
        context = (context as? ContextWrapper)?.baseContext
    }
    return null
}

internal object NormalMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float
        get() = try {
            val v = 1f
            if (!v.isFinite()) 1f else v.coerceIn(0.1f, 10f)
        } catch (_: Throwable) { 1f }
}

suspend fun AwaitPointerEventScope.waitForCleanUp(
    pointerId: PointerId, timeout: Long, touchSlop: Float
): PointerEvent? = try {
    withTimeout(timeout) {
        var acc = Offset.Zero

        while (true) {
            val event = awaitPointerEvent()

            if (event.changes.any { it.isConsumed }) {
                return@withTimeout null
            }

            val change = event.changes.firstOrNull { it.id == pointerId } ?: return@withTimeout null

            if (event.changes.any { it.id != pointerId && it.pressed }) {
                return@withTimeout null
            }

            acc += event.calculatePan()
            if (acc.getDistance() > touchSlop) {
                return@withTimeout null
            }
            if (change.changedToUp()) {
                return@withTimeout event
            }
        }
    }
} catch (e: PointerEventTimeoutCancellationException) {
    null
} as PointerEvent?

suspend fun AwaitPointerEventScope.waitForDown(timeout: Long) = try {
    withTimeout(timeout) {
        var down = awaitPointerEvent().changes.firstOrNull { it.pressed }
        while (down == null) {
            down = awaitPointerEvent().changes.firstOrNull { it.pressed }
        }
        down
    }
} catch (e: PointerEventTimeoutCancellationException) {
    null
}

fun Float.orZero(): Float = if (this.isNaN()) 0f else this

fun Float.closeTo(x: Float, eps: Float = 0.0001f): Boolean = abs(this - x) < eps

// KMK --> The old `RequestMaxRefreshRate()` is gone, not deprecated: it pinned the panel's top
// mode for as long as the viewer was composed, which is what held a 120Hz LTPO panel at its most
// expensive state through a whole reading session of static pages. Every call site now goes through
// [RequestMaxRefreshRateFor], where motion decides - so there is no longer any way to reintroduce
// the unconditional pin without writing it deliberately. A display mode is a request that must be
// withdrawn, never a capability to claim.
// KMK <--

/**
 * Requests the panel's highest refresh mode only while [motion] is true, and releases the request
 * (back to the system's own choice) when it is false.
 *
 * This is the battery lever. `preferredDisplayModeId` is what *stops* a 120Hz LTPO panel dropping to
 * 60Hz, and a reader spends most of its session looking at a page that is not moving - so holding
 * the top mode unconditionally spends the panel's most expensive state on a static image. Motion is
 * the only thing that makes the higher rate worth it: a fling, a drag, a zoom animation, or a page
 * turn, all of which are brief and all of which the viewer already tracks as state.
 *
 * Releasing means clearing the preference rather than setting a lower mode: the system picks, and on
 * a 60Hz panel that is a no-op, so this costs nothing on the devices that cannot benefit.
 */
@Composable
fun RequestMaxRefreshRateFor(motion: Boolean) {
    val activity = LocalContext.current as? Activity
    LaunchedEffect(activity, motion) {
        applyPreferredDisplayMode(activity, maxMode = motion)
    }
}

/**
 * Applies or releases the highest-refresh-rate request on [activity]'s window.
 *
 * `maxMode = true` selects the fastest supported mode; `false` clears the preference entirely, which
 * is what hands the decision back to the platform. Both paths are wrapped because a window that is
 * being torn down throws on `attributes` rather than reporting a mode list, and a display-mode
 * request must never be the thing that crashes a viewer teardown.
 */
private fun applyPreferredDisplayMode(activity: Activity?, maxMode: Boolean) {
    val window = activity?.window ?: return
    runCatching {
        val display = window.windowManager?.defaultDisplay ?: return
        val layoutParams = window.attributes
        if (maxMode) {
            val max = display.supportedModes.maxByOrNull { it.refreshRate } ?: return
            layoutParams.preferredDisplayModeId = max.modeId
        } else {
            // 0 means "let the system choose". preferredDisplayModeId is documented as 0 for
            // no preference, and assigning it is the release - setting a *lower* mode id would
            // instead lock a 60Hz panel to 60Hz, which is the opposite of what is wanted.
            layoutParams.preferredDisplayModeId = 0
        }
        window.attributes = layoutParams
    }
}
