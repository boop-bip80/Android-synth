package com.orbital.synth.core.stage

/**
 * Melodies a player can learn on the stage. Only traditional and public-domain
 * tunes are included, written as simple one-hand arrangements (single melody line)
 * — they are teaching sketches, not transcriptions of any recording.
 *
 * Notation: `step:beats`, steps counted from the tonic in the tune's native
 * scale. Fractions such as `1/3` are allowed for triplets.
 */
object SongBook {
    private fun song(
        id: String, title: String, origin: String, scaleId: String = "major",
        bpm: Int, difficulty: Int, tonic: Int, notes: String,
    ) = Song(id, title, origin, scaleId, bpm, difficulty, tonic, Song.parse(notes))

    // Phrase building blocks, reused so the tunes stay readable and consistent.
    private const val ODE_A = "2:1 2:1 3:1 4:1 4:1 3:1 2:1 1:1 0:1 0:1 1:1 2:1"
    private const val ODE_END_1 = "2:1.5 1:.5 1:2"
    private const val ODE_END_2 = "1:1.5 0:.5 0:2"
    private const val ODE_B = "1:1 1:1 2:1 0:1 1:1 2:.5 3:.5 2:1 0:1 1:1 2:.5 3:.5 2:1 1:1 0:1 1:1 -3:2"

    val ALL: List<Song> = listOf(
        song(
            "climb", "Climb the Lanes", "Your first lesson",
            bpm = 96, difficulty = 1, tonic = 0,
            notes = "0:1 1:1 2:1 3:1 4:1 5:1 6:1 7:2 6:1 5:1 4:1 3:1 2:1 1:1 0:2",
        ),
        song(
            "leap", "Three-Note Leaps", "Chord tones, root-third-fifth",
            bpm = 92, difficulty = 1, tonic = 0,
            notes = "0:1 2:1 4:1 7:2 4:1 2:1 0:2 1:1 3:1 5:1 8:2 5:1 3:1 1:2 0:1 2:1 4:1 7:4",
        ),
        song(
            "twinkle", "Twinkle, Twinkle, Little Star", "Traditional · Ah! vous dirai-je, maman",
            bpm = 100, difficulty = 1, tonic = 0,
            notes = "0:1 0:1 4:1 4:1 5:1 5:1 4:2 3:1 3:1 2:1 2:1 1:1 1:1 0:2 " +
                "4:1 4:1 3:1 3:1 2:1 2:1 1:2 4:1 4:1 3:1 3:1 2:1 2:1 1:2 " +
                "0:1 0:1 4:1 4:1 5:1 5:1 4:2 3:1 3:1 2:1 2:1 1:1 1:1 0:2",
        ),
        song(
            "ode", "Ode to Joy", "Beethoven · 9th Symphony, 1824",
            bpm = 108, difficulty = 1, tonic = 7,
            notes = "$ODE_A $ODE_END_1 $ODE_A $ODE_END_2 $ODE_B $ODE_A $ODE_END_2",
        ),
        song(
            "jacques", "Frère Jacques", "Traditional · France",
            bpm = 96, difficulty = 1, tonic = 5,
            notes = "0:1 1:1 2:1 0:1 0:1 1:1 2:1 0:1 2:1 3:1 4:2 2:1 3:1 4:2 " +
                "4:.5 5:.5 4:.5 3:.5 2:1 0:1 4:.5 5:.5 4:.5 3:.5 2:1 0:1 " +
                "0:1 -3:1 0:2 0:1 -3:1 0:2",
        ),
        song(
            "lamb", "Mary Had a Little Lamb", "Traditional · 1830",
            bpm = 108, difficulty = 1, tonic = 0,
            notes = "2:1 1:1 0:1 1:1 2:1 2:1 2:2 1:1 1:1 1:2 2:1 4:1 4:2 " +
                "2:1 1:1 0:1 1:1 2:1 2:1 2:1 2:1 1:1 1:1 2:1 1:1 0:4",
        ),
        song(
            "buns", "Hot Cross Buns", "Traditional · London street cry",
            bpm = 100, difficulty = 1, tonic = 0,
            notes = "2:1 1:1 0:2 2:1 1:1 0:2 0:.5 0:.5 0:.5 0:.5 1:.5 1:.5 1:.5 1:.5 2:1 1:1 0:2",
        ),
        song(
            "lune", "Au Clair de la Lune", "Traditional · France",
            bpm = 96, difficulty = 1, tonic = 7,
            notes = "0:1 0:1 0:1 1:1 2:2 1:2 0:1 2:1 1:1 1:1 0:4 " +
                "0:1 0:1 0:1 1:1 2:2 1:2 0:1 2:1 1:1 1:1 0:4",
        ),
        song(
            "london", "London Bridge", "Traditional · England",
            bpm = 104, difficulty = 2, tonic = 7,
            notes = "4:1.5 5:.5 4:1 3:1 2:1 3:1 4:2 1:1 2:1 3:2 2:1 3:1 4:2 " +
                "4:1.5 5:.5 4:1 3:1 2:1 3:1 4:2 1:2 4:2 2:2 0:2",
        ),
        song(
            "boat", "Row, Row, Row Your Boat", "Traditional · 1852",
            bpm = 100, difficulty = 2, tonic = 0,
            notes = "0:1 0:1 0:.75 1:.25 2:1 2:.75 1:.25 2:.75 3:.25 4:2 " +
                "7:1/3 7:1/3 7:1/3 4:1/3 4:1/3 4:1/3 2:1/3 2:1/3 2:1/3 0:1/3 0:1/3 0:1/3 " +
                "4:.75 3:.25 2:.75 1:.25 0:2",
        ),
        song(
            "jingle", "Jingle Bells (chorus)", "Pierpont · 1857",
            bpm = 120, difficulty = 2, tonic = 0,
            notes = "2:1 2:1 2:2 2:1 2:1 2:2 2:1 4:1 0:1.5 1:.5 2:4 " +
                "3:1 3:1 3:1 3:1 3:1 2:1 2:1 2:1 2:1 4:1 4:1 3:1 1:1 0:4",
        ),
        song(
            "susanna", "Oh! Susanna", "Foster · 1848",
            scaleId = "major_pentatonic", bpm = 112, difficulty = 2, tonic = 0,
            notes = "0:.5 1:.5 2:1 3:1 3:1.5 4:.5 3:1 2:1 0:1 1:1 2:1 2:1 1:1 0:1 1:2 " +
                "0:.5 1:.5 2:1 3:1 3:1.5 4:.5 3:1 2:1 0:1 1:1 2:1 2:1 1:1 1:1 0:3",
        ),
        song(
            "canon", "Canon in D (opening theme)", "Pachelbel · c. 1680",
            bpm = 76, difficulty = 3, tonic = 2,
            notes = "2:2 1:2 0:2 -1:2 -2:2 -3:2 -2:2 -1:2 " +
                "0:2 -1:2 -2:2 -3:2 -4:2 -5:2 -4:2 -3:2 " +
                "2:2 1:2 0:2 -1:2 -2:2 -3:2 -2:2 -1:2 0:6",
        ),
    )

    fun byId(id: String): Song? = ALL.firstOrNull { it.id == id }
}
