package com.orbital.synth.ui

import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.MainActivity
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real API35 SurfaceFlinger screenshots plus independent semantic bounds.
 * The second capture is a true 360dp-wide Compose fixture with fontScale 1.3,
 * NOT a dishonest claim that the whole emulator window was resized to 360dp.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceSoundLegibilityScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

    private fun fixtureSentinelPixels(screenshot: Bitmap): Int {
        var count = 0
        val row = IntArray(screenshot.width)
        for (y in 0 until screenshot.height step 4) {
            screenshot.getPixels(row, 0, screenshot.width, 0, y, screenshot.width, 1)
            for (x in 0 until screenshot.width step 4) {
                val pixel = row[x]
                if (PixelColor.red(pixel) > 226 && PixelColor.green(pixel) < 45 &&
                    PixelColor.blue(pixel) > 175) count++
            }
        }
        return count
    }

    private fun takeRealScreenshot(
        fileName: String,
        requireFixtureSentinel: Boolean = false,
    ): Bitmap {
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 8_000) { compose.activity.hasWindowFocus() }
        // A Compose semantics frame can precede SurfaceFlinger's actual
        // committed frame, especially on the long-running GENERAL shard.
        // Never accept an old launcher/host frame: retry real OS screenshots
        // until the separately composed magenta fixture marker is *visible*.
        var strongestSentinel = 0
        repeat(10) { attempt ->
            Thread.sleep(if (attempt == 0) 220L else 260L)
            assertTrue("Only an actual focused host can prove SurfaceFlinger pixels",
                compose.activity.hasWindowFocus())
            val image = InstrumentationRegistry.getInstrumentation()
                .uiAutomation.takeScreenshot() ?: return@repeat
            val count = if (requireFixtureSentinel) fixtureSentinelPixels(image) else 0
            strongestSentinel = maxOf(strongestSentinel, count)
            if (requireFixtureSentinel && count < 30) {
                image.recycle()
                return@repeat
            }
            val out = File(compose.activity.getExternalFilesDir(null), fileName)
            FileOutputStream(out).use { stream ->
                assertTrue("Could not write genuine screenshot", image.compress(
                    Bitmap.CompressFormat.PNG, 100, stream,
                ))
            }
            println("LEGIBILITY_REAL_SCREENSHOT name=$fileName pixels=${image.width}x${image.height} path=${out.absolutePath} sentinel=$count")
            return image
        }
        throw AssertionError(
            "Real focused fixture never appeared in ten SurfaceFlinger frames; " +
                "strongest magenta sentinel=$strongestSentinel",
        )
    }

    @Test fun native411dpHostedSoundStillFitsAllFourTilesAndRealTouchControls() {
        compose.onNodeWithTag("referenceSoundHero").assertExists()
        val minis = listOf("referenceMiniEnv", "referenceMiniLfo",
            "referenceMiniExpr", "referenceMiniMod")
        val advanced = bounds("referenceSoundAdvancedToggle")
        val keybed = bounds("referencePerformanceKeybed")
        for (name in minis) {
            compose.onNodeWithTag(name).assertIsDisplayed()
            val mini = bounds(name)
            assertTrue("$name must be fully ABOVE advanced, not beneath keys: $mini",
                mini.bottom <= advanced.top + 2f)
            assertTrue("All four tiles must fit within visible portrait", mini.top >= 0f)
        }
        assertTrue("Advanced row must remain anchored above the keyboard",
            advanced.bottom <= keybed.top + 2f)
        val density = compose.activity.resources.displayMetrics.density
        for (tag in listOf("referenceHeroPatchName", "referenceHeroPreviousPatch",
                "referenceHeroNextPatch", "referenceHeroEngineName",
                "referenceKnobWAVE", "referenceKnobCUTOFF",
                "referenceKnobRESONANCE", "referenceKnobMOVEMENT",
                "referenceKnobFX_AMOUNT")) {
            val b = bounds(tag)
            assertTrue("$tag must retain a 48dp action width, got ${b.width / density}dp",
                b.width + .5f >= density * 48f)
            assertTrue("$tag must retain a 48dp action height, got ${b.height / density}dp",
                b.height + .5f >= density * 48f)
        }
        // Do not guess the factory preset cutoff; require an honest physical-unit label.
        compose.onNodeWithTag("referenceKnobCUTOFFValue")
            .assertTextContains("Hz", substring = true)
        val screenshot = takeRealScreenshot("orbital-legibility-411-native.png")
        assertTrue("Real full portrait framebuffer required",
            screenshot.width > 900 && screenshot.height > 1800)
        screenshot.recycle()
    }

    @Test fun constrained360dpFont130ShowsTruthfulUnitsAndAllFourMiniPanels() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val hostDensity = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides
                    Density(hostDensity.density, fontScale = 1.3f)) {
                    MaterialTheme {
                        Box(
                            Modifier.fillMaxSize()
                                .background(Color(0xFF01050F)),
                        ) {
                            Box(Modifier.width(360.dp).align(Alignment.TopStart)) {
                                ReferenceSoundHero(
                                    parameters = mapOf(
                                        ParameterId.WAVE to .42f,
                                        ParameterId.MORPH to .5f,
                                        ParameterId.MOVEMENT to .74f,
                                        ParameterId.CUTOFF to .63f,
                                        ParameterId.RESONANCE to .2f,
                                        ParameterId.FX_AMOUNT to .71f,
                                    ),
                                    lfos = List(8) { ModulationLfoConfig() },
                                    routes = List(64) { ModulationRouteConfig() },
                                    onParameterChange = { _, _ -> },
                                    patchName = "AURORA",
                                    engineName = "ORBITAL WT",
                                    onPreviousPatch = { },
                                    onNextPatch = { },
                                    compact = true,
                                    synthSampleRate = 48_000,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            // True compositor sentinel: if a launcher/old host
                            // screenshot is captured, the magenta marker is absent.
                            Box(
                                Modifier.align(Alignment.BottomEnd)
                                    .padding(end = 12.dp, bottom = 36.dp)
                                    .size(22.dp)
                                    .background(Color(0xFFF708DE))
                                    .testTag("legibilityPhysicalScreenshotSentinel"),
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("referenceSoundHero").assertIsDisplayed()
        compose.onNodeWithTag("referenceKnobCUTOFFValue").assertTextEquals("2.0 kHz")
        compose.onNodeWithTag("referenceKnobWAVEValue").assertTextEquals("42%")
        compose.onNodeWithTag("referenceKnobRESONANCELabel").assertTextEquals("RESO")
        compose.onNodeWithTag("referenceKnobFX_AMOUNTLabel").assertTextEquals("FX MIX")
        for (tag in listOf("referenceMiniEnv", "referenceMiniLfo",
                "referenceMiniExpr", "referenceMiniMod")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        val density = compose.activity.resources.displayMetrics.density
        for (tag in listOf("referenceKnobWAVE", "referenceKnobCUTOFF",
                "referenceKnobRESONANCE", "referenceKnobMOVEMENT",
                "referenceKnobFX_AMOUNT", "referenceHeroPreviousPatch",
                "referenceHeroNextPatch")) {
            val b = bounds(tag)
            assertTrue("$tag lost its 48dp gesture surface at fontScale 1.3",
                b.height >= 48f * density - .5f &&
                    b.width >= 48f * density - .5f)
        }
        val screenshot = takeRealScreenshot(
            "orbital-legibility-360-font130.png", requireFixtureSentinel = true,
        )
        assertTrue("UIAutomation must capture real phone framebuffer",
            screenshot.width > 900 && screenshot.height > 1800)
        // Count the separately composed magenta sentinel, proving it is
        // our live fixture rather than an old or external launcher screen.
        val sentinelPixels = fixtureSentinelPixels(screenshot)
        assertTrue("Genuine fixture sentinel missing from real screen: $sentinelPixels",
            sentinelPixels >= 30)
        screenshot.recycle()
    }
}
