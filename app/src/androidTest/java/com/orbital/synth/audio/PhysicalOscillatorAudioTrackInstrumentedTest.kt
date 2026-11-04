package com.orbital.synth.audio

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.MainActivity
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.ParameterId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full physical finger -> production MainActivity callback -> same OrbitalSynth
 * -> AndroidAudioOutput -> acknowledged Android AudioTrack PCM regression.
 *
 * Unlike the isolated slot bridge test, NO test calls setOscillatorSlot() or
 * publishes noise snapshots directly. Only actual Compose pointer gestures
 * mutate the oscillator lane. Waveform brightness is a slope proxy computed
 * from accepted output PCM, NOT an FFT, microphone capture or offline render.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalOscillatorAudioTrackInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val TAG = "OrbitalPhysicalV6"
        private const val SOURCE_ID = 0x6A92L
        private const val SAMPLE_RATE = 48_000
    }

    private fun activitySynth(): OrbitalSynth {
        val field = MainActivity::class.java.getDeclaredField("synth")
        field.isAccessible = true
        return field.get(compose.activity) as OrbitalSynth
    }

    private fun liveOutput(): AndroidAudioOutput {
        val field = MainActivity::class.java.getDeclaredField("audioOutput")
        field.isAccessible = true
        return field.get(compose.activity) as AndroidAudioOutput
    }

    private fun outputSynth(output: AndroidAudioOutput): OrbitalSynth {
        val field = AndroidAudioOutput::class.java.getDeclaredField("synth")
        field.isAccessible = true
        return field.get(output) as OrbitalSynth
    }

    private fun visible(node: SemanticsNodeInteraction): Boolean =
        try {
            node.assertIsDisplayed()
            true
        } catch (_: AssertionError) {
            false
        }

    /**
     * The SOUND editor is a real vertical scroller, never a Lazy fake.
     * Use only bounded physical 300ms pointer swipes in its 3px side gutter.
     * Compose performScrollTo() has hung some API35 emulator gesture paths.
     */
    private fun revealByFinger(tag: String, minHeightDp: Float): Rect {
        val target = compose.onNodeWithTag(tag)
        val editor = compose.onNodeWithTag("soundEditorScroll").assertIsDisplayed()
        val density = compose.activity.resources.displayMetrics.density

        // The ADVANCED disclosure and initial OSC enable button are below
        // the SOUND hero. Enabling OSC1 replaces the small empty-source card
        // with a larger waveform+rail section: the luminous level rail moves
        // farther DOWN the same document, followed by the noise XY pad.
        // Thus all four first probes begin below. Once any clipped real bounds
        // appear, remember their physical top/bottom edge even if subsequent
        // Compose semantics reports Rect(0,0,0,0).
        var lastKnownAbove = false
        for (attempt in 0 until 36) {
            val targetRect = target.fetchSemanticsNode().boundsInRoot
            val editorRect = editor.fetchSemanticsNode().boundsInRoot
            val requiredPx = minHeightDp * density
            val fullyVisible = visible(target) &&
                targetRect.width >= requiredPx - 1f &&
                targetRect.height >= requiredPx - 1f &&
                targetRect.left >= editorRect.left - 1f &&
                targetRect.right <= editorRect.right + 1f &&
                targetRect.top >= editorRect.top - 1f &&
                targetRect.bottom <= editorRect.bottom + 1f
            if (fullyVisible) break

            val hasPhysicalBounds = targetRect.width > 1f && targetRect.height > 1f
            val clippedAtTop = targetRect.top <= editorRect.top + 1f
            val clippedAtBottom = targetRect.bottom >= editorRect.bottom - 1f
            if (hasPhysicalBounds) {
                if (clippedAtTop && !clippedAtBottom) lastKnownAbove = true
                if (clippedAtBottom && !clippedAtTop) lastKnownAbove = false
            }

            // The compositor may reveal all but 3–6 physical pixels of a
            // 48/108dp Canvas. Android touch slop consumes ~12px; fine swipes
            // start at 30px but never jump past the opposite viewport edge.
            val fineFingerDrag = (requiredPx - targetRect.height + 14f)
                .coerceIn(30f, (editorRect.height * .21f).coerceAtLeast(30f))
            // Even the coarse zero-rect probe is narrower than the control's
            // physical height, preventing a 44dp button from being skipped.
            val zeroRectDrag = (requiredPx * .60f)
                .coerceIn(30f, (editorRect.height * .38f).coerceAtLeast(30f))
            val drag = when {
                !hasPhysicalBounds ->
                    if (lastKnownAbove) zeroRectDrag else -zeroRectDrag
                targetRect.height < requiredPx - 1f &&
                    clippedAtTop && !clippedAtBottom -> fineFingerDrag
                targetRect.height < requiredPx - 1f &&
                    clippedAtBottom && !clippedAtTop -> -fineFingerDrag
                targetRect.bottom > editorRect.bottom ->
                    -(targetRect.bottom - editorRect.bottom + 14f).coerceAtLeast(30f)
                targetRect.top < editorRect.top ->
                    (editorRect.top - targetRect.top + 14f).coerceAtLeast(30f)
                else -> if (lastKnownAbove) fineFingerDrag else -fineFingerDrag
            }.coerceIn(-editorRect.height * .45f, editorRect.height * .45f)
            Log.i(TAG, "phase=physical-scroll-$tag-$attempt target=$targetRect " +
                "viewport=$editorRect requiredPx=$requiredPx above=$lastKnownAbove drag=$drag")
            editor.performTouchInput {
                // The 4px SOUND gutter is outside child XY gesture owners.
                swipe(
                    start = Offset(4f, center.y - drag * .5f),
                    end = Offset(4f, center.y + drag * .5f),
                    durationMillis = 340,
                )
            }
            compose.waitForIdle()
        }
        val rect = target.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val viewport = editor.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$tag must be PHYSICALLY inside the real SOUND viewport " +
                "(not a semantics-only offscreen node): rect=$rect viewport=$viewport",
            rect.width >= minHeightDp * density - 1f &&
                rect.height >= minHeightDp * density - 1f &&
                rect.left >= viewport.left - 1f && rect.right <= viewport.right + 1f &&
                rect.top >= viewport.top - 1f && rect.bottom <= viewport.bottom + 1f,
        )
        return rect
    }

    /** Real AudioTrack-acknowledged frame meter, never synth.renderStereo(). */
    private fun acceptedMeter(
        output: AndroidAudioOutput,
        label: String,
    ): Pair<Float, Float> {
        awaitAccepted(output, 16_384L, "$label de-click settle")
        var brightness = 0f
        var rms = 0f
        repeat(6) { window ->
            awaitAccepted(output, 8_192L, "$label accepted PCM window $window")
            val meter = output.audioVisualSnapshot
            assertTrue("$label must produce non-silent accepted PCM", meter.rms > .003f)
            assertTrue("$label RMS must be finite", meter.rms.isFinite())
            assertTrue("$label brightness must be a finite normalized PCM statistic",
                meter.brightness.isFinite() && meter.brightness in 0f..1f)
            brightness += meter.brightness
            rms += meter.rms
        }
        return (brightness / 6f) to (rms / 6f)
    }

    private fun awaitAccepted(
        output: AndroidAudioOutput,
        frames: Long,
        label: String,
    ) {
        val baseline = output.diagnostics.framesRendered
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (output.isRunning &&
            output.diagnostics.framesRendered - baseline < frames &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(20)
        }
        assertTrue("$label: foreground AudioTrack must remain running", output.isRunning)
        assertTrue("$label: accepted frames must advance by $frames",
            output.diagnostics.framesRendered - baseline >= frames)
    }

    private fun assertRealForegroundAndroidPixels() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // UiAutomation's accessibility connection occasionally returns null
        // immediately after the Compose rule has finished a physical gesture.
        // Wait for Android to publish a REAL active window owned by the synth;
        // never substitute a Compose capture or skip the foreground assertion.
        var focused = automation.rootInActiveWindow
        for (attempt in 0 until 30) {
            if (focused?.packageName?.toString() == compose.activity.packageName) break
            SystemClock.sleep(120)
            focused = automation.rootInActiveWindow
        }
        assertEquals(
            "Synth must own Android window focus while fingers sound-design",
            compose.activity.packageName, focused?.packageName?.toString(),
        )
        val frame = checkNotNull(automation.takeScreenshot()) {
            "Actual Android framebuffer is required (no synthetic Compose mock)"
        }
        try {
            assertTrue("Real Android pixels must have nonzero dimensions",
                frame.width >= 600 && frame.height >= 1000)
            var chromatic = 0
            for (y in 0 until frame.height step 6) {
                for (x in 0 until frame.width step 6) {
                    val pixel = frame.getPixel(x, y)
                    val red = android.graphics.Color.red(pixel)
                    val green = android.graphics.Color.green(pixel)
                    val blue = android.graphics.Color.blue(pixel)
                    if (maxOf(red, green, blue) >= 110 &&
                        maxOf(red, green, blue) - minOf(red, green, blue) >= 35) {
                        chromatic++
                    }
                }
            }
            assertTrue("Foreground synth controls must be visibly colorful, not blank",
                chromatic >= 70)
            Log.i(TAG, "phase=physical-foreground-screenshot-${frame.width}x${frame.height}" +
                "-chromatic-$chromatic")
        } finally {
            frame.recycle()
        }
    }

    @Test fun realFingerWhiteToBrownNoiseChangesAcceptedLiveAudioTrackPcm() {
        Log.i(TAG, "phase=start-real-main-activity")
        val synth = activitySynth()
        val output = liveOutput()
        assertSame("MainActivity must use this EXACT audio worker's synth",
            synth, outputSynth(output))
        assertEquals(SAMPLE_RATE, synth.sampleRate)
        compose.waitUntil(10_000L) { output.isRunning }
        assertTrue("Real foreground AudioTrack must be open and running", output.isRunning)

        // Stable musical fixture: only SOUND-DESIGN CONTROLS are touched below.
        // The automation never calls setOscillatorSlot(), the slot bridge, or
        // renderStereo(), and cannot fake the AudioTrack-acknowledged meter.
        synth.setParameter(ParameterId.WAVE, 0f)
        synth.setParameter(ParameterId.MORPH, 1f)
        synth.setParameter(ParameterId.CUTOFF, 1f)
        synth.setParameter(ParameterId.RESONANCE, 0f)
        synth.setParameter(ParameterId.ATTACK, 0f)
        synth.setParameter(ParameterId.DECAY, 0f)
        synth.setParameter(ParameterId.SUSTAIN, 1f)
        synth.setParameter(ParameterId.RELEASE, 0f)
        synth.setParameter(ParameterId.MOVEMENT, 0f)
        synth.setParameter(ParameterId.LFO_DEPTH, 0f)
        synth.setParameter(ParameterId.FX_AMOUNT, 0f)
        synth.setParameter(ParameterId.ENGINE, 0f)

        // The portrait SOUND workbench intentionally keeps the oscillator
        // editor collapsed until the real ADVANCED disclosure is touched.
        // Opening it through the same visible Activity is mandatory; otherwise
        // oscillatorEnable does not exist at all in the Compose semantics tree.
        revealByFinger("referenceSoundAdvancedToggle", minHeightDp = 48f)
        compose.onNodeWithTag("referenceSoundAdvancedToggle")
            .assertIsDisplayed().performTouchInput { click(center) }
        compose.waitForIdle()
        Log.i(TAG, "phase=real-finger-opened-oscillator-editor")

        // The actual visual rack begins empty. Activate OSC1 by real click.
        revealByFinger("oscillatorEnable", minHeightDp = 44f)
        compose.onNodeWithTag("oscillatorEnable")
            .assertIsDisplayed().performTouchInput { click(center) }
        compose.waitForIdle()
        assertTrue("Physical ADD OSCILLATOR must reach the SAME real synth",
            synth.oscillatorSlot(0).enabled)
        Log.i(TAG, "phase=real-finger-enabled-osc1")

        // Make noise modulation dominant using a real luminous 52/70dp level
        // rail touch; no test callback or reflection can change this slot.
        revealByFinger("luminousLevelControl", minHeightDp = 48f)
        compose.onNodeWithTag("luminousLevelControl").performTouchInput {
            click(Offset((center.x * 2f) * .95f, center.y))
        }
        compose.waitForIdle()
        assertTrue("Physical luminous level finger must update real oscillator",
            synth.oscillatorSlot(0).level >= .87f)
        Log.i(TAG, "phase=real-finger-set-osc-level")

        assertTrue("Held note must enter the real AudioTrack worker",
            output.noteOn(SOURCE_ID, channel = 0, note = 69, velocity = 127))
        try {
            val tonal = acceptedMeter(output, "physically configured clean sine")
            assertTrue("Clean sine from held note must be audible", tonal.second > .003f)

            // First genuine noise Canvas finger: X~0 selects WHITE and Y~0
            // selects high noise mix. The host publishes all V6 fields.
            revealByFinger("oscillatorNoiseCanvas", minHeightDp = 108f)
            compose.onNodeWithTag("oscillatorNoiseCanvas").performTouchInput {
                click(Offset((center.x * 2f) * .02f, (center.y * 2f) * .04f))
            }
            compose.waitForIdle()
            val whiteSlot = synth.oscillatorSlot(0)
            assertTrue("WHITE finger must reach real V6 noise level",
                whiteSlot.noiseLevel >= .85f)
            assertTrue("WHITE finger must reach real V6 noise color",
                whiteSlot.noiseColor <= .10f)
            val white = acceptedMeter(output, "finger white noise")
            Log.i(TAG, "phase=real-white-accepted-pcm")

            // Second genuine finger on SAME live canvas, same held note,
            // changing the RNG coloration continuously toward BROWN.
            compose.onNodeWithTag("oscillatorNoiseCanvas").assertIsDisplayed()
                .performTouchInput {
                    click(Offset((center.x * 2f) * .98f, (center.y * 2f) * .04f))
                }
            compose.waitForIdle()
            val brownSlot = synth.oscillatorSlot(0)
            assertTrue("BROWN finger must change real V6 color",
                brownSlot.noiseColor >= .90f)
            assertTrue("BROWN finger must keep real V6 noise mix audible",
                brownSlot.noiseLevel >= .85f)
            val brown = acceptedMeter(output, "finger brown noise")
            Log.i(TAG, "phase=real-brown-accepted-pcm")

            assertTrue("Finger white noise must increase accepted PCM brightness: " +
                "tone=${tonal.first} white=${white.first}",
                white.first > tonal.first + .035f)
            assertTrue("Finger brown coloration must reduce accepted PCM brightness: " +
                "white=${white.first} brown=${brown.first}",
                white.first > brown.first + .025f)
            assertTrue("Both noise colors must still produce actual sound",
                white.second > .003f && brown.second > .003f)
            assertTrue("Real AudioTrack must have no fatal error", output.fatalErrorMessage == null)
            // SOUND intentionally has the twelve-strip liquid-glass keybed,
            // not PLAY/FX's legacy OrbitalKeyboard description. Require the
            // actual SOUND performance surface to remain physically pinned
            // below the scrolling oscillator editor while audio is held.
            val keybed = compose.onNodeWithTag("referencePerformanceKeybed")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val editor = compose.onNodeWithTag("soundEditorScroll")
                .fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("SOUND piano must remain anchored below the scrolling rack",
                keybed.top >= editor.bottom - 1f &&
                    keybed.height >= 96f * density - 1f)
            assertRealForegroundAndroidPixels()
            Log.i(TAG, "phase=physical-finger-to-audiotrack-success")
        } finally {
            // The Activity owns its AudioTrack; do not stop or replace it.
            output.allNotesOff(SOURCE_ID)
        }
    }
}
