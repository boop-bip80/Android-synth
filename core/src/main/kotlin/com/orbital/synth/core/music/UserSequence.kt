package com.orbital.synth.core.music

/**
 * Named, editable sequencer program. Unlike factory patterns, these values can
 * be saved by the app into a private user bank. IDs are filesystem-safe.
 *
 * BPM and swing belong to a sequence snapshot so switching user programs
 * restores their intended groove, without depending on a running transport.
 */
data class UserSequence(
    val id: String,
    val name: String,
    val pattern: StepPattern,
    val bpm: Double = 120.0,
    val swing: Double = 0.0,
) {
    init {
        require(validId(id)) { "Invalid sequence identifier" }
        require(name.isNotBlank() && name.length <= 80 && name.none(Char::isISOControl)) {
            "Invalid sequence name"
        }
        require(bpm.isFinite() && bpm in 20.0..300.0) { "BPM must be between 20 and 300" }
        require(swing.isFinite() && swing in 0.0..1.0) { "Swing must be between 0 and 1" }
    }

    companion object {
        private val ID_RULE = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        fun validId(id: String): Boolean = ID_RULE.matches(id)
    }
}
