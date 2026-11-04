package com.orbital.synth.ui

import android.app.Activity
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Genuine Android SurfaceFlinger screenshot of the *actual foreground test Activity*.
 *
 * Compose captureToImage() uses PixelCopy and can time out late in API35's
 * instrumented graphics suite despite a healthy screen. This helper does not
 * synthesize pixels or relax assertions: it verifies OS foreground ownership,
 * an exact-Activity physical magenta marker, then removes that marker before
 * sampling UiAutomation.takeScreenshot() within measured Compose-node bounds.
 */
internal class RealAndroidFrameCapture(
    private val compose: ComposeTestRule,
    private val activity: Activity,
) {
    private val automation: UiAutomation =
        InstrumentationRegistry.getInstrumentation().uiAutomation
    private var provenForeground = false

    fun capture(node: SemanticsNodeInteraction, label: String): RealAndroidPixelRegion {
        compose.waitForIdle()
        if (!provenForeground) {
            proveThisActivityIsForeground()
            provenForeground = true
        }

        val rootPackage = runCatching {
            automation.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
        val currentWindow = foregroundShell(automation, "dumpsys window")
            .lineSequence().firstOrNull { it.contains("mCurrentFocus=") }.orEmpty().trim()
        if (!belongsToSynthWindow(currentWindow) ||
            rootPackage?.startsWith("com.orbital.synth") != true
        ) {
            throw AssertionError(
                "Real screenshot capture lost OS foreground app; current=" +
                    currentWindow + " root=" + rootPackage,
            )
        }

        val windowBounds = node.fetchSemanticsNode().boundsInWindow
        val decorPosition = IntArray(2)
        compose.runOnIdle {
            activity.window.decorView.getLocationOnScreen(decorPosition)
        }
        // Android's actual frame, after Compose layout and a compositor frame.
        // Never substitute a Compose bitmap or generated reference image.
        Thread.sleep(160L)
        val frame = requireRealScreenShot(label)
        try {
            val left = floor(windowBounds.left + decorPosition[0]).toInt().coerceIn(0, frame.width)
            val top = floor(windowBounds.top + decorPosition[1]).toInt().coerceIn(0, frame.height)
            val right = ceil(windowBounds.right + decorPosition[0]).toInt().coerceIn(0, frame.width)
            val bottom = ceil(windowBounds.bottom + decorPosition[1]).toInt().coerceIn(0, frame.height)
            if (right - left < 2 || bottom - top < 2) {
                throw AssertionError(
                    "Visible " + label + " node is outside actual Android framebuffer; " +
                        "windowNode=" + windowBounds +
                        " decor=" + decorPosition.contentToString() +
                        " screen=" + frame.width + "x" + frame.height,
                )
            }
            println(
                "REAL_ANDROID_FRAME label=" + label +
                    " ROI=[" + left + "," + top + "][" + right + "," + bottom + "]" +
                    " screen=" + frame.width + "x" + frame.height +
                    " root=" + rootPackage +
                    " localFocus=" + activity.window.decorView.hasWindowFocus(),
            )
            return RealAndroidPixelRegion(frame, left, top, right - left, bottom - top)
        } catch (error: Throwable) {
            frame.recycle()
            throw error
        }
    }

    private fun proveThisActivityIsForeground() {
        val marker = View(activity).apply {
            setBackgroundColor(Color.MAGENTA)
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val markerPx = (20f * activity.resources.displayMetrics.density)
            .roundToInt().coerceAtLeast(20)
        compose.runOnIdle {
            activity.addContentView(
                marker,
                FrameLayout.LayoutParams(markerPx, markerPx, Gravity.END or Gravity.BOTTOM),
            )
        }
        var strongestMarker = 0
        var evidence = "not collected"
        var recoveredLauncherAnr = false
        try {
            for (attempt in 0 until 12) {
                val dump = foregroundShell(automation, "dumpsys window")
                val currentWindow = dump.lineSequence()
                    .firstOrNull { it.contains("mCurrentFocus=") }.orEmpty().trim()
                val focusedApp = dump.lineSequence()
                    .firstOrNull { it.contains("mFocusedApp=") }.orEmpty().trim()
                val activeRootPackage = runCatching {
                    automation.rootInActiveWindow?.packageName?.toString()
                }.getOrNull()
                val frame = automation.takeScreenshot()
                if (frame != null) {
                    try {
                        var hits = 0
                        for (y in (frame.height * .75f).toInt() until frame.height step 3) {
                            for (x in (frame.width * .75f).toInt() until frame.width step 3) {
                                val pixel = frame.getPixel(x, y)
                                if (Color.red(pixel) >= 245 &&
                                    Color.green(pixel) <= 10 &&
                                    Color.blue(pixel) >= 245
                                ) hits++
                            }
                        }
                        strongestMarker = maxOf(strongestMarker, hits)
                    } finally {
                        frame.recycle()
                    }
                }
                evidence = "current=" + currentWindow +
                    " focused=" + focusedApp + " root=" + activeRootPackage +
                    " markerHits=" + strongestMarker +
                    " localFocus=" + activity.window.decorView.hasWindowFocus()
                // A fresh Google API35 emulator can show an ANR for its HOME
                // launcher *over* an otherwise healthy foreground test Activity.
                // Recover only that exact emulator-system failure, once. Never
                // dismiss a synth ANR or accept pixels while another window owns
                // SurfaceFlinger; the physical magenta marker is still mandatory.
                val emulator = Build.HARDWARE.equals("ranchu", ignoreCase = true) ||
                    Build.HARDWARE.equals("goldfish", ignoreCase = true) ||
                    Build.PRODUCT.startsWith("sdk_")
                if (!recoveredLauncherAnr && emulator &&
                    currentWindow.contains(
                        "Application Not Responding: com.google.android.apps.nexuslauncher",
                    ) &&
                    focusedApp.contains("com.orbital.synth/androidx.activity.ComponentActivity") &&
                    activeRootPackage == "android"
                ) {
                    recoveredLauncherAnr = true
                    println("REAL_ANDROID_EMULATOR_LAUNCHER_ANR_RECOVERY " + evidence)
                    ParcelFileDescriptor.AutoCloseInputStream(
                        automation.executeShellCommand(
                            "am force-stop com.google.android.apps.nexuslauncher",
                        ),
                    ).use { it.readBytes() }
                    Thread.sleep(650L)
                    continue
                }
                if (belongsToSynthWindow(currentWindow) &&
                    activeRootPackage?.startsWith("com.orbital.synth") == true &&
                    strongestMarker >= 50
                ) {
                    println("REAL_ANDROID_FOREGROUND_INSTANCE " + evidence)
                    return
                }
                Thread.sleep(250L)
            }
            throw AssertionError(
                "This exact Compose test Activity did not own the real " +
                    "SurfaceFlinger foreground window (not accepting a launcher frame): " + evidence,
            )
        } finally {
            compose.runOnIdle {
                (marker.parent as? ViewGroup)?.removeView(marker)
            }
            compose.waitForIdle()
            Thread.sleep(150L)
        }
    }

    private fun requireRealScreenShot(label: String): Bitmap {
        for (attempt in 0 until 4) {
            val frame = automation.takeScreenshot()
            if (frame != null && !frame.isRecycled) return frame
            Thread.sleep(170L)
        }
        throw AssertionError(
            "No genuine foreground UiAutomation.takeScreenshot for " + label +
                " after four bounded attempts",
        )
    }

    private fun belongsToSynthWindow(currentWindow: String): Boolean =
        currentWindow.contains("mCurrentFocus=Window{") &&
            (currentWindow.contains("com.orbital.synth/") ||
                currentWindow.contains("com.orbital.synth.test/"))
}

/** Owns one real Android framebuffer; callers must close() to free device RAM. */
internal class RealAndroidPixelRegion(
    private val screen: Bitmap,
    private val left: Int,
    private val top: Int,
    val width: Int,
    val height: Int,
) : Closeable {
    fun argbAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) {
            "Pixel outside captured real Android node: x=" + x + " y=" + y +
                " node=" + width + "x" + height
        }
        return screen.getPixel(left + x, top + y)
    }

    override fun close() {
        screen.recycle()
    }
}
