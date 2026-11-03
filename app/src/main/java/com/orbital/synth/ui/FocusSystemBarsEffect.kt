package com.orbital.synth.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Android OS chrome is hidden ONLY for the performer's opt-in clean canvas.
 *
 * The owner retains Android Back and transient edge-swipe system navigation.
 * We never mutate activity-wide window flags in MainActivity: a scoped effect
 * restores the previous per-bar visibility and behavior on focus reveal,
 * disposal, Activity ON_STOP, and configuration changes.
 *
 * A preview host without an Activity simply keeps the normal Compose canvas.
 */
@Composable
internal fun FocusSystemBarsEffect(focusMode: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { findHostActivity(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(focusMode, activity, lifecycleOwner) {
        if (!focusMode || activity == null) {
            onDispose { }
        } else {
            val decorView = activity.window.decorView
            val controller = WindowCompat.getInsetsController(activity.window, decorView)
            val insets = ViewCompat.getRootWindowInsets(decorView)

            // Preserve the host's original status/nav policy independently. A
            // window may legitimately hide one bar before entering our stage.
            val statusWasVisible =
                insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
            val navigationWasVisible =
                insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
            val previousBehavior = controller.systemBarsBehavior
            var hiddenByFocus = false

            fun hideForFocus() {
                if (hiddenByFocus) return
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
                hiddenByFocus = true
            }

            fun restoreHostBars() {
                if (!hiddenByFocus) return
                controller.systemBarsBehavior = previousBehavior
                val status = WindowInsetsCompat.Type.statusBars()
                val navigation = WindowInsetsCompat.Type.navigationBars()
                val toShow =
                    (if (statusWasVisible) status else 0) or
                        (if (navigationWasVisible) navigation else 0)
                val toHide =
                    (if (statusWasVisible) 0 else status) or
                        (if (navigationWasVisible) 0 else navigation)
                if (toShow != 0) controller.show(toShow)
                if (toHide != 0) controller.hide(toHide)
                hiddenByFocus = false
            }

            val lifecycleObserver = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> hideForFocus()
                    Lifecycle.Event.ON_STOP -> restoreHostBars()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                hideForFocus()
            }

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
                restoreHostBars()
            }
        }
    }
}

private tailrec fun findHostActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> {
        val base = context.baseContext
        if (base === context) null else findHostActivity(base)
    }
    else -> null
}
