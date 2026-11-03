package com.orbital.synth.audio

import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.music.NoteEventKind
import com.orbital.synth.core.music.TimedNoteEvent
import com.orbital.synth.core.music.TimedNoteSink
import com.orbital.synth.core.performance.ArpeggiatorDirection
import com.orbital.synth.core.performance.ArpeggiatorSettings
import com.orbital.synth.core.performance.PerformanceArpeggiator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Actual synth stereo output for the primitive ARP event path versus its old List path. */
class RealtimeArpStereoPcmTest {
    private val sourceId = PerformanceArpTransport.ARP_SOURCE_ID

    private fun synth() = OrbitalSynth(sampleRate = 8_000, maxVoices = 8).apply {
        setParameter(ParameterId.WAVE, 0.34f)
        setParameter(ParameterId.MORPH, 0.62f)
        setParameter(ParameterId.CUTOFF, 0.85f)
        setParameter(ParameterId.RESONANCE, 0.24f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.LFO_DEPTH, 0f)
        setParameter(ParameterId.ATTACK, 0f)
        setParameter(ParameterId.DECAY, 0.08f)
        setParameter(ParameterId.SUSTAIN, 1f)
        setParameter(ParameterId.RELEASE, 0f)
    }

    @Test fun activeRatchetChordAndSourceEditsMatchLegacyArpPcmBits() {
        val settings = ArpeggiatorSettings(
            bpm = 278.0, subdivisionsPerBeat = 16,
            direction = ArpeggiatorDirection.UP_DOWN,
            octaves = 2, ratchets = 5, gate = 0.62,
            swing = 0.55, probability = 0.82
        )
        val baseline = PerformanceArpeggiator(8_000, settings, seed = 123)
        val optimized = PerformanceArpeggiator(8_000, settings, seed = 123)
        val baselineSynth = synth()
        val optimizedSynth = synth()
        for (arp in listOf(baseline, optimized)) {
            arp.noteOn(11, 0, 60, 107)
            arp.noteOn(11, 0, 64, 99)
            arp.noteOn(22, 1, 67, 110)
            arp.start(atSample = 81)
        }

        fun deliver(synth: OrbitalSynth, event: TimedNoteEvent) {
            when (event.kind) {
                NoteEventKind.NOTE_ON ->
                    synth.noteOn(sourceId, 0, event.midiNote, event.velocity.coerceIn(1, 127) / 127f)
                NoteEventKind.NOTE_OFF -> synth.noteOff(sourceId, 0, event.midiNote)
            }
        }

        fun segment(synth: OrbitalSynth, out: FloatArray, scratch: FloatArray,
                    start: Int, frames: Int, total: Int) {
            if (start == 0 && frames == total) {
                synth.renderStereo(out, frames)
            } else {
                synth.renderStereo(scratch, frames)
                System.arraycopy(scratch, 0, out, start * 2, frames * 2)
            }
        }

        var eventStart = 0L
        var eventRendered = 0
        var eventFrames = 0
        var sinkOutput = FloatArray(0)
        var sinkScratch = FloatArray(0)
        val sink = TimedNoteSink { sampleTime, kind, pitch, velocity ->
            val at = (sampleTime - eventStart).toInt()
            require(at in eventRendered until eventFrames)
            if (at > eventRendered) {
                segment(optimizedSynth, sinkOutput, sinkScratch, eventRendered,
                    at - eventRendered, eventFrames)
                eventRendered = at
            }
            when (kind) {
                NoteEventKind.NOTE_ON ->
                    optimizedSynth.noteOn(sourceId, 0, pitch, velocity.coerceIn(1, 127) / 127f)
                NoteEventKind.NOTE_OFF -> optimizedSynth.noteOff(sourceId, 0, pitch)
            }
        }

        var maxPeak = 0f
        fun renderExact(frames: Int) {
            val oldPcm = FloatArray(frames * 2)
            val newPcm = FloatArray(frames * 2)
            val oldScratch = FloatArray(frames * 2)
            val newScratch = FloatArray(frames * 2)
            val blockStart = baseline.cursorSample
            var rendered = 0
            for (event in baseline.advance(frames)) {
                val at = (event.sampleTime - blockStart).toInt()
                require(at in rendered until frames)
                if (at > rendered) {
                    segment(baselineSynth, oldPcm, oldScratch, rendered, at - rendered, frames)
                    rendered = at
                }
                deliver(baselineSynth, event)
            }
            if (rendered < frames) {
                segment(baselineSynth, oldPcm, oldScratch, rendered, frames - rendered, frames)
            }

            sinkOutput = newPcm
            sinkScratch = newScratch
            eventStart = optimized.cursorSample
            eventFrames = frames
            eventRendered = 0
            optimized.advanceInto(frames, sink)
            if (eventRendered < frames) {
                segment(optimizedSynth, newPcm, newScratch,
                    eventRendered, frames - eventRendered, frames)
            }
            for (index in oldPcm.indices) {
                assertEquals(
                    "Stereo PCM must match old ARP at ${blockStart * 2 + index}",
                    java.lang.Float.floatToRawIntBits(oldPcm[index]),
                    java.lang.Float.floatToRawIntBits(newPcm[index])
                )
                assertTrue(newPcm[index].isFinite())
                maxPeak = maxOf(maxPeak, abs(newPcm[index]))
            }
        }

        val frames = intArrayOf(1, 17, 192, 512, 37)
        repeat(14) { iteration ->
            for (count in frames) renderExact(count)
            if (iteration == 5) {
                val oldOffs = baseline.noteOff(11, 0, 64)
                val newOffs = optimized.noteOff(11, 0, 64)
                assertEquals(oldOffs, newOffs)
                oldOffs.forEach { deliver(baselineSynth, it) }
                newOffs.forEach { deliver(optimizedSynth, it) }
            }
            if (iteration == 9) {
                val changed = settings.copy(
                    direction = ArpeggiatorDirection.CHORD, ratchets = 8,
                    probability = 1.0, gate = 0.85
                )
                val oldOffs = baseline.configure(changed)
                val newOffs = optimized.configure(changed)
                assertEquals(oldOffs, newOffs)
                oldOffs.forEach { deliver(baselineSynth, it) }
                newOffs.forEach { deliver(optimizedSynth, it) }
            }
        }
        assertTrue("Actual ARP/chord DSP must be audible", maxPeak > 0.002f)
        val oldOffs = baseline.panic()
        val newOffs = optimized.panic()
        assertEquals(oldOffs, newOffs)
        oldOffs.forEach { deliver(baselineSynth, it) }
        newOffs.forEach { deliver(optimizedSynth, it) }
        repeat(4) { renderExact(512) }
        assertEquals(0, baselineSynth.activeVoiceCount)
        assertEquals(0, optimizedSynth.activeVoiceCount)
    }
}
