package com.orbital.synth.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.assertTrue
import kotlin.math.max
import kotlin.math.min

/**
 * Physical gestures must be injected into the full **unclipped** viewport.
 *
 * performScrollTo() can leave a child entirely clipped under a tall scroller
 * (API35 observed boundsInRoot=Rect.Zero despite a measured Canvas). When
 * clipped, boundsInRoot cannot tell us the direction; the caller supplies
 * whether the target lies below (+1) or above (-1) the current view. Walk
 * by bounded ScrollBy steps until its measured size is really visible.
 */
internal fun ComposeTestRule.fullyRevealOscillatorCanvas(
    canvasTag: String,
    scrollContainerTag: String,
    searchDirection: Int = 1,
): SemanticsNodeInteraction {
    require(searchDirection == 1 || searchDirection == -1)
    val canvas = onNodeWithTag(canvasTag).performScrollTo()
    val editor = onNodeWithTag(scrollContainerTag)

    for (attempt in 0 until 24) {
        waitForIdle()
        val node = canvas.fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val editorBounds = editor.fetchSemanticsNode().boundsInRoot
        val rootBounds = onRoot().fetchSemanticsNode().boundsInRoot
        val visibleTop = max(editorBounds.top, rootBounds.top) + 12f
        val visibleBottom = min(editorBounds.bottom, rootBounds.bottom) - 12f
        val measuredWidth = node.size.width.toFloat()
        val measuredHeight = node.size.height.toFloat()
        assertTrue(
            "Touch Canvas has zero measured size: tag=$canvasTag node-size=${node.size} bounds=$bounds",
            measuredWidth > 20f && measuredHeight > 20f,
        )
        val fullyVisible = bounds.width >= measuredWidth - 1f &&
            bounds.height >= measuredHeight - 1f &&
            bounds.top >= visibleTop - 1f && bounds.bottom <= visibleBottom + 1f
        if (fullyVisible) return canvas

        val step = ((visibleBottom - visibleTop) * .55f).coerceIn(120f, 900f)
        val missingHeight = (measuredHeight - bounds.height).coerceAtLeast(0f)
        val scrollDeltaY = when {
            bounds.width <= 1f || bounds.height <= 1f -> searchDirection * step
            bounds.bottom >= visibleBottom - 2f && missingHeight > 1f ->
                (missingHeight + 16f).coerceAtMost(step)
            bounds.top <= visibleTop + 2f && missingHeight > 1f ->
                -(missingHeight + 16f).coerceAtMost(step)
            bounds.bottom > visibleBottom -> bounds.bottom - visibleBottom + 16f
            bounds.top < visibleTop -> bounds.top - visibleTop - 16f
            missingHeight > 1f -> searchDirection * step
            else -> searchDirection * 20f
        }
        editor.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            scroll(0f, scrollDeltaY)
        }
    }

    waitForIdle()
    val node = canvas.fetchSemanticsNode()
    val bounds = node.boundsInRoot
    val editorBounds = editor.fetchSemanticsNode().boundsInRoot
    val rootBounds = onRoot().fetchSemanticsNode().boundsInRoot
    assertTrue(
        "Canvas still clipped after bounded explicit ScrollBy: tag=$canvasTag " +
            "measured=${node.size} bounds=$bounds editor=$editorBounds root=$rootBounds",
        false,
    )
    return canvas
}
