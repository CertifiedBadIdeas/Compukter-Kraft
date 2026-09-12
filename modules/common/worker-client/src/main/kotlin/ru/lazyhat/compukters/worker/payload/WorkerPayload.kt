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

package ru.lazyhat.compukters.worker.payload

import ru.lazyhat.compukters.worker.value.Sha256
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

data class WorkerPayloadFile(
    val path: String,
    val bytes: Long,
    val sha256: Sha256,
)

class WorkerPayloadManifest internal constructor(
    val format: UInt,
    val kind: String,
    identityProperties: Map<String, String>,
    val mainClass: String,
    files: List<WorkerPayloadFile>,
    val payloadHash: Sha256,
) {
    val identityProperties: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(identityProperties))
    val files: List<WorkerPayloadFile> = Collections.unmodifiableList(files.toList())

    override fun equals(other: Any?): Boolean =
        other is WorkerPayloadManifest &&
            format == other.format &&
            kind == other.kind &&
            identityProperties == other.identityProperties &&
            mainClass == other.mainClass &&
            files == other.files &&
            payloadHash == other.payloadHash

    override fun hashCode(): Int = listOf(format, kind, identityProperties, mainClass, files, payloadHash).hashCode()

    companion object {
        const val FORMAT = 1u

        fun create(
            kind: String,
            identityProperties: Map<String, String>,
            mainClass: String,
            files: Map<String, ByteArray>,
        ): WorkerPayloadManifest {
            val identities = canonicalIdentityProperties(identityProperties)
            val records =
                files.entries
                    .map { (path, bytes) ->
                        validatePayloadPath(path)
                        WorkerPayloadFile(path, bytes.size.toLong(), sha256(bytes))
                    }.sortedBy(WorkerPayloadFile::path)
            val hash = hashManifest(FORMAT, kind, identities, mainClass, records)
            return WorkerPayloadManifest(FORMAT, kind, identities, mainClass, records, hash).also(::validateManifest)
        }
    }
}

fun interface WorkerPayloadSource {
    fun open(path: String): InputStream
}

data class PublishedWorkerPayload(
    val root: Path,
    val manifest: WorkerPayloadManifest,
    val classpath: List<Path>,
)

data class WorkerPayloadLimits(
    val files: Int = 128,
    val bytes: Long = 512L * 1024 * 1024,
) {
    init {
        require(files >= 0) { "payload file limit must not be negative" }
        require(bytes >= 0) { "payload byte limit must not be negative" }
    }
}

class WorkerPayloadException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

object WorkerPayloadPublisher {
    fun publish(
        manifest: WorkerPayloadManifest,
        source: WorkerPayloadSource,
        cacheRoot: Path,
        limits: WorkerPayloadLimits = WorkerPayloadLimits(),
    ): PublishedWorkerPayload {
        validateManifest(manifest)
        require(manifest.files.size <= limits.files) { "payload file count exceeds limit" }
        val totalBytes = manifest.files.fold(0L) { total, file -> Math.addExact(total, file.bytes) }
        require(totalBytes <= limits.bytes) { "payload bytes exceed limit" }
        cacheRoot.createDirectories()
        val destination = cacheRoot.resolve(manifest.payloadHash.hex())
        if (destination.exists()) return validatePublished(destination, manifest)

        val staging = cacheRoot.resolve(".staging-${manifest.payloadHash.hex()}-${UUID.randomUUID()}")
        staging.createDirectories()
        try {
            manifest.files.forEach { file -> publishFile(staging, file, source) }
            writeManifest(staging, manifest)
            forceDirectory(staging)
            movePublished(staging, destination)
            return validatePublished(destination, manifest)
        } catch (exception: Exception) {
            deleteTree(staging)
            throw if (exception is WorkerPayloadException) exception else WorkerPayloadException("payload publication failed", exception)
        }
    }

    private fun publishFile(
        staging: Path,
        file: WorkerPayloadFile,
        source: WorkerPayloadSource,
    ) {
        val target = staging.resolve(file.path).normalize()
        if (!target.startsWith(staging)) throw WorkerPayloadException("payload path escapes staging directory")
        target.parent.createDirectories()
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        source.open(file.path).use { input ->
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    written = Math.addExact(written, count.toLong())
                    if (written > file.bytes) throw WorkerPayloadException("payload file exceeds declared size: ${file.path}")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        if (written != file.bytes || Sha256.of(digest.digest()) != file.sha256) {
            throw WorkerPayloadException("payload file hash mismatch: ${file.path}")
        }
        forceFile(target)
    }

    private fun validatePublished(
        root: Path,
        manifest: WorkerPayloadManifest,
    ): PublishedWorkerPayload {
        val manifestPath = root.resolve(MANIFEST_FILE)
        if (!manifestPath.isRegularFile() || Files.readString(manifestPath, StandardCharsets.UTF_8) != manifest.canonicalText()) {
            throw WorkerPayloadException("published payload manifest is missing or invalid")
        }
        val classpath =
            manifest.files.map { file ->
                val path = root.resolve(file.path).normalize()
                if (!path.startsWith(root) || !path.isRegularFile() || path.fileSize() != file.bytes) {
                    throw WorkerPayloadException("published payload file is missing or invalid: ${file.path}")
                }
                if (Files.newInputStream(path).use(::sha256) != file.sha256) {
                    throw WorkerPayloadException("published payload hash mismatch: ${file.path}")
                }
                path
            }
        return PublishedWorkerPayload(root, manifest, Collections.unmodifiableList(classpath))
    }
}

internal fun WorkerPayloadManifest.canonicalText(): String =
    buildString {
        appendLine("format=$format")
        appendLine("kind=$kind")
        identityProperties.forEach { (name, value) -> appendLine("identity.$name=$value") }
        appendLine("mainClass=$mainClass")
        appendLine("payloadSha256=${payloadHash.hex()}")
        files.forEach { file -> appendLine("file=${file.path}\t${file.bytes}\t${file.sha256.hex()}") }
    }

internal fun validateManifest(manifest: WorkerPayloadManifest) {
    try {
        require(manifest.format == WorkerPayloadManifest.FORMAT) { "unsupported payload manifest format" }
        validateToken("payload kind", manifest.kind)
        require(manifest.identityProperties == canonicalIdentityProperties(manifest.identityProperties)) {
            "identity properties are not canonical"
        }
        validateText("worker main class", manifest.mainClass)
        require(manifest.files == manifest.files.sortedBy(WorkerPayloadFile::path)) { "payload files are not ordered" }
        require(
            manifest.files
                .map(WorkerPayloadFile::path)
                .distinct()
                .size == manifest.files.size,
        ) { "duplicate payload path" }
        manifest.files.forEach { file ->
            validatePayloadPath(file.path)
            require(file.bytes >= 0) { "payload file size must not be negative" }
        }
        require(
            manifest.payloadHash ==
                hashManifest(manifest.format, manifest.kind, manifest.identityProperties, manifest.mainClass, manifest.files),
        ) { "payload manifest hash mismatch" }
    } catch (exception: IllegalArgumentException) {
        throw WorkerPayloadException("payload manifest is invalid", exception)
    }
}

internal fun canonicalIdentityProperties(values: Map<String, String>): Map<String, String> =
    LinkedHashMap<String, String>().apply {
        values.entries.sortedBy(Map.Entry<String, String>::key).forEach { (name, value) ->
            validateToken("identity property name", name)
            validateText("identity property value", value)
            require(put(name, value) == null) { "duplicate identity property" }
        }
    }

internal fun validatePayloadPath(path: String) {
    require(path.length in 1..1024) { "payload path length is invalid" }
    require('\\' !in path && '\u0000' !in path) { "payload path contains a forbidden character" }
    require(path.startsWith("lib/") && path.endsWith(".jar")) { "payload entries must be jars below lib/" }
    val parts = path.split('/')
    require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "payload path is not canonical" }
}

internal fun hashManifest(
    format: UInt,
    kind: String,
    identityProperties: Map<String, String>,
    mainClass: String,
    files: List<WorkerPayloadFile>,
): Sha256 {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("Compukters worker payload v1\u0000".encodeToByteArray())
    digest.update(format.littleEndian())
    digest.field(kind)
    identityProperties.forEach { (name, value) ->
        digest.field(name)
        digest.field(value)
    }
    digest.field(mainClass)
    files.forEach { file ->
        digest.field(file.path)
        digest.update(file.bytes.littleEndian())
        digest.update(file.sha256.toByteArray())
    }
    return Sha256.of(digest.digest())
}

internal fun sha256(bytes: ByteArray): Sha256 = Sha256.of(MessageDigest.getInstance("SHA-256").digest(bytes))

internal fun sha256(input: InputStream): Sha256 {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return Sha256.of(digest.digest())
}

internal fun writeManifest(
    staging: Path,
    manifest: WorkerPayloadManifest,
) {
    val target = staging.resolve(MANIFEST_FILE)
    Files.writeString(target, manifest.canonicalText(), StandardCharsets.UTF_8)
    forceFile(target)
}

internal fun movePublished(
    staging: Path,
    destination: Path,
) {
    try {
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        moveWithoutReplacement(staging, destination)
    } catch (exception: IOException) {
        if (destination.exists()) deleteTree(staging) else throw exception
    }
}

private fun moveWithoutReplacement(
    staging: Path,
    destination: Path,
) {
    try {
        Files.move(staging, destination)
    } catch (_: FileAlreadyExistsException) {
        deleteTree(staging)
    } catch (exception: IOException) {
        if (destination.exists()) deleteTree(staging) else throw exception
    }
}

internal fun forceFile(path: Path) {
    java.nio.channels.FileChannel
        .open(path, java.nio.file.StandardOpenOption.WRITE)
        .use { channel -> channel.force(true) }
}

internal fun forceDirectory(path: Path) {
    try {
        java.nio.channels.FileChannel
            .open(path, java.nio.file.StandardOpenOption.READ)
            .use { channel -> channel.force(true) }
    } catch (_: UnsupportedOperationException) {
        // Directory fsync is best-effort across platforms.
    } catch (_: IOException) {
        // File contents were already forced.
    }
}

internal fun deleteTree(path: Path) {
    if (!path.exists()) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

private fun validateToken(
    description: String,
    value: String,
) {
    require(TOKEN.matches(value)) { "$description is invalid" }
}

private fun validateText(
    description: String,
    value: String,
) {
    require(value.isNotBlank() && value.length <= 1024) { "$description is invalid" }
    require(value.none { it == '\u0000' || it == '\r' || it == '\n' }) { "$description contains a forbidden character" }
}

private fun MessageDigest.field(value: String) {
    val bytes = value.encodeToByteArray()
    update(bytes.size.toUInt().littleEndian())
    update(bytes)
}

private fun UInt.littleEndian(): ByteArray =
    ByteBuffer
        .allocate(UInt.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(toInt())
        .array()

private fun Long.littleEndian(): ByteArray =
    ByteBuffer
        .allocate(Long.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(this)
        .array()

internal const val MANIFEST_FILE = "worker.payload"
private val TOKEN = Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")
