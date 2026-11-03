package com.orbital.synth.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue

/**
 * Samples genuine Android display pixels from the measured Compose Canvas
 * bounds. Avoids Compose's PixelCopy-based captureToImage() path, which may
 * time out under API35 software graphics despite a healthy foreground app.
 *
 * Callers must obtain bounds from their real semantics node and the exact
 * Activity's decorView screen origin; the captured region is never guessed
 * or substituted with a synthetic Bitmap.
 */
internal data class NativeCanvasPixelEvidence(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val pixels: IntArray,
) {
    private fun requireSameRegion(other: NativeCanvasPixelEvidence) {
        check(
            left == other.left && top == other.top &&
                right == other.right && bottom == other.bottom &&
                displayWidth == other.displayWidth &&
                displayHeight == other.displayHeight &&
                pixels.size == other.pixels.size,
        ) {
            "Real framebuffer/Canvas bounds changed between captures: " +
                "before=[" + left + "," + top + "][" + right + "," + bottom +
                "] after=[" + other.left + "," + other.top + "][" +
                other.right + "," + other.bottom + "]. " +
                "Cannot attribute differences to sound or controls."
        }
    }

    /** Meaningful per-channel difference; 1-LSB compositing jitter is ignored. */
    fun countMaxChannelChanged(other: NativeCanvasPixelEvidence, threshold: Int = 12): Int {
        requireSameRegion(other)
        var changed = 0
        for (index in pixels.indices) {
            val a = pixels[index]
            val b = other.pixels[index]
            val diff = maxOf(
                abs(Color.red(a) - Color.red(b)),
                abs(Color.green(a) - Color.green(b)),
                abs(Color.blue(a) - Color.blue(b)),
            )
            if (diff >= threshold) changed++
        }
        return changed
    }

    /** Normalized RGB-sum threshold scaled to real 8-bit channel values. */
    fun countRgbSumChanged(other: NativeCanvasPixelEvidence, threshold: Int): Int {
        requireSameRegion(other)
        var changed = 0
        for (index in pixels.indices) {
            val a = pixels[index]
            val b = other.pixels[index]
            val diff = abs(Color.red(a) - Color.red(b)) +
                abs(Color.green(a) - Color.green(b)) +
                abs(Color.blue(a) - Color.blue(b))
            if (diff >= threshold) changed++
        }
        return changed
    }

    fun luminousPixels(minChannel: Int = 36): Int = pixels.count { pixel ->
        maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) >= minChannel
    }

    fun colorfulPixels(minChannel: Int = 64, minSpread: Int = 31): Int =
        pixels.count { pixel ->
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            maxOf(r, g, b) >= minChannel &&
                maxOf(r, g, b) - minOf(r, g, b) >= minSpread
        }

    fun regionLabel(): String =
        "[" + left + "," + top + "][" + right + "," + bottom +
            "]@" + displayWidth + "x" + displayHeight
}

internal object NativeCanvasPixelCapture {
    /**
     * UiAutomation returns a real compositor screen frame. Only display
     * pixels inside the exact input Compose node are sampled. The full-frame
     * Bitmap is recycled immediately and never kept between assertions.
     */
    fun capture(
        nodeBoundsInWindow: Rect,
        decorScreenX: Int,
        decorScreenY: Int,
        expectedForegroundPackage: String,
        columns: Int = 48,
        rows: Int = 48,
    ): NativeCanvasPixelEvidence {
        require(columns in 8..128 && rows in 8..128)

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val foreground = automation.rootInActiveWindow?.packageName?.toString()
        assertTrue(
            "Native framebuffer foreground mismatch: root=" + foreground +
                " activity=" + expectedForegroundPackage,
            foreground == expectedForegroundPackage,
        )

        var screenshot: Bitmap? = null
        for (attempt in 1..5) {
            screenshot = automation.takeScreenshot()
            if (screenshot != null) break
            Thread.sleep(150L)
        }
        val frame = screenshot
            ?: throw AssertionError("No real Android display framebuffer after five attempts")
        try {
            val left = (nodeBoundsInWindow.left + decorScreenX)
                .roundToInt().coerceIn(0, frame.width)
            val right = (nodeBoundsInWindow.right + decorScreenX)
                .roundToInt().coerceIn(0, frame.width)
            val top = (nodeBoundsInWindow.top + decorScreenY)
                .roundToInt().coerceIn(0, frame.height)
            val bottom = (nodeBoundsInWindow.bottom + decorScreenY)
                .roundToInt().coerceIn(0, frame.height)
            assertTrue(
                "Actual Compose Canvas ROI missing from display: [" +
                    left + "," + top + "][" + right + "," + bottom +
                    "] frame=" + frame.width + "x" + frame.height,
                right - left >= 64 && bottom - top >= 64,
            )

            val samples = IntArray(columns * rows)
            for (y in 0 until rows) {
                val actualY = top + y * (bottom - top - 1) / (rows - 1)
                for (x in 0 until columns) {
                    val actualX = left + x * (right - left - 1) / (columns - 1)
                    samples[y * columns + x] = frame.getPixel(actualX, actualY)
                }
            }
            return NativeCanvasPixelEvidence(
                left, top, right, bottom, frame.width, frame.height, samples,
            )
        } finally {
            frame.recycle()
        }
    }
}
