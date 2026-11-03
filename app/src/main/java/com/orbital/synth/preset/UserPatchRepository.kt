package com.orbital.synth.preset

import android.content.Context
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.preset.FactoryPresets
import com.orbital.synth.core.preset.PatchCodec
import com.orbital.synth.core.preset.PatchDirectoryStore
import com.orbital.synth.core.preset.SynthPatch
import com.orbital.synth.core.preset.loadPatch
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class PatchFailureKind {
    INVALID_NAME, INVALID_ID, INVALID_FORMAT, NOT_FOUND, STORAGE_ERROR, INVALID_DATA, ID_EXHAUSTED
}

sealed interface PatchOutcome<out T> {
    data class Success<T>(val value: T) : PatchOutcome<T>
    data class Failure(val kind: PatchFailureKind, val message: String) : PatchOutcome<Nothing>
}

data class SavedUserPatch(
    val patch: SynthPatch,
    /** True when a numeric suffix was added instead of overwriting an existing ID. */
    val renamedOnCollision: Boolean,
)

/**
 * Android-facing, private-files patch bank. All potentially blocking operations
 * are suspend functions explicitly dispatched to Dispatchers.IO. No storage
 * permissions or network are required.
 *
 * Callers are responsible for surfacing PatchOutcome.Failure, and for keeping
 * their UI state consistent when a patch is activated. The engine setter is
 * already thread-safe; no audio rendering occurs here.
 */
class UserPatchRepository internal constructor(
    private val store: PatchDirectoryStore,
    private val snapshotParameters: () -> Map<String, Float>,
    private val applyPatch: (SynthPatch) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val snapshotOscillators: () -> List<OscillatorSlot> = { emptyList() },
    private val snapshotModulationRoutes: () -> List<ModulationRouteConfig> = { emptyList() },
    private val snapshotModulationLfos: () -> List<ModulationLfoConfig> = { emptyList() },
    private val snapshotModulationTempo: () -> Float = { 120f },
    private val snapshotFxSlots: () -> List<FxModuleConfig> = { emptyList() },
) {
    constructor(context: Context, synth: OrbitalSynth) : this(
        PatchDirectoryStore(
            context.applicationContext.filesDir.toPath().resolve("orbital-user-patches")
        ),
        { ParameterId.entries.associate { it.name to synth.parameter(it) } },
        { synth.loadPatch(it) },
        snapshotOscillators = {
            List(synth.oscillatorSlotCount) { index -> synth.oscillatorSlot(index) }
        },
        snapshotModulationRoutes = {
            List(synth.modulationRouteCount) { index -> synth.modulationRoute(index) }
        },
        snapshotModulationLfos = {
            List(synth.modulationLfoCount) { index -> synth.modulationLfo(index) }
        },
        snapshotModulationTempo = { synth.modulationTempoBpm },
        snapshotFxSlots = {
            List(synth.fxSlotCount) { index -> synth.fxSlot(index) }
        },
    )

    private val mutex = Mutex()

    /** Factory patch metadata is an in-memory immutable lookup. */
    fun listFactory(): List<SynthPatch> = FactoryPresets.ALL

    suspend fun listUser(): PatchOutcome<List<SynthPatch>> = onIo {
        safe(PatchFailureKind.INVALID_FORMAT) {
            PatchOutcome.Success(store.list())
        }
    }

    suspend fun listAll(): PatchOutcome<List<SynthPatch>> = onIo {
        safe(PatchFailureKind.INVALID_FORMAT) {
            PatchOutcome.Success(FactoryPresets.ALL + store.list())
        }
    }

    /**
     * Capture current atomic engine controls and persist under a safe user-* ID.
     * Saving the same name again creates "-2", "-3", etc.; it never destroys an
     * earlier patch. Use overwriteCurrent() when replacement is intentional.
     */
    suspend fun saveCurrentAs(
        name: String,
        description: String = "",
        tags: Set<String> = setOf("user"),
    ): PatchOutcome<SavedUserPatch> = onIo {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            return@onIo PatchOutcome.Failure(
                PatchFailureKind.INVALID_NAME, "Enter a patch name."
            )
        }
        safe(PatchFailureKind.INVALID_DATA) {
            val used = store.list().map { it.id }.toSet()
            val base = UserPatchIds.baseForName(cleanName)
            val id = UserPatchIds.uniqueForName(cleanName, used)
            val patch = SynthPatch(
                id = id, name = cleanName, description = description,
                tags = tags.toSet(), parameters = snapshotParameters().toMap(),
                oscillatorSlots = snapshotOscillators().toList(),
                modulationRoutes = snapshotModulationRoutes().toList(),
                modulationLfos = snapshotModulationLfos().toList(),
                modulationTempoBpm = snapshotModulationTempo(),
                fxSlots = snapshotFxSlots().toList(),
            )
            store.save(patch)
            PatchOutcome.Success(SavedUserPatch(patch, id != base))
        }
    }

    /** Explicit replacement retains the stable ID even if its title changes. */
    suspend fun overwriteCurrent(
        id: String,
        name: String? = null,
        description: String? = null,
        tags: Set<String>? = null,
    ): PatchOutcome<SynthPatch> = onIo {
        if (!UserPatchIds.isUserId(id)) return@onIo invalidId()
        safe(PatchFailureKind.INVALID_DATA) {
            val current = store.load(id)
                ?: return@safe notFound(id)
            val patch = current.copy(
                name = name?.trim() ?: current.name,
                description = description ?: current.description,
                tags = tags?.toSet() ?: current.tags,
                parameters = snapshotParameters().toMap(),
                oscillatorSlots = snapshotOscillators().toList(),
                modulationRoutes = snapshotModulationRoutes().toList(),
                modulationLfos = snapshotModulationLfos().toList(),
                modulationTempoBpm = snapshotModulationTempo(),
                fxSlots = snapshotFxSlots().toList(),
            )
            store.save(patch)
            PatchOutcome.Success(patch)
        }
    }

    /**
     * Import does not trust the external embedded ID. A fresh local user ID is
     * allocated from the validated display name, so imports cannot overwrite
     * factory patches or existing user patches.
     */
    suspend fun importPatchText(text: String): PatchOutcome<SavedUserPatch> = onIo {
        safe(PatchFailureKind.INVALID_FORMAT) {
            val incoming = PatchCodec.decode(text)
            val used = store.list().map { it.id }.toSet()
            val base = UserPatchIds.baseForName(incoming.name)
            val id = UserPatchIds.uniqueForName(incoming.name, used)
            val patch = incoming.copy(id = id)
            store.save(patch)
            PatchOutcome.Success(SavedUserPatch(patch, id != base))
        }
    }

    /** Export V1/V2/V3 legacy or V4 FX patches, preserving all validated edit state. */
    suspend fun exportPatchText(id: String): PatchOutcome<String> = onIo {
        safe(PatchFailureKind.INVALID_FORMAT) {
            when (val resolved = resolve(id)) {
                is PatchOutcome.Failure -> resolved
                is PatchOutcome.Success -> PatchOutcome.Success(PatchCodec.encode(resolved.value))
            }
        }
    }

    /** Retrieve a patch without changing the sound engine. */
    suspend fun load(id: String): PatchOutcome<SynthPatch> = onIo {
        safe(PatchFailureKind.INVALID_FORMAT) { resolve(id) }
    }

    /** Retrieve and apply a validated patch to the live engine atomics. */
    suspend fun activate(id: String): PatchOutcome<SynthPatch> = onIo {
        safe(PatchFailureKind.INVALID_FORMAT) {
            when (val resolved = resolve(id)) {
                is PatchOutcome.Failure -> resolved
                is PatchOutcome.Success -> {
                    applyPatch(resolved.value)
                    resolved
                }
            }
        }
    }

    /** Factory patches and invalid/path-traversal IDs can never be deleted. */
    suspend fun deleteUser(id: String): PatchOutcome<Boolean> = onIo {
        if (!UserPatchIds.isUserId(id)) return@onIo invalidId()
        safe(PatchFailureKind.INVALID_FORMAT) {
            if (!store.delete(id)) notFound(id)
            else PatchOutcome.Success(true)
        }
    }

    private fun resolve(id: String): PatchOutcome<SynthPatch> {
        FactoryPresets.byId(id)?.let { return PatchOutcome.Success(it) }
        if (!UserPatchIds.isUserId(id)) return invalidId()
        return store.load(id)?.let { PatchOutcome.Success(it) } ?: notFound(id)
    }

    private suspend fun <T> onIo(action: () -> PatchOutcome<T>): PatchOutcome<T> =
        withContext(ioDispatcher) {
            mutex.withLock { action() }
        }

    private fun <T> safe(
        invalidKind: PatchFailureKind,
        action: () -> PatchOutcome<T>,
    ): PatchOutcome<T> = try {
        action()
    } catch (_: IOException) {
        PatchOutcome.Failure(
            PatchFailureKind.STORAGE_ERROR, "Could not access the private patch bank."
        )
    } catch (_: SecurityException) {
        PatchOutcome.Failure(
            PatchFailureKind.STORAGE_ERROR, "Patch storage access was denied."
        )
    } catch (_: IllegalArgumentException) {
        PatchOutcome.Failure(
            invalidKind, when (invalidKind) {
                PatchFailureKind.INVALID_FORMAT -> "The patch data is malformed or unsupported."
                else -> "The patch name, metadata, or controls are invalid."
            }
        )
    } catch (_: IllegalStateException) {
        PatchOutcome.Failure(
            PatchFailureKind.ID_EXHAUSTED, "Could not allocate a unique patch ID."
        )
    }

    private fun invalidId() = PatchOutcome.Failure(
        PatchFailureKind.INVALID_ID, "This is not a valid user patch ID."
    )

    private fun notFound(id: String) = PatchOutcome.Failure(
        PatchFailureKind.NOT_FOUND, "The requested patch was not found."
    )
}
