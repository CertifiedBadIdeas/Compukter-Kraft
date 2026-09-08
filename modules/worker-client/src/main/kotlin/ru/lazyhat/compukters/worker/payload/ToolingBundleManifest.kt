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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections

data class ToolingBundleFile(
    val path: String,
    val bytes: Long,
    val sha256: Sha256,
)

data class ToolingProfileDefinition(
    val identityProperties: Map<String, String>,
    val mainClass: String,
    val classpath: List<String>,
)

class ToolingProfileManifest internal constructor(
    val kind: String,
    identityProperties: Map<String, String>,
    val mainClass: String,
    classpath: List<String>,
    val payloadHash: Sha256,
) {
    val identityProperties: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(identityProperties))
    val classpath: List<String> = Collections.unmodifiableList(classpath.toList())

    override fun equals(other: Any?): Boolean =
        other is ToolingProfileManifest &&
            kind == other.kind &&
            identityProperties == other.identityProperties &&
            mainClass == other.mainClass &&
            classpath == other.classpath &&
            payloadHash == other.payloadHash

    override fun hashCode(): Int = listOf(kind, identityProperties, mainClass, classpath, payloadHash).hashCode()
}

class ToolingBundleManifest internal constructor(
    val format: UInt,
    files: List<ToolingBundleFile>,
    profiles: Map<String, ToolingProfileManifest>,
    val bundleHash: Sha256,
) {
    val files: List<ToolingBundleFile> = Collections.unmodifiableList(files.toList())
    val profiles: Map<String, ToolingProfileManifest> = Collections.unmodifiableMap(LinkedHashMap(profiles))

    fun canonicalBundleText(): String = ToolingBundleManifestCodec.encodeBundle(this)

    fun encodedFiles(): Map<String, ByteArray> =
        linkedMapOf<String, ByteArray>().apply {
            put(TOOLING_BUNDLE_MANIFEST_FILE, canonicalBundleText().encodeToByteArray())
            profiles.forEach { (kind, profile) -> put("manifests/$kind.payload", profile.canonicalText().encodeToByteArray()) }
        }

    override fun equals(other: Any?): Boolean =
        other is ToolingBundleManifest &&
            format == other.format &&
            files == other.files &&
            profiles == other.profiles &&
            bundleHash == other.bundleHash

    override fun hashCode(): Int = listOf(format, files, profiles, bundleHash).hashCode()

    companion object {
        const val FORMAT = 1u

        fun create(
            files: Map<String, ByteArray>,
            profiles: Map<String, ToolingProfileDefinition>,
        ): ToolingBundleManifest {
            val records =
                files
                    .map { (path, bytes) ->
                        validateToolingPath(path)
                        ToolingBundleFile(path, bytes.size.toLong(), sha256(bytes))
                    }.sortedBy(ToolingBundleFile::path)
            val recordsByPath = records.associateBy(ToolingBundleFile::path)
            val manifests =
                profiles.entries
                    .sortedBy(Map.Entry<String, ToolingProfileDefinition>::key)
                    .associateTo(linkedMapOf()) { (kind, definition) ->
                        val identities = canonicalIdentityProperties(definition.identityProperties)
                        val profile =
                            ToolingProfileManifest(
                                kind,
                                identities,
                                definition.mainClass,
                                definition.classpath,
                                hashProfile(kind, identities, definition.mainClass, definition.classpath, recordsByPath),
                            )
                        kind to profile
                    }
            val manifest = ToolingBundleManifest(FORMAT, records, manifests, hashBundle(FORMAT, records, manifests))
            ToolingBundleManifestCodec.validate(manifest)
            return manifest
        }
    }
}

class ToolingBundleException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

data class ToolingBundleIdentity(
    val format: UInt,
    val bundleHash: Sha256,
)

object ToolingBundleManifestCodec {
    fun decodeIdentity(bundleDocument: ByteArray): ToolingBundleIdentity =
        try {
            val lines = canonicalLines(decodeText(bundleDocument))
            val format = canonicalUInt(property(lines, 0, "format"))
            val bundleHash = decodeHash(property(lines, 1, "bundleSha256"))
            require(format == ToolingBundleManifest.FORMAT) { "unsupported tooling bundle format" }
            ToolingBundleIdentity(format, bundleHash)
        } catch (exception: ToolingBundleException) {
            throw exception
        } catch (exception: Exception) {
            throw ToolingBundleException("tooling bundle identity is invalid", exception)
        }

    fun decode(documents: Map<String, ByteArray>): ToolingBundleManifest =
        try {
            decodeChecked(documents)
        } catch (exception: ToolingBundleException) {
            throw exception
        } catch (exception: Exception) {
            throw ToolingBundleException("tooling bundle manifest is invalid", exception)
        }

    fun validate(manifest: ToolingBundleManifest) {
        try {
            require(manifest.format == ToolingBundleManifest.FORMAT) { "unsupported tooling bundle format" }
            require(manifest.files == manifest.files.sortedBy(ToolingBundleFile::path)) { "tooling files are not ordered" }
            require(
                manifest.files
                    .map(ToolingBundleFile::path)
                    .distinct()
                    .size == manifest.files.size,
            ) { "duplicate tooling file" }
            manifest.files.forEach { file ->
                validateToolingPath(file.path)
                require(file.bytes >= 0) { "tooling file size must not be negative" }
            }
            require(manifest.profiles.keys == REQUIRED_PROFILES) { "tooling bundle profiles are invalid" }
            require(manifest.profiles.keys.toList() == manifest.profiles.keys.sorted()) { "tooling profiles are not ordered" }
            val recordsByPath = manifest.files.associateBy(ToolingBundleFile::path)
            val referencedBy = mutableMapOf<String, MutableSet<String>>()
            manifest.profiles.forEach { (kind, profile) ->
                require(kind == profile.kind) { "tooling profile key does not match its kind" }
                require(profile.identityProperties == canonicalIdentityProperties(profile.identityProperties)) {
                    "tooling profile identities are not canonical"
                }
                validateToolingText("tooling profile main class", profile.mainClass)
                require(profile.classpath.isNotEmpty()) { "tooling profile classpath is empty" }
                require(profile.classpath.distinct().size == profile.classpath.size) { "duplicate tooling classpath entry" }
                require(profile.classpath.any { it.startsWith("$kind/lib/") }) { "tooling profile has no private worker jar" }
                profile.classpath.forEach { path ->
                    require(path in recordsByPath) { "tooling classpath references an undeclared file" }
                    require(path.startsWith("common/lib/") || path.startsWith("$kind/lib/")) {
                        "tooling profile references another profile's private file"
                    }
                    referencedBy.getOrPut(path, ::mutableSetOf).add(kind)
                }
                require(
                    profile.payloadHash ==
                        hashProfile(kind, profile.identityProperties, profile.mainClass, profile.classpath, recordsByPath),
                ) { "tooling profile hash mismatch" }
            }
            require(referencedBy.keys == recordsByPath.keys) { "tooling bundle contains an unreferenced file" }
            manifest.files.filter { it.path.startsWith("common/lib/") }.forEach { file ->
                require(referencedBy.getValue(file.path) == REQUIRED_PROFILES) { "common tooling file is not shared by every profile" }
            }
            require(manifest.bundleHash == hashBundle(manifest.format, manifest.files, manifest.profiles)) {
                "tooling bundle hash mismatch"
            }
        } catch (exception: IllegalArgumentException) {
            throw ToolingBundleException("tooling bundle manifest is invalid", exception)
        }
    }

    internal fun encodeBundle(manifest: ToolingBundleManifest): String =
        buildString {
            appendLine("format=${manifest.format}")
            appendLine("bundleSha256=${manifest.bundleHash.hex()}")
            manifest.files.forEach { file -> appendLine("file=${file.path}\t${file.bytes}\t${file.sha256.hex()}") }
            manifest.profiles.forEach { (kind, profile) ->
                appendLine("profile=$kind\tmanifests/$kind.payload\t${profile.payloadHash.hex()}")
            }
        }

    private fun decodeChecked(documents: Map<String, ByteArray>): ToolingBundleManifest {
        require(documents.keys == REQUIRED_DOCUMENTS) { "tooling manifest documents are missing or unexpected" }
        val profileDocuments =
            REQUIRED_PROFILES.associateWith { kind -> decodeText(documents.getValue("manifests/$kind.payload")) }
        val bundleText = decodeText(documents.getValue(TOOLING_BUNDLE_MANIFEST_FILE))
        val bundleLines = canonicalLines(bundleText)
        var index = 0

        fun property(name: String): String {
            val prefix = "$name="
            val line = bundleLines.getOrNull(index++) ?: invalid("tooling bundle property is missing: $name")
            if (!line.startsWith(prefix)) invalid("tooling bundle property is noncanonical: $name")
            return line.removePrefix(prefix)
        }

        val format = canonicalUInt(property("format"))
        val bundleHash = decodeHash(property("bundleSha256"))
        val files = mutableListOf<ToolingBundleFile>()
        while (bundleLines.getOrNull(index)?.startsWith("file=") == true) {
            val fields = bundleLines[index++].removePrefix("file=").split('\t')
            if (fields.size != 3) invalid("tooling file record is malformed")
            files += ToolingBundleFile(fields[0], canonicalLong(fields[1]), decodeHash(fields[2]))
        }
        val profiles = linkedMapOf<String, ToolingProfileManifest>()
        while (index < bundleLines.size) {
            val fields = bundleLines[index++].removePrefix("profile=").split('\t')
            if (fields.size != 3) invalid("tooling profile record is malformed")
            val kind = fields[0]
            if (fields[1] != "manifests/$kind.payload") invalid("tooling profile manifest path is invalid")
            val profile = decodeProfile(profileDocuments[kind] ?: invalid("tooling profile document is missing"))
            if (profile.payloadHash != decodeHash(fields[2])) invalid("tooling profile record hash mismatch")
            if (profiles.put(kind, profile) != null) invalid("tooling profile is duplicated")
        }
        val manifest = ToolingBundleManifest(format, files, profiles, bundleHash)
        validate(manifest)
        if (manifest.canonicalBundleText() != bundleText) invalid("tooling bundle manifest is not canonical")
        manifest.profiles.forEach { (kind, profile) ->
            if (profile.canonicalText() != profileDocuments.getValue(kind)) invalid("tooling profile manifest is not canonical")
        }
        return manifest
    }

    private fun decodeProfile(text: String): ToolingProfileManifest {
        val lines = canonicalLines(text)
        var index = 0

        fun property(name: String): String {
            val prefix = "$name="
            val line = lines.getOrNull(index++) ?: invalid("tooling profile property is missing: $name")
            if (!line.startsWith(prefix)) invalid("tooling profile property is noncanonical: $name")
            return line.removePrefix(prefix)
        }

        val format = canonicalUInt(property("format"))
        if (format != ToolingBundleManifest.FORMAT) invalid("unsupported tooling profile format")
        val kind = property("kind")
        val identities = linkedMapOf<String, String>()
        while (lines.getOrNull(index)?.startsWith("identity.") == true) {
            val line = lines[index++]
            val separator = line.indexOf('=')
            if (separator <= "identity.".length) invalid("tooling identity property is malformed")
            val name = line.substring("identity.".length, separator)
            if (identities.put(name, line.substring(separator + 1)) != null) invalid("tooling identity property is duplicated")
        }
        val mainClass = property("mainClass")
        val payloadHash = decodeHash(property("payloadSha256"))
        val classpath = mutableListOf<String>()
        while (index < lines.size) classpath += property("classpath")
        return ToolingProfileManifest(kind, identities, mainClass, classpath, payloadHash)
    }

    private fun decodeText(bytes: ByteArray): String =
        bytes.toString(Charsets.UTF_8).also { text ->
            if (!text.encodeToByteArray().contentEquals(bytes)) invalid("tooling manifest is not valid UTF-8")
        }

    private fun canonicalLines(text: String): List<String> {
        if (!text.endsWith('\n') || '\r' in text) invalid("tooling manifest is not canonical text")
        return text.dropLast(1).split('\n')
    }

    private fun canonicalUInt(value: String): UInt =
        value.toUIntOrNull()?.takeIf { it.toString() == value } ?: invalid("tooling unsigned integer is noncanonical")

    private fun canonicalLong(value: String): Long =
        value.toLongOrNull()?.takeIf { it >= 0 && it.toString() == value } ?: invalid("tooling size is noncanonical")

    private fun decodeHash(value: String): Sha256 {
        if (value.length != 64 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) invalid("tooling SHA-256 is invalid")
        return Sha256.of(ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() })
    }

    private fun property(
        lines: List<String>,
        index: Int,
        name: String,
    ): String {
        val prefix = "$name="
        val line = lines.getOrNull(index) ?: invalid("tooling bundle property is missing: $name")
        if (!line.startsWith(prefix)) invalid("tooling bundle property is noncanonical: $name")
        return line.removePrefix(prefix)
    }

    private fun invalid(message: String): Nothing = throw ToolingBundleException(message)
}

fun ToolingProfileManifest.canonicalText(): String =
    buildString {
        appendLine("format=${ToolingBundleManifest.FORMAT}")
        appendLine("kind=$kind")
        identityProperties.forEach { (name, value) -> appendLine("identity.$name=$value") }
        appendLine("mainClass=$mainClass")
        appendLine("payloadSha256=${payloadHash.hex()}")
        classpath.forEach { path -> appendLine("classpath=$path") }
    }

private fun validateToolingPath(path: String) {
    require(path.length in 1..1024) { "tooling path length is invalid" }
    require('\\' !in path && '\u0000' !in path && !path.startsWith('/')) { "tooling path contains a forbidden character" }
    val parts = path.split('/')
    require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "tooling path is not canonical" }
    require(parts.size == 3 && parts[0] in TOOLING_ROOTS && parts[1] == "lib" && parts[2].endsWith(".jar")) {
        "tooling entries must be jars below a fixed library root"
    }
}

private fun validateToolingText(
    description: String,
    value: String,
) {
    require(value.isNotBlank() && value.length <= 1024) { "$description is invalid" }
    require(value.none { it == '\u0000' || it == '\r' || it == '\n' }) { "$description contains a forbidden character" }
}

private fun hashProfile(
    kind: String,
    identityProperties: Map<String, String>,
    mainClass: String,
    classpath: List<String>,
    files: Map<String, ToolingBundleFile>,
): Sha256 {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("Compukters tooling profile v1\u0000".encodeToByteArray())
    digest.field(kind)
    identityProperties.forEach { (name, value) ->
        digest.field(name)
        digest.field(value)
    }
    digest.field(mainClass)
    classpath.forEach { path ->
        val file = files[path] ?: throw IllegalArgumentException("tooling classpath references an undeclared file")
        digest.field(path)
        digest.update(file.bytes.toolingLittleEndian())
        digest.update(file.sha256.toByteArray())
    }
    return Sha256.of(digest.digest())
}

private fun hashBundle(
    format: UInt,
    files: List<ToolingBundleFile>,
    profiles: Map<String, ToolingProfileManifest>,
): Sha256 {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("Compukters tooling bundle v1\u0000".encodeToByteArray())
    digest.update(format.toolingLittleEndian())
    files.forEach { file ->
        digest.field(file.path)
        digest.update(file.bytes.toolingLittleEndian())
        digest.update(file.sha256.toByteArray())
    }
    profiles.forEach { (kind, profile) ->
        digest.field(kind)
        digest.update(profile.payloadHash.toByteArray())
    }
    return Sha256.of(digest.digest())
}

private fun MessageDigest.field(value: String) {
    val bytes = value.encodeToByteArray()
    update(bytes.size.toUInt().toolingLittleEndian())
    update(bytes)
}

private fun UInt.toolingLittleEndian(): ByteArray =
    ByteBuffer
        .allocate(UInt.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(toInt())
        .array()

private fun Long.toolingLittleEndian(): ByteArray =
    ByteBuffer
        .allocate(Long.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(this)
        .array()

internal const val TOOLING_BUNDLE_MANIFEST_FILE = "tooling.bundle"
private val TOOLING_ROOTS = setOf("common", "compiler", "analysis")
private val REQUIRED_PROFILES = setOf("analysis", "compiler")
private val REQUIRED_DOCUMENTS = setOf(TOOLING_BUNDLE_MANIFEST_FILE, "manifests/analysis.payload", "manifests/compiler.payload")
