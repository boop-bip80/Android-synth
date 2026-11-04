package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTouchSettingsTest {
    private fun List<TouchVoiceEvent>.on() = filterIsInstance<TouchVoiceEvent.NoteOn>()
    private fun List<TouchVoiceEvent>.off() = filterIsInstance<TouchVoiceEvent.NoteOff>()
    private fun List<TouchVoiceEvent>.expression() = filterIsInstance<TouchVoiceEvent.Expression>()

    @Test fun defaultsGiveABassToTrebleMajorStage() {
        // Aurora default: C major, three octaves rising from C2 at the bottom of the screen.
        val config = StageTouchSettings().toTouchConfig()
        assertEquals(36, config.rootMidiNote)
        assertEquals(22, config.noteCount)
        assertEquals(TouchScale.MAJOR, config.scale)
        assertEquals(TouchPitchAxis.VERTICAL, config.pitchAxis)
        assertTrue(config.glissando)
        assertEquals(.5f, config.pressureSensitivity, 0f)
    }

    @Test fun everyScaleMapsToACoreScaleAndBack() {
        for (scale in TouchScale.entries) {
            assertEquals(scale, TouchScale.fromScaleId(scale.scaleId))
            val core = com.orbital.synth.core.music.MusicalScales.byId(scale.scaleId)
            org.junit.Assert.assertNotNull("no core scale for $scale", core)
            assertEquals(
                scale.semitones.toList().sorted(),
                core!!.degrees.sorted(),
            )
        }
        assertEquals(TouchScale.MAJOR, TouchScale.fromScaleId("no-such-scale"))
    }

    @Test fun tonicAndMusicalOctaveRangeFollowScaleDegrees() {
        val settings = StageTouchSettings(
            tonic = 2, baseOctave = 3, octaveRange = 3, scale = TouchScale.MAJOR,
        )
        val config = settings.toTouchConfig()
        assertEquals(50, config.rootMidiNote)
        assertEquals(22, config.noteCount)
        val mapper = TouchPerformanceMapper(config)
        assertEquals(50, mapper.touchDown(1L, 0f, .5f).on().single().note)
        assertEquals(86, mapper.touchDown(2L, 1f, .5f).on().single().note)
        assertEquals(2, mapper.cancelAll().off().size)
    }

    @Test fun pitchAxisSwitchChangesPitchWithoutChangingFingerIdentity() {
        val mapper = TouchPerformanceMapper()
        assertEquals(60, mapper.touchDown(3L, 0f, 0f).on().single().note)
        val changes = mapper.updateConfig(StageTouchSettings(
            pitchAxis = TouchPitchAxis.VERTICAL,
        ).toTouchConfig())
        assertEquals(60, changes.off().single().note)
        assertEquals(84, changes.on().single().note)
        assertEquals(84, mapper.touchUp(3L).off().single().note)
        assertEquals(0, mapper.activePointerCount)
    }

    @Test fun verticalPitchMapsTopHighAndBottomLow() {
        val config = StageTouchSettings(pitchAxis = TouchPitchAxis.VERTICAL).toTouchConfig()
        val mapper = TouchPerformanceMapper(config)
        assertEquals(84, mapper.touchDown(1L, 0f, 0f).on().single().note)
        assertEquals(60, mapper.touchDown(2L, 1f, 1f).on().single().note)
        val bright = mapper.touchMove(1L, 0f, .5f).expression().single()
        val dark = mapper.touchMove(1L, 1f, .5f).expression().single()
        assertTrue(bright.cutoff > dark.cutoff)
        assertEquals(2, mapper.cancelAll().off().size)
    }

    @Test fun noHardwarePressureStillOffersXYExpressionAndDynamicallySafeVelocity() {
        val noPressure = StageTouchSettings(pressureSensitivity = 0f).toTouchConfig()
        val first = TouchPerformanceMapper(noPressure)
            .touchDown(9L, .4f, .2f, pressure = .2f)
        val second = TouchPerformanceMapper(noPressure)
            .touchDown(9L, .4f, .2f, pressure = 1.8f)
        assertEquals(first.on().single().velocity, second.on().single().velocity, 0f)
        assertEquals(first.expression().single().morph, second.expression().single().morph, 0f)
        assertEquals(first.expression().single().fx, second.expression().single().fx, 0f)
        val high = TouchPerformanceMapper(noPressure)
            .touchDown(9L, .4f, .1f).expression().single()
        val low = TouchPerformanceMapper(noPressure)
            .touchDown(9L, .4f, .9f).expression().single()
        assertTrue(high.cutoff > low.cutoff)
    }

    @Test fun enabledPressureChangesDynamicsButNeverGoesOutOfRange() {
        val config = StageTouchSettings(pressureSensitivity = 1f).toTouchConfig()
        val soft = TouchPerformanceMapper(config).touchDown(1L, .4f, .3f, pressure = .2f)
        val firm = TouchPerformanceMapper(config).touchDown(1L, .4f, .3f, pressure = 1.8f)
        assertTrue(firm.on().single().velocity > soft.on().single().velocity)
        assertTrue(firm.expression().single().morph > soft.expression().single().morph)
        assertTrue(firm.expression().single().fx > soft.expression().single().fx)
        assertTrue(firm.on().single().velocity <= 1f)
    }

    @Test fun holdPitchModeBlocksRetriggerWhenMovingAcrossVerticalRange() {
        val config = StageTouchSettings(
            pitchAxis = TouchPitchAxis.VERTICAL, glissando = false,
        ).toTouchConfig()
        val mapper = TouchPerformanceMapper(config)
        assertEquals(60, mapper.touchDown(9L, .4f, 1f).on().single().note)
        val drag = mapper.touchMove(9L, .4f, 0f)
        assertTrue(drag.on().isEmpty())
        assertTrue(drag.off().isEmpty())
        assertFalse(drag.expression().isEmpty())
        assertEquals(60, mapper.touchUp(9L).off().single().note)
    }

    @Test fun unsafeConfigValuesClampAndStayFinite() {
        val config = StageTouchSettings(
            tonic = -100, baseOctave = 100, octaveRange = -5,
            pressureSensitivity = Float.NaN,
        ).toTouchConfig()
        assertEquals(108, config.rootMidiNote)
        assertEquals(13, config.noteCount)
        assertEquals(.5f, config.pressureSensitivity, 0f)
        val mapper = TouchPerformanceMapper(config)
        val events = mapper.touchDown(999L, Float.NaN, Float.NaN, Float.NaN)
        assertTrue(events.on().single().note in 0..127)
        events.expression().single().let {
            assertTrue(it.cutoff.isFinite())
            assertTrue(it.morph.isFinite())
            assertTrue(it.fx.isFinite())
        }
        assertEquals(1, mapper.cancelAll().off().size)
    }
}
