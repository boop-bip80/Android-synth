package com.orbital.synth.ui

import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceSoundUiModelTest {
    @Test fun fiveKnobsTargetFiveRealSynthParametersInReferenceOrder() {
        assertEquals(
            listOf(ParameterId.WAVE, ParameterId.CUTOFF, ParameterId.RESONANCE,
                ParameterId.MOVEMENT, ParameterId.FX_AMOUNT),
            ReferenceSoundUiModel.knobOrder,
        )
    }

    @Test fun normalizedVisualInputNeverOverflowsOrPropagatesNaN() {
        assertEquals(0f, ReferenceSoundUiModel.normalized(Float.NaN), 0f)
        assertEquals(0f, ReferenceSoundUiModel.normalized(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0f, ReferenceSoundUiModel.normalized(-20f), 0f)
        // Non-finite inputs are explicitly zero, even positive infinity.
        assertEquals(0f, ReferenceSoundUiModel.normalized(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, ReferenceSoundUiModel.normalized(4f), 0f)
    }

    @Test fun schematicEnvelopeHonorsAttackPeakSustainAndNoteRelease() {
        val attack = .45f
        val decay = .32f
        val sustain = .26f
        val release = .38f
        val attackEnd = .035f + attack * .255f
        val decayEnd = attackEnd + .035f + decay * .245f
        assertEquals(0f, ReferenceSoundUiModel.envelopeAt(
            0f, attack, decay, sustain, release), 0.0001f)
        assertEquals(1f, ReferenceSoundUiModel.envelopeAt(
            attackEnd, attack, decay, sustain, release), 0.0001f)
        assertEquals(sustain, ReferenceSoundUiModel.envelopeAt(
            decayEnd + .02f, attack, decay, sustain, release), 0.001f)
        assertEquals(0f, ReferenceSoundUiModel.envelopeAt(
            1f, attack, decay, sustain, release), 0.0001f)
        for (i in 0..80) {
            assertTrue(ReferenceSoundUiModel.envelopeAt(
                i / 80f, attack, decay, sustain, release) in 0f..1f)
        }
    }

    @Test fun everyLiveLfoShapeHasCorrectSchematicOrientationAndBounds() {
        assertTrue(ReferenceSoundUiModel.lfoAt(LfoShape.SAW_UP, .2f) <
            ReferenceSoundUiModel.lfoAt(LfoShape.SAW_UP, .8f))
        assertTrue(ReferenceSoundUiModel.lfoAt(LfoShape.SAW_DOWN, .2f) >
            ReferenceSoundUiModel.lfoAt(LfoShape.SAW_DOWN, .8f))
        assertEquals(1f, ReferenceSoundUiModel.lfoAt(
            LfoShape.SQUARE, .1f), .0001f)
        assertEquals(-1f, ReferenceSoundUiModel.lfoAt(
            LfoShape.SQUARE, .9f), .0001f)
        for (shape in LfoShape.entries) {
            for (i in 0..60) {
                val output = ReferenceSoundUiModel.lfoAt(shape, i / 60f)
                assertFalse(output.isNaN())
                assertTrue("$shape=$output", output in -1f..1f)
            }
        }
    }

    @Test fun allFiveXYRibbonsAreDeterministicParameterDrivenNotFabricatedPcm() {
        for (layer in 0..4) {
            for (i in 0..100) {
                val u = i / 100f
                val result = ReferenceSoundUiModel.ribbonAt(u, layer, .44f, .79f)
                assertEquals(result,
                    ReferenceSoundUiModel.ribbonAt(u, layer, .44f, .79f), 0f)
                assertTrue(result.isFinite())
                assertTrue(result in 0.04f..0.96f)
            }
        }
        assertTrue(ReferenceSoundUiModel.ribbonAt(.33f, 2, .1f, .2f) !=
            ReferenceSoundUiModel.ribbonAt(.33f, 2, .9f, .2f))
        assertTrue(ReferenceSoundUiModel.ribbonAt(.33f, 2, .1f, .2f) !=
            ReferenceSoundUiModel.ribbonAt(.33f, 2, .1f, .9f))
    }

    @Test fun modulationTileCountsOnlyLiveEnabledNonZeroSavedRoutesAndLimitsTo64() {
        val reserved = ModDestination.entries.first {
            it !in ModulationConstellationModel.liveDestinations
        }
        val live = ModulationRouteConfig(
            enabled = true, destination = ModDestination.CUTOFF, depth = -.51f,
        )
        val reservedOnly = live.copy(destination = reserved)
        val disabled = live.copy(enabled = false)
        val zero = live.copy(depth = 0f)
        val nonFinite = live.copy(depth = Float.NaN)
        assertEquals(1, ReferenceSoundUiModel.liveModulatedCount(
            listOf(reservedOnly, disabled, zero, nonFinite, live),
        ))
        assertEquals(64, ReferenceSoundUiModel.liveModulatedCount(
            List(65) { live },
        ))
    }
}
