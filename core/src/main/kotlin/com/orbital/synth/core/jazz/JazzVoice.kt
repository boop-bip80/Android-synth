package com.orbital.synth.core.jazz

/** How a voice behaves in time; the per-tune tailoring treats each family differently. */
enum class VoiceFamily {
    /** Struck or hammered keys: percussive attack, finite decay. */
    KEYS,

    /** Plucked strings from the physical-model source. */
    PLUCK,

    /** Struck bars from the modal source. */
    MALLET,

    /** Sustained, breath-like leads. */
    WIND,

    /** Sustained ensemble sounds that swell in. */
    SUSTAIN,
}

/**
 * The instrument family a jazz standard is played with by default. Every voice is built
 * from the ORBITAL engine itself (physical-model sources, the FM / SEM / ladder / phase
 * distortion architectures, the additive oscillator rack and the six-slot FX chain); none
 * is a sample. The names describe the *role* in a jazz ensemble the voice is shaped for,
 * not a claim to reproduce a particular instrument.
 */
enum class JazzVoice(
    /** Short token used in the catalogue data file. */
    val code: String,
    val label: String,
    val family: VoiceFamily,
    /** One line shown to the player under the title. */
    val blurb: String,
) {
    SUITCASE_EP("ep", "Suitcase Keys", VoiceFamily.KEYS, "tine-style FM electric piano with a slow auto-pan"),
    STUDIO_PIANO("piano", "Studio Hammer", VoiceFamily.KEYS, "clear, quick-decaying FM piano"),
    DRAWBAR_ORGAN("organ", "Drawbar Organ", VoiceFamily.SUSTAIN, "additive organ drawbars through a rotary-style chorus"),
    ARCHTOP_GUITAR("guitar", "Archtop Guitar", VoiceFamily.PLUCK, "round plucked string through a warm ladder filter"),
    NYLON_BOSSA("nylon", "Nylon Bossa", VoiceFamily.PLUCK, "soft nylon-string pluck"),
    UPRIGHT_BASS("bass", "Upright Bass", VoiceFamily.PLUCK, "dark, woody low pluck for walking lines"),
    VIBRAPHONE("vibes", "Vibraphone", VoiceFamily.MALLET, "ringing bars with a motor tremolo"),
    MARIMBA("marimba", "Marimba", VoiceFamily.MALLET, "dry, woody tuned bars"),
    MUTED_TRUMPET("trumpet", "Muted Trumpet", VoiceFamily.WIND, "pinched, nasal SEM brass"),
    TENOR_SAX("sax", "Tenor Breath", VoiceFamily.WIND, "reedy ladder lead with breath noise and vibrato"),
    FLUTE("flute", "Silver Flute", VoiceFamily.WIND, "soft phase-distortion flute with a breathy edge"),
    BALLAD_STRINGS("strings", "Velvet Strings", VoiceFamily.SUSTAIN, "slow SEM strings that breathe with the tempo"),
    CLAV_FUNK("clav", "Funk Clav", VoiceFamily.KEYS, "short, plucky clavinet-style phase-distortion keys");

    companion object {
        fun byCode(code: String): JazzVoice? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The rhythmic character of a standard. It chooses the suggested tempo, the beats per bar
 * shown to the player and the instrument a tune starts with.
 */
enum class JazzFeel(
    val code: String,
    val label: String,
    val defaultBpm: Int,
    val beatsPerBar: Int,
    val defaultVoice: JazzVoice,
) {
    BALLAD("ballad", "Ballad", 66, 4, JazzVoice.SUITCASE_EP),
    MEDIUM("medium", "Medium swing", 132, 4, JazzVoice.STUDIO_PIANO),
    UP("up", "Up-tempo swing", 200, 4, JazzVoice.ARCHTOP_GUITAR),
    BLUES("blues", "Blues", 120, 4, JazzVoice.DRAWBAR_ORGAN),
    BOSSA("bossa", "Bossa nova", 138, 4, JazzVoice.NYLON_BOSSA),
    LATIN("latin", "Latin", 176, 4, JazzVoice.MARIMBA),
    WALTZ("waltz", "Jazz waltz", 150, 3, JazzVoice.STUDIO_PIANO),
    FUNK("funk", "Funk / soul-jazz", 108, 4, JazzVoice.CLAV_FUNK),
    MODAL("modal", "Modal", 120, 4, JazzVoice.MUTED_TRUMPET),
    ODD("odd", "Odd meter", 160, 5, JazzVoice.VIBRAPHONE);

    companion object {
        fun byCode(code: String): JazzFeel? = entries.firstOrNull { it.code == code }
    }
}
