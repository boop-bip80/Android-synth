package com.orbital.synth.ui

import android.content.Context
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class StageTouchLoadSource {
    SAVED,
    LEGACY,
    DEFAULT_MISSING,
    DEFAULT_CORRUPT,
    DEFAULT_UNSUPPORTED,
    DEFAULT_STORAGE_ERROR,
}

/**
 * [settings] is always safe to use. If the saved file could not be interpreted,
 * the original file remains untouched and [source] communicates why defaults
 * are in use, rather than pretending an invalid save succeeded.
 */
data class StageTouchSettingsLoad(
    val settings: StageTouchSettings,
    val source: StageTouchLoadSource,
)

sealed interface StageTouchSettingsSave {
    data object Saved : StageTouchSettingsSave
    data class Invalid(val reason: String) : StageTouchSettingsSave
    data class UnreadableExisting(val reason: StageTouchLoadSource) : StageTouchSettingsSave
    data class StorageError(val reason: String) : StageTouchSettingsSave
}

/**
 * App-private, atomic, UTF-8 persistence for the keyboardless stage only.
 *
 * API:
 *     val repo = StageTouchSettingsRepository(applicationContext)
 *     val loaded = repo.load()            // call from lifecycleScope.launch
 *     val saved = repo.save(newSettings)  // call from lifecycleScope.launch
 *
 * Operations are serialized, dispatched to Dispatchers.IO and never touch the
 * audio render worker. No permissions, database, or network are required.
 *
 * Unknown future versions and corrupted files are NOT silently deleted or
 * overwritten. To replace an unreadable file after an explicit user reset,
 * call save(defaults, replaceUnreadable = true).
 */
class StageTouchSettingsRepository internal constructor(
    private val file: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        context.applicationContext.filesDir.toPath().resolve("orbital-stage-touch.settings")
    )

    private val mutex = Mutex()

    suspend fun load(): StageTouchSettingsLoad = withContext(ioDispatcher) {
        mutex.withLock { readBlocking() }
    }

    suspend fun save(
        settings: StageTouchSettings,
        replaceUnreadable: Boolean = false,
    ): StageTouchSettingsSave = withContext(ioDispatcher) {
        mutex.withLock {
            val serialized = try {
                StageTouchSettingsCodec.encode(settings)
            } catch (_: IllegalArgumentException) {
                return@withLock StageTouchSettingsSave.Invalid(
                    "Stage settings are out of range; nothing was written."
                )
            }

            // Preserve files we cannot decode. An explicit reset may replace them.
            if (!replaceUnreadable) {
                val existing = readBlocking().source
                if (existing == StageTouchLoadSource.DEFAULT_CORRUPT ||
                    existing == StageTouchLoadSource.DEFAULT_UNSUPPORTED ||
                    existing == StageTouchLoadSource.DEFAULT_STORAGE_ERROR
                ) {
                    return@withLock StageTouchSettingsSave.UnreadableExisting(existing)
                }
            }

            val parent = file.parent
                ?: return@withLock StageTouchSettingsSave.StorageError("Invalid stage storage path")
            var temporary: Path? = null
            try {
                Files.createDirectories(parent)
                temporary = Files.createTempFile(parent, ".orbital-stage-", ".tmp")
                val bytes = serialized.toByteArray(StandardCharsets.UTF_8)
                FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
                ).use { channel ->
                    val input = ByteBuffer.wrap(bytes)
                    while (input.hasRemaining()) channel.write(input)
                    channel.force(true)
                }
                // Atomic replacement preserves the previous complete file if a
                // write is interrupted. A filesystem without atomic rename gets
                // a visible failure instead of an unsafe partial replacement.
                Files.move(
                    temporary, file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                temporary = null
                // Best effort: some Android filesystems forbid forcing dirs.
                runCatching {
                    FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
                }
                StageTouchSettingsSave.Saved
            } catch (_: IOException) {
                StageTouchSettingsSave.StorageError("Could not save stage settings.")
            } catch (_: SecurityException) {
                StageTouchSettingsSave.StorageError("Private stage settings access was denied.")
            } finally {
                temporary?.let { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun readBlocking(): StageTouchSettingsLoad {
        val defaults = StageTouchSettings()
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return StageTouchSettingsLoad(defaults, StageTouchLoadSource.DEFAULT_MISSING)
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return StageTouchSettingsLoad(defaults, StageTouchLoadSource.DEFAULT_STORAGE_ERROR)
            }
            if (Files.size(file) > StageTouchSettingsCodec.MAX_BYTES) {
                return StageTouchSettingsLoad(defaults, StageTouchLoadSource.DEFAULT_CORRUPT)
            }
            val bytes = Files.readAllBytes(file)
            if (bytes.size > StageTouchSettingsCodec.MAX_BYTES) {
                return StageTouchSettingsLoad(defaults, StageTouchLoadSource.DEFAULT_CORRUPT)
            }
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            val result = StageTouchSettingsCodec.decode(text)
            return StageTouchSettingsLoad(
                result.settings,
                if (result.legacy) StageTouchLoadSource.LEGACY
                else StageTouchLoadSource.SAVED,
            )
        } catch (_: StageTouchSettingsCodec.UnsupportedVersionException) {
            return StageTouchSettingsLoad(defaults, StageTouchLoadSource.DEFAULT_UNSUPPORTED)
        } catch (_: IllegalArgumentException) {
            return StageTouchSettingsLoad(defaults, StageTouchLoadSource.DEFAULT_CORRUPT)
        } catch (_: java.nio.charset.CharacterCodingException) {
            return StageTouchSettingsLoad(defaults, StageTouchLoadSource.DEFAULT_CORRUPT)
        } catch (_: NoSuchFileException) {
            return StageTouchSettingsLoad(defaults, StageTouchLoadSource.DEFAULT_MISSING)
        } catch (_: IOException) {
            return StageTouchSettingsLoad(defaults, StageTouchLoadSource.DEFAULT_STORAGE_ERROR)
        } catch (_: SecurityException) {
            return StageTouchSettingsLoad(defaults, StageTouchLoadSource.DEFAULT_STORAGE_ERROR)
        }
    }
}
