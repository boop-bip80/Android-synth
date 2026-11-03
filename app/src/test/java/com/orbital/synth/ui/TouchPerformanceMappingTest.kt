package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPerformanceMappingTest {
    private fun List<TouchVoiceEvent>.ons(): List<TouchVoiceEvent.NoteOn> =
        filterIsInstance<TouchVoiceEvent.NoteOn>()

    private fun List<TouchVoiceEvent>.offs(): List<TouchVoiceEvent.NoteOff> =
        filterIsInstance<TouchVoiceEvent.NoteOff>()

    private fun List<TouchVoiceEvent>.expressions(): List<TouchVoiceEvent.Expression> =
        filterIsInstance<TouchVoiceEvent.Expression>()

    @Test fun chromaticStageSpansC4ToC6WithoutVisualKeys() {
        val mapper = TouchPerformanceMapper()
        assertEquals(60, mapper.touchDown(1L, 0f, 0.5f).ons().single().note)
        assertEquals(72, mapper.touchDown(2L, 0.5f, 0.5f).ons().single().note)
        assertEquals(84, mapper.touchDown(3L, 1f, 0.5f).ons().single().note)
        assertEquals(3, mapper.activePointerCount)
        assertEquals(3, mapper.cancelAll().offs().size)
        assertEquals(0, mapper.activePointerCount)
    }

    @Test fun repeatedDownForSamePointerReleasesPreviousBeforeRetriggering() {
        val mapper = TouchPerformanceMapper()
        val first = mapper.touchDown(8L, 0f, 0.3f)
        assertEquals(60, first.ons().single().note)
        val duplicate = mapper.touchDown(8L, 1f, 0.1f)
        assertEquals(60, duplicate[0].let { (it as TouchVoiceEvent.NoteOff).note })
        assertEquals(84, duplicate[1].let { (it as TouchVoiceEvent.NoteOn).note })
        assertEquals(1, mapper.activePointerCount)
        assertEquals(84, mapper.touchUp(8L).offs().single().note)
        assertTrue(mapper.touchUp(8L).isEmpty())
    }

    @Test fun duplicatePitchFromTwoFingersHasIndependentBalancedReleases() {
        val mapper = TouchPerformanceMapper()
        val events = mutableListOf<TouchVoiceEvent>()
        events += mapper.touchDown(11L, .1f, .2f)
        events += mapper.touchDown(12L, .1f, .8f)
        events += mapper.touchUp(11L)
        assertEquals(1, mapper.activePointerCount)
        events += mapper.cancelAll()
        assertEquals(2, events.ons().size)
        assertEquals(2, events.offs().size)
        assertEquals(events.ons().map { it.note }.sorted(), events.offs().map { it.note }.sorted())
        assertTrue(mapper.cancelAll().isEmpty())
    }

    @Test fun intentionalGlissandoSwitchesNotesWithOffBeforeOnAndJitterDoesNot() {
        val mapper = TouchPerformanceMapper()
        mapper.touchDown(41L, .50f, .50f, eventTimeMs = 1000L)
        val tinyMove = mapper.touchMove(41L, .505f, .50f, eventTimeMs = 1016L)
        assertTrue(tinyMove.ons().isEmpty())
        assertTrue(tinyMove.offs().isEmpty())
        val swipe = mapper.touchMove(41L, .78f, .20f, eventTimeMs = 1032L)
        assertEquals(72, swipe.offs().single().note)
        assertEquals(79, swipe.ons().single().note)
        assertTrue(swipe.last() is TouchVoiceEvent.Expression)
        assertEquals(79, mapper.touchUp(41L).offs().single().note)
    }

    @Test fun glideCanBeDisabledWithoutLosingExpression() {
        val mapper = TouchPerformanceMapper(
            TouchPerformanceConfig(glissando = false),
        )
        mapper.touchDown(9L, 0f, .7f)
        val moving = mapper.touchMove(9L, 1f, 0f, eventTimeMs = 80L)
        assertTrue(moving.ons().isEmpty())
        assertTrue(moving.offs().isEmpty())
        assertEquals(1, moving.expressions().size)
        assertEquals(60, mapper.touchUp(9L).offs().single().note)
    }

    @Test fun scaleChangesRetuneAllHeldContactsAndPreserveReleaseIdentity() {
        val mapper = TouchPerformanceMapper()
        mapper.touchDown(21L, 0.25f, 0.5f)
        val next = TouchPerformanceConfig(
            rootMidiNote = 48, noteCount = 25, scale = TouchScale.MINOR_PENTATONIC,
        )
        val change = mapper.updateConfig(next)
        assertEquals(1, change.ons().size)
        assertEquals(1, change.offs().size)
        assertEquals(66, change.ons().single().note)
        assertEquals(66, mapper.touchUp(21L).offs().single().note)
        assertEquals(0, mapper.activePointerCount)
        assertTrue(mapper.updateConfig(next).isEmpty())
    }

    @Test fun cancelAndUnknownPointerAreSafeEvenWhenNoTouchIsActive() {
        val mapper = TouchPerformanceMapper()
        assertTrue(mapper.touchUp(999L).isEmpty())
        assertTrue(mapper.touchMove(999L, 1f, 1f).isEmpty())
        assertTrue(mapper.cancelAll().isEmpty())
        mapper.touchDown(1L, 0f, 0f)
        mapper.touchDown(2L, 1f, 1f)
        assertEquals(2, mapper.cancelAll().offs().size)
        assertTrue(mapper.cancelAll().isEmpty())
        assertTrue(mapper.touchUp(2L).isEmpty())
    }

    @Test fun invalidCoordinatesPressureAndTimeCannotCreateNaNControls() {
        val mapper = TouchPerformanceMapper(TouchPerformanceConfig(rootMidiNote = 127))
        val down = mapper.touchDown(90L, Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, -1L)
        assertEquals(127, down.ons().single().note)
        val move = mapper.touchMove(
            90L, Float.NEGATIVE_INFINITY, 500f, Float.POSITIVE_INFINITY, 0L,
        )
        val all = down.expressions() + move.expressions()
        assertFalse(all.isEmpty())
        all.forEach { expr ->
            assertTrue(expr.cutoff.isFinite() && expr.cutoff in 0f..1f)
            assertTrue(expr.morph.isFinite() && expr.morph in 0f..1f)
            assertTrue(expr.movement.isFinite() && expr.movement in 0f..1f)
            assertTrue(expr.fx.isFinite() && expr.fx in 0f..1f)
        }
        assertTrue(down.ons().single().velocity in 0f..1f)
        assertEquals(127, mapper.touchUp(90L).offs().single().note)
    }

    @Test fun capacityOverflowDoesNotStealAnotherPointersNote() {
        val mapper = TouchPerformanceMapper(maxContacts = 2)
        assertEquals(1, mapper.touchDown(1L, .0f, .1f).ons().size)
        assertEquals(1, mapper.touchDown(2L, 1f, .9f).ons().size)
        assertTrue(mapper.touchDown(3L, .5f, .5f).isEmpty())
        assertEquals(2, mapper.activePointerCount)
        assertTrue(mapper.touchUp(3L).isEmpty())
        assertEquals(2, mapper.cancelAll().offs().size)
    }

    @Test fun expressionRespondsToDragSpeedAndYEvenWithoutRealPressure() {
        val mapper = TouchPerformanceMapper()
        val initial = mapper.touchDown(7L, .3f, .8f, eventTimeMs = 100L)
            .expressions().single()
        val fast = mapper.touchMove(7L, .5f, .1f, eventTimeMs = 116L)
            .expressions().single()
        assertTrue(fast.cutoff > initial.cutoff)
        assertTrue(fast.fx > initial.fx)
        assertTrue(fast.movement > initial.movement)
        assertEquals(1, mapper.touchUp(7L).offs().size)
    }
}
