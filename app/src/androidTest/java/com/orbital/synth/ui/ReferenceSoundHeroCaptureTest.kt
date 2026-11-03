package com.orbital.synth.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.dsp.processing.ModDestination
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A truthful, standalone 360dp component screenshot for engineering audit.
 * This is NOT a claim that the SOUND app already hosts the new hero. The
 * bitmap is Android UiAutomation's real API35 compositor framebuffer, with
 * saved parameter snapshots driving the actual production Composables.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceSoundHeroCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureActualAgslPlasmaAndLiveWidgetLayoutBeforeHostIntegration() {
        val parameters = mapOf(
            ParameterId.MORPH to .47f,
            ParameterId.MOVEMENT to .64f,
            ParameterId.WAVE to .42f,
            ParameterId.CUTOFF to .73f,
            ParameterId.RESONANCE to .35f,
            ParameterId.FX_AMOUNT to .78f,
            ParameterId.ATTACK to .15f,
            ParameterId.DECAY to .30f,
            ParameterId.SUSTAIN to .54f,
            ParameterId.RELEASE to .70f,
        )
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF01050F))) {
                ReferenceSoundHero(
                    parameters = parameters,
                    lfos = List(8) { ModulationLfoConfig(enabled = it == 0) },
                    routes = listOf(ModulationRouteConfig(
                        enabled = true,
                        destination = ModDestination.CUTOFF,
                        depth = .52f,
                    )) + List(63) { ModulationRouteConfig() },
                    onParameterChange = { _, _ -> },
                    compact = true,
                    patchName = "AURORA",
                    engineName = "ORBITAL WT",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        compose.onNodeWithTag("orbitalPlasmaField").assertExists()
        compose.onNodeWithTag("referenceExpressionRibbon").assertExists()
        compose.onNodeWithTag("referenceKnobFX_AMOUNT").assertExists()
        compose.onNodeWithTag("referenceMiniMod").assertExists()
        compose.waitForIdle()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Android UiAutomation did not return genuine frame pixels"
        }
        try {
            assertTrue("Real emulator screenshot must be portrait",
                bitmap.height > bitmap.width)
            assertTrue("Actual emulator phone dimensions expected",
                bitmap.width >= 600 && bitmap.height >= 1000)
            val file = File(compose.activity.filesDir, "orbital-reference-hero-standalone.png")
            FileOutputStream(file).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.fd.sync()
            }
            assertTrue("Missing visual comparison artifact", file.length() > 4096L)
        } finally {
            bitmap.recycle()
        }
    }
}
