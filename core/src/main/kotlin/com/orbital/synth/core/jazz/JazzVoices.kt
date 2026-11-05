package com.orbital.synth.core.jazz

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.preset.PatchParameters
import com.orbital.synth.core.preset.SynthPatch

/**
 * The thirteen jazz voices, authored on the existing ORBITAL engine only:
 *
 *  - `SOURCE` .5 = plucked STRING (WAVE ring time, MORPH brightness, CHARACTER pick position)
 *  - `SOURCE` 1 = struck GLASS/bar (WAVE marimba -> glass -> bell, MORPH hardness, CHARACTER ring)
 *  - `ENGINE` .25 ladder, .5 SEM, .75 six-operator FM, 1 phase distortion
 *  - the additive oscillator rack (organ drawbars, breath noise)
 *  - the six-slot FX chain (tremolo, chorus, saturation, phaser, reverb)
 *  - the LFO / modulation matrix (vibrato, tempo-synced swell)
 *
 * These are design intent expressed as parameters. They have been checked structurally
 * (finite, non-silent, bounded, distinct) by the unit tests, not auditioned on a phone.
 *
 * Every voice writes all eighteen engine parameters, so loading one fully replaces the
 * previous sound instead of inheriting leftovers.
 */
object JazzVoices {
    /** Neutral, quiet-but-safe starting point; each voice overrides what makes it itself. */
    private val BASE: Map<String, Float> = mapOf(
        "WAVE" to 0.25f, "MORPH" to 0.35f,
        "CUTOFF" to 0.60f, "RESONANCE" to 0.15f,
        "MOVEMENT" to 0.20f, "FX_AMOUNT" to 0.20f,
        "ATTACK" to 0.00f, "DECAY" to 0.40f,
        "SUSTAIN" to 0.70f, "RELEASE" to 0.35f,
        "LFO_RATE" to 0.22f, "LFO_DEPTH" to 0.00f, "GLIDE" to 0.00f,
        "UNISON" to 0.00f, "DETUNE" to 0.00f,
        "ENGINE" to 0.00f, "CHARACTER" to 0.00f, "SOURCE" to 0.00f,
    )

    private const val ENGINE_LADDER = 0.25f
    private const val ENGINE_SEM = 0.50f
    private const val ENGINE_FM = 0.75f
    private const val ENGINE_PD = 1.00f
    private const val SOURCE_STRING = 0.5f
    private const val SOURCE_GLASS = 1.0f

    /**
     * Vibrato depths. Measured on the engine, a LFO1 -> PITCH route moves pitch by about
     * 1160 cents per unit of depth, so these are roughly +-19 cents (reed) and +-10 cents
     * (flute). `JazzVoicesTest` re-measures them.
     */
    internal const val SAX_VIBRATO_DEPTH = 0.016f
    internal const val FLUTE_VIBRATO_DEPTH = 0.009f

    private fun fx(type: FxModuleType, wet: Float, depth: Float = 0.5f, rate: Float = 0.5f, stereo: Float = 0.5f) =
        FxModuleConfig(type, enabled = true, wet = wet, depth = depth, rate = rate, stereo = stereo)

    private val vibratoLfo = ModulationLfoConfig(enabled = true, shape = LfoShape.SINE, rateHz = 5.2f)
    private fun vibratoRoute(depth: Float) = ModulationRouteConfig(
        enabled = true, source = ModSource.LFO1, destination = ModDestination.PITCH, depth = depth,
    )

    private fun voice(
        voice: JazzVoice,
        tags: Set<String>,
        parameters: Map<String, Float>,
        slots: List<OscillatorSlot> = emptyList(),
        fxSlots: List<FxModuleConfig> = emptyList(),
        lfos: List<ModulationLfoConfig> = emptyList(),
        routes: List<ModulationRouteConfig> = emptyList(),
    ): SynthPatch {
        require(parameters.keys.all { it in PatchParameters.NAMES }) { "unknown key in ${voice.code}" }
        return SynthPatch(
            id = "jazz-${voice.code}",
            name = voice.label,
            author = "Orbital Jazz",
            description = voice.blurb.replaceFirstChar { it.uppercase() } + ".",
            tags = tags + "jazz",
            parameters = BASE + parameters,
            oscillatorSlots = slots,
            modulationRoutes = routes,
            modulationLfos = lfos,
            fxSlots = fxSlots,
        )
    }

    /** The factory sound for each [JazzVoice], before any per-tune tailoring. */
    val ALL: List<SynthPatch> = listOf(
        voice(
            JazzVoice.SUITCASE_EP, setOf("keys", "fm"),
            mapOf(
                "ENGINE" to ENGINE_FM, "CHARACTER" to .38f, "WAVE" to .30f, "MORPH" to .48f,
                "CUTOFF" to .80f, "RESONANCE" to .12f, "DECAY" to .56f, "SUSTAIN" to .26f,
                "RELEASE" to .40f, "FX_AMOUNT" to .30f, "MOVEMENT" to .25f,
            ),
            fxSlots = listOf(
                fx(FxModuleType.TREMOLO, wet = .30f, depth = .30f, rate = .34f, stereo = .85f),
                fx(FxModuleType.CHORUS, wet = .22f, depth = .35f, rate = .30f, stereo = .70f),
            ),
        ),
        voice(
            JazzVoice.STUDIO_PIANO, setOf("keys", "fm"),
            mapOf(
                "ENGINE" to ENGINE_FM, "CHARACTER" to .22f, "WAVE" to .20f, "MORPH" to .38f,
                "CUTOFF" to .90f, "RESONANCE" to .08f, "DECAY" to .62f, "SUSTAIN" to .14f,
                "RELEASE" to .34f, "FX_AMOUNT" to .22f,
            ),
            fxSlots = listOf(fx(FxModuleType.REVERB, wet = .18f, depth = .30f, rate = .55f, stereo = .70f)),
        ),
        voice(
            JazzVoice.DRAWBAR_ORGAN, setOf("organ", "additive"),
            mapOf(
                "ENGINE" to ENGINE_SEM, "CHARACTER" to .15f, "WAVE" to 0f, "MORPH" to .25f,
                "CUTOFF" to .78f, "RESONANCE" to .10f, "DECAY" to .50f, "SUSTAIN" to 1f,
                "RELEASE" to .10f, "UNISON" to .35f, "DETUNE" to .10f, "FX_AMOUNT" to .20f,
            ),
            // Drawbars: 4', 2 2/3' and 2' above the 8' fundamental.
            slots = listOf(
                OscillatorSlot(true, waveform = 0f, morph = 1f, level = .50f, coarseSemitones = 12),
                OscillatorSlot(true, waveform = 0f, morph = 1f, level = .30f, coarseSemitones = 19),
                OscillatorSlot(true, waveform = 0f, morph = 1f, level = .25f, coarseSemitones = 24),
            ),
            fxSlots = listOf(
                fx(FxModuleType.SATURATION, wet = .25f, depth = .30f),
                fx(FxModuleType.CHORUS, wet = .45f, depth = .50f, rate = .55f, stereo = .80f),
            ),
        ),
        voice(
            JazzVoice.ARCHTOP_GUITAR, setOf("pluck", "string"),
            mapOf(
                "SOURCE" to SOURCE_STRING, "ENGINE" to ENGINE_LADDER, "WAVE" to .46f, "MORPH" to .34f,
                "CHARACTER" to .28f, "CUTOFF" to .58f, "RESONANCE" to .10f, "SUSTAIN" to .90f,
                "RELEASE" to .30f, "FX_AMOUNT" to .22f,
            ),
            fxSlots = listOf(
                fx(FxModuleType.SATURATION, wet = .12f, depth = .20f),
                fx(FxModuleType.REVERB, wet = .16f, depth = .30f, rate = .60f, stereo = .70f),
            ),
        ),
        voice(
            JazzVoice.NYLON_BOSSA, setOf("pluck", "string"),
            mapOf(
                "SOURCE" to SOURCE_STRING, "WAVE" to .42f, "MORPH" to .32f, "CHARACTER" to .18f,
                "CUTOFF" to .72f, "SUSTAIN" to .88f, "RELEASE" to .32f, "FX_AMOUNT" to .26f,
            ),
            fxSlots = listOf(fx(FxModuleType.REVERB, wet = .14f, depth = .25f, rate = .50f, stereo = .70f)),
        ),
        voice(
            JazzVoice.UPRIGHT_BASS, setOf("pluck", "bass"),
            mapOf(
                "SOURCE" to SOURCE_STRING, "ENGINE" to ENGINE_SEM, "WAVE" to .36f, "MORPH" to .22f,
                "CHARACTER" to .55f, "CUTOFF" to .34f, "RESONANCE" to .16f, "DECAY" to .30f,
                "SUSTAIN" to .70f, "RELEASE" to .16f, "FX_AMOUNT" to .06f,
            ),
            fxSlots = listOf(fx(FxModuleType.SATURATION, wet = .15f, depth = .25f)),
        ),
        voice(
            JazzVoice.VIBRAPHONE, setOf("mallet", "glass"),
            mapOf(
                "SOURCE" to SOURCE_GLASS, "WAVE" to .30f, "MORPH" to .58f, "CHARACTER" to .62f,
                "CUTOFF" to .94f, "SUSTAIN" to .90f, "RELEASE" to .62f, "FX_AMOUNT" to .38f,
            ),
            fxSlots = listOf(
                fx(FxModuleType.TREMOLO, wet = .55f, depth = .45f, rate = .40f, stereo = .60f),
                fx(FxModuleType.REVERB, wet = .20f, depth = .35f, rate = .55f, stereo = .70f),
            ),
        ),
        voice(
            JazzVoice.MARIMBA, setOf("mallet", "glass"),
            mapOf(
                "SOURCE" to SOURCE_GLASS, "WAVE" to .02f, "MORPH" to .55f, "CHARACTER" to .30f,
                "CUTOFF" to .90f, "SUSTAIN" to .90f, "RELEASE" to .26f, "FX_AMOUNT" to .25f,
            ),
            fxSlots = listOf(fx(FxModuleType.REVERB, wet = .12f, depth = .25f, rate = .50f, stereo = .60f)),
        ),
        voice(
            JazzVoice.MUTED_TRUMPET, setOf("brass", "sem"),
            mapOf(
                "ENGINE" to ENGINE_SEM, "CHARACTER" to .40f, "WAVE" to .55f, "MORPH" to .62f,
                "CUTOFF" to .58f, "RESONANCE" to .55f, "ATTACK" to .06f, "DECAY" to .30f,
                "SUSTAIN" to .78f, "RELEASE" to .22f, "GLIDE" to .10f, "FX_AMOUNT" to .26f,
            ),
            fxSlots = listOf(fx(FxModuleType.REVERB, wet = .20f, depth = .30f, rate = .60f, stereo = .70f)),
        ),
        voice(
            JazzVoice.TENOR_SAX, setOf("lead", "reed"),
            mapOf(
                "ENGINE" to ENGINE_LADDER, "CHARACTER" to .45f, "WAVE" to .62f, "MORPH" to .45f,
                "CUTOFF" to .52f, "RESONANCE" to .30f, "ATTACK" to .04f, "DECAY" to .30f,
                "SUSTAIN" to .82f, "RELEASE" to .24f, "GLIDE" to .12f, "FX_AMOUNT" to .28f,
            ),
            // A little pink noise under the tone is the breath of the reed.
            slots = listOf(OscillatorSlot(true, waveform = 0f, morph = 1f, level = .10f, noiseLevel = 1f, noiseColor = .5f)),
            fxSlots = listOf(fx(FxModuleType.REVERB, wet = .22f, depth = .35f, rate = .55f, stereo = .70f)),
            lfos = listOf(vibratoLfo),
            routes = listOf(vibratoRoute(SAX_VIBRATO_DEPTH)),
        ),
        voice(
            JazzVoice.FLUTE, setOf("lead", "wind"),
            mapOf(
                "ENGINE" to ENGINE_PD, "CHARACTER" to .20f, "WAVE" to .08f, "MORPH" to .30f,
                "CUTOFF" to .66f, "RESONANCE" to .12f, "ATTACK" to .12f, "DECAY" to .30f,
                "SUSTAIN" to .85f, "RELEASE" to .30f, "FX_AMOUNT" to .30f,
            ),
            slots = listOf(OscillatorSlot(true, waveform = 0f, morph = 1f, level = .08f, noiseLevel = 1f, noiseColor = .5f)),
            fxSlots = listOf(fx(FxModuleType.REVERB, wet = .24f, depth = .35f, rate = .55f, stereo = .75f)),
            lfos = listOf(vibratoLfo),
            routes = listOf(vibratoRoute(FLUTE_VIBRATO_DEPTH)),
        ),
        voice(
            JazzVoice.BALLAD_STRINGS, setOf("strings", "pad"),
            mapOf(
                "ENGINE" to ENGINE_SEM, "CHARACTER" to .30f, "WAVE" to .41f, "MORPH" to .55f,
                "CUTOFF" to .46f, "RESONANCE" to .14f, "ATTACK" to .46f, "SUSTAIN" to .85f,
                "RELEASE" to .72f, "UNISON" to .66f, "DETUNE" to .45f, "FX_AMOUNT" to .50f,
            ),
            fxSlots = listOf(
                fx(FxModuleType.CHORUS, wet = .30f, depth = .40f, rate = .30f, stereo = .85f),
                fx(FxModuleType.REVERB, wet = .35f, depth = .55f, rate = .60f, stereo = .85f),
            ),
            // One slow breath every two bars, locked to the song tempo by the tuned patch.
            lfos = listOf(ModulationLfoConfig(enabled = true, shape = LfoShape.SINE, rateHz = 0.25f, beatsPerCycle = 8f)),
            routes = listOf(
                ModulationRouteConfig(
                    enabled = true, source = ModSource.LFO1, destination = ModDestination.CUTOFF, depth = .06f,
                ),
            ),
        ),
        voice(
            JazzVoice.CLAV_FUNK, setOf("keys", "funk"),
            mapOf(
                "ENGINE" to ENGINE_PD, "CHARACTER" to .62f, "WAVE" to .72f, "MORPH" to .55f,
                "CUTOFF" to .78f, "RESONANCE" to .38f, "DECAY" to .20f, "SUSTAIN" to .06f,
                "RELEASE" to .09f, "FX_AMOUNT" to .12f,
            ),
            fxSlots = listOf(fx(FxModuleType.PHASER, wet = .25f, depth = .40f, rate = .30f, stereo = .50f)),
        ),
    )

    private val byVoice: Map<JazzVoice, SynthPatch> =
        JazzVoice.entries.associateWith { voice -> ALL.first { it.id == "jazz-${voice.code}" } }

    fun patch(voice: JazzVoice): SynthPatch = byVoice.getValue(voice)
}
