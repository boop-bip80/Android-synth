package com.orbital.synth.core.jazz

import com.orbital.synth.core.dsp.processing.FxModuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JazzPresetsTest {
    private fun tune(
        key: String? = "C", feel: JazzFeel = JazzFeel.MEDIUM, bpm: Int = feel.defaultBpm,
        voice: JazzVoice = feel.defaultVoice, id: String = "probe",
    ) = JazzStandard(
        id = id, title = "Probe", composer = "Test",
        key = key?.let { JazzKey.parse(it) }, feel = feel, bpm = bpm, voice = voice,
    )

    private fun p(t: JazzStandard, name: String) = JazzPresets.patchFor(t).parameters.getValue(name)

    @Test fun theDefaultPatchIsAnOrdinaryNamedPatchForThatTune() {
        val wave = JazzCatalogue.byId("wave")!!
        val patch = JazzCatalogue.defaultPatch(wave)
        assertEquals("rb1-wave", patch.id)
        assertEquals("Wave", patch.name)
        assertEquals("Orbital Jazz", patch.author)
        assertTrue("realbook" in patch.tags && "jazz" in patch.tags && "bossa" in patch.tags && "nylon" in patch.tags)
        assertTrue(patch.description.contains("Nylon Bossa") && patch.description.contains("Wave"))
        assertEquals(wave.bpm.toFloat(), patch.modulationTempoBpm, 0f)
    }

    @Test fun fasterTunesGetAShorterReleaseAndLessWash() {
        val slow = tune(feel = JazzFeel.MEDIUM, bpm = 60, voice = JazzVoice.STUDIO_PIANO)
        val fast = tune(feel = JazzFeel.MEDIUM, bpm = 240, voice = JazzVoice.STUDIO_PIANO)
        assertTrue(p(slow, "RELEASE") > p(fast, "RELEASE"))
        assertTrue(p(slow, "DECAY") > p(fast, "DECAY"))
        assertTrue(p(slow, "FX_AMOUNT") > p(fast, "FX_AMOUNT"))
    }

    @Test fun minorHomesAreDarkerThanMajorOnes() {
        val major = tune("C")
        val minor = tune("Cm")
        assertTrue(p(minor, "CUTOFF") < p(major, "CUTOFF"))
        assertTrue(p(minor, "MORPH") < p(major, "MORPH"))
        // No recorded key behaves like a major one: nothing is darkened by guesswork.
        assertEquals(p(major, "CUTOFF"), p(tune(null), "CUTOFF"), 0f)
    }

    @Test fun balladsGainRoomAndUpTempoSwingLosesSome() {
        fun reverb(t: JazzStandard) = JazzPresets.patchFor(t).fxSlots.single { it.type == FxModuleType.REVERB }.wet
        val voice = JazzVoice.STUDIO_PIANO
        val ballad = reverb(tune(feel = JazzFeel.BALLAD, voice = voice))
        val medium = reverb(tune(feel = JazzFeel.MEDIUM, voice = voice))
        val up = reverb(tune(feel = JazzFeel.UP, voice = voice))
        assertTrue("$ballad > $medium > $up", ballad > medium && medium > up)
    }

    @Test fun aDryVoiceStillGetsRoomOnABalladButNeverMoreThanSixSlots() {
        // The upright bass ships with saturation only.
        val ballad = JazzPresets.patchFor(tune(feel = JazzFeel.BALLAD, voice = JazzVoice.UPRIGHT_BASS))
        assertTrue(ballad.fxSlots.any { it.type == FxModuleType.REVERB })
        val medium = JazzPresets.patchFor(tune(feel = JazzFeel.MEDIUM, voice = JazzVoice.UPRIGHT_BASS))
        assertTrue(medium.fxSlots.none { it.type == FxModuleType.REVERB })
        for (feel in JazzFeel.entries) for (voice in JazzVoice.entries) {
            assertTrue(JazzPresets.patchFor(tune(feel = feel, voice = voice)).fxSlots.size <= 6)
        }
    }

    @Test fun funkIsShorterAndModalBreathesMore() {
        val voice = JazzVoice.CLAV_FUNK
        assertTrue(p(tune(feel = JazzFeel.FUNK, voice = voice), "DECAY") < p(tune(feel = JazzFeel.MEDIUM, voice = voice), "DECAY"))
        val trumpet = JazzVoice.MUTED_TRUMPET
        assertTrue(p(tune(feel = JazzFeel.MODAL, voice = trumpet), "MOVEMENT") > p(tune(feel = JazzFeel.MEDIUM, voice = trumpet), "MOVEMENT"))
    }

    @Test fun voiceOverrideChangesTheInstrumentNotTheTuning() {
        val asPiano = JazzPresets.patchFor(tune(voice = JazzVoice.STUDIO_PIANO))
        val asSax = JazzPresets.patchFor(tune(voice = JazzVoice.TENOR_SAX))
        assertNotEquals(asPiano.parameters, asSax.parameters)
        assertEquals(asPiano.modulationTempoBpm, asSax.modulationTempoBpm, 0f)
        assertTrue(asSax.modulationLfos.isNotEmpty() && asPiano.modulationLfos.isEmpty())
    }

    @Test fun everyValueStaysNormalisedAndRoundedToTwoDecimals() {
        for (feel in JazzFeel.entries) for (voice in JazzVoice.entries) for (bpm in listOf(30, 66, 140, 300)) {
            for ((name, value) in JazzPresets.patchFor(tune("Dm", feel, bpm, voice)).parameters) {
                assertTrue("$name=$value", value in 0f..1f)
                assertEquals("$name=$value", Math.round(value * 100f) / 100f, value, 1e-6f)
            }
        }
    }
}
