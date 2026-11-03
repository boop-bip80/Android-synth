package com.orbital.synth.ui

import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.util.ArrayDeque

/**
 * AOSP first-run immersive navigation education belongs to Android SystemUI.
 *
 * Only when dumpsys confirms the EXACT focused ImmersiveModeConfirmation
 * window over the real ORBITAL MainActivity do we look for its known OK
 * control. Never press Back, change secure settings, or dismiss a different
 * OS dialog. The caller must still verify app focus and real framebuffer.
 */
internal object AndroidImmersiveTutorial {
    private const val TAG = "OrbitalImmersiveTutorial"
    private const val MAX_VISITED_NODES = 320
    private const val MAX_NODES_PER_ROOT = 96

    fun acknowledgeOnlyIfSystemOverlayOwnsFocus(): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val focusedLines = runCatching {
            val descriptor = automation.executeShellCommand("dumpsys window")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { input ->
                input.lineSequence()
                    .filter { "mCurrentFocus=" in it || "mFocusedApp=" in it }
                    .take(4)
                    .toList()
            }
        }.getOrElse {
            Log.w(TAG, "Cannot inspect foreground window: ${it.javaClass.simpleName}")
            return false
        }
        val currentWindow = focusedLines.firstOrNull { "mCurrentFocus=" in it }.orEmpty()
        val focusedApp = focusedLines.firstOrNull { "mFocusedApp=" in it }.orEmpty()
        if (!currentWindow.contains(" ImmersiveModeConfirmation}")) return false
        if (!focusedApp.contains("com.orbital.synth/.MainActivity")) {
            Log.w(TAG, "Immersive tutorial detected, but ORBITAL is not the focused app: " +
                focusedApp.trim().take(200))
            return false
        }

        // On the fresh API35 emulator the active accessibility root may lag
        // the new SystemUI dialog. Inspect bounded, real accessibility windows
        // as well, while retaining the exact OS-focus precondition above.
        val roots = mutableListOf<AccessibilityNodeInfo>()
        automation.rootInActiveWindow?.let(roots::add)
        runCatching { automation.windows }.getOrNull()?.take(12)?.forEach { window ->
            window.root?.let(roots::add)
        }
        if (roots.isEmpty()) {
            Log.w(TAG, "Confirmed ImmersiveModeConfirmation owns focus; no accessibility root yet")
            return false
        }

        val treeEvidence = mutableListOf<String>()
        var visited = 0
        for (root in roots) {
            val rootPackage = root.packageName?.toString().orEmpty()
            if (rootPackage != "com.android.systemui" && rootPackage != "android") {
                if (treeEvidence.size < 26) treeEvidence += "ignored unrelated root: $rootPackage"
                continue
            }
            val nodes = ArrayDeque<AccessibilityNodeInfo>()
            nodes.add(root)
            var visitedThisRoot = 0
            while (nodes.isNotEmpty() && visited < MAX_VISITED_NODES &&
                visitedThisRoot < MAX_NODES_PER_ROOT) {
                val node = nodes.removeFirst()
                visited++
                visitedThisRoot++
                val packageName = node.packageName?.toString()
                    ?: root.packageName?.toString().orEmpty()
                val className = node.className?.toString().orEmpty()
                val resourceId = node.viewIdResourceName.orEmpty()
                val label = node.text?.toString()?.trim()?.lowercase().orEmpty()
                val description =
                    node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                if (treeEvidence.size < 26) {
                    treeEvidence +=
                        "pkg=$packageName class=$className id=$resourceId " +
                            "text=${label.take(36)} desc=${description.take(36)} " +
                            "clickable=${node.isClickable}"
                }

                val isSystemDialogNode = packageName == "com.android.systemui" ||
                    packageName == "android"
                val exactKnownButtonId = resourceId == "com.android.systemui:id/ok" ||
                    resourceId == "android:id/ok" ||
                    resourceId == "android:id/button1"
                val exactKnownButtonLabel =
                    (label == "got it" || label == "ok" ||
                        description == "got it" || description == "ok") &&
                        className == "android.widget.Button"
                if (isSystemDialogNode && node.isClickable &&
                    (exactKnownButtonId || exactKnownButtonLabel)) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Exact AOSP immersive OK: clicked=$clicked " +
                        "id=$resourceId label=$label package=$packageName " +
                        "visited=$visited")
                    return clicked
                }
                for (index in 0 until node.childCount.coerceAtMost(32)) {
                    node.getChild(index)?.let(nodes::addLast)
                }
            }
        }
        Log.w(TAG, "Confirmed focused ImmersiveModeConfirmation but no exact AOSP OK " +
            "button found; roots=${roots.size} visited=$visited tree=" +
            treeEvidence.joinToString(" | ").take(3000))
        return false
    }
}
