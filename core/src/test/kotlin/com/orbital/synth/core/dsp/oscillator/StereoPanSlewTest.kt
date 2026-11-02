package com.orbital.synth.core.dsp.oscillator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class StereoPanSlewTest {
    @Test fun legatoRetuneDoesNotJumpStereoGainsAtTheNoteBoundary() {
        val voice = sustainedVoice(60)
        repeat(2_048) { voice.nextSample(0.55f, 1f, 1.0) }
        val beforeLeft = voice.panLeft
        val beforeRight = voice.panRight

        voice.begin(71, 1f, 2L, null, 0.0, 0.55f, 1f, legato = true)
        voice.preparePitch(1.0)
        assertEquals("Legato pan must not change before a sample", beforeLeft, voice.panLeft, 0f)
        assertEquals(beforeRight, voice.panRight, 0f)

        voice.nextSample(0.55f, 1f, 1.0)
        assertTrue(abs(voice.panLeft - beforeLeft) < 0.005f)
        assertTrue(abs(voice.panRight - beforeRight) < 0.005f)
        repeat(239) { voice.nextSample(0.55f, 1f, 1.0) }

        val expected = panGains(71)
        assertEquals(expected.first, voice.panLeft, 0.000001f)
        assertEquals(expected.second, voice.panRight, 0.000001f)
    }

    @Test fun stolenVoiceGhostTailRetainsOldPanThenMovesSmoothly() {
        val voice = sustainedVoice(60)
        repeat(2_048) { voice.nextSample(0.79f, 1f, 1.0) }
        val oldPan = voice.panLeft

        // A hard retrigger creates a fading ghost of the previous voice. Its
        // first audible sample must not be abruptly moved across the stereo field.
        voice.begin(71, 1f, 2L, null, 0.0, 0.79f, 1f)
        voice.preparePitch(1.0)
        assertTrue(voice.hasTail)
        assertEquals(oldPan, voice.panLeft, 0f)

        voice.nextSample(0.79f, 1f, 1.0)
        assertTrue(abs(voice.panLeft - oldPan) < 0.005f)
        repeat(239) { voice.nextSample(0.79f, 1f, 1.0) }
        assertEquals(panGains(71).first, voice.panLeft, 0.000001f)
    }

    @Test fun initialNoteAndHardStoppedVoiceUseTheirRequestedPanImmediately() {
        val voice = sustainedVoice(60)
        assertEquals(panGains(60).first, voice.panLeft, 0.000001f)

        voice.hardStop()
        voice.begin(71, 1f, 2L, null, 0.0, 0.55f, 1f)
        assertEquals(panGains(71).first, voice.panLeft, 0.000001f)
        assertEquals(panGains(71).second, voice.panRight, 0.000001f)
    }

    private fun sustainedVoice(note: Int): Voice = Voice(48_000).apply {
        configure(0.001, 0.1, 1f, 0.3, 12_000.0, 0.2f)
        begin(note, 1f, 1L, null, 0.0, 0.55f, 1f)
        preparePitch(1.0)
    }

    private fun panGains(note: Int): Pair<Float, Float> {
        val pan = (((note % 12) - 5.5) / 5.5 * 0.18).coerceIn(-1.0, 1.0)
        val angle = (pan + 1.0) * (PI / 4.0)
        return cos(angle).toFloat() to sin(angle).toFloat()
    }
}
