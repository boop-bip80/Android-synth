package com.orbital.synth.ui

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock

/**
 * The OS-owned window and active accessibility root are foreground proof.
 *
 * On API 35 under UiAutomation, a retained Activity decor can report
 * hasWindowFocus() == false even while WindowManager focuses MainActivity.
 * Its local focus bit must not override the actual user-facing window.
 * Component names alone are insufficient: the visible accessibility root
 * must belong to the same application too.
 */
internal data class MainWindowEvidence(
    val currentWindow: String,
    val focusedActivity: String,
    val accessibleRootPackage: String?,
) {
    val isRealMainActivityForeground: Boolean get() =
        currentWindow.contains("mCurrentFocus=Window{") &&
            currentWindow.contains("com.orbital.synth/com.orbital.synth.MainActivity") &&
            focusedActivity.contains("mFocusedApp=ActivityRecord{") &&
            focusedActivity.contains("com.orbital.synth/.MainActivity") &&
            accessibleRootPackage == "com.orbital.synth"

    override fun toString(): String =
        "current=${currentWindow.take(260)} focused=${focusedActivity.take(260)} " +
            "accessibilityPackage=$accessibleRootPackage"
}

internal fun foregroundShell(automation: UiAutomation, command: String): String {
    val descriptor = automation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        .bufferedReader().use { it.readText() }
}

internal fun probeMainActivityForeground(automation: UiAutomation): MainWindowEvidence {
    val windowDump = foregroundShell(automation, "dumpsys window")
    val current = windowDump.lineSequence()
        .firstOrNull { it.contains("mCurrentFocus=") }.orEmpty().trim()
    val focused = windowDump.lineSequence()
        .firstOrNull { it.contains("mFocusedApp=") }.orEmpty().trim()
    // UiAutomation reports the real currently visible accessibility window.
    // A Compose test tree can be drawn in an unfocused Activity, so it does
    // not constitute user-facing foreground proof on its own.
    val activePackage = runCatching {
        automation.rootInActiveWindow?.packageName?.toString()
    }.getOrNull()
    return MainWindowEvidence(current, focused, activePackage)
}

internal fun awaitMainActivityForeground(
    automation: UiAutomation,
    timeoutMs: Long = 12_000L,
): MainWindowEvidence {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    var evidence = probeMainActivityForeground(automation)
    while (!evidence.isRealMainActivityForeground &&
        SystemClock.uptimeMillis() < deadline
    ) {
        SystemClock.sleep(180L)
        evidence = probeMainActivityForeground(automation)
    }
    return evidence
}
