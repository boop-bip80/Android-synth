package com.orbital.synth.ui

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.compositionLocalOf

/**
 * Scoped, one-shot SOUND-mini navigation into the existing eight-source
 * LFO editor. Only an explicit mini-card tap supplies this value. No routing
 * event or scroll can leak to another user's FX tab or synth instance.
 */
internal data class LfoGardenRevealRequest(
    val requester: BringIntoViewRequester,
    val laneIndex: Int,
    val navigationEpoch: Int,
)

internal val LocalLfoGardenRevealRequest =
    compositionLocalOf<LfoGardenRevealRequest?> { null }
