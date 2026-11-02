package com.orbital.synth.core.music

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * App-supplied private-directory persistence for editable user sequences.
 *
 * Never call from a realtime audio render or Compose composition callback:
 * all filesystem I/O belongs on an app background dispatcher. The class itself
 * is portable Kotlin/JVM with no Android framework dependency.
 */
class SequenceDirectoryStore(private val directory: Path) {
    private val extension = ".orbital-sequence"

    private fun pathFor(id: String): Path {
        require(UserSequence.validId(id)) { "Unsafe sequence identifier" }
        return directory.resolve(id + extension)
    }

    fun save(sequence: UserSequence) {
        Files.createDirectories(directory)
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "Sequence bank must be a real directory"
        }
        val destination = pathFor(sequence.id)
        require(!Files.isSymbolicLink(destination)) { "Refusing to replace sequence symlink" }

        val payload = SequenceCodec.encode(sequence).toByteArray(StandardCharsets.UTF_8)
        val temporary = Files.createTempFile(directory, "seq-" + sequence.id + "-", ".tmp")
        try {
            Files.write(temporary, payload)
            try {
                Files.move(
                    temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun load(id: String): UserSequence? {
        val source = pathFor(id)
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.size(source) <= SequenceCodec.MAX_BYTES) { "Stored sequence too large" }
        val bytes = Files.readAllBytes(source)
        require(bytes.size <= SequenceCodec.MAX_BYTES) { "Stored sequence too large" }
        val sequence = SequenceCodec.decode(String(bytes, StandardCharsets.UTF_8))
        require(sequence.id == id) { "Sequence filename and embedded ID differ" }
        return sequence
    }

    fun list(): List<UserSequence> {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val ids = Files.newDirectoryStream(directory, "*" + extension).use { files ->
            files.map { it.fileName.toString().removeSuffix(extension) }
                .filter(UserSequence::validId)
                .sorted()
                .toList()
        }
        // Corrupt files surface errors; silently losing user compositions is unsafe.
        return ids.mapNotNull(::load)
    }

    fun delete(id: String): Boolean = Files.deleteIfExists(pathFor(id))
}
