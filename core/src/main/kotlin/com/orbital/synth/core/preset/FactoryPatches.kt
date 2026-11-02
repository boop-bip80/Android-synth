package com.orbital.synth.core.preset

/** Curated normalized starting points; each remains editable by the player. */
object FactoryPatches {
    private val neutral = mapOf(
        "WAVE" to 0.25f, "MORPH" to 0.35f,
        "CUTOFF" to 0.60f, "RESONANCE" to 0.20f,
        "MOVEMENT" to 0.35f, "FX_AMOUNT" to 0.20f,
        "ATTACK" to 0.12f, "DECAY" to 0.30f,
        "SUSTAIN" to 0.70f, "RELEASE" to 0.45f,
        "LFO_RATE" to 0.22f, "LFO_DEPTH" to 0.16f, "GLIDE" to 0.00f,
        "UNISON" to 0.00f, "DETUNE" to 0.00f,
    )

    private fun patch(
        id: String,
        name: String,
        description: String,
        vararg tags: String,
        changes: Map<String, Float>,
    ): SynthPatch = SynthPatch(
        id = id, name = name, description = description, tags = tags.toSet(),
        parameters = neutral + changes,
    )

    val ALL: List<SynthPatch> = listOf(
        patch(
            "aurora", "AURORA", "Glass-bright moving wavetable pad, with a wide cinematic bloom.",
            "pad", "lush", "evolving",
            changes = mapOf("WAVE" to .42f, "MORPH" to .72f, "CUTOFF" to .63f,
                "MOVEMENT" to .74f, "FX_AMOUNT" to .71f, "ATTACK" to .53f,
                "SUSTAIN" to .84f, "RELEASE" to .80f, "LFO_DEPTH" to .55f),
        ),
        patch(
            "orbital-glass", "Orbital Glass", "Pristine crystalline harmonics and long ringing tails.",
            "bell", "bright", "ambient",
            changes = mapOf("WAVE" to .83f, "MORPH" to .90f, "CUTOFF" to .86f,
                "RESONANCE" to .39f, "ATTACK" to .02f, "DECAY" to .77f,
                "SUSTAIN" to .12f, "RELEASE" to .84f, "FX_AMOUNT" to .75f),
        ),
        patch(
            "nebula-strings", "Nebula Strings", "Slow attack, diffuse chorus-like movement and warm harmonics.",
            "pad", "strings", "slow",
            changes = mapOf("WAVE" to .27f, "MORPH" to .68f, "CUTOFF" to .47f,
                "ATTACK" to .70f, "RELEASE" to .89f, "MOVEMENT" to .81f,
                "FX_AMOUNT" to .58f, "LFO_RATE" to .16f, "LFO_DEPTH" to .48f),
        ),
        patch(
            "subterra", "Subterra", "Dense sub bass with minimal tail and dark lowpass tone.",
            "bass", "dark", "mono",
            changes = mapOf("WAVE" to .08f, "MORPH" to .14f, "CUTOFF" to .24f,
                "RESONANCE" to .12f, "ATTACK" to .01f, "DECAY" to .19f,
                "SUSTAIN" to .91f, "RELEASE" to .08f, "FX_AMOUNT" to .03f),
        ),
        patch(
            "helios-lead", "Helios Lead", "Focused soaring lead with controlled glide and high overtone motion.",
            "lead", "bright", "expressive",
            changes = mapOf("WAVE" to .74f, "MORPH" to .59f, "CUTOFF" to .78f,
                "RESONANCE" to .51f, "ATTACK" to .01f, "RELEASE" to .27f,
                "GLIDE" to .37f, "LFO_DEPTH" to .22f),
        ),
        patch(
            "lunar-dust", "Lunar Dust", "Hushed, breathy evolving texture with a slow spectral rise.",
            "texture", "ambient", "soft",
            changes = mapOf("WAVE" to .94f, "MORPH" to .82f, "CUTOFF" to .29f,
                "ATTACK" to .81f, "SUSTAIN" to .66f, "RELEASE" to .91f,
                "MOVEMENT" to .93f, "FX_AMOUNT" to .86f),
        ),
        patch(
            "prism-bell", "Prism Bell", "Hard transient spectral bell with animated overtones.",
            "bell", "percussive", "bright",
            changes = mapOf("WAVE" to .92f, "MORPH" to .96f, "CUTOFF" to .95f,
                "RESONANCE" to .29f, "ATTACK" to .00f, "DECAY" to .65f,
                "SUSTAIN" to .00f, "RELEASE" to .48f, "FX_AMOUNT" to .45f),
        ),
        patch(
            "nightfall", "Nightfall Pad", "Velvety dark pad, with very long rise and release.",
            "pad", "warm", "dark",
            changes = mapOf("WAVE" to .18f, "MORPH" to .61f, "CUTOFF" to .22f,
                "RESONANCE" to .35f, "ATTACK" to .87f, "SUSTAIN" to .90f,
                "RELEASE" to .98f, "LFO_DEPTH" to .61f, "FX_AMOUNT" to .67f),
        ),
        patch(
            "ion-bass", "Ion Bass", "Punchy, hard-edged animated bass with a brisk envelope.",
            "bass", "punchy", "electro",
            changes = mapOf("WAVE" to .71f, "MORPH" to .27f, "CUTOFF" to .54f,
                "RESONANCE" to .66f, "ATTACK" to .00f, "DECAY" to .23f,
                "SUSTAIN" to .36f, "RELEASE" to .12f, "FX_AMOUNT" to .08f),
        ),
        patch(
            "comet-arp", "Comet Arp", "Agile sparkling pluck shaped for rhythmic patterns.",
            "arp", "pluck", "bright",
            changes = mapOf("WAVE" to .63f, "MORPH" to .79f, "CUTOFF" to .89f,
                "ATTACK" to .00f, "DECAY" to .18f, "SUSTAIN" to .02f,
                "RELEASE" to .15f, "MOVEMENT" to .67f, "FX_AMOUNT" to .41f),
        ),
        patch(
            "pulsar-keys", "Pulsar Keys", "Warm articulated poly synth keys with lightly moving timbre.",
            "keys", "warm", "poly",
            changes = mapOf("WAVE" to .35f, "MORPH" to .46f, "CUTOFF" to .64f,
                "ATTACK" to .04f, "DECAY" to .41f, "SUSTAIN" to .52f,
                "RELEASE" to .31f, "LFO_DEPTH" to .13f),
        ),
        patch(
            "ether-vox", "Ether Vox", "Airy resonant choir-like tones with an organic slow motion.",
            "vox", "pad", "ethereal",
            changes = mapOf("WAVE" to .88f, "MORPH" to .74f, "CUTOFF" to .58f,
                "RESONANCE" to .62f, "ATTACK" to .62f, "SUSTAIN" to .89f,
                "RELEASE" to .93f, "MOVEMENT" to .91f, "FX_AMOUNT" to .81f),
        ),
        patch(
            "gravity-brass", "Gravity Brass", "Biting animated synth brass with a broad body.",
            "brass", "bold", "poly",
            changes = mapOf("WAVE" to .57f, "MORPH" to .33f, "CUTOFF" to .53f,
                "RESONANCE" to .43f, "ATTACK" to .14f, "DECAY" to .31f,
                "SUSTAIN" to .79f, "RELEASE" to .26f, "FX_AMOUNT" to .17f),
        ),
        patch(
            "quantum-pluck", "Quantum Pluck", "Short bright precision plucks for percussive sequences.",
            "pluck", "short", "digital",
            changes = mapOf("WAVE" to .95f, "MORPH" to .42f, "CUTOFF" to .93f,
                "ATTACK" to .00f, "DECAY" to .12f, "SUSTAIN" to .00f,
                "RELEASE" to .07f, "FX_AMOUNT" to .29f),
        ),
        patch(
            "meteor-drone", "Meteor Drone", "Slow-burning spectral atmosphere with thick movement.",
            "drone", "cinematic", "texture",
            changes = mapOf("WAVE" to .49f, "MORPH" to .94f, "CUTOFF" to .41f,
                "RESONANCE" to .53f, "ATTACK" to .97f, "SUSTAIN" to .99f,
                "RELEASE" to .97f, "MOVEMENT" to .99f, "FX_AMOUNT" to .92f),
        ),
        patch(
            "ice-field", "Ice Field", "Thin frozen overtone clouds and long reflective tails.",
            "ambient", "icy", "high",
            changes = mapOf("WAVE" to .99f, "MORPH" to .89f, "CUTOFF" to .82f,
                "RESONANCE" to .75f, "ATTACK" to .76f, "SUSTAIN" to .31f,
                "RELEASE" to .95f, "FX_AMOUNT" to .98f, "LFO_RATE" to .04f),
        ),
    )

    val DEFAULT: SynthPatch get() = ALL.first()
    fun byId(id: String): SynthPatch? = ALL.firstOrNull { it.id == id }
}
