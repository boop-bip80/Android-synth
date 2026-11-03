package com.orbital.synth.ci

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.audio.AndroidAudioOutput
import com.orbital.synth.core.OrbitalSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android AudioTrack lifecycle check: the UI must observe a monotonic
 * note-owner invalidation revision even when a stop + restart happens between
 * two normal 32ms Compose polls. This does not claim a physical audio route or
 * fixed hardware latency in headless emulator.
 */
@RunWith(AndroidJUnit4::class)
class AudioTouchSessionEpochTest {
    @Test fun stopStartWorkerLifecycleInvalidatesStaleTouchOwnersAcrossSessions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = AndroidAudioOutput(context, OrbitalSynth())
        try {
            val firstSeen = output.performanceSessionEpoch
            assertEquals(0L, firstSeen)

            // A user can release the Activity without an active audio sink;
            // explicit stop is still an ownership panic and must invalidate.
            output.stop()
            val afterFirstStop = output.performanceSessionEpoch
            assertTrue("Stop must invalidate note leases even without a worker",
                afterFirstStop > firstSeen)

            val firstAccepted = output.start()
            val afterStartAttempt = output.performanceSessionEpoch
            if (firstAccepted && output.isRunning) {
                assertTrue("Actual new AudioTrack worker must own a new session",
                    afterStartAttempt > afterFirstStop)
            }

            output.stop()
            val afterSecondStop = output.performanceSessionEpoch
            assertTrue("Session revisions must survive fast stop and restart",
                afterSecondStop > afterStartAttempt)

            // The prior worker owns its own AudioTrack.release(). A fast
            // restart may be scheduled asynchronously; poll ONLY the revision
            // and running flag, never wait on the render/audio thread.
            val secondAccepted = output.start()
            val deadline = SystemClock.uptimeMillis() + 2_500L
            while (secondAccepted && !output.isRunning &&
                SystemClock.uptimeMillis() < deadline
            ) {
                Thread.sleep(20L)
            }
            if (output.isRunning) {
                assertTrue("Replacement audio session must be uniquely numbered",
                    output.performanceSessionEpoch > afterSecondStop)
            }
            val latest = output.performanceSessionEpoch
            output.stop()
            assertTrue("Last explicit panic must remain visible to stale UI poll",
                output.performanceSessionEpoch > latest)
            // A single observer reading only first + last never misses that
            // multiple ownership-invalidation transitions occurred.
            assertTrue(output.performanceSessionEpoch >= firstSeen + 3L)
        } finally {
            output.close()
        }
    }
}
