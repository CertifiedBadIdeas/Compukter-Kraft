/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.compiler.cache

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class PersistentCompilationCache private constructor(
    root: Path,
    private val policy: CompilationCachePolicy,
    private val verifier: ArtifactVerifier,
) : AutoCloseable {
    private val versionRoot = root.resolve(VERSION_DIRECTORY)
    private val entries = mutableMapOf<Hash256, CompilationCacheEntry>()
    private var artifactBytes = 0L
    private var sequence = 0L
    private var closed = false

    init {
        require(root.isAbsolute) { "cache root must be absolute" }
        require(root.normalize() == root) { "cache root must already be normalized" }
        versionRoot.createDirectories()
        loadEntries()
        evictToPolicy()
    }

    @Synchronized
    fun get(identity: Hash256): ByteArray? {
        requireOpen()
        val entry = entries[identity] ?: return null
        val artifact =
            validateArtifact(entry) ?: run {
                removeEntry(entry)
                return null
            }
        entry.lastAccessSequence = nextSequence()
        entry.recencyDirty = true
        return artifact
    }

    @Synchronized
    fun put(
        identity: Hash256,
        artifact: ByteArray,
    ): ByteArray {
        requireOpen()
        require(artifact.isNotEmpty()) { "cache artifact must not be empty" }
        require(artifact.size <= policy.maximumSingleArtifactBytes) { "cache artifact exceeds per-entry limit" }
        require(artifact.size.toLong() <= policy.maximumArtifactBytes) { "cache artifact exceeds total byte limit" }
        val candidate = artifact.copyOf()
        if (!verifier.verify(candidate.copyOf())) throw CompilationCacheException("artifact verifier rejected cache candidate")

        entries[identity]?.let { existing ->
            val winner = validateArtifact(existing)
            if (winner != null) {
                existing.lastAccessSequence = nextSequence()
                existing.recencyDirty = true
                return winner
            }
            removeEntry(existing)
        }

        val artifactHash = sha256(candidate)
        val accessSequence = nextSequence()
        val staging = versionRoot.resolve("$STAGING_PREFIX${identity.hex()}-${UUID.randomUUID()}")
        val destination = versionRoot.resolve(identity.hex())
        try {
            staging.createDirectories()
            val stagedArtifact = staging.resolve(ARTIFACT_FILE)
            Files.write(stagedArtifact, candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            forceFile(stagedArtifact)
            val entry =
                CompilationCacheEntry(
                    identity = identity,
                    directory = staging,
                    artifactHash = artifactHash,
                    artifactBytes = candidate.size,
                    lastAccessSequence = accessSequence,
                )
            writeMetadata(entry)
            forceDirectory(staging)
            movePublished(staging, destination)
            val published = entry.copy(directory = destination)
            entries[identity] = published
            artifactBytes = Math.addExact(artifactBytes, candidate.size.toLong())
            evictToPolicy()
            return candidate.copyOf()
        } catch (_: FileAlreadyExistsException) {
            deleteTree(staging)
            val winner =
                loadEntry(destination, identity)
                    ?: throw CompilationCacheException("concurrent cache publisher produced an invalid entry")
            entries[identity] = winner
            artifactBytes = Math.addExact(artifactBytes, winner.artifactBytes.toLong())
            evictToPolicy()
            return requireNotNull(get(identity))
        } catch (exception: Exception) {
            deleteTree(staging)
            throw if (exception is CompilationCacheException) {
                exception
            } else {
                CompilationCacheException(
                    "cache publication failed",
                    exception,
                )
            }
        }
    }

    @Synchronized
    fun stats(): CompilationCacheStats {
        requireOpen()
        return CompilationCacheStats(entries.size, artifactBytes)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        var failure: Throwable? = null
        entries.values.sortedBy { it.identity.hex() }.forEach { entry ->
            if (!entry.recencyDirty) return@forEach
            try {
                writeMetadataAtomically(entry)
                entry.recencyDirty = false
            } catch (error: Throwable) {
                failure = failure ?: error
            }
        }
        failure?.let { throw CompilationCacheException("failed to persist cache recency", it) }
        closed = true
    }

    private fun loadEntries() {
        val candidates =
            Files.list(versionRoot).use { paths ->
                paths.limit(policy.maximumStartupEntries.toLong() + 1).toList()
            }
        if (candidates.size > policy.maximumStartupEntries) {
            throw CompilationCacheException("cache startup entry count exceeds limit")
        }
        candidates.sorted().forEach { path ->
            val name = path.name
            when {
                name.startsWith(STAGING_PREFIX) -> {
                    deleteTree(path)
                }

                !path.isDirectory() || !IDENTITY_PATTERN.matches(name) -> {
                    deleteTree(path)
                }

                else -> {
                    val identity = decodeHash(name)
                    val entry = loadEntry(path, identity)
                    if (entry == null || entries.putIfAbsent(identity, entry) != null) {
                        deleteTree(path)
                    } else {
                        artifactBytes = Math.addExact(artifactBytes, entry.artifactBytes.toLong())
                        sequence = maxOf(sequence, entry.lastAccessSequence)
                    }
                }
            }
        }
    }

    private fun loadEntry(
        directory: Path,
        identity: Hash256,
    ): CompilationCacheEntry? =
        try {
            val metadataPath = directory.resolve(METADATA_FILE)
            if (!metadataPath.isRegularFile() || Files.size(metadataPath) > policy.maximumMetadataBytes) return null
            val metadata = parseMetadata(metadataPath.readText(StandardCharsets.UTF_8))
            if (metadata.identity != identity) return null
            val entry =
                CompilationCacheEntry(
                    identity = identity,
                    directory = directory,
                    artifactHash = metadata.artifactHash,
                    artifactBytes = metadata.artifactBytes,
                    lastAccessSequence = metadata.lastAccessSequence,
                )
            validateArtifact(entry) ?: return null
            entry
        } catch (_: Exception) {
            null
        }

    private fun validateArtifact(entry: CompilationCacheEntry): ByteArray? =
        try {
            if (entry.artifactBytes <= 0 || entry.artifactBytes > policy.maximumSingleArtifactBytes) return null
            val path = entry.directory.resolve(ARTIFACT_FILE)
            if (!path.isRegularFile() || Files.size(path) != entry.artifactBytes.toLong()) return null
            val artifact = path.readBytes()
            if (artifact.size != entry.artifactBytes || sha256(artifact) != entry.artifactHash) return null
            if (!verifier.verify(artifact.copyOf())) return null
            artifact
        } catch (_: Exception) {
            null
        }

    private fun evictToPolicy() {
        while (entries.size > policy.maximumEntries || artifactBytes > policy.maximumArtifactBytes) {
            val victim =
                entries.values.minWithOrNull(
                    compareBy<CompilationCacheEntry> { it.lastAccessSequence }.thenBy { it.identity.hex() },
                ) ?: return
            removeEntry(victim)
        }
    }

    private fun removeEntry(entry: CompilationCacheEntry) {
        if (entries.remove(entry.identity, entry)) artifactBytes -= entry.artifactBytes.toLong()
        deleteTree(entry.directory)
    }

    private fun writeMetadata(entry: CompilationCacheEntry) {
        val target = entry.directory.resolve(METADATA_FILE)
        Files.writeString(
            target,
            metadataText(entry),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        forceFile(target)
    }

    private fun writeMetadataAtomically(entry: CompilationCacheEntry) {
        val temporary = entry.directory.resolve(".$METADATA_FILE-${UUID.randomUUID()}")
        try {
            Files.writeString(
                temporary,
                metadataText(entry),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            forceFile(temporary)
            moveReplacing(temporary, entry.directory.resolve(METADATA_FILE))
            forceDirectory(entry.directory)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun nextSequence(): Long {
        sequence = Math.addExact(sequence, 1)
        return sequence
    }

    private fun requireOpen() = check(!closed) { "compilation cache is closed" }

    companion object {
        fun open(
            root: Path,
            policy: CompilationCachePolicy = CompilationCachePolicy(),
            verifier: ArtifactVerifier,
        ): PersistentCompilationCache = PersistentCompilationCache(root, policy, verifier)

        private const val VERSION_DIRECTORY = "v1"
        private const val ARTIFACT_FILE = "artifact"
        private const val METADATA_FILE = "metadata"
        private const val STAGING_PREFIX = ".staging-"
        private val IDENTITY_PATTERN = Regex("[0-9a-f]{64}")
    }
}

private data class CacheMetadata(
    val identity: Hash256,
    val artifactHash: Hash256,
    val artifactBytes: Int,
    val lastAccessSequence: Long,
)

private fun metadataText(entry: CompilationCacheEntry): String =
    buildString {
        appendLine("format=1")
        appendLine("identity=${entry.identity.hex()}")
        appendLine("artifactSha256=${entry.artifactHash.hex()}")
        appendLine("artifactBytes=${entry.artifactBytes}")
        appendLine("lastAccess=${entry.lastAccessSequence}")
    }

private fun parseMetadata(text: String): CacheMetadata {
    val lines = text.lineSequence().filter(String::isNotEmpty).toList()
    if (lines.size != 5 || lines[0] != "format=1") throw CompilationCacheException("invalid cache metadata format")

    fun field(
        index: Int,
        name: String,
    ): String =
        lines[index].removePrefix("$name=").takeIf { it.length != lines[index].length }
            ?: throw CompilationCacheException("missing cache metadata field: $name")
    val bytes = field(3, "artifactBytes").toInt()
    val access = field(4, "lastAccess").toLong()
    if (bytes <= 0 || access < 0) throw CompilationCacheException("invalid cache metadata values")
    return CacheMetadata(
        identity = decodeHash(field(1, "identity")),
        artifactHash = decodeHash(field(2, "artifactSha256")),
        artifactBytes = bytes,
        lastAccessSequence = access,
    )
}

private fun decodeHash(value: String): Hash256 {
    if (!Regex("[0-9a-f]{64}").matches(value)) throw CompilationCacheException("invalid cache hash")
    return Hash256.of(ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() })
}

private fun sha256(bytes: ByteArray): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun movePublished(
    source: Path,
    destination: Path,
) {
    try {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination)
    }
    forceDirectory(destination.parent)
}

private fun moveReplacing(
    source: Path,
    destination: Path,
) {
    try {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun forceFile(path: Path) {
    FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
}

private fun forceDirectory(path: Path) {
    try {
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        // Some supported filesystems do not permit opening directories. The
        // files themselves are still forced and the move remains atomic.
    }
}

private fun deleteTree(root: Path) {
    if (!root.exists()) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
