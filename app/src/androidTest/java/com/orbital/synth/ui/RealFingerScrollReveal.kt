package com.orbital.synth.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.max
import kotlin.math.min

/**
 * Real Android DOWN/MOVE/UP scroll: no Compose performScrollTo semantics action.
 *
 * The start x=4 px follows the proven 350/360dp route grid test's quiet gutter,
 * avoiding graph node drag recognizers. Edge-aware short nudges preserve almost
 * visible 48dp controls instead of repeatedly overshooting them.
 *
 * This helper never claims a touch target is >=48dp if its actual layout isn't;
 * it proves all of that target's available height up to 48dp is within the
 * physical viewport before a real or semantic activation follows.
 */
internal fun revealByRealFingerSwipes(
    compose: ComposeTestRule,
    scroll: ScrollState,
    viewportTag: String,
    target: SemanticsNodeInteraction,
    label: String,
    minVisibleDp: Float = 48f,
    maxSwipes: Int = 28,
): Rect {
    val viewport = compose.onNodeWithTag(viewportTag)
    val density = InstrumentationRegistry.getInstrumentation()
        .targetContext.resources.displayMetrics.density
    val margin = 5f * density
    val requested = minVisibleDp * density
    require(maxSwipes in 1..40)
    repeat(maxSwipes) { attempt ->
        compose.waitForIdle()
        val targetBounds = target.fetchSemanticsNode().boundsInRoot
        val viewBounds = viewport.fetchSemanticsNode().boundsInRoot
        val intersectionWidth = max(0f,
            min(targetBounds.right, viewBounds.right) -
                max(targetBounds.left, viewBounds.left))
        val intersectionHeight = max(0f,
            min(targetBounds.bottom, viewBounds.bottom) -
                max(targetBounds.top, viewBounds.top))
        val enough = targetBounds.width > 1f && targetBounds.height > 1f &&
            intersectionWidth >= min(requested, targetBounds.width) - 1f &&
            intersectionHeight >= min(requested, targetBounds.height) - 1f &&
            // A physical center tap only works when its coordinate is onscreen.
            (targetBounds.height > viewBounds.height ||
                targetBounds.center.y in (viewBounds.top + margin)..(viewBounds.bottom - margin))
        if (enough) return targetBounds
        if (attempt == maxSwipes - 1) throw AssertionError(
            "Cannot reveal $label after $maxSwipes real gestures: " +
                "target=$targetBounds viewport=$viewBounds " +
                "scroll=${scroll.value}/${scroll.maxValue}",
        )
        val hasBounds = targetBounds.width > 1f && targetBounds.height > 1f
        val tooHigh = hasBounds &&
            (targetBounds.bottom < viewBounds.top + margin ||
                targetBounds.center.y < viewBounds.top + margin)
        val tooLow = hasBounds &&
            (targetBounds.top > viewBounds.bottom - margin ||
                targetBounds.center.y > viewBounds.bottom - margin ||
                targetBounds.bottom > viewBounds.bottom - margin)
        val reachedEnd = scroll.maxValue > 0 && scroll.value >= scroll.maxValue - 1
        val down = when {
            tooHigh && !tooLow -> true
            tooLow && !tooHigh -> false
            hasBounds -> targetBounds.center.y < viewBounds.center.y
            else -> reachedEnd // Revisit a clipped-out node from the far end.
        }
        val deficit = if (!hasBounds) viewBounds.height * .48f
            else if (down) viewBounds.top + margin - targetBounds.top
            else targetBounds.bottom - (viewBounds.bottom - margin)
        viewport.performTouchInput {
            val x = min(4f, size.width * .5f)
            val allowed = size.height * .52f
            val minimum = min(12f * density, allowed)
            val travel = (deficit + margin).coerceIn(minimum, allowed)
            val startY = if (down) size.height * .23f else size.height * .78f
            val endY = if (down) startY + travel else startY - travel
            swipe(Offset(x, startY), Offset(x, endY), durationMillis = 320L)
        }
    }
    error("unreachable")
}
