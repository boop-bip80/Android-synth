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
 * Physical gesture tests must inject into the **unclipped** viewport.
 *
 * performScrollTo() alone can minimally reveal a descendant at the editor
 * boundary; a pointer at 20% height then lands underneath the clipped parent
 * and never reaches the Canvas. ScrollBy targets the real parent viewport
 * instead of watering down the intended gesture coordinates/assertions.
 */
internal fun ComposeTestRule.fullyRevealOscillatorCanvas(
    canvasTag: String,
    scrollContainerTag: String,
): SemanticsNodeInteraction {
    val canvas = onNodeWithTag(canvasTag).performScrollTo()
    val editor = onNodeWithTag(scrollContainerTag)

    repeat(5) {
        waitForIdle()
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        val editorBounds = editor.fetchSemanticsNode().boundsInRoot
        val rootBounds = onRoot().fetchSemanticsNode().boundsInRoot
        val visibleTop = max(editorBounds.top, rootBounds.top) + 12f
        val visibleBottom = min(editorBounds.bottom, rootBounds.bottom) - 12f
        val scrollDeltaY = when {
            bounds.bottom > visibleBottom -> bounds.bottom - visibleBottom
            bounds.top < visibleTop -> bounds.top - visibleTop
            else -> 0f
        }
        if (kotlin.math.abs(scrollDeltaY) > 0.5f) {
            editor.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                scroll(0f, scrollDeltaY)
            }
        }
    }

    waitForIdle()
    val bounds = canvas.fetchSemanticsNode().boundsInRoot
    val editorBounds = editor.fetchSemanticsNode().boundsInRoot
    val rootBounds = onRoot().fetchSemanticsNode().boundsInRoot
    val visibleTop = max(editorBounds.top, rootBounds.top) + 12f
    val visibleBottom = min(editorBounds.bottom, rootBounds.bottom) - 12f
    assertTrue(
        "Oscillator canvas must be fully touch-visible, not just semantically present: " +
            "canvas=$bounds editor=$editorBounds root=$rootBounds",
        bounds.top >= visibleTop - 1f && bounds.bottom <= visibleBottom + 1f,
    )
    return canvas
}
