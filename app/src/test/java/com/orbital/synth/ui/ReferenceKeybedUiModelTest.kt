package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceKeybedUiModelTest {
    @Test fun chromaticTwelvePadsCoverOneAndExactlyOneSemitoneEach() {
        val width = 360f
        val height = 104f
        assertEquals(12, ReferenceKeybedUiModel.KEY_COUNT)
        for (index in 0..11) {
            val x = (index + .5f) * width / 12f
            assertEquals(index,
                ReferenceKeybedUiModel.keyAt(x, 50f, width, height))
            assertEquals(60 + index,
                ReferenceKeybedUiModel.noteAt(octave = 4, key = index))
        }
        assertEquals(0, ReferenceKeybedUiModel.keyAt(0f, 0f, width, height))
        assertEquals(11, ReferenceKeybedUiModel.keyAt(
            width - .0001f, height - .0001f, width, height))
    }

    @Test fun malformedPositionsCannotTriggerInvisibleOrOutsideKeys() {
        val invalid = listOf(
            -1f to 30f, 360f to 30f,
            45f to -1f, 45f to 110f,
            Float.NaN to 30f, Float.POSITIVE_INFINITY to 60f,
        )
        invalid.forEach { (x, y) ->
            assertNull(ReferenceKeybedUiModel.keyAt(x, y, 360f, 110f))
        }
        assertNull(ReferenceKeybedUiModel.keyAt(10f, 20f, Float.NaN, 110f))
        assertNull(ReferenceKeybedUiModel.keyAt(10f, 20f, 360f, Float.POSITIVE_INFINITY))
        assertNull(ReferenceKeybedUiModel.keyAt(10f, 20f, 0f, 110f))
    }

    @Test fun octaveAndNoteNamesMatchActualMidiSemitonesWithoutOverflow() {
        assertEquals(12, ReferenceKeybedUiModel.noteAt(0, 0))
        assertEquals(60, ReferenceKeybedUiModel.noteAt(4, 0))
        assertEquals(71, ReferenceKeybedUiModel.noteAt(4, 11))
        assertEquals(119, ReferenceKeybedUiModel.noteAt(8, 11))
        assertNull(ReferenceKeybedUiModel.noteAt(-1, 0))
        assertNull(ReferenceKeybedUiModel.noteAt(9, 0))
        assertNull(ReferenceKeybedUiModel.noteAt(4, -1))
        assertNull(ReferenceKeybedUiModel.noteAt(4, 12))
        assertEquals("C4", ReferenceKeybedUiModel.noteLabel(60))
        assertEquals("C♯4", ReferenceKeybedUiModel.noteLabel(61))
        assertEquals("B4", ReferenceKeybedUiModel.noteLabel(71))
        assertEquals("Note", ReferenceKeybedUiModel.noteLabel(-1))
    }

    @Test fun strikeVelocityComesFromExplicitVerticalControlNotAssumedPressure() {
        assertEquals(1f,
            ReferenceKeybedUiModel.verticalStrikeVelocity(0f, 100f), .0001f)
        assertEquals(.45f,
            ReferenceKeybedUiModel.verticalStrikeVelocity(100f, 100f), .0001f)
        assertTrue(ReferenceKeybedUiModel.verticalStrikeVelocity(25f, 100f) >
            ReferenceKeybedUiModel.verticalStrikeVelocity(75f, 100f))
        assertEquals(.72f, ReferenceKeybedUiModel.verticalStrikeVelocity(
            Float.NaN, 100f), .0001f)
        assertEquals(.72f, ReferenceKeybedUiModel.verticalStrikeVelocity(
            20f, 0f), .0001f)
    }

    @Test fun liquidKeybedGlowsAreBoundedSchematicFunctionsOfRealParameters() {
        for (layer in 0..2) for (i in 0..60) {
            val x = i / 60f
            val wave = ReferenceKeybedUiModel.lightRiverAt(x, layer, .65f, .25f)
            assertTrue(wave.isFinite())
            assertTrue(wave in .12f.. .91f)
            assertEquals(wave,
                ReferenceKeybedUiModel.lightRiverAt(x, layer, .65f, .25f), 0f)
        }
        assertTrue(ReferenceKeybedUiModel.lightRiverAt(.2f, 1, .1f, .3f) !=
            ReferenceKeybedUiModel.lightRiverAt(.2f, 1, .9f, .3f))
    }
}
