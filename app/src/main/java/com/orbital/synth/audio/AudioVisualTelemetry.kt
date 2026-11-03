package com.orbital.synth.audio

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Meter values derived from PCM successfully submitted to Android AudioTrack.
 *
 * rms and peak are normalized linear amplitudes; transient is a short-lived
 * increase in peak energy. brightness is a waveform-slope proxy (not an FFT,
 * spectral centroid, device microphone level, or speaker SPL measurement).
 */
data class AudioVisualSnapshot(
    val rms: Float = 0f,
    val peak: Float = 0f,
    val transient: Float = 0f,
    val brightness: Float = 0f,
)

/**
 * A single audio worker computes meters in primitive fields without locks or
 * allocations. It publishes at <= [maxRefreshHz] using ONE atomic 64-bit word
 * containing four quantized 16-bit channels. UI snapshot() allocates the
 * immutable value on the UI thread only, so all four channels come from the
 * same completed meter window.
 *
 * The worker owns observe* and its accumulators. The lifecycle thread may call
 * stop() while the worker unwinds; snapshot() immediately returns zero while
 * stopped, even if an in-flight worker published just after stop(). begin() must
 * only be called once the previous worker has exited.
 */
class AudioVisualTelemetry(
    sampleRate: Int,
    maxRefreshHz: Int = 30,
) {
    init {
        require(sampleRate in 8_000..192_000)
        require(maxRefreshHz in 1..60)
    }

    // Ceiling division guarantees an absolute <= maxRefreshHz publication cap
    // even at sample rates that are not divisible by the requested frame rate.
    private val framesPerPublication =
        ((sampleRate + maxRefreshHz - 1) / maxRefreshHz).coerceAtLeast(1)
    private val packed = AtomicLong(0L)
    @Volatile private var active = false

    // Only the render worker mutates these fields while active.
    private var windowFrames = 0
    private var sumSquared = 0.0
    private var windowPeak = 0f
    private var sumDifference = 0.0
    private var sumAbsolute = 0.0
    private var precedingLeft = 0f
    private var precedingRight = 0f
    private var havePreceding = false
    private var heldPeak = 0f

    /** Called before a new worker starts; never while another worker is alive. */
    fun begin() {
        active = false
        packed.set(0L)
        resetAccumulators()
        active = true
    }

    /** Immediately hide stale audio activity, including during asynchronous stop. */
    fun stop() {
        active = false
        packed.set(0L)
    }

    /** Process the exact float PCM submitted to the track, after a successful write. */
    fun observeFloat(stereo: FloatArray, frames: Int) {
        require(frames in 0..stereo.size / 2)
        if (!active) return
        var offset = 0
        repeat(frames) {
            accumulate(sanitize(stereo[offset]), sanitize(stereo[offset + 1]))
            offset += 2
        }
    }

    /** Process the exact signed 16-bit PCM submitted to a fallback AudioTrack. */
    fun observePcm16(stereo: ShortArray, frames: Int) {
        require(frames in 0..stereo.size / 2)
        if (!active) return
        var offset = 0
        repeat(frames) {
            accumulate(stereo[offset].toFloat() / 32768f, stereo[offset + 1].toFloat() / 32768f)
            offset += 2
        }
    }

    /** One coherent allocation on the reader, none on the render worker. */
    fun snapshot(): AudioVisualSnapshot {
        val value = if (active) packed.get() else 0L
        return AudioVisualSnapshot(
            rms = decode((value ushr 48) and 0xffffL),
            peak = decode((value ushr 32) and 0xffffL),
            transient = decode((value ushr 16) and 0xffffL),
            brightness = decode(value and 0xffffL),
        )
    }

    private fun accumulate(left: Float, right: Float) {
        // This hot path uses primitive arithmetic only: no collections, locks,
        // kotlin sequences, FFT buffers, snapshots, or per-frame allocations.
        val power = (left * left + right * right) * .5f
        sumSquared += power.toDouble()
        val p = max(abs(left), abs(right))
        if (p > windowPeak) windowPeak = p
        sumAbsolute += abs(left).toDouble() + abs(right).toDouble()
        if (havePreceding) {
            sumDifference += abs(left - precedingLeft).toDouble() +
                abs(right - precedingRight).toDouble()
        }
        precedingLeft = left
        precedingRight = right
        havePreceding = true

        windowFrames++
        if (windowFrames >= framesPerPublication) publishWindow()
    }

    private fun publishWindow() {
        if (windowFrames <= 0) return
        val rms = sqrt(sumSquared / windowFrames.toDouble()).toFloat().coerceIn(0f, 1f)
        val peak = windowPeak.coerceIn(0f, 1f)
        // Peak rises flash once. The previous peak decays across quiet windows
        // so a new attack can trigger after a short silence.
        val transient = ((peak - heldPeak).coerceAtLeast(0f) * 1.4f).coerceIn(0f, 1f)
        heldPeak = max(peak, heldPeak * .72f)
        // A cheap, useful timbre proxy that changes with actual waveform rate.
        // Low-amplitude silence is gated so numerical noise cannot glow.
        val brightness = if (rms < .0001f) 0f else
            (sumDifference / (2.0 * sumAbsolute + 1e-9)).toFloat().coerceIn(0f, 1f)

        // A stop may race the final frame. A stopped UI always reads zero.
        if (active) {
            val bits = (encode(rms) shl 48) or (encode(peak) shl 32) or
                (encode(transient) shl 16) or encode(brightness)
            packed.set(bits)
        }
        windowFrames = 0
        sumSquared = 0.0
        windowPeak = 0f
        sumDifference = 0.0
        sumAbsolute = 0.0
    }

    private fun resetAccumulators() {
        windowFrames = 0
        sumSquared = 0.0
        windowPeak = 0f
        sumDifference = 0.0
        sumAbsolute = 0.0
        precedingLeft = 0f
        precedingRight = 0f
        havePreceding = false
        heldPeak = 0f
    }

    private fun sanitize(value: Float): Float =
        if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f

    private fun encode(value: Float): Long =
        (value.coerceIn(0f, 1f) * 65535f + .5f).toLong()

    private fun decode(value: Long): Float = value.toFloat() / 65535f
}
