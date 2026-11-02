package com.orbital.synth.core.music

/** Ready-to-play compositions for the SEQ page, not sound presets. */
data class FactorySequence(
    val id: String,
    val name: String,
    val pattern: StepPattern,
    val bpm: Double,
    val swing: Double = 0.0,
) {
    init {
        require(Regex("[a-z0-9-]+").matches(id))
        require(name.isNotBlank())
        require(bpm.isFinite() && bpm in 20.0..300.0)
        require(swing.isFinite() && swing in 0.0..1.0)
    }
}

object FactoryPatterns {
    private fun step(vararg notes: SequencerNote) = SequencerStep(notes.toList())
    private fun n(pitch: Int, velocity: Int = 100, gate: Double = .72,
                  probability: Double = 1.0, tie: Boolean = false) =
        SequencerNote(pitch, velocity, gate, probability, tie)

    val AURORA = FactorySequence(
        "aurora", "Aurora Orbit", StepPattern(listOf(
            step(n(60, 95, .95), n(67, 80, .95)),
            step(), step(n(64, 76)), step(n(67, 82)),
            step(n(72, 112)), step(), step(n(67, 74)), step(n(64, 83)),
            step(n(57, 95), n(64, 77)),
            step(), step(n(60, 79)), step(n(64, 91)),
            step(n(69, 109)), step(), step(n(64, 82)), step(n(60, 77)),
        )), bpm = 112.0, swing = 0.10,
    )

    val COMET_ARP = FactorySequence(
        "comet", "Comet Cascade", StepPattern(List(16) {
            val pitch = listOf(48, 55, 60, 64, 67, 72, 76, 79, 76, 72, 67, 64, 60, 55, 52, 55)[it]
            step(n(pitch, velocity = if (it % 4 == 0) 117 else 89,
                gate = .49, probability = if (it % 4 == 3) .75 else 1.0))
        }), bpm = 138.0,
    )

    val ION_BASS = FactorySequence(
        "ion-bass", "Ion Bassline", StepPattern(listOf(
            step(n(36, 124, .80)), step(), step(n(36, 84, .45)), step(n(43, 105, .70)),
            step(n(36, 110, .82)), step(), step(n(39, 88, .42)), step(),
            step(n(34, 121, .81)), step(), step(n(41, 91, .48)), step(n(43, 99, .56)),
            step(n(36, 120, .82)), step(), step(n(48, 96, .35)), step(n(43, 90, .38)),
        )), bpm = 126.0, swing = .25,
    )

    val CHORD_CLOUD = FactorySequence(
        "chord-cloud", "Chord Cloud", StepPattern(listOf(
            step(n(60, 84, 1.0), n(64, 81, 1.0), n(67, 78, 1.0)),
            step(n(60, tie = true, gate = 1.0), n(64, tie = true, gate = 1.0),
                n(67, tie = true, gate = 1.0)),
            step(n(60, tie = true, gate = .98), n(64, tie = true, gate = .98),
                n(67, tie = true, gate = .98)),
            step(),
            step(n(57, 83, 1.0), n(60, 80, 1.0), n(64, 77, 1.0)),
            step(n(57, tie = true, gate = 1.0), n(60, tie = true, gate = 1.0),
                n(64, tie = true, gate = 1.0)),
            step(n(57, tie = true, gate = .98), n(60, tie = true, gate = .98),
                n(64, tie = true, gate = .98)),
            step(),
            step(n(53, 86, 1.0), n(57, 81, 1.0), n(60, 79, 1.0)),
            step(n(53, tie = true, gate = 1.0), n(57, tie = true, gate = 1.0),
                n(60, tie = true, gate = 1.0)),
            step(n(53, tie = true, gate = .98), n(57, tie = true, gate = .98),
                n(60, tie = true, gate = .98)),
            step(),
            step(n(55, 86, 1.0), n(59, 80, 1.0), n(62, 77, 1.0)),
            step(n(55, tie = true, gate = 1.0), n(59, tie = true, gate = 1.0),
                n(62, tie = true, gate = 1.0)),
            step(n(55, tie = true, gate = .98), n(59, tie = true, gate = .98),
                n(62, tie = true, gate = .98)),
            step(),
        )), bpm = 88.0,
    )

    val PRISM_PULSE = FactorySequence(
        "prism-pulse", "Prism Pulse", StepPattern(List(16) { index ->
            when (index % 4) {
                0 -> step(n(72 + index / 4 * 2, 115, .20))
                2 -> step(n(84 - index / 4 * 2, 82, .25, probability = .64))
                else -> step()
            }
        }), bpm = 142.0, swing = .08,
    )

    val LUNAR_PLUCK = FactorySequence(
        "lunar-pluck", "Lunar Pluck", StepPattern(List(16) { index ->
            val notes = listOf(60, 64, 67, 71, 72, 67, 64, 59, 60, 62, 65, 69, 72, 69, 65, 62)
            if (index % 7 == 3) step() else step(n(notes[index],
                velocity = 75 + index % 4 * 11, gate = .31,
                probability = if (index % 5 == 0) .85 else 1.0))
        }), bpm = 104.0, swing = .12,
    )

    /** Interlocking 5:3:7 Euclidean attacks on one sixteen-step grid. */
    val NEBULA_CIRCUIT = FactorySequence(
        "nebula-circuit", "Nebula Circuit",
        PatternComposer.layer(
            PatternComposer.euclidean(16, 5, 48, velocity = 105, gate = .40,
                accentEvery = 2, accentVelocity = 124),
            PatternComposer.euclidean(16, 3, 67, rotation = 2, velocity = 83, gate = .68),
            PatternComposer.euclidean(16, 7, 76, rotation = 6, velocity = 77, gate = .28),
        ),
        bpm = 118.0, swing = .08,
    )

    /** Syncopated bass pulse against a sparse high-register response. */
    val GRAVITY_GARDEN = FactorySequence(
        "gravity-garden", "Gravity Garden",
        PatternComposer.layer(
            PatternComposer.euclidean(16, 7, 36, velocity = 104, gate = .61,
                accentEvery = 3, accentVelocity = 126),
            PatternComposer.euclidean(16, 4, 72, rotation = 3, velocity = 86, gate = .34),
            PatternComposer.euclidean(16, 9, 79, rotation = 1, velocity = 68, gate = .22),
        ),
        bpm = 132.0, swing = .22,
    )

    val ALL: List<FactorySequence> =
        listOf(AURORA, COMET_ARP, ION_BASS, CHORD_CLOUD, PRISM_PULSE, LUNAR_PLUCK,
            NEBULA_CIRCUIT, GRAVITY_GARDEN)

    val DEFAULT: FactorySequence get() = AURORA
    fun byId(id: String): FactorySequence? = ALL.firstOrNull { it.id == id }
}
