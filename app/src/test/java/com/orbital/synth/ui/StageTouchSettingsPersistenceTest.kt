package com.orbital.synth.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StageTouchSettingsPersistenceTest {
    @Test fun completeV1RoundTripsEveryEditableStageField() {
        val original = StageTouchSettings(
            tonic = 11, baseOctave = 3, octaveRange = 4,
            scale = TouchScale.BLUES, pressureSensitivity = .72f,
            pitchAxis = TouchPitchAxis.VERTICAL, glissando = false,
        )
        val wire = StageTouchSettingsCodec.encode(original)
        assertTrue(wire.startsWith(StageTouchSettingsCodec.V1_HEADER + "\n"))
        assertFalse(wire.contains("null"))
        val decoded = StageTouchSettingsCodec.decode(wire)
        assertEquals(original, decoded.settings)
        assertFalse(decoded.legacy)
        assertEquals(wire, StageTouchSettingsCodec.encode(decoded.settings))
    }

    @Test fun allSupportedScalesAndAxesHaveStableRoundTrips() {
        for (scale in TouchScale.entries) {
            for (axis in TouchPitchAxis.entries) {
                val original = StageTouchSettings(scale = scale, pitchAxis = axis)
                assertEquals(original, StageTouchSettingsCodec.decode(
                    StageTouchSettingsCodec.encode(original)
                ).settings)
            }
        }
    }

    @Test fun legacyV0AliasesImportWithoutLosingPerformanceChoices() {
        val legacy = """
            ORBITAL-STAGE-TOUCH-V0
            tonic=6
            octave=2
            span=3
            scale=MINOR_PENTATONIC
            pressure=0.8
            axis=Y
            hold=true
        """.trimIndent()
        val decoded = StageTouchSettingsCodec.decode(legacy)
        assertTrue(decoded.legacy)
        assertEquals(StageTouchSettings(
            tonic = 6, baseOctave = 2, octaveRange = 3,
            scale = TouchScale.MINOR_PENTATONIC, pressureSensitivity = .8f,
            pitchAxis = TouchPitchAxis.VERTICAL, glissando = false,
        ), decoded.settings)
        // Migrating writes V1 while preserving the actual sound-design mapping.
        assertFalse(StageTouchSettingsCodec.decode(
            StageTouchSettingsCodec.encode(decoded.settings)
        ).legacy)
    }

    @Test fun bomAndWindowsLineEndingsImportWithoutImplicitlyRewritingUserFile() = runBlocking {
        withScratch { path ->
            val legacy = "\uFEFF# saved on a desktop\r\nORBITAL-STAGE-TOUCH-V0\r\n" +
                "tonic=10\r\nspan=2\r\naxis=X\r\nhold=false\r\n"
            writeUtf8(path, legacy)
            val repo = StageTouchSettingsRepository(path, Dispatchers.Unconfined)

            val loaded = repo.load()
            assertEquals(StageTouchLoadSource.LEGACY, loaded.source)
            assertEquals(StageTouchSettings(
                tonic = 10, octaveRange = 2,
                pitchAxis = TouchPitchAxis.HORIZONTAL, glissando = true,
            ), loaded.settings)
            assertEquals(legacy, readUtf8(path)) // Loading never modifies a legacy file.

            assertEquals(StageTouchSettingsSave.Saved, repo.save(loaded.settings))
            assertEquals(StageTouchSettingsLoad(
                loaded.settings, StageTouchLoadSource.SAVED,
            ), repo.load())
            assertTrue(readUtf8(path).startsWith(StageTouchSettingsCodec.V1_HEADER + "\n"))
        }
    }

    @Test fun unversionedEarlyKeyValueFilesRestoreDefaultsForMissingFields() {
        val decoded = StageTouchSettingsCodec.decode(
            "scale=DORIAN\naxis=X\nglide=false\noctave=5\n"
        )
        assertTrue(decoded.legacy)
        assertEquals(TouchScale.DORIAN, decoded.settings.scale)
        assertEquals(TouchPitchAxis.HORIZONTAL, decoded.settings.pitchAxis)
        assertEquals(5, decoded.settings.baseOctave)
        assertFalse(decoded.settings.glissando)
        assertEquals(0, decoded.settings.tonic)
        assertEquals(.5f, decoded.settings.pressureSensitivity, 0f)
    }

    @Test fun unknownVersionAndUnsupportedFieldsCannotBeMisinterpreted() {
        assertFails {
            StageTouchSettingsCodec.decode("ORBITAL-STAGE-TOUCH-V999\ntonic=5\n")
        }
        assertFails {
            StageTouchSettingsCodec.decode(StageTouchSettingsCodec.encode(
                StageTouchSettings()
            ) + "unknownFutureSynthesisEngine=true\n")
        }
    }

    @Test fun duplicatesMissingFieldsAndMalformedBooleansFailClosed() {
        val valid = StageTouchSettingsCodec.encode(StageTouchSettings())
        assertFails { StageTouchSettingsCodec.decode(valid + "tonic=4\n") }
        assertFails { StageTouchSettingsCodec.decode(valid.replace("glissando=true\n", "")) }
        assertFails { StageTouchSettingsCodec.decode(valid.replace("glissando=true", "glissando=maybe")) }
        assertFails { StageTouchSettingsCodec.decode("scale=BLUES\nhold=true\nglide=false") }
    }

    @Test fun invalidNumbersAndOversizedPayloadsAreRejected() {
        assertFails { StageTouchSettingsCodec.encode(StageTouchSettings(tonic = 12)) }
        assertFails { StageTouchSettingsCodec.encode(StageTouchSettings(baseOctave = -1)) }
        assertFails { StageTouchSettingsCodec.encode(StageTouchSettings(octaveRange = 0)) }
        assertFails { StageTouchSettingsCodec.encode(StageTouchSettings(pressureSensitivity = Float.NaN)) }
        val valid = StageTouchSettingsCodec.encode(StageTouchSettings())
        for (value in listOf("NaN", "Infinity", "1.5", "-.1")) {
            assertFails {
                StageTouchSettingsCodec.decode(
                    valid.replace("pressureSensitivity=0.5", "pressureSensitivity=" + value)
                )
            }
        }
        assertFails { StageTouchSettingsCodec.decode("a".repeat(StageTouchSettingsCodec.MAX_BYTES + 1)) }
    }

    @Test fun firstRunDefaultsThenDurablyRestoresSavedSettings() = runBlocking {
        withScratch { path ->
            val repo = StageTouchSettingsRepository(path, Dispatchers.Unconfined)
            val initially = repo.load()
            assertEquals(StageTouchLoadSource.DEFAULT_MISSING, initially.source)
            assertEquals(StageTouchSettings(), initially.settings)
            val saved = StageTouchSettings(
                tonic = 9, baseOctave = 2, octaveRange = 1,
                scale = TouchScale.WHOLE_TONE, pressureSensitivity = .25f,
                pitchAxis = TouchPitchAxis.VERTICAL, glissando = false,
            )
            assertEquals(StageTouchSettingsSave.Saved, repo.save(saved))
            assertEquals(StageTouchSettingsLoad(saved, StageTouchLoadSource.SAVED), repo.load())
            assertTrue(readUtf8(path).startsWith(StageTouchSettingsCodec.V1_HEADER))
            assertEquals(1, Files.list(path.parent).use { stream ->
                stream.filter { it.fileName.toString() == path.fileName.toString() }.count().toInt()
            })
        }
    }

    @Test fun corruptedOrUnknownFilesArePreservedUntilExplicitReset() = runBlocking {
        withScratch { path ->
            val repo = StageTouchSettingsRepository(path, Dispatchers.Unconfined)
            val corrupt = "ORBITAL-STAGE-TOUCH-V1\nscale=BAD\n"
            writeUtf8(path, corrupt)
            val damaged = repo.load()
            assertEquals(StageTouchLoadSource.DEFAULT_CORRUPT, damaged.source)
            assertEquals(StageTouchSettings(), damaged.settings)
            assertEquals(StageTouchSettingsSave.UnreadableExisting(
                StageTouchLoadSource.DEFAULT_CORRUPT,
            ), repo.save(StageTouchSettings(tonic = 8)))
            assertEquals(corrupt, readUtf8(path))
            assertEquals(StageTouchSettingsSave.Saved, repo.save(
                StageTouchSettings(tonic = 8), replaceUnreadable = true,
            ))
            assertEquals(8, repo.load().settings.tonic)

            val unknown = "ORBITAL-STAGE-TOUCH-V4\nmagic=prototype\n"
            writeUtf8(path, unknown)
            assertEquals(StageTouchLoadSource.DEFAULT_UNSUPPORTED, repo.load().source)
            assertEquals(StageTouchSettingsSave.UnreadableExisting(
                StageTouchLoadSource.DEFAULT_UNSUPPORTED,
            ), repo.save(StageTouchSettings(tonic = 5)))
            assertEquals(unknown, readUtf8(path))
        }
    }

    @Test fun invalidUtf8AndOversizeFilesDefaultWithoutThrowing() = runBlocking {
        withScratch { path ->
            val repo = StageTouchSettingsRepository(path, Dispatchers.Unconfined)
            Files.write(path, byteArrayOf(0xc3.toByte(), 0x28))
            assertEquals(StageTouchLoadSource.DEFAULT_CORRUPT, repo.load().source)
            Files.write(path, ByteArray(StageTouchSettingsCodec.MAX_BYTES + 5) { 65 })
            assertEquals(StageTouchLoadSource.DEFAULT_CORRUPT, repo.load().source)
        }
    }

    @Test fun failedSaveNeverReplacesAValidExistingFile() = runBlocking {
        withScratch { path ->
            val repo = StageTouchSettingsRepository(path, Dispatchers.Unconfined)
            val first = StageTouchSettings(tonic = 3)
            assertEquals(StageTouchSettingsSave.Saved, repo.save(first))
            val originalBytes = Files.readAllBytes(path)
            val invalid = repo.save(StageTouchSettings(pressureSensitivity = Float.POSITIVE_INFINITY))
            assertTrue(invalid is StageTouchSettingsSave.Invalid)
            assertTrue(originalBytes.contentEquals(Files.readAllBytes(path)))
            assertEquals(StageTouchSettingsLoad(first, StageTouchLoadSource.SAVED), repo.load())
        }
    }

    @Test fun legacyFileIsUpgradedOnlyAfterExplicitUserSave() = runBlocking {
        withScratch { path ->
            val legacy = "ORBITAL-STAGE-TOUCH-V0\noctave=3\nscale=MAJOR\naxis=Y\n"
            writeUtf8(path, legacy)
            val repo = StageTouchSettingsRepository(path, Dispatchers.Unconfined)
            val loaded = repo.load()
            assertEquals(StageTouchLoadSource.LEGACY, loaded.source)
            assertEquals(legacy, readUtf8(path)) // A read never mutates user data.
            assertEquals(StageTouchSettingsSave.Saved, repo.save(loaded.settings))
            assertEquals(StageTouchLoadSource.SAVED, repo.load().source)
            assertEquals(loaded.settings, repo.load().settings)
        }
    }

    // API-26 compatible java.nio I/O in Android-targeted JVM unit tests.
    private fun readUtf8(path: Path): String =
        String(Files.readAllBytes(path), Charsets.UTF_8)

    private fun writeUtf8(path: Path, value: String) {
        Files.write(path, value.toByteArray(Charsets.UTF_8))
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected malformed stage settings to be rejected")
        } catch (_: IllegalArgumentException) {
            // Fail-closed; repository maps this to safe defaults without deleting user data.
        }
    }

    private suspend fun withScratch(block: suspend (Path) -> Unit) {
        val dir = Files.createTempDirectory("orbital-stage-settings-test-")
        val path = dir.resolve("stage.settings")
        try {
            block(path)
        } finally {
            Files.list(dir).use { stream -> stream.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }
}
