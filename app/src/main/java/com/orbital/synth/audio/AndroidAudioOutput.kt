package com.orbital.synth.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.music.FactorySequence
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Runtime observations, not a claim of verified end-to-end device latency. */
data class AudioOutputDiagnostics(
    val sampleRate: Int,
    val running: Boolean,
    val encoding: String,
    val framesRendered: Long,
    val underruns: Int
)

/**
 * Streaming stereo Android output for the platform-independent [OrbitalSynth].
 *
 * A single high-priority render worker owns AudioTrack.play/write/release. UI and lifecycle
 * callbacks only request shutdown and pause the track to unblock writes. A separate
 * pure Kotlin sequencer transport splits audio renders at absolute sample offsets.
 * Device-dependent low-latency mode is requested but not assumed to be granted.
 */
class AndroidAudioOutput(
    context: Context,
    private val synth: OrbitalSynth,
    val sampleRate: Int = 48_000,
    private val framesPerWrite: Int = 192
) : Closeable {
    init {
        require(sampleRate in 8_000..192_000)
        require(framesPerWrite in 32..4096)
    }

    private val lifecycleLock = Any()
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> requestStopForFocusLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                runCatching { playback?.setVolume(0.3f) }
            AudioManager.AUDIOFOCUS_GAIN ->
                runCatching { playback?.setVolume(1f) }
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(focusListener)
        .build()
    private val hasFocus = AtomicBoolean(false)
    private val renderedFrameCounter = AtomicLong(0)
    private val audioSequencer = AudioSequencerTransport(
        sampleRate = sampleRate,
        renderFrames = { output, frames -> synth.renderStereo(output, frames) },
        noteOn = { note, velocity -> synth.noteOn(note, velocity) },
        noteOff = { note -> synth.noteOff(note) }
    )

    private var lifecycleGeneration = 0L
    private var pendingRestartToken: Long? = null

    @Volatile private var running = false
    @Volatile private var disposed = false
    @Volatile private var playback: AudioTrack? = null
    @Volatile private var workerThread: Thread? = null
    @Volatile private var lastUnderruns = 0
    @Volatile private var lastEncoding = AudioFormat.ENCODING_PCM_FLOAT

    val isRunning: Boolean get() = running
    val isSequencerPlaying: Boolean get() = audioSequencer.isPlaying
    val sequencerPlayhead: Int get() = audioSequencer.playhead
    val sequencerTempo: Int get() = audioSequencer.tempo
    val sequencerEnabledSteps: Set<Int> get() = audioSequencer.enabledSteps
    val sequencerFactoryPatterns: List<FactorySequence> get() = audioSequencer.factoryPatterns
    val sequencerPatternSelection: FactoryPatternSelection get() = audioSequencer.selection
    val sequencerPatternId: String get() = audioSequencer.selectedPatternId
    val sequencerPatternName: String get() = audioSequencer.selectedPatternName
    val sequencerStepCount: Int get() = audioSequencer.stepCount

    /** All sequencer mutations are queued for the audio render worker. */
    fun setSequencerPlaying(playing: Boolean) = audioSequencer.setPlaying(playing)
    fun setSequencerTempo(bpm: Int) = audioSequencer.setTempo(bpm)
    fun setSequencerStepEnabled(index: Int, enabled: Boolean) =
        audioSequencer.setStepEnabled(index, enabled)
    /** Queues a factory sequence; returns false immediately for an unknown ID. */
    fun selectFactoryPattern(id: String): Boolean = audioSequencer.selectFactoryPattern(id)

    val diagnostics: AudioOutputDiagnostics
        get() = AudioOutputDiagnostics(
            sampleRate = sampleRate,
            running = running,
            encoding = if (lastEncoding == AudioFormat.ENCODING_PCM_FLOAT) "PCM_FLOAT" else "PCM_16BIT",
            framesRendered = renderedFrameCounter.get(),
            underruns = runCatching { playback?.underrunCount ?: lastUnderruns }
                .getOrDefault(lastUnderruns)
        )

    /**
     * Acquires audio focus and starts the render worker. Returns false if focus or
     * device output cannot be obtained; no half-initialized track is retained.
     */
    fun start(): Boolean = synchronized(lifecycleLock) {
        if (disposed) return@synchronized false
        if (running) return@synchronized true
        if (workerThread?.isAlive == true) {
            // A rapid background -> foreground cycle must not leave the app silent.
            pendingRestartToken = ++lifecycleGeneration
            return@synchronized true
        }
        pendingRestartToken = null
        lifecycleGeneration++

        val focusResult = runCatching { audioManager.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return@synchronized false
        hasFocus.set(true)

        val config = openTrack()
        if (config == null) {
            abandonFocus()
            return@synchronized false
        }
        lastEncoding = config.encoding
        playback = config.track
        running = true
        val thread = Thread({ renderLoop(config) }, "OrbitalAudioRender")
        thread.isDaemon = true
        workerThread = thread
        thread.start()
        true
    }

    /**
     * Safe to call from onStop/onDestroy. This never releases a track while its
     * worker might still be using it; the worker owns release in its finally block.
     * This method does not block the main/UI thread. A subsequent rapid start() is queued.
     */
    fun stop() {
        val track: AudioTrack?
        val thread: Thread?
        synchronized(lifecycleLock) {
            lifecycleGeneration++
            pendingRestartToken = null
            running = false
            track = playback
            thread = workerThread
        }
        synth.allNotesOff()
        runCatching { track?.pause() }
        thread?.interrupt()
        // No join on the UI thread: worker owns release and will exit after the pause.
        abandonFocus()
    }

    override fun close() {
        synchronized(lifecycleLock) { disposed = true }
        stop()
    }

    private data class TrackConfig(val track: AudioTrack, val encoding: Int)

    private fun openTrack(): TrackConfig? {
        for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
            val minBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                encoding
            )
            if (minBytes <= 0) continue
            val bytesPerFrame = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 8 else 4
            val targetBytes = maxOf(minBytes, framesPerWrite * bytesPerFrame * 8)
            val bufferBytes = ((targetBytes + bytesPerFrame - 1) / bytesPerFrame) * bytesPerFrame
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } catch (failure: RuntimeException) {
                Log.w("OrbitalAudio", "AudioTrack format rejected", failure)
                null
            }
            if (track != null) {
                if (track.state == AudioTrack.STATE_INITIALIZED) return TrackConfig(track, encoding)
                runCatching { track.release() }
            }
        }
        return null
    }

    private fun renderLoop(config: TrackConfig) {
        try {
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            }
            val sampleCount = framesPerWrite * 2
            val floats = FloatArray(sampleCount)
            val scratch = FloatArray(sampleCount)
            val shorts = if (config.encoding == AudioFormat.ENCODING_PCM_16BIT)
                ShortArray(sampleCount) else null

            config.track.play()
            audioSequencer.onWorkerStart()
            while (running && !Thread.currentThread().isInterrupted) {
                // Sample-addressed notes are applied at exact offsets within the
                // buffer; UI commands reach the sequencer only on this worker.
                audioSequencer.renderBlock(floats, scratch, framesPerWrite)
                if (shorts != null) Pcm16Converter.convert(floats, shorts, sampleCount)

                var written = 0
                var noProgress = 0
                while (running && written < sampleCount) {
                    val count = if (shorts == null) {
                        config.track.write(
                            floats, written, sampleCount - written, AudioTrack.WRITE_BLOCKING
                        )
                    } else {
                        config.track.write(
                            shorts, written, sampleCount - written, AudioTrack.WRITE_BLOCKING
                        )
                    }
                    if (count < 0) throw IllegalStateException("AudioTrack write failed: " + count)
                    if (count == 0) {
                        if (++noProgress >= 20) throw IllegalStateException("AudioTrack stalled")
                        Thread.sleep(2)
                    } else {
                        written += count
                        noProgress = 0
                    }
                }
                renderedFrameCounter.addAndGet(framesPerWrite.toLong())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: RuntimeException) {
            if (running) Log.e("OrbitalAudio", "Audio render stopped unexpectedly", failure)
        } finally {
            running = false
            audioSequencer.onWorkerStop()
            lastUnderruns = runCatching { config.track.underrunCount }.getOrDefault(lastUnderruns)
            runCatching { config.track.pause() }
            runCatching { config.track.flush() }
            runCatching { config.track.release() }
            // Abandon the previous focus claim before advertising an idle worker.
            // Otherwise a concurrent start() could acquire focus just before we
            // accidentally abandon that newer session.
            abandonFocus()
            val restartToken: Long?
            synchronized(lifecycleLock) {
                if (playback === config.track) playback = null
                if (workerThread === Thread.currentThread()) workerThread = null
                restartToken = pendingRestartToken
                pendingRestartToken = null
            }
            if (restartToken != null) {
                synchronized(lifecycleLock) {
                    if (!disposed && lifecycleGeneration == restartToken) start()
                }
            }
        }
    }

    private fun requestStopForFocusLoss() {
        synchronized(lifecycleLock) {
            lifecycleGeneration++
            pendingRestartToken = null
            running = false
        }
        synth.allNotesOff()
        runCatching { playback?.pause() }
        workerThread?.interrupt()
    }

    private fun abandonFocus() {
        if (hasFocus.compareAndSet(true, false)) {
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        }
    }
}
