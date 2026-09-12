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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Collections

data class WorkerPayloadLoadLimits(
    val manifestBytes: Int = 1024 * 1024,
    val files: Int = 128,
    val payloadBytes: Long = 512L * 1024 * 1024,
) {
    init {
        require(manifestBytes >= 0) { "manifest byte limit must not be negative" }
        require(files >= 0) { "payload file limit must not be negative" }
        require(payloadBytes >= 0) { "payload byte limit must not be negative" }
    }
}

object WorkerPayloadLoader {
    fun load(
        root: Path,
        limits: WorkerPayloadLoadLimits = WorkerPayloadLoadLimits(),
    ): PublishedWorkerPayload =
        try {
            loadChecked(root.toAbsolutePath().normalize(), limits)
        } catch (exception: WorkerPayloadException) {
            throw exception
        } catch (exception: Exception) {
            throw WorkerPayloadException("published worker payload is invalid", exception)
        }

    private fun loadChecked(
        root: Path,
        limits: WorkerPayloadLoadLimits,
    ): PublishedWorkerPayload {
        requireRegularDirectory(root)
        val text = decodeManifest(readBoundedRegularFile(root.resolve(MANIFEST_FILE), limits.manifestBytes))
        val manifest = parseManifest(text, limits)
        if (manifest.canonicalText() != text) invalid("payload manifest is not canonical")
        val classpath = manifest.files.map { file -> validatePayloadFile(root, file) }
        return PublishedWorkerPayload(root, manifest, Collections.unmodifiableList(classpath))
    }

    private fun parseManifest(
        text: String,
        limits: WorkerPayloadLoadLimits,
    ): WorkerPayloadManifest {
        if (!text.endsWith('\n') || '\r' in text) invalid("payload manifest is not canonical text")
        val lines = text.dropLast(1).split('\n')
        var index = 0

        fun property(name: String): String {
            val prefix = "$name="
            val line = lines.getOrNull(index++) ?: invalid("payload manifest property is missing: $name")
            if (!line.startsWith(prefix)) invalid("payload manifest property is noncanonical: $name")
            return line.removePrefix(prefix)
        }
        val format = property("format").toUIntOrNull() ?: invalid("payload format is invalid")
        val kind = property("kind")
        val identities = linkedMapOf<String, String>()
        while (lines.getOrNull(index)?.startsWith("identity.") == true) {
            val line = lines[index++]
            val separator = line.indexOf('=')
            if (separator <= "identity.".length) invalid("identity property is malformed")
            val name = line.substring("identity.".length, separator)
            if (identities.put(name, line.substring(separator + 1)) != null) invalid("identity property is duplicated")
        }
        val mainClass = property("mainClass")
        val payloadHash = decodeHash(property("payloadSha256"))
        val files = mutableListOf<WorkerPayloadFile>()
        while (index < lines.size) {
            if (files.size >= limits.files) invalid("payload file count exceeds limit")
            val line = lines[index++]
            if (!line.startsWith("file=")) invalid("payload manifest contains an unknown property")
            val fields = line.removePrefix("file=").split('\t')
            if (fields.size != 3) invalid("payload file record is malformed")
            val bytes = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: invalid("payload file size is invalid")
            files += WorkerPayloadFile(fields[0], bytes, decodeHash(fields[2]))
        }
        val total = files.fold(0L) { value, file -> Math.addExact(value, file.bytes) }
        if (total > limits.payloadBytes) invalid("payload bytes exceed limit")
        val manifest = WorkerPayloadManifest(format, kind, identities, mainClass, files, payloadHash)
        validateManifest(manifest)
        return manifest
    }

    private fun validatePayloadFile(
        root: Path,
        file: WorkerPayloadFile,
    ): Path {
        val path = root.resolve(file.path).normalize()
        if (!path.startsWith(root)) invalid("payload file escapes its root")
        requireNoSymbolicLinks(root, path)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != file.bytes) {
            invalid("payload file is missing or has an invalid size: ${file.path}")
        }
        if (Files.newInputStream(path).use(::sha256) != file.sha256) invalid("payload file hash mismatch: ${file.path}")
        return path
    }

    private fun requireNoSymbolicLinks(
        root: Path,
        path: Path,
    ) {
        var current = root
        root.relativize(path).forEach { component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) invalid("payload contains a symbolic link")
        }
    }

    private fun requireRegularDirectory(root: Path) {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            invalid("payload root is not a regular directory")
        }
    }

    private fun readBoundedRegularFile(
        path: Path,
        maximumBytes: Int,
    ): ByteArray {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) invalid("payload manifest is missing")
        val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(minOf(maximumBytes.coerceAtLeast(1), DEFAULT_BUFFER_SIZE))
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count)
                if (total > maximumBytes) invalid("payload manifest exceeds its byte limit")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun decodeManifest(bytes: ByteArray): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun decodeHash(value: String): Sha256 {
        if (value.length != 64 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) invalid("invalid SHA-256 value")
        return Sha256.of(ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() })
    }

    private fun invalid(message: String): Nothing = throw WorkerPayloadException(message)
}
