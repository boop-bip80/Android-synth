package com.orbital.synth.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTouchSettingsHostStoreTest {

    @Test fun rapidSliderEditsCoalesceWithoutLosingFinalSevenFieldSnapshot() = runBlocking {
        withScratch { path ->
            withStore(path) { host, repo ->
                repeat(500) { index ->
                    host.requestSave(StageTouchSettings(
                        tonic = index % 12,
                        pressureSensitivity = (index % 100) / 100f,
                    ))
                }
                val final = StageTouchSettings(
                    tonic = 11,
                    baseOctave = 2,
                    octaveRange = 4,
                    scale = TouchScale.BLUES,
                    pressureSensitivity = .83f,
                    pitchAxis = TouchPitchAxis.VERTICAL,
                    glissando = false,
                )
                val revision = host.requestSave(final)
                withTimeout(15_000L) { host.awaitPendingWrites() }
                assertTrue(host.completedRevision() >= revision)
                assertEquals(StageTouchSettingsLoad(
                    final, StageTouchLoadSource.SAVED,
                ), repo.load())
                assertEquals(StageTouchSettingsLoad(
                    final, StageTouchLoadSource.SAVED,
                ), host.loadAfterPendingWrites())
                assertTrue(Files.readString(path).startsWith(StageTouchSettingsCodec.V1_HEADER))
            }
        }
    }

    @Test fun activityEquivalentRestoreWaitsForPreviousQueuedWrite() = runBlocking {
        withScratch { path ->
            withStore(path) { host, repo ->
                val old = StageTouchSettings(tonic = 2, scale = TouchScale.MAJOR)
                assertEquals(StageTouchSettingsSave.Saved, repo.save(old))
                val new = StageTouchSettings(
                    tonic = 9, baseOctave = 5,
                    scale = TouchScale.DORIAN, glissando = false,
                )
                host.requestSave(new)
                // This is what the new Activity calls after the previous one
                // enqueued a user edit, even if that async write has not begun.
                val restored = withTimeout(15_000L) { host.loadAfterPendingWrites() }
                assertEquals(StageTouchSettingsLoad(
                    new, StageTouchLoadSource.SAVED,
                ), restored)
            }
        }
    }

    @Test fun futureFileVersionSurvivesUserEditsWithoutImplicitDowngrade() = runBlocking {
        withScratch { path ->
            val future = "ORBITAL-STAGE-TOUCH-V932\nfutureMode=unreadable\n"
            Files.writeString(path, future)
            withStore(path) { host, _ ->
                val request = host.requestSave(StageTouchSettings(tonic = 7))
                withTimeout(15_000L) { host.awaitPendingWrites() }
                assertEquals(request, host.notices.value?.revision)
                assertEquals(
                    StageTouchSettingsSave.UnreadableExisting(
                        StageTouchLoadSource.DEFAULT_UNSUPPORTED,
                    ),
                    host.notices.value?.result,
                )
                assertEquals(future, Files.readString(path))
                assertEquals(
                    StageTouchLoadSource.DEFAULT_UNSUPPORTED,
                    host.loadAfterPendingWrites().source,
                )
            }
        }
    }

    @Test fun corruptFileIsPreservedEvenAfterBurstOfChanges() = runBlocking {
        withScratch { path ->
            val invalid = "ORBITAL-STAGE-TOUCH-V1\nscale=NOT_A_SCALE\n"
            Files.writeString(path, invalid)
            withStore(path) { host, _ ->
                repeat(80) { index ->
                    host.requestSave(StageTouchSettings(tonic = index % 12))
                }
                withTimeout(15_000L) { host.awaitPendingWrites() }
                assertEquals(
                    StageTouchSettingsSave.UnreadableExisting(
                        StageTouchLoadSource.DEFAULT_CORRUPT,
                    ),
                    host.notices.value?.result,
                )
                assertEquals(invalid, Files.readString(path))
                assertEquals(
                    StageTouchLoadSource.DEFAULT_CORRUPT,
                    host.loadAfterPendingWrites().source,
                )
            }
        }
    }

    @Test fun readingLegacyFileDoesNotMigrateUntilUserMakesEdit() = runBlocking {
        withScratch { path ->
            val legacy = "ORBITAL-STAGE-TOUCH-V0\nscale=MAJOR\naxis=Y\n"
            Files.writeString(path, legacy)
            withStore(path) { host, _ ->
                val restored = host.loadAfterPendingWrites()
                assertEquals(StageTouchLoadSource.LEGACY, restored.source)
                assertEquals(legacy, Files.readString(path))
                host.requestSave(restored.settings.copy(tonic = 10))
                withTimeout(15_000L) { host.awaitPendingWrites() }
                assertEquals(StageTouchSettingsSave.Saved, host.notices.value?.result)
                assertEquals(
                    StageTouchLoadSource.SAVED,
                    host.loadAfterPendingWrites().source,
                )
                assertEquals(10, host.loadAfterPendingWrites().settings.tonic)
            }
        }
    }

    private suspend fun withStore(
        path: Path,
        block: suspend (StageTouchSettingsHostStore, StageTouchSettingsRepository) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = StageTouchSettingsRepository(path, Dispatchers.IO)
            block(StageTouchSettingsHostStore(repo, scope), repo)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun withScratch(block: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory("orbital-stage-host-store-test-")
        try {
            block(directory.resolve("touch.settings"))
        } finally {
            Files.list(directory).use { stream ->
                stream.forEach { Files.deleteIfExists(it) }
            }
            Files.deleteIfExists(directory)
        }
    }
}