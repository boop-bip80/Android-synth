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
import com.orbital.synth.core.music.SequencerNote
import com.orbital.synth.core.music.StepPattern
import com.orbital.synth.core.performance.ArpeggiatorDirection
import java.io.Closeable
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
    // All state transitions and AudioManager focus operations run under this lock.
    // Keeping request and abandon serialized prevents a stale stop abandoning
    // a newer start's focus claim.
    private val focusState = AudioFocusSessionState()
    // An unexpected AudioTrack failure gets at most one retry per foreground session.
    // All policy transitions are guarded by lifecycleLock, never the audio sample loop.
    private val failureRecovery = AudioFailureRecoveryPolicy()
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> requestStopForFocusLoss(transient = false)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> requestStopForFocusLoss(transient = true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> synchronized(lifecycleLock) {
                if (focusState.hasClaim) runCatching { playback?.setVolume(0.3f) }
            }
            AudioManager.AUDIOFOCUS_GAIN -> onFocusGained()
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(focusListener)
        .build()
    private val renderedFrameCounter = AtomicLong(0)
    // Monotonic UI touch-session invalidation; never increments per PCM frame.
    private val performanceSessionRevision = AtomicLong(0L)
    // Submitted PCM is metered on the audio worker with no allocations or locks.
    // A snapshot allocation occurs only when a UI consumer requests one.
    private val visualTelemetry = AudioVisualTelemetry(sampleRate)
    // Two preallocated scratch buffers support nested sample-accurate arp and
    // step-sequencer event boundaries without allocating arrays per callback.
    private val performanceScratch = FloatArray(framesPerWrite * 2)
    private val performanceArp = PerformanceArpTransport(
        sampleRate = sampleRate,
        renderFrames = { output, frames -> synth.renderStereo(output, frames) },
        noteOn = { source, channel, note, velocity ->
            synth.noteOn(source, channel, note, velocity)
        },
        noteOff = { source, channel, note -> synth.noteOff(source, channel, note) }
    )
    private val audioSequencer = AudioSequencerTransport(
        sampleRate = sampleRate,
        renderFrames = { output, frames ->
            performanceArp.renderBlock(output, performanceScratch, frames)
        },
        noteOn = { note, velocity -> synth.noteOn(SEQUENCER_SOURCE_ID, 0, note, velocity) },
        noteOff = { note -> synth.noteOff(SEQUENCER_SOURCE_ID, 0, note) }
    )

    private var lifecycleGeneration = 0L
    private var pendingRestartToken: Long? = null

    @Volatile private var running = false
    @Volatile private var disposed = false
    @Volatile private var playback: AudioTrack? = null
    @Volatile private var workerThread: Thread? = null
    @Volatile private var lastUnderruns = 0
    @Volatile private var lastEncoding = AudioFormat.ENCODING_PCM_FLOAT

    /** Current unconsumed terminal error, for diagnostics or a foreground UI notice. */
    val fatalErrorMessage: String?
        get() = synchronized(lifecycleLock) { failureRecovery.pendingFatalNotice }

    /** Take a terminal error exactly once from the UI poll (never from the audio callback). */
    fun consumeFatalErrorNotice(): String? = synchronized(lifecycleLock) {
        failureRecovery.consumeFatalNotice()
    }

    val isRunning: Boolean get() = running

    /** Revoke stale touch leases across fast stop/restart and focus interruptions. */
    val performanceSessionEpoch: Long get() = performanceSessionRevision.get()

    /** Coherent, normalized PCM-derived meter for visual consumers (up to 30 Hz). */
    val audioVisualSnapshot: AudioVisualSnapshot get() = visualTelemetry.snapshot()
    val isSequencerPlaying: Boolean get() = audioSequencer.isPlaying
    val sequencerPlayhead: Int get() = audioSequencer.playhead
    val sequencerTempo: Int get() = audioSequencer.tempo
    val sequencerEnabledSteps: Set<Int> get() = audioSequencer.enabledSteps
    val sequencerFactoryPatterns: List<FactorySequence> get() = audioSequencer.factoryPatterns
    val sequencerPatternSelection: FactoryPatternSelection get() = audioSequencer.selection
    val sequencerPatternId: String get() = audioSequencer.selectedPatternId
    val sequencerPatternName: String get() = audioSequencer.selectedPatternName
    val sequencerStepCount: Int get() = audioSequencer.stepCount

    val isArpeggiatorEnabled: Boolean get() = performanceArp.enabled
    val arpeggiatorDirection: ArpeggiatorDirection get() = performanceArp.direction
    val arpeggiatorRate: Int get() = performanceArp.subdivisionsPerBeat
    val arpeggiatorTempo: Int get() = performanceArp.tempo
    val arpeggiatorLatch: Boolean get() = performanceArp.latch
    val arpeggiatorOctaves: Int get() = performanceArp.octaves
    val arpeggiatorRatchets: Int get() = performanceArp.ratchets
    val arpeggiatorGate: Double get() = performanceArp.gate
    val arpeggiatorProbability: Double get() = performanceArp.probability

    /** Worker-queued controls; safe to call from Compose or MIDI callbacks. */
    fun setArpeggiatorEnabled(enabled: Boolean) = performanceArp.setEnabled(enabled)
    fun setArpeggiatorDirection(direction: ArpeggiatorDirection) =
        performanceArp.setDirection(direction)
    fun setArpeggiatorRate(subdivisionsPerBeat: Int): Boolean =
        performanceArp.setRate(subdivisionsPerBeat)
    fun setArpeggiatorTempo(bpm: Int): Boolean = performanceArp.setTempo(bpm)
    fun setArpeggiatorLatch(latch: Boolean) = performanceArp.setLatch(latch)
    fun setArpeggiatorSwing(swing: Double): Boolean = performanceArp.setSwing(swing)
    fun setArpeggiatorOctaves(octaves: Int): Boolean = performanceArp.setOctaves(octaves)
    fun setArpeggiatorRatchets(ratchets: Int): Boolean = performanceArp.setRatchets(ratchets)
    fun setArpeggiatorGate(gate: Double): Boolean = performanceArp.setGate(gate)
    fun setArpeggiatorProbability(probability: Double): Boolean =
        performanceArp.setProbability(probability)

    /**
     * Route touch and MIDI keys here (instead of directly to the synth) for
     * automatic source-aware bypass/arp selection. Inputs arriving during audio
     * focus loss are ignored, avoiding stale keys on the next focus-gain restart.
     */
    fun noteOn(sourceId: Long, channel: Int, note: Int, velocity: Int = 127): Boolean =
        running && performanceArp.inputNoteOn(sourceId, channel, note, velocity)

    fun noteOff(sourceId: Long, channel: Int, note: Int): Boolean =
        running && performanceArp.inputNoteOff(sourceId, channel, note)

    fun allNotesOff(sourceId: Long) = performanceArp.inputSourceAllNotesOff(sourceId)

    fun arpeggiatorPanic() = performanceArp.panic()

    /** All sequencer mutations are queued for the audio render worker. */
    fun setSequencerPlaying(playing: Boolean) = audioSequencer.setPlaying(playing)
    fun setSequencerTempo(bpm: Int) = audioSequencer.setTempo(bpm)
    fun setSequencerStepEnabled(index: Int, enabled: Boolean) =
        audioSequencer.setStepEnabled(index, enabled)
    /** All edits are queued for the audio worker and validated before enqueueing. */
    fun setSequencerStepCount(steps: Int): Boolean = audioSequencer.setStepCount(steps)
    fun setSequencerStepNotes(index: Int, notes: List<SequencerNote>): Boolean =
        audioSequencer.setStepNotes(index, notes)
    fun loadSequencerPattern(
        id: String,
        name: String,
        pattern: StepPattern,
        bpm: Double = 120.0,
        swing: Double = 0.0,
        enabledSteps: Set<Int>? = null,
    ): Boolean = audioSequencer.loadPattern(id, name, pattern, bpm, swing, enabledSteps)
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
        focusState.requestPlayback()
        // A temporary focus loss keeps its registered claim to receive GAIN.
        // Do not restart the output until Android actually returns that focus.
        if (focusState.awaitingGain) return@synchronized true
        if (running) return@synchronized true
        if (workerThread?.isAlive == true) {
            // A rapid background -> foreground cycle must not leave the app silent.
            pendingRestartToken = ++lifecycleGeneration
            return@synchronized true
        }
        pendingRestartToken = null
        lifecycleGeneration++

        val granted = focusState.acquireClaim {
            runCatching {
                audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }.getOrDefault(false)
        }
        if (!granted) {
            focusState.cancelPlayback()
            return@synchronized false
        }

        val config = runCatching { openTrack() }.onFailure {
            Log.w("OrbitalAudio", "AudioTrack initialization failed", it)
        }.getOrNull()
        if (config == null) {
            focusState.cancelPlayback()
            abandonFocusLocked()
            return@synchronized false
        }
        lastEncoding = config.encoding
        failureRecovery.clearFatalNotice()
        playback = config.track
        visualTelemetry.begin()
        // The previous worker has completed onWorkerStop() before start() may
        // reach this branch. Arm the atomic input epoch before publishing
        // running=true: a very first touch can queue into the first render block.
        // Worker-only DSP cleanup still happens inside renderLoop.onWorkerStart().
        performanceArp.armInputForPendingWorker()
        running = true
        performanceSessionRevision.incrementAndGet() // actual new worker
        val workerGeneration = lifecycleGeneration
        val thread = Thread({ renderLoop(config, workerGeneration) }, "OrbitalAudioRender")
        thread.isDaemon = true
        workerThread = thread
        try {
            thread.start()
            true
        } catch (failure: RuntimeException) {
            // No render worker started, so only this owner may release the
            // unopened-for-playback AudioTrack. Deactivate the prearmed epoch
            // without calling any worker-only DSP lifecycle hook on the UI.
            Log.e("OrbitalAudio", "Audio render worker could not start", failure)
            running = false
            workerThread = null
            playback = null
            visualTelemetry.stop()
            performanceArp.cancelPendingWorkerInput()
            runCatching { config.track.release() }
            focusState.cancelPlayback()
            abandonFocusLocked()
            false
        }
    }

    /**
     * Safe to call from onStop/onDestroy. This never releases a track while its
     * worker might still be using it; the worker owns release in its finally block.
     * This method does not block the main/UI thread. A subsequent rapid start() is queued.
     */
    fun stop() {
        synchronized(lifecycleLock) {
            lifecycleGeneration++
            pendingRestartToken = null
            focusState.cancelPlayback()
            failureRecovery.resetOnExplicitStop()
            running = false
            visualTelemetry.stop()
            synth.allNotesOff()
            performanceSessionRevision.incrementAndGet() // explicit stop/panic
            runCatching { playback?.pause() }
            workerThread?.interrupt()
            // Must execute under the same lock as start() requests focus: a
            // concurrent restart cannot have its newer claim abandoned here.
            abandonFocusLocked()
        }
        // No join on the UI thread: the worker owns AudioTrack.release().
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

    private fun renderLoop(config: TrackConfig, workerGeneration: Long) {
        var unexpectedFailure = false
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
            performanceArp.onWorkerStart()
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
                if (running && written == sampleCount) {
                    // Meter the PCM actually accepted by AudioTrack, including
                    // 16-bit quantization on the fallback path. Never sample
                    // unsubmitted buffers or an interrupted partial write.
                    if (shorts == null) visualTelemetry.observeFloat(floats, framesPerWrite)
                    else visualTelemetry.observePcm16(shorts, framesPerWrite)
                }
                renderedFrameCounter.addAndGet(framesPerWrite.toLong())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: RuntimeException) {
            if (running) {
                unexpectedFailure = true
                Log.e("OrbitalAudio", "Audio render stopped unexpectedly", failure)
            }
        } finally {
            val unrequestedExit = unexpectedFailure || running
            running = false
            visualTelemetry.stop()
            audioSequencer.onWorkerStop()
            performanceArp.onWorkerStop()
            // A failed worker may have left source-tagged or untagged engine notes
            // queued beyond the transport's last block. Flush all ownership before
            // any replacement worker renders a fresh buffer.
            if (unexpectedFailure) synth.allNotesOff()
            if (unrequestedExit) performanceSessionRevision.incrementAndGet()
            lastUnderruns = runCatching { config.track.underrunCount }.getOrDefault(lastUnderruns)
            runCatching { config.track.pause() }
            runCatching { config.track.flush() }
            runCatching { config.track.release() }
            synchronized(lifecycleLock) {
                if (playback === config.track) playback = null
                if (workerThread === Thread.currentThread()) workerThread = null
                val restartToken = pendingRestartToken
                pendingRestartToken = null
                val restartAllowed = restartToken != null && !disposed &&
                    lifecycleGeneration == restartToken &&
                    focusState.playbackRequested && !focusState.awaitingGain
                val sameForegroundFailure = unexpectedFailure &&
                    lifecycleGeneration == workerGeneration &&
                    focusState.playbackRequested && !focusState.awaitingGain && !disposed
                val boundedRecovery = !restartAllowed && failureRecovery.claimRetry(
                    unexpectedFailure = unexpectedFailure,
                    generationMatches = lifecycleGeneration == workerGeneration,
                    playbackRequested = focusState.playbackRequested,
                    awaitingFocusGain = focusState.awaitingGain,
                    disposed = disposed,
                )
                // Keep our focus request registered while temporarily suspended,
                // otherwise Android cannot deliver the focus-gain callback.
                // A queued rapid restart also retains the claim without churn.
                if (!focusState.retainClaimAfterWorkerExit(restartAllowed || boundedRecovery)) {
                    abandonFocusLocked()
                }
                if (restartAllowed || boundedRecovery) {
                    if (!start()) {
                        failureRecovery.publishFatalNotice(
                            "Audio output could not restart. Reopen the app to retry."
                        )
                    }
                } else if (sameForegroundFailure) {
                    failureRecovery.publishFatalNotice(
                        "Audio output stopped unexpectedly. Reopen the app to retry."
                    )
                }
            }
        }
    }

    private fun requestStopForFocusLoss(transient: Boolean) {
        synchronized(lifecycleLock) {
            if (!focusState.hasClaim) return
            if (transient) {
                if (!focusState.transientLoss()) return
            } else {
                focusState.permanentLoss()
            }
            lifecycleGeneration++
            pendingRestartToken = null
            running = false
            visualTelemetry.stop()
            synth.allNotesOff()
            performanceSessionRevision.incrementAndGet() // focus interruption
            runCatching { playback?.pause() }
            workerThread?.interrupt()
            if (!transient) abandonFocusLocked()
        }
    }

    private fun onFocusGained() {
        synchronized(lifecycleLock) {
            if (!focusState.hasClaim) return
            runCatching { playback?.setVolume(1f) }
            // If the old worker is still unwinding, start() queues its restart
            // token; otherwise it starts a fresh AudioTrack immediately.
            if (focusState.gain() && !disposed) start()
        }
    }

    /** Must be called with lifecycleLock held, as in every focus acquisition. */
    private fun abandonFocusLocked() {
        focusState.abandonClaim {
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
            Unit
        }
    }

    private companion object {
        // MIDI port IDs are positive, keyboard uses -1L. Keep the sequence independent.
        const val SEQUENCER_SOURCE_ID = -2L
    }

}
