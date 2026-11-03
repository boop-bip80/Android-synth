package com.orbital.synth.core.preset

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Local patch persistence for a caller-supplied private directory.
 * Crash-safe best-effort atomic replacement; IDs cannot escape this directory.
 * No Android Context dependency and no hidden background I/O.
 */
class PatchDirectoryStore(private val directory: Path) {
    private fun pathFor(id: String): Path {
        require(SynthPatch.validId(id)) { "Unsafe patch id" }
        return directory.resolve(id + ".orbital")
    }

    /** Never follow a substituted patch-bank symlink into unrelated app data. */
    private fun rejectSymlinkedBank() {
        require(!Files.isSymbolicLink(directory)) { "Patch bank cannot be a symlink" }
    }

    fun save(patch: SynthPatch) {
        rejectSymlinkedBank()
        Files.createDirectories(directory)
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "Patch bank must be a real directory"
        }
        val target = pathFor(patch.id)
        require(!Files.isSymbolicLink(target)) { "Refusing to replace a patch symlink" }
        val temporary = Files.createTempFile(directory, patch.id + "-", ".tmp")
        try {
            Files.write(temporary, PatchCodec.encode(patch).toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(
                    temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun load(id: String): SynthPatch? {
        rejectSymlinkedBank()
        val path = pathFor(id)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.size(path) <= 65_536L) { "Patch too large" }
        val bytes = Files.readAllBytes(path)
        require(bytes.size <= 65_536) { "Patch too large" }
        val patch = PatchCodec.decode(String(bytes, StandardCharsets.UTF_8))
        require(patch.id == id) { "Patch filename and embedded ID differ" }
        return patch
    }

    fun list(): List<SynthPatch> {
        rejectSymlinkedBank()
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val ids = Files.newDirectoryStream(directory, "*.orbital").use { entries ->
            entries.map { it.fileName.toString().removeSuffix(".orbital") }
                .filter(SynthPatch::validId)
                .sorted()
                .toList()
        }
        // A malformed stored patch is surfaced to callers, never silently discarded.
        return ids.mapNotNull(::load)
    }

    fun delete(id: String): Boolean {
        rejectSymlinkedBank()
        val path = pathFor(id)
        require(!Files.isSymbolicLink(path)) { "Refusing to delete a patch symlink" }
        return Files.deleteIfExists(path)
    }
}
