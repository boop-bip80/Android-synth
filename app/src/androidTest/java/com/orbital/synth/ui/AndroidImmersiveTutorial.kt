package com.orbital.synth.ui

import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.util.ArrayDeque

/**
 * Test-only simulation of a user's first-entry immersive navigation tutorial.
 *
 * Android SystemUI owns the display on a fresh API35 device while its trusted
 * ImmersiveModeConfirmation education window is visible, before the app can
 * ever acquire focus or produce an unobscured physical screenshot.
 *
 * We interact only with that exact focused AOSP window's explicit OK button.
 * This never changes secure settings, dismisses an ANR, or substitutes a fake
 * screenshot. Every caller MUST still assert true Activity window focus and
 * strict black/live framebuffer pixels after returning.
 */
internal object AndroidImmersiveTutorial {
    fun acknowledgeOnlyIfSystemOverlayOwnsFocus(): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val descriptor = automation.executeShellCommand("dumpsys window")
        val immersivePromptHasFocus =
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { stream ->
                stream.lineSequence().any { line ->
                    "mCurrentFocus=" in line && "ImmersiveModeConfirmation" in line
                }
            }
        if (!immersivePromptHasFocus) return false

        val root = automation.rootInActiveWindow ?: return false
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        var traversed = 0
        while (nodes.isNotEmpty() && traversed++ < 100) {
            val node = nodes.removeFirst()
            val resourceId = node.viewIdResourceName.orEmpty()
            val label = node.text?.toString()?.trim()?.lowercase().orEmpty()
            if (node.className?.toString() == "android.widget.Button" &&
                node.isClickable &&
                (resourceId == "com.android.systemui:id/ok" ||
                    label == "got it" || label == "ok")) {
                val acknowledged = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                println(
                    "ORBITAL_SYSTEM_IMMERSIVE_CONFIRMATION acknowledged=$acknowledged " +
                        "buttonId=$resourceId",
                )
                return acknowledged
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(nodes::addLast)
            }
        }
        println("ORBITAL_SYSTEM_IMMERSIVE_CONFIRMATION exact OS overlay lacks accessible OK button")
        return false
    }
}
