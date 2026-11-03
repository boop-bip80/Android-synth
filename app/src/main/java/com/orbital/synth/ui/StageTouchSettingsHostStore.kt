package com.orbital.synth.ui

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application-process owned write-through bridge for [StageTouchSettingsRepository].
 *
 * A single worker serializes user edits. MutableStateFlow conflates rapid slider
 * events while never cancelling an atomic save already in progress. Activity
 * recreation leaves the worker alive; the next Activity waits for the final
 * pending revision before reading disk and cannot restore stale settings.
 *
 * No user data leaves app-private storage. This object holds application context
 * only (indirectly through the repository), never Activity or Composable callbacks.
 */
internal class StageTouchSettingsHostStore internal constructor(
    private val repository: StageTouchSettingsRepository,
    scope: CoroutineScope,
) {
    data class SaveNotice(
        val revision: Long,
        val result: StageTouchSettingsSave,
    )

    private data class Request(val revision: Long, val settings: StageTouchSettings)

    private val lock = Any()
    private var lastIssuedRevision = 0L
    private val pending = MutableStateFlow<Request?>(null)
    private val completed = MutableStateFlow(0L)
    private val _notices = MutableStateFlow<SaveNotice?>(null)

    /** Latest completed write outcome for a lifecycle-scoped UI collector. */
    val notices: StateFlow<SaveNotice?> = _notices.asStateFlow()

    init {
        scope.launch {
            // StateFlow is conflated, but collect (NOT collectLatest) deliberately
            // never cancels a FileChannel write midway through an atomic save.
            pending.filterNotNull().collect { request ->
                val result = try {
                    repository.save(request.settings)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    StageTouchSettingsSave.StorageError("Could not save stage settings.")
                }
                _notices.value = SaveNotice(request.revision, result)
                completed.value = request.revision
            }
        }
    }

    /** Main-thread safe and non-blocking. Last value in a burst always wins. */
    fun requestSave(settings: StageTouchSettings): Long = synchronized(lock) {
        val revision = ++lastIssuedRevision
        pending.value = Request(revision, settings)
        revision
    }

    /** Suppress stale outcomes from Activity instances that are already gone. */
    fun completedRevision(): Long = completed.value

    /** Await the last requested edit, including a save started by a prior Activity. */
    suspend fun awaitPendingWrites() {
        val revision = synchronized(lock) { lastIssuedRevision }
        completed.first { it >= revision }
    }

    /**
     * A recreation cannot see an old file while the previous Activity still has
     * edits queued. If a new edit arrives during read, retry instead of returning
     * an obsolete result. The UI independently guards restore against fresh edits.
     */
    suspend fun loadAfterPendingWrites(): StageTouchSettingsLoad {
        while (true) {
            val revision = synchronized(lock) { lastIssuedRevision }
            completed.first { it >= revision }
            val loaded = try {
                repository.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                StageTouchSettingsLoad(
                    StageTouchSettings(),
                    StageTouchLoadSource.DEFAULT_STORAGE_ERROR,
                )
            }
            if (synchronized(lock) { lastIssuedRevision == revision }) return loaded
        }
    }

    companion object {
        @Volatile private var instance: StageTouchSettingsHostStore? = null

        fun forApplication(context: Context): StageTouchSettingsHostStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: StageTouchSettingsHostStore(
                    StageTouchSettingsRepository(context.applicationContext),
                    CoroutineScope(SupervisorJob() + Dispatchers.IO),
                ).also { instance = it }
            }
        }
    }
}