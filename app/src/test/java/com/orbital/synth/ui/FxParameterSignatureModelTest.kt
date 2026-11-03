package com.orbital.synth.ui

import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FxParameterSignatureModelTest {
    private fun module(type: FxModuleType) =
        FxModuleConfig(type = type, enabled = true, wet = .66f,
            depth = .72f, rate = .45f, stereo = .83f)

    @Test fun eachOfSevenRealDspTypesHasDeterministicBoundedUniqueSchematic() {
        val headers = mutableSetOf<String>()
        val shapes = mutableSetOf<List<FxSignatureTrace>>()
        FxModuleType.entries.forEach { type ->
            val config = module(type)
            val first = FxParameterSignatureModel.build(config)
            val repeated = FxParameterSignatureModel.build(config)
            assertEquals(type.name, first, repeated)
            assertTrue(first.engaged)
            assertTrue(first.heading.isNotBlank())
            assertTrue(first.conceptualLabel.isNotBlank())
            assertTrue(first.accessibleDescription.contains("not measured PCM or FFT"))
            assertEquals(ModularFxUiModel.shortName(type), first.heading)
            assertTrue("Missing type-specific geometry: $type", first.traces.size in 2..4)
            assertTrue(headers.add(first.conceptualLabel))
            assertTrue("Type has same geometry as prior type: $type", shapes.add(first.traces))
            first.traces.forEach { trace ->
                assertEquals(49, trace.points.size)
                trace.points.forEach { point ->
                    assertTrue(point.x.isFinite())
                    assertTrue(point.y.isFinite())
                    assertTrue(point.x in 0f..1f)
                    assertTrue(point.y >= .05f && point.y <= .95f)
                }
            }
        }
        assertEquals(7, headers.size)
    }

    @Test fun changedDepthAndRateAffectTheirRespectiveRealSchematicFunctions() {
        FxModuleType.entries.forEach { type ->
            val config = module(type)
            val base = FxParameterSignatureModel.build(config)
            val depthChanged = FxParameterSignatureModel.build(config.copy(depth = .05f))
            assertNotEquals("Depth must shape the $type schematic", base.traces,
                depthChanged.traces)
            if (ModularFxUiModel.usesRate(type)) {
                val rateChanged = FxParameterSignatureModel.build(config.copy(rate = .93f))
                assertNotEquals("Rate/damping/hold must shape $type", base.traces,
                    rateChanged.traces)
            }
        }
    }

    @Test fun saturationDoesNotFakeUnusedStereoOrRateResponse() {
        val c = module(FxModuleType.SATURATION)
        val base = FxParameterSignatureModel.build(c)
        assertEquals(base.traces,
            FxParameterSignatureModel.build(c.copy(rate = 1f, stereo = 0f)).traces)
        val desc = FxParameterSignatureModel.accessibleDescription(c)
        assertFalse(desc.contains("NOT USED"))
        assertFalse(desc.contains("STEREO"))
        assertFalse(desc.contains("RATE"))
        assertTrue(desc.contains("DRIVE"))
    }

    @Test fun dryAndBypassedConfigurationsPreserveGeometryWithoutClaimingAudio() {
        val original = module(FxModuleType.REVERB)
        val active = FxParameterSignatureModel.build(original)
        val bypass = FxParameterSignatureModel.build(original.copy(enabled = false))
        val dry = FxParameterSignatureModel.build(original.copy(wet = 0f))
        assertTrue(active.engaged)
        assertFalse(bypass.engaged)
        assertFalse(dry.engaged)
        assertEquals(active.traces, bypass.traces)
        assertEquals(active.traces, dry.traces)
        assertTrue(bypass.accessibleDescription.contains("bypassed"))
        assertTrue(FxParameterSignatureModel.accessibleDescription(original.copy(wet = 0f)).contains("0%"))
    }

    @Test fun allConfigurationsSanitizeNonfiniteAndOutOfRangeInputs() {
        FxModuleType.entries.forEach { type ->
            val bad = FxModuleConfig(type, true,
                Float.NaN, Float.POSITIVE_INFINITY, -99f, 99f)
            val output = FxParameterSignatureModel.build(bad)
            assertEquals(0f, output.wet, 0f)
            assertEquals(0f, output.depth, 0f)
            assertEquals(0f, output.rate, 0f)
            assertEquals(1f, output.stereo, 0f)
            assertFalse(output.engaged)
            assertTrue(output.traces.all { t -> t.points.all { it.y.isFinite() } })
        }
    }
}

