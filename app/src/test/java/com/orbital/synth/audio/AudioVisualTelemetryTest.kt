package com.orbital.synth.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVisualTelemetryTest {
    private val sampleRate = 48_000
    private val window = 1_600 // 48 kHz / 30 visual updates per second

    @Test
    fun silencePublishesExactlyZeroAndDoesNotInventSound() {
        val meter = AudioVisualTelemetry(sampleRate)
        meter.begin()
        meter.observeFloat(FloatArray(window * 2), window)
        val snapshot = meter.snapshot()
        assertEquals(0f, snapshot.rms, 0f)
        assertEquals(0f, snapshot.peak, 0f)
        assertEquals(0f, snapshot.transient, 0f)
        assertEquals(0f, snapshot.brightness, 0f)
    }

    @Test
    fun knownStereoSineHasCorrectRmsAndPeak() {
        val meter = AudioVisualTelemetry(sampleRate)
        meter.begin()
        val sine = stereoSine(1_200)
        meter.observeFloat(sine, window)
        val snapshot = meter.snapshot()
        assertEquals(0.7071068f, snapshot.rms, .003f)
        assertEquals(1f, snapshot.peak, .0001f)
        assertTrue(snapshot.brightness > 0f)
        assertTrue(snapshot.transient > .8f)
    }

    @Test
    fun impulsePreservesPeakDespiteTinyAverageLevel() {
        val meter = AudioVisualTelemetry(sampleRate)
        meter.begin()
        meter.observeFloat(FloatArray(window * 2), window)
        val impulse = FloatArray(window * 2)
        impulse[0] = 1f
        impulse[1] = 1f
        meter.observeFloat(impulse, window)
        val value = meter.snapshot()
        assertEquals(1f, value.peak, .0001f)
        assertEquals(1f / kotlin.math.sqrt(window.toFloat()), value.rms, .0001f)
        assertTrue("Transient must react to actual impulse", value.transient > .95f)
        assertTrue("A broadband impulse is high-rate motion", value.brightness > .8f)
    }

    @Test
    fun brightnessProxyIncreasesForHigherFrequencyAtEqualAmplitude() {
        val meter = AudioVisualTelemetry(sampleRate)
        meter.begin()
        meter.observeFloat(stereoSine(120), window)
        val low = meter.snapshot()
        meter.begin()
        meter.observeFloat(stereoSine(4_200), window)
        val high = meter.snapshot()
        assertEquals(low.rms, high.rms, .005f)
        assertTrue("High-frequency waveform must register brighter", high.brightness > low.brightness + .08f)
    }

    @Test
    fun pcm16PathMeasuresActualQuantizedInterleavedSamples() {
        val meter = AudioVisualTelemetry(sampleRate)
        meter.begin()
        meter.observePcm16(ShortArray(window * 2) { 16_384 }, window)
        val value = meter.snapshot()
        assertEquals(.5f, value.rms, .0001f)
        assertEquals(.5f, value.peak, .0001f)
        assertEquals(0f, value.brightness, .0001f)
    }

    @Test
    fun meterIsBoundedToThirtyPublicationsPerSecond() {
        val meter = AudioVisualTelemetry(sampleRate)
        meter.begin()
        val shortWindow = FloatArray((window - 1) * 2) { .8f }
        meter.observeFloat(shortWindow, window - 1)
        assertEquals(0f, meter.snapshot().rms, 0f)
        meter.observeFloat(floatArrayOf(.8f, .8f), 1)
        assertEquals(.8f, meter.snapshot().rms, .0002f)
    }

    @Test
    fun nonDivisibleSampleRateStillCapsVisualRefresh() {
        // 8,000 / 30 = 266.666...; floor division would exceed the 30 Hz cap.
        val meter = AudioVisualTelemetry(sampleRate = 8_000)
        meter.begin()
        meter.observeFloat(FloatArray(266 * 2) { .75f }, 266)
        assertEquals(0f, meter.snapshot().rms, 0f)
        meter.observeFloat(floatArrayOf(.75f, .75f), 1)
        assertEquals(.75f, meter.snapshot().rms, .0002f)
    }

    @Test
    fun stopSuppressesStaleWorkerUpdatesAndBeginResetsHalfWindows() {
        val meter = AudioVisualTelemetry(sampleRate)
        meter.begin()
        meter.observeFloat(FloatArray(window * 2) { .65f }, window)
        assertTrue(meter.snapshot().peak > .6f)
        meter.stop()
        assertEquals(AudioVisualSnapshot(), meter.snapshot())
        meter.observeFloat(FloatArray(window * 2) { 1f }, window)
        assertEquals(AudioVisualSnapshot(), meter.snapshot())

        meter.begin()
        meter.observeFloat(FloatArray((window - 1) * 2) { 1f }, window - 1)
        meter.stop()
        meter.begin()
        meter.observeFloat(FloatArray(window * 2), window)
        assertEquals(AudioVisualSnapshot(), meter.snapshot())
    }

    @Test
    fun invalidFloatSamplesCannotLeakNaNOrInfinityIntoVisuals() {
        val meter = AudioVisualTelemetry(sampleRate)
        meter.begin()
        val samples = FloatArray(window * 2) { index ->
            when (index % 5) {
                0 -> Float.NaN
                1 -> Float.POSITIVE_INFINITY
                2 -> Float.NEGATIVE_INFINITY
                3 -> 9f
                else -> -9f
            }
        }
        meter.observeFloat(samples, window)
        val v = meter.snapshot()
        assertTrue(v.rms.isFinite() && v.rms in 0f..1f)
        assertTrue(v.peak.isFinite() && v.peak in 0f..1f)
        assertTrue(v.transient.isFinite() && v.transient in 0f..1f)
        assertTrue(v.brightness.isFinite() && v.brightness in 0f..1f)
    }

    @Test
    fun steadySignalDoesNotRetriggerTransientInEveryWindow() {
        val meter = AudioVisualTelemetry(sampleRate)
        meter.begin()
        val tone = stereoSine(1_200)
        meter.observeFloat(tone, window)
        assertTrue(meter.snapshot().transient > .9f)
        meter.observeFloat(tone, window)
        assertEquals(0f, meter.snapshot().transient, .0001f)
        meter.observeFloat(FloatArray(window * 2), window)
        assertEquals(0f, meter.snapshot().transient, .0001f)
    }

    private fun stereoSine(hz: Int): FloatArray = FloatArray(window * 2) { index ->
        sin(2.0 * PI * hz * (index / 2) / sampleRate).toFloat()
    }
}
