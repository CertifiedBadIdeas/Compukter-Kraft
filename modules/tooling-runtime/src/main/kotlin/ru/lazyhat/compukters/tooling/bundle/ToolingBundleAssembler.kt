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

package ru.lazyhat.compukters.tooling.bundle

import ru.lazyhat.compukters.worker.payload.ToolingBundleManifest
import ru.lazyhat.compukters.worker.payload.ToolingProfileDefinition
import ru.lazyhat.compukters.worker.payload.WorkerPayloadLoader
import ru.lazyhat.compukters.worker.value.Sha256
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes

object ToolingBundleAssembler {
    fun assemble(
        compilerRoot: Path,
        analysisRoot: Path,
        outputRoot: Path,
    ): ToolingBundleManifest {
        val inputs =
            linkedMapOf(
                COMPILER to load(COMPILER, compilerRoot),
                ANALYSIS to load(ANALYSIS, analysisRoot),
            )
        val destination = outputRoot.toAbsolutePath().normalize()
        requireEmptyDestination(destination)

        val entries = inputs.values.flatMap(ProfileInput::entries)
        rejectDuplicateContentWithinProfiles(entries)
        rejectMixedCompilerDistributions(entries)
        val sharedKeys =
            entries
                .groupBy(InputEntry::contentKey)
                .filterValues { group -> group.map(InputEntry::profile).toSet() == PROFILES }
                .keys
        val groups =
            entries
                .groupBy { entry -> groupKey(entry, sharedKeys) }
                .entries
                .sortedWith(
                    compareBy<Map.Entry<GroupKey, List<InputEntry>>>(
                        { it.key.profile ?: "" },
                        {
                            it.key.content.sha256
                                .hex()
                        },
                        { it.key.content.role },
                        { it.key.content.bytes },
                    ),
                )
        val proposedNames = groups.associate { (key, group) -> key to group.minOf { it.source.name } }
        val collidingNames =
            proposedNames.entries
                .groupBy(Map.Entry<GroupKey, String>::value)
                .filterValues { it.size > 1 }
                .keys
        val outputByInput = mutableMapOf<InputCoordinate, String>()
        val outputFiles = linkedMapOf<String, ByteArray>()
        groups.forEach { groupEntry ->
            val group = groupEntry.value
            val proposed = proposedNames.getValue(groupEntry.key)
            val filename = if (proposed in collidingNames) qualify(proposed, groupEntry.key.content.sha256) else proposed
            val directory = groupEntry.key.profile ?: COMMON
            val outputPath = "$directory/lib/$filename"
            val bytes = normalizeJar(group.first().source)
            check(outputFiles.put(outputPath, bytes) == null) { "tooling output path collision: $outputPath" }
            group.forEach { entry -> outputByInput[InputCoordinate(entry.profile, entry.inputPath)] = outputPath }
        }

        val definitions =
            inputs.mapValues { (kind, input) ->
                ToolingProfileDefinition(
                    identityProperties = input.identityProperties,
                    mainClass = input.mainClass,
                    classpath = input.entries.map { entry -> outputByInput.getValue(InputCoordinate(kind, entry.inputPath)) },
                )
            }
        val manifest = ToolingBundleManifest.create(outputFiles, definitions)
        writeBundle(destination, outputFiles, manifest, inputs.values.map(ProfileInput::root))
        return manifest
    }

    private fun load(
        expectedKind: String,
        root: Path,
    ): ProfileInput {
        val payload = WorkerPayloadLoader.load(root)
        require(payload.manifest.kind == expectedKind) { "expected $expectedKind worker payload" }
        val entries =
            payload.manifest.files.zip(payload.classpath).map { (record, source) ->
                require(record.path.endsWith(".jar")) { "worker payload contains a non-JAR runtime file" }
                InputEntry(
                    profile = expectedKind,
                    inputPath = record.path,
                    source = source,
                    contentKey = ContentKey(record.bytes, record.sha256, role(source.name)),
                )
            }
        return ProfileInput(payload.root, payload.manifest.identityProperties, payload.manifest.mainClass, entries)
    }

    private fun role(filename: String): String =
        when {
            "kotlinx-coroutines" !in filename -> GENERIC_ROLE
            "intellij" in filename -> INTELLIJ_COROUTINES_ROLE
            else -> ORDINARY_COROUTINES_ROLE
        }

    private fun normalizeJar(source: Path): ByteArray {
        val output = ByteArrayOutputStream()
        ZipFile(source.toFile()).use { jar ->
            val entries =
                jar
                    .entries()
                    .asSequence()
                    .filterNot(ZipEntry::isDirectory)
                    .sortedBy(ZipEntry::getName)
                    .toList()
            require(entries.map(ZipEntry::getName).distinct().size == entries.size) { "runtime JAR contains duplicate entries" }
            ZipOutputStream(output).use { normalized ->
                entries.forEach { sourceEntry ->
                    val bytes = jar.getInputStream(sourceEntry).use { it.readAllBytes() }
                    val checksum = CRC32().apply { update(bytes) }
                    val targetEntry =
                        ZipEntry(sourceEntry.name).apply {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            crc = checksum.value
                            setTimeLocal(FIXED_ZIP_TIME)
                        }
                    normalized.putNextEntry(targetEntry)
                    normalized.write(bytes)
                    normalized.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private fun groupKey(
        entry: InputEntry,
        sharedKeys: Set<ContentKey>,
    ): GroupKey = GroupKey(entry.profile.takeUnless { entry.contentKey in sharedKeys }, entry.contentKey)

    private fun rejectDuplicateContentWithinProfiles(entries: List<InputEntry>) {
        entries.groupBy { entry -> entry.profile to entry.contentKey }.forEach { (key, duplicates) ->
            require(duplicates.size == 1) { "${key.first} payload repeats the same runtime content" }
        }
    }

    private fun rejectMixedCompilerDistributions(entries: List<InputEntry>) {
        entries.groupBy(InputEntry::profile).forEach { (profile, profileEntries) ->
            val names = profileEntries.map { it.source.name }
            val hasEmbeddable = names.any { "kotlin-compiler-embeddable" in it }
            val hasOrdinary = names.any { it.startsWith("kotlin-compiler-") && "embeddable" !in it }
            require(!(hasEmbeddable && hasOrdinary)) { "$profile payload mixes embeddable and ordinary Kotlin compilers" }
        }
    }

    private fun requireEmptyDestination(destination: Path) {
        if (destination.exists()) {
            require(destination.isDirectory(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(destination)) {
                "tooling output is not a regular directory"
            }
            Files.list(destination).use { entries -> require(entries.findAny().isEmpty) { "tooling output directory is not empty" } }
        } else {
            destination.createDirectories()
        }
    }

    private fun writeBundle(
        destination: Path,
        files: Map<String, ByteArray>,
        manifest: ToolingBundleManifest,
        inputRoots: List<Path>,
    ) {
        (files + manifest.encodedFiles()).toSortedMap().forEach { (relative, bytes) ->
            val target = destination.resolve(relative)
            target.parent.createDirectories()
            Files.write(target, bytes)
        }
        copyMetadataUnion(inputRoots, destination)
    }

    private fun copyMetadataUnion(
        inputRoots: List<Path>,
        destination: Path,
    ) {
        inputRoots.forEach { root ->
            val metadata = root.resolve("META-INF")
            if (!metadata.isDirectory(LinkOption.NOFOLLOW_LINKS)) return@forEach
            Files.walk(metadata).use { paths ->
                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.sorted().forEach { source ->
                    val relative = metadata.relativize(source)
                    val target = destination.resolve("META-INF").resolve(relative)
                    target.parent.createDirectories()
                    if (target.exists()) {
                        require(Files.mismatch(source, target) == -1L) { "conflicting tooling metadata: $relative" }
                    } else {
                        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
        }
    }

    private fun qualify(
        filename: String,
        digest: Sha256,
    ): String {
        val suffix = digest.hex().take(12)
        return if (filename.endsWith(".jar")) "${filename.removeSuffix(".jar")}-$suffix.jar" else "$filename-$suffix"
    }

    private data class ProfileInput(
        val root: Path,
        val identityProperties: Map<String, String>,
        val mainClass: String,
        val entries: List<InputEntry>,
    )

    private data class InputEntry(
        val profile: String,
        val inputPath: String,
        val source: Path,
        val contentKey: ContentKey,
    )

    private data class ContentKey(
        val bytes: Long,
        val sha256: Sha256,
        val role: String,
    )

    private data class GroupKey(
        val profile: String?,
        val content: ContentKey,
    )

    private data class InputCoordinate(
        val profile: String,
        val path: String,
    )

    private const val COMPILER = "compiler"
    private const val ANALYSIS = "analysis"
    private const val COMMON = "common"
    private const val GENERIC_ROLE = "generic"
    private const val INTELLIJ_COROUTINES_ROLE = "intellij-coroutines"
    private const val ORDINARY_COROUTINES_ROLE = "ordinary-coroutines"
    private val PROFILES = setOf(COMPILER, ANALYSIS)
    private val FIXED_ZIP_TIME: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0, 2)
}
