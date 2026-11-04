package com.orbital.synth.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.audio.AudioVisualSnapshot
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.stage.ChordAssist
import com.orbital.synth.core.stage.DiatonicGrid
import com.orbital.synth.core.stage.GuideOutcome
import com.orbital.synth.core.stage.GuideResult
import com.orbital.synth.core.stage.GuideSession
import com.orbital.synth.core.stage.Song
import com.orbital.synth.core.stage.SongBook
import com.orbital.synth.core.stage.StageExpressionMap
import com.orbital.synth.core.stage.StageSurface
import com.orbital.synth.core.stage.SurfaceEvent
import com.orbital.synth.core.stage.glow.AuroraPalette
import com.orbital.synth.core.stage.glow.AuroraSim
import com.orbital.synth.ui.aurora.AuroraFooter
import com.orbital.synth.ui.aurora.AuroraHeader
import com.orbital.synth.ui.aurora.AuroraPrefs
import com.orbital.synth.ui.aurora.AuroraResultCard
import com.orbital.synth.ui.aurora.AuroraSongSheet
import com.orbital.synth.ui.aurora.ComposeGlowCanvas
import com.orbital.synth.ui.aurora.GuideHud
import com.orbital.synth.ui.aurora.LANE_MIN_DP
import com.orbital.synth.ui.aurora.LANE_MIN_DP_LESSON
import com.orbital.synth.ui.aurora.auroraKeyOf
import com.orbital.synth.ui.aurora.fitOctaves
import com.orbital.synth.ui.aurora.ordinalLabel
import com.orbital.synth.ui.aurora.rgbColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

private val StageFlickFadeEasing = Easing { StageFlickMotionMath.smoothProgress(it) }

/** A pointer id no real finger can have; the "ghost finger" that plays a song demo. */
private const val DEMO_POINTER = -100L

private const val FRAME_SECONDS = 1f / 60f

// One mutable sample per admitted DOWN; dragging mutates it in place. Recency
// is assigned only to real sample changes, never stationary bystanders.
private class StagePointerSample(
    var x: Float,
    var y: Float,
    var pressure: Float,
    var revision: Long,
)

/**
 * The AURORA stage: a diatonic touch instrument. Every horizontal lane is a note of the
 * chosen key, bass at the bottom and treble at the top, so any touch is in key. The surface
 * glows and moves like it is awake, and a built-in song guide can teach melodies by lighting
 * the next note to play.
 *
 * This is a real input surface: touches are routed to the application's one audio engine
 * through the same note and parameter callbacks as the physical keyboard. There is no second
 * synth in the renderer.
 */
@Composable
internal fun ImmersiveStage(
    state: OrbitalUiState,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    onParameterChange: (ParameterId, Float) -> Unit,
    onExpressiveNoteOn: ((Int, Float) -> Unit)? = null,
    onExit: () -> Unit,
    /** Optional source for DSP modulation: normalized X, Y, and touch pressure. */
    onModulationTouchExpression: ((Float, Float, Float) -> Unit)? = null,
    /** Restores a previously configured stage without affecting the main keyboard. */
    initialPerformanceConfig: StageTouchSettings? = null,
    /** Emit only performer-initiated configuration changes for host persistence. */
    onPerformanceConfigChange: ((StageTouchSettings) -> Unit)? = null,
    /** Meter from real Android AudioTrack output, sampled only while the stage is on-screen. */
    audioVisualSource: (() -> AudioVisualSnapshot)? = null,
    /** Host revision invalidating all held performance voices on background/recall. */
    performanceResetEpoch: Long = 0L,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { AuroraPrefs(context) }
    // Continuous motion only when Android animations are on (the same rule the sound field
    // follows); otherwise the stage redraws just when something changes.
    val animatorsOn = remember { ValueAnimator.areAnimatorsEnabled() }

    // Independent Back/FOCUS cancellation revokes the current pointer recognizer
    // while retaining its stage configuration and the host's audio session epoch.
    var pointerCancelEpoch by remember { mutableIntStateOf(0) }
    var showConfig by remember { mutableStateOf(false) }
    var showSongs by remember { mutableStateOf(false) }
    // Explicit opt-in. On a clean canvas there is no synth HUD or keyboard.
    // Android Back (or the screen reader custom action) reveals controls first.
    var cleanCanvas by remember { mutableStateOf(false) }
    var stageSettings by remember { mutableStateOf(initialPerformanceConfig ?: StageTouchSettings()) }
    var chord by remember { mutableStateOf(prefs.chord) }
    var everTouched by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }

    val haptics = LocalHapticFeedback.current
    val hapticContext = LocalContext.current
    val hapticView = LocalView.current
    val accessibilityService = remember(hapticContext) {
        hapticContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    // pointerInput is keyed to the surface, not the settings dialog. A mutable
    // state reader ensures a freshly opted-in toggle takes effect on next note.
    val latestHapticsOptIn = rememberUpdatedState(stageSettings.hapticsEnabled)
    val latestOn = rememberUpdatedState(onNoteOn)
    val latestExpressiveOn = rememberUpdatedState(onExpressiveNoteOn)
    val latestOff = rememberUpdatedState(onNoteOff)
    val latestParameter = rememberUpdatedState(onParameterChange)
    val latestModulationTouch = rememberUpdatedState(onModulationTouchExpression)
    val latestPerformanceConfigChange = rememberUpdatedState(onPerformanceConfigChange)
    val latestAudioVisualSource = rememberUpdatedState(audioVisualSource)
    val latestState = rememberUpdatedState(state)
    var audioVisual by remember { mutableStateOf(AudioVisualSnapshot()) }
    val latestAudioVisual = rememberUpdatedState(audioVisual)
    // Snapshot reads do not touch the DSP engine and never run on its audio thread.
    // No polling occurs for preview hosts without a meter, or after stage disposal.
    LaunchedEffect(audioVisualSource != null) {
        val silent = AudioVisualSnapshot()
        while (isActive) {
            val source = latestAudioVisualSource.value
            if (source == null) {
                if (audioVisual != silent) audioVisual = silent
                break
            }
            val next = source()
            if (next != audioVisual) audioVisual = next
            delay(33L)
        }
    }

    // ---- the playable lanes ---------------------------------------------------------------
    var playfieldHeightDp by remember { mutableFloatStateOf(0f) }
    var guide by remember { mutableStateOf<GuideSession?>(null) }
    var guideRevision by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<GuideResult?>(null) }
    var resultIsBest by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }

    fun gridFor(settings: StageTouchSettings, lesson: Boolean): DiatonicGrid {
        val key = auroraKeyOf(settings.tonic, settings.scale)
        val octaves = fitOctaves(
            settings.octaveRange, playfieldHeightDp, key.stepsPerOctave,
            if (lesson) LANE_MIN_DP_LESSON else LANE_MIN_DP,
        )
        return DiatonicGrid(key, settings.baseOctave, octaves)
    }

    val lessonActive = guide != null
    val grid = remember(
        stageSettings.tonic, stageSettings.scale, stageSettings.baseOctave,
        stageSettings.octaveRange, playfieldHeightDp, lessonActive,
    ) { gridFor(stageSettings, lessonActive) }
    val latestGrid = rememberUpdatedState(grid)
    val palette = remember(grid.key) { AuroraPalette(grid.key) }
    val accent = remember(palette) { rgbColor(palette.root) }

    val surface = remember(performanceResetEpoch) { StageSurface(grid) }
    val sim = remember { AuroraSim() }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    var frameTick by remember { mutableLongStateOf(0L) }
    // The simulation must know the grid before the first frame is drawn.
    remember(grid) { sim.setGrid(grid); true }
    sim.reducedMotion = !animatorsOn
    sim.ambient = animatorsOn && !cleanCanvas
    sim.idleVisibility = if (cleanCanvas) 0f else 1f

    fun feedAudio() {
        val a = latestAudioVisual.value
        sim.audio(a.rms, a.peak, a.transient, a.lowBand, a.midBand, a.highBand)
    }

    /** Ask for a redraw: wake the frame loop, or draw once when motion is turned off. */
    fun poke() {
        if (animatorsOn) {
            wake.trySend(Unit)
        } else {
            feedAudio()
            sim.step(FRAME_SECONDS)
            frameTick++
        }
    }

    LaunchedEffect(sim, animatorsOn) {
        if (!animatorsOn) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            if (!sim.needsFrames) {
                last = 0L
                // Sleep until something changes, then fall through and draw at least one
                // frame: that final frame is what makes a faded or hidden surface truly dark.
                wake.receive()
            }
            withFrameNanos { now ->
                val dt = if (last == 0L) FRAME_SECONDS else (now - last) / 1_000_000_000f
                last = now
                feedAudio()
                sim.step(dt)
                frameTick++
            }
            // Idle ambience needs only a gentle cadence; real activity runs every frame.
            if (!sim.busy) delay(33L)
        }
    }
    // With motion off, still follow the audio meter (light brightens with real output).
    LaunchedEffect(audioVisual) { if (!animatorsOn) poke() }
    // Entering or leaving FOCUS changes what an idle surface looks like: draw it once.
    LaunchedEffect(cleanCanvas, grid) { poke() }

    // ---- expression: offsets around the patch's own sound, restored on release ------------
    val expressionIds = remember {
        arrayOf(ParameterId.CUTOFF, ParameterId.MORPH, ParameterId.MOVEMENT, ParameterId.FX_AMOUNT)
    }
    val baseline = remember { FloatArray(4) { .5f } }
    val restored = remember { FloatArray(4) { Float.NaN } }
    // Dedupe minute pointer jitter before triggering state invalidations.
    // Finger position and the visual renderer still update on every pointer event.
    val lastExpression = remember { FloatArray(4) { Float.NaN } }
    val lastModulationTouch = remember { FloatArray(3) { Float.NaN } }
    var touchX by remember { mutableFloatStateOf(.5f) }
    var touchY by remember { mutableFloatStateOf(.5f) }
    var touchIntensity by remember { mutableFloatStateOf(0f) }
    // Purely visual finger-flick energy: a real position/time derivative, NEVER
    // synthetic microphone pressure or a second DSP parameter. The one-shot
    // 420ms Animatable pauses completely once fingers stop and audio falls quiet.
    var flickKick by remember { mutableFloatStateOf(0f) }
    var flickRevision by remember { mutableLongStateOf(0L) }
    val touchSessions = remember { StageTouchSessionOwner() }
    val flickLight = remember { Animatable(0f) }
    LaunchedEffect(flickRevision) {
        if (flickRevision == 0L) return@LaunchedEffect
        // Honour the device's reduce-motion animator setting. Position-driven
        // touch feedback still works without this optional flash animation.
        if (!ValueAnimator.areAnimatorsEnabled() || flickKick <= .001f) {
            flickLight.snapTo(0f)
        } else {
            flickLight.snapTo(maxOf(flickKick.coerceIn(0f, 1f), flickLight.value * .72f))
            flickLight.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = StageFlickMotionMath.DECAY_MS,
                    easing = StageFlickFadeEasing,
                ),
            )
        }
    }
    var pulse by remember { mutableLongStateOf(0L) }
    var lastHapticAt by remember { mutableLongStateOf(-1000L) }

    fun captureBaseline() {
        for (i in expressionIds.indices) {
            val current = (latestState.value.parameters[expressionIds[i]] ?: .5f).coerceIn(0f, 1f)
            // If the host has already caught up with our restore the old baseline is still the
            // truth; a different value means the performer moved a knob, so adopt that.
            if (restored[i].isNaN() || abs(current - restored[i]) > .03f) baseline[i] = current
        }
    }

    fun sendExpression(index: Int, value: Float) {
        if (lastExpression[index].isNaN() || abs(lastExpression[index] - value) >= .008f) {
            lastExpression[index] = value
            latestParameter.value(expressionIds[index], value)
        }
    }

    fun restoreBaseline() {
        for (i in expressionIds.indices) {
            restored[i] = baseline[i]
            lastExpression[i] = Float.NaN
            latestParameter.value(expressionIds[i], baseline[i])
        }
    }

    fun reportTouchModulation(x: Float, y: Float, pressure: Float, force: Boolean = false) {
        val vx = if (x.isFinite()) x.coerceIn(0f, 1f) else .5f
        val vy = if (y.isFinite()) y.coerceIn(0f, 1f) else .5f
        val vp = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else .5f
        if (force || lastModulationTouch[0].isNaN() ||
            abs(vx - lastModulationTouch[0]) >= .01f ||
            abs(vy - lastModulationTouch[1]) >= .01f ||
            abs(vp - lastModulationTouch[2]) >= .01f
        ) {
            lastModulationTouch[0] = vx
            lastModulationTouch[1] = vy
            lastModulationTouch[2] = vp
            latestModulationTouch.value?.invoke(vx, vy, vp)
        }
    }

    // ---- lessons ---------------------------------------------------------------------------------
    fun refreshGuideVisual() {
        val g = guide
        if (g == null || g.finished) sim.clearGuide() else sim.setGuide(g.upcoming(7), g.combo)
        guideRevision++
        poke()
    }

    fun finishLesson(g: GuideSession) {
        val r = g.result() ?: return
        resultIsBest = prefs.recordStars(g.song.id, r.stars)
        result = r
        sim.clearGuide()
        sim.celebrate(r.stars)
        guideRevision++
        poke()
    }

    fun guideStrike(lane: Int) {
        val g = guide ?: return
        if (result != null) return
        when (g.onStrike(lane)) {
            GuideOutcome.HIT -> {
                hint = ""
                sim.guideHit(lane, g.combo)
                if (g.finished) finishLesson(g) else refreshGuideVisual()
            }
            GuideOutcome.MISS -> {
                sim.guideMiss(lane)
                hint = "FOLLOW THE GOLD BEACON"
                guideRevision++
            }
            GuideOutcome.IDLE -> Unit
        }
        poke()
    }

    // Never route an audio event through visual-only state. All updates go to the
    // same callbacks that own the AudioTrack worker and its source-specific notes.
    fun deliver(events: List<SurfaceEvent>) {
        for (event in events) {
            when (event) {
                is SurfaceEvent.NoteOn -> {
                    // Keep the legacy note-only callback as a compatible fallback.
                    // A velocity-aware host can send the exact contact dynamic to DSP.
                    val expressiveOn = latestExpressiveOn.value
                    if (expressiveOn != null) {
                        expressiveOn(event.note, event.velocity.coerceIn(0f, 1f))
                    } else {
                        latestOn.value(event.note)
                    }
                }
                is SurfaceEvent.NoteOff -> latestOff.value(event.note)
                is SurfaceEvent.Expression -> {
                    val t = StageExpressionMap.targets(
                        baseline[0], baseline[1], baseline[2], baseline[3], event,
                    )
                    sendExpression(0, t.cutoff)
                    sendExpression(1, t.morph)
                    sendExpression(2, t.movement)
                    sendExpression(3, t.fx)
                }
                is SurfaceEvent.Strike -> {
                    val g = latestGrid.value
                    sim.strike(
                        event.pointerId, event.lane, event.x, event.y, event.velocity, event.slid,
                        event.chordSymbol,
                        event.roman.ifEmpty { ordinalLabel(g.degreeNumber(event.lane)) },
                        event.lanes,
                    )
                    everTouched = true
                    if (event.pointerId != DEMO_POINTER) guideStrike(event.lane) else poke()
                }
                is SurfaceEvent.Release -> {
                    sim.release(event.pointerId)
                    poke()
                    // The last finger home: the patch returns exactly to how it was.
                    if (surface.activePointerCount == 0) restoreBaseline()
                }
            }
        }
    }

    var demoJob by remember { mutableStateOf<Job?>(null) }

    fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
        listening = false
    }

    fun startDemo(song: Song, g: DiatonicGrid = latestGrid.value) {
        stopDemo()
        if (!song.fitsIn(g)) return
        val offset = song.laneOffset(g)
        val timeline = song.timeline()
        demoJob = scope.launch {
            listening = true
            val started = SystemClock.elapsedRealtime()
            try {
                // Recomposition may not have retuned the surface yet: make it match this grid.
                deliver(
                    surface.configure(
                        grid = g, chord = chord, glide = stageSettings.glissando,
                        pressureSensitivity = stageSettings.pressureSensitivity,
                    ),
                )
                if (surface.activePointerCount == 0) captureBaseline()
                for ((i, step) in timeline.withIndex()) {
                    val wait = step.startMs - (SystemClock.elapsedRealtime() - started)
                    if (wait > 0) delay(wait)
                    val lane = (step.step + offset).coerceIn(0, g.laneCount - 1)
                    // A ghost finger sweeps gently across the surface as the tune plays.
                    val x = 0.22f + 0.56f * (i.toFloat() / timeline.size.coerceAtLeast(1))
                    deliver(surface.touchDown(DEMO_POINTER, x, g.centerY(lane), 1f, SystemClock.uptimeMillis()))
                    delay(step.durationMs)
                    deliver(surface.touchUp(DEMO_POINTER))
                }
            } finally {
                deliver(surface.touchUp(DEMO_POINTER))
                listening = false
            }
        }
    }

    fun applySettings(next: StageTouchSettings) {
        if (stageSettings != next) {
            stageSettings = next
            latestPerformanceConfigChange.value?.invoke(next)
        }
    }

    fun endLesson() {
        stopDemo()
        hint = ""
        guide = null
        result = null
        sim.clearGuide()
        guideRevision++
        poke()
    }

    fun beginLesson(song: Song): DiatonicGrid? {
        stopDemo()
        showSongs = false
        result = null
        var next = stageSettings
        val scale = TouchScale.fromScaleId(song.scaleId)
        if (next.scale != scale) next = next.copy(scale = scale)
        if (next.octaveRange < 2) next = next.copy(octaveRange = 2)
        val lessonGrid = gridFor(next, lesson = true)
        if (!song.fitsIn(lessonGrid)) {
            hint = "THIS SONG NEEDS A TALLER SCREEN"
            return null
        }
        applySettings(next)
        guide = GuideSession(song, lessonGrid)
        hint = "PLAY THE GLOWING NOTE"
        refreshGuideVisual()
        return lessonGrid
    }

    // A key or range change mid-song re-lays the melody on the new lanes, keeping progress.
    LaunchedEffect(grid) {
        val g = guide ?: return@LaunchedEffect
        if (result != null) return@LaunchedEffect
        val moved = runCatching { g.withGrid(grid) }.getOrNull()
        if (moved == null) {
            hint = "LESSON ENDED: SCALE CHANGED"
            endLesson()
        } else {
            guide = moved
            refreshGuideVisual()
        }
    }

    // A restored stage's custom settings must survive initial composition.
    // Accept a newly loaded host preference, without treating restore as a user edit.
    LaunchedEffect(initialPerformanceConfig) {
        if (initialPerformanceConfig != null && initialPerformanceConfig != stageSettings) {
            stageSettings = initialPerformanceConfig
        }
    }
    LaunchedEffect(surface, grid, chord, stageSettings.glissando, stageSettings.pressureSensitivity) {
        deliver(
            surface.configure(
                grid = grid,
                chord = chord,
                glide = stageSettings.glissando,
                pressureSensitivity = stageSettings.pressureSensitivity,
            ),
        )
        poke()
    }
    DisposableEffect(surface, performanceResetEpoch) {
        onDispose {
            deliver(surface.cancelAll())
            sim.cancelAll()
            // The old surface owns only its note leases. Its pointerInput
            // recognizer's finally block releases the shared visual/touch
            // sources through StageTouchSessionOwner. An unconditional reset
            // here can run AFTER a fresh epoch's DOWN and silently steal its
            // live pressure, X/Y and caustic lease.
        }
    }
    DisposableEffect(Unit) { onDispose { demoJob?.cancel() } }

    fun cancelPerformanceTouch() {
        stopDemo()
        deliver(surface.cancelAll())
        sim.cancelAll()
        pointerCancelEpoch++
        flickKick = 0f
        flickRevision++
        touchIntensity = 0f
        reportTouchModulation(.5f, .5f, .5f, force = true)
        poke()
    }

    // Back never traps the performer in a control-free surface. Overlays close first,
    // then configuration, focus mode reveals chrome next, and the normal stage exits last.
    BackHandler {
        when {
            showSongs -> showSongs = false
            showConfig -> showConfig = false
            result != null -> { result = null; endLesson() }
            cleanCanvas -> {
                cancelPerformanceTouch()
                cleanCanvas = false
            }
            else -> {
                cancelPerformanceTouch()
                onExit()
            }
        }
    }

    // Only the explicit keyboardless FOCUS sub-mode hides Android OS chrome.
    // Back first reveals app controls, which disposes the bar-hiding effect.
    FocusSystemBarsEffect(focusMode = cleanCanvas)

    val parameter = { id: ParameterId ->
        (state.parameters[id] ?: .5f).coerceIn(0f, 1f)
    }
    val fieldActive = touchIntensity > .01f ||
        audioVisual.rms > .004f || audioVisual.peak > .02f
    // A touch or actual PCM output lights the field immediately. In clean
    // canvas mode the lingering light fades fully to black when sound stops.
    val fieldAlpha by animateFloatAsState(
        targetValue = if (fieldActive) 1f else if (cleanCanvas) 0f else .16f,
        animationSpec = tween(
            durationMillis = if (fieldActive) 90 else if (cleanCanvas) 950 else 260,
        ),
        label = "Immersive canvas luminance",
    )
    val textMeasurer = rememberTextMeasurer()
    val glowCanvas = remember(textMeasurer, density) { ComposeGlowCanvas(textMeasurer, density) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("immersiveStage"),
    ) {
        // The audio-reactive backdrop paints parameter-driven filaments behind the lanes.
        // The background is truly black; visual luminance is tied to the performed gesture
        // rather than to an idle animated wallpaper.
        ImmersiveVisualField(
            modifier = Modifier.fillMaxSize().alpha(fieldAlpha),
            morph = parameter(ParameterId.MORPH),
            movement = parameter(ParameterId.MOVEMENT),
            cutoff = parameter(ParameterId.CUTOFF),
            resonance = parameter(ParameterId.RESONANCE),
            touchX = touchX,
            touchY = touchY,
            touchIntensity = touchIntensity,
            touchFlickEnergy = flickLight.value,
            pulse = pulse,
            audioRms = audioVisual.rms,
            audioPeak = audioVisual.peak,
            audioTransient = audioVisual.transient,
            audioBrightness = audioVisual.brightness,
            audioLowBand = audioVisual.lowBand,
            audioMidBand = audioVisual.midBand,
            audioHighBand = audioVisual.highBand,
            skipArtwork = ImmersiveFieldDrawGate.shouldSkip(cleanCanvas, fieldAlpha),
        )

        Column(Modifier.fillMaxSize()) {
            if (!cleanCanvas) {
                AuroraHeader(
                    tonic = stageSettings.tonic,
                    scale = stageSettings.scale,
                    accent = accent,
                    onTonic = { applySettings(stageSettings.copy(tonic = it.coerceIn(0, 11))) },
                    onScale = { applySettings(stageSettings.copy(scale = it)) },
                    onExit = {
                        cancelPerformanceTouch()
                        onExit()
                    },
                    onFocus = {
                        cancelPerformanceTouch()
                        showConfig = false
                        showSongs = false
                        cleanCanvas = true
                    },
                    onConfig = {
                        cancelPerformanceTouch()
                        showConfig = true
                    },
                )
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { playfieldHeightDp = with(density) { it.height.toDp().value } },
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("immersiveCanvas")
                        .semantics {
                            contentDescription = "Immersive sound field"
                            stateDescription = if (touchIntensity > .01f)
                                "Touch sculpting" else "Touch idle"
                            if (cleanCanvas) {
                                customActions = listOf(
                                    CustomAccessibilityAction("Reveal stage controls") {
                                        cancelPerformanceTouch()
                                        cleanCanvas = false
                                        true
                                    },
                                )
                            }
                        }
                        .pointerInput(surface, performanceResetEpoch, pointerCancelEpoch) {
                            // Do not use awaitEachGesture here: it waits for EVERY old
                            // pointer to lift, including fingers held over a stopped
                            // AudioTrack worker. A brand-new contact must be admitted
                            // immediately after the epoch cancels the old note leases.
                            awaitPointerEventScope {
                                val activeIds = mutableSetOf<Long>()
                                val flickTracker = StageFlickVelocityTracker()
                                // A cancelled recognizer may finish after its successor.
                                // Its entire shared-state cleanup must respect one lease.
                                val touchSession = touchSessions.begin()
                                // Per-pointer, UI-thread-only last delivered samples. A frame from
                                // another finger's UP must never zero this contact's velocity FX.
                                // Samples allocate on fresh DOWN, not during high-rate drags.
                                val deliveredSamples = mutableMapOf<Long, StagePointerSample>()
                                var sampleRevision = 0L
                                var expressionOwnerId: Long? = null
                                fun safePressure(raw: Float): Float =
                                    // AndroidX's real MotionEvent injector does not
                                    // populate PointerCoords.pressure; Android devices
                                    // without force sensors can likewise report 0.
                                    // A *pressed* finger still needs a live TOUCH_P
                                    // source and visual intensity. Preserve any
                                    // positive measured pressure when available.
                                    if (raw.isFinite() && raw > 0f) raw.coerceIn(0f, 1f)
                                    else 1f
                                fun normalizeX(x: Float): Float =
                                    (x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                fun normalizeY(y: Float): Float =
                                    (y / size.height.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

                                fun start(id: Long, x: Float, y: Float, pressure: Float, time: Long) {
                                    activeIds.add(id)
                                    touchX = normalizeX(x)
                                    touchY = normalizeY(y)
                                    val contactPressure = safePressure(pressure)
                                    deliveredSamples[id] = StagePointerSample(
                                        touchX, touchY, contactPressure, ++sampleRevision,
                                    )
                                    expressionOwnerId = id
                                    flickTracker.down(id, touchX, touchY, time)
                                    touchIntensity = contactPressure.coerceIn(.15f, 1f)
                                    pulse += 1L
                                    reportTouchModulation(touchX, touchY, contactPressure)
                                    if (surface.activePointerCount == 0) captureBaseline()
                                    val events = surface.touchDown(id, touchX, touchY, contactPressure, time)
                                    deliver(events)
                                    // Haptic accent ONLY for an actual newly sounded note:
                                    // never during sustained drags, expression or release.
                                    // Every policy gate is read only at contact onset,
                                    // never on the audio thread or animation frame.
                                    if (events.any { it is SurfaceEvent.NoteOn }) {
                                        val systemAllowsHaptics = runCatching {
                                            Settings.System.getInt(
                                                hapticContext.contentResolver,
                                                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                                                1,
                                            ) != 0
                                        }.getOrDefault(false)
                                        if (StageHapticPolicy.shouldTrigger(
                                                optedIn = latestHapticsOptIn.value,
                                                systemEnabled = systemAllowsHaptics,
                                                viewEnabled = hapticView.isHapticFeedbackEnabled,
                                                touchExplorationEnabled =
                                                    accessibilityService?.isTouchExplorationEnabled == true,
                                                event = StageHapticPolicy.Event.NOTE_DOWN,
                                                atMs = time,
                                                previousAtMs = lastHapticAt,
                                            )
                                        ) {
                                            // Platform intensity varies. This short OS
                                            // UI feedback honours Android user settings;
                                            // it is not waveform-rate motor synthesis.
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            lastHapticAt = time
                                        }
                                    }
                                }
                                try {
                                    while (true) {
                                        val pointerEvent = awaitPointerEvent()
                                        pointerEvent.changes.forEach { change ->
                                            val id = change.id.value
                                            var handled = false
                                            when {
                                                change.pressed &&
                                                    !change.previousPressed &&
                                                    !change.isConsumed &&
                                                    id !in activeIds -> {
                                                    // Only a physical fresh DOWN may
                                                    // acquire a voice in this epoch.
                                                    // Held pre-reset pointers are ignored.
                                                    start(
                                                        id, change.position.x, change.position.y,
                                                        change.pressure, change.uptimeMillis,
                                                    )
                                                    handled = true
                                                }
                                                change.pressed && id in activeIds -> {
                                                    val nextX = normalizeX(change.position.x)
                                                    val nextY = normalizeY(change.position.y)
                                                    val nextPressure = safePressure(change.pressure)
                                                    val sample = deliveredSamples[id]
                                                    if (sample == null || sample.x != nextX ||
                                                        sample.y != nextY ||
                                                        abs(sample.pressure - nextPressure) >= .01f
                                                    ) {
                                                        if (sample == null) {
                                                            deliveredSamples[id] = StagePointerSample(
                                                                nextX, nextY, nextPressure, ++sampleRevision,
                                                            )
                                                        } else {
                                                            sample.x = nextX
                                                            sample.y = nextY
                                                            sample.pressure = nextPressure
                                                            sample.revision = ++sampleRevision
                                                        }
                                                        expressionOwnerId = id
                                                        val swipeSpeed = flickTracker.move(
                                                            id, nextX, nextY, change.uptimeMillis,
                                                        )
                                                        if (swipeSpeed != null &&
                                                            (swipeSpeed >= .015f || flickKick > .015f)
                                                        ) {
                                                            flickKick = swipeSpeed
                                                            flickRevision++
                                                        }
                                                        touchX = nextX
                                                        touchY = nextY
                                                        touchIntensity = nextPressure.coerceIn(.15f, 1f)
                                                        reportTouchModulation(touchX, touchY, nextPressure)
                                                        sim.move(id, touchX, touchY)
                                                        deliver(
                                                            surface.touchMove(
                                                                id, touchX, touchY, nextPressure,
                                                                change.uptimeMillis,
                                                            ),
                                                        )
                                                        poke()
                                                    }
                                                    handled = true
                                                }
                                                !change.pressed && activeIds.remove(id) -> {
                                                    deliveredSamples.remove(id)
                                                    flickTracker.up(id)
                                                    deliver(surface.touchUp(id))
                                                    if (id == expressionOwnerId) {
                                                        // Return the global touch buses/visual origin to
                                                        // the most recently delivered surviving contact.
                                                        // Do NOT call surface.touchMove: a handoff is not a
                                                        // new physical move and must not invent speed=0.
                                                        val successor = deliveredSamples.maxByOrNull {
                                                            it.value.revision
                                                        }
                                                        expressionOwnerId = successor?.key
                                                        val heldSample = successor?.value
                                                        if (heldSample != null) {
                                                            touchX = heldSample.x
                                                            touchY = heldSample.y
                                                            touchIntensity =
                                                                heldSample.pressure.coerceIn(.15f, 1f)
                                                            reportTouchModulation(
                                                                touchX, touchY, heldSample.pressure,
                                                                force = true,
                                                            )
                                                        } else {
                                                            touchIntensity = 0f
                                                            reportTouchModulation(
                                                                .5f, .5f, .5f, force = true,
                                                            )
                                                        }
                                                    }
                                                    handled = true
                                                }
                                            }
                                            // Do not consume a stale pointer belonging
                                            // to the prior worker epoch or to another UI.
                                            if (handled) change.consume()
                                        }
                                    }
                                } finally {
                                    // A cancellation, tab change or focus loss revokes
                                    // only this session's owned note leases. Late UP
                                    // events for pre-reset IDs cannot kill new notes.
                                    activeIds.forEach { id -> deliver(surface.touchUp(id)) }
                                    activeIds.clear()
                                    deliveredSamples.clear()
                                    flickTracker.cancelAll()
                                    // Local note bookkeeping above remains separate.
                                    // Shared visual and global DSP source state belongs to
                                    // the newest recognizer, so guard every reset together.
                                    touchSessions.finish(touchSession) {
                                        flickKick = 0f
                                        flickRevision++
                                        touchIntensity = 0f
                                        touchX = .5f
                                        touchY = .5f
                                        reportTouchModulation(.5f, .5f, .5f, force = true)
                                    }
                                }
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize().testTag("auroraField")) {
                        // Reading the tick subscribes this draw to every simulation frame.
                        if (frameTick < 0L) return@Canvas
                        sim.resize(size.width, size.height, this.density)
                        glowCanvas.bind(this)
                        sim.draw(glowCanvas)
                        glowCanvas.unbind()
                    }
                }

                if (!cleanCanvas) {
                    val coach = when {
                        hint.isNotEmpty() -> hint
                        !everTouched -> "BOTTOM = BASS   ·   TOP = HIGH   ·   EVERY LANE IS IN KEY"
                        else -> ""
                    }
                    if (coach.isNotEmpty()) {
                        Text(
                            coach,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).testTag("auroraCoach"),
                            color = Color.White.copy(alpha = .55f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.6.sp,
                        )
                    }
                }
            }

            if (!cleanCanvas) {
                // Keyed on guideRevision so the readout recomposes as the melody advances.
                val hud = remember(guideRevision, listening, guide, result) {
                    guide?.takeIf { result == null }
                        ?.let { GuideHud(it.song.title, it.progress, it.combo, listening) }
                }
                AuroraFooter(
                    accent = accent,
                    chord = chord,
                    bassOctave = stageSettings.baseOctave,
                    guide = hud,
                    onSongs = { showSongs = true },
                    onChord = {
                        val all = ChordAssist.entries
                        chord = all[(all.indexOf(chord) + 1) % all.size]
                        prefs.chord = chord
                    },
                    onOctave = { applySettings(stageSettings.copy(baseOctave = it.coerceIn(0, 6))) },
                    onListen = { guide?.let { startDemo(it.song) } },
                    onEndGuide = { endLesson() },
                )
            }
        }

        if (showSongs) {
            AuroraSongSheet(
                songs = SongBook.ALL,
                bestStars = { prefs.bestStars(it) },
                accent = accent,
                onLearn = { beginLesson(it) },
                onListen = { song ->
                    // Hearing a song first also sets up its lesson, so the player can follow on.
                    beginLesson(song)?.let { lessonGrid -> startDemo(song, lessonGrid) }
                },
                onClose = { showSongs = false },
            )
        }
        val finished = result
        val finishedGuide = guide
        if (finished != null && finishedGuide != null) {
            AuroraResultCard(
                song = finishedGuide.song,
                result = finished,
                isBest = resultIsBest,
                onAgain = { beginLesson(finishedGuide.song) },
                onSongs = { result = null; endLesson(); showSongs = true },
                onClose = { result = null; endLesson() },
            )
        }
        if (showConfig) {
            StageTouchConfigurationPanel(
                settings = stageSettings,
                onChange = { applySettings(it) },
                onDismiss = { showConfig = false },
                onReset = {
                    val reset = StageTouchSettings()
                    stageSettings = reset
                    latestPerformanceConfigChange.value?.invoke(reset)
                },
            )
        }
    }
}
