package com.orbital.synth.ui

/**
 * A SOUND preview never invents a new synth parameter or audio pipeline.
 * It opens one of the existing editors connected to the host synthesis engine.
 */
enum class ReferenceMiniSignalDestination(
    val editorLabel: String,
) {
    ENVELOPE("ADSR envelope"),
    LFO("LFO source controls"),
    EXPRESSION("XY routing modes"),
    MODULATION("64-route modulation matrix");

    /**
     * The 64-route editor is not advertised as tappable until its live 8-LFO
     * state, route state and mutation callbacks are supplied by the host.
     */
    fun canOpen(modulationRackConnected: Boolean): Boolean =
        this != MODULATION || modulationRackConnected
}
