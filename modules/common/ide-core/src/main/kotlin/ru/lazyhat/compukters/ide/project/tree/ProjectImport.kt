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

package ru.lazyhat.compukters.ide.project.tree

import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.TomlSupport
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.util.Collections

enum class ProjectImportStep {
    STAGED,
    BEFORE_PUBLISH,
    EXISTING_BACKED_UP,
    PUBLISHED,
}

sealed interface ProjectImportEntry {
    val relativePath: String

    data class Directory(
        override val relativePath: String,
    ) : ProjectImportEntry

    class File(
        override val relativePath: String,
        bytes: ByteArray,
    ) : ProjectImportEntry {
        private val content = bytes.copyOf()

        fun bytes(): ByteArray = content.copyOf()

        internal fun ownedBytes(): ByteArray = content

        override fun equals(other: Any?): Boolean =
            other is File && relativePath == other.relativePath && content.contentEquals(other.content)

        override fun hashCode(): Int = 31 * relativePath.hashCode() + content.contentHashCode()
    }
}

class ProjectImport private constructor(
    val destination: ProjectPath,
    val replace: Boolean,
    entries: List<ProjectImportEntry>,
) {
    val entries: List<ProjectImportEntry> = Collections.unmodifiableList(entries.toList())

    fun replacingExisting(): ProjectImport = if (replace) this else ProjectImport(destination, true, entries)

    companion object {
        fun admit(
            destination: ProjectPath,
            replace: Boolean,
            entries: List<ProjectImportEntry>,
            limits: ProjectLimits,
        ): ProjectImport {
            require(entries.isNotEmpty()) { "project import must not be empty" }
            require(entries.size <= limits.treeEntries) { "project import exceeds entry limit" }
            val owned =
                entries.map { entry ->
                    val path = ProjectPath.file(entry.relativePath)
                    when (entry) {
                        is ProjectImportEntry.Directory -> ProjectImportEntry.Directory(path.value)
                        is ProjectImportEntry.File -> ProjectImportEntry.File(path.value, entry.bytes())
                    }
                }
            val paths = owned.map { ProjectPath.file(it.relativePath) }
            val rootNames = paths.map { it.components.first() }.toSet()
            require(rootNames.size == 1) { "project import must contain one top-level subtree" }
            val rootName = rootNames.single()
            require(paths.first().value == rootName) { "project import root must be first" }
            require(destination.components.last() == rootName) { "project import root must match destination name" }
            require(paths.map { it.value }.toSet().size == paths.size) { "project import paths must be unique" }
            require(paths.map { it.value } == paths.map { it.value }.sortedWith(TomlSupport.utf8Comparator)) {
                "project import paths must use deterministic parent-first order"
            }

            val directories = mutableSetOf<String>()
            var aggregateBytes = 0L
            var metadataBytes = 0L
            owned.zip(paths).forEach { (entry, relative) ->
                val parent = relative.components.dropLast(1).joinToString("/")
                require(parent.isEmpty() || parent in directories) { "project import parents must precede children" }
                if (entry is ProjectImportEntry.Directory) directories += relative.value

                val projected =
                    (destination.components.dropLast(1) + relative.components).joinToString("/")
                val projectedPath = ProjectPath.file(projected)
                require(projectedPath.components.size <= limits.treeDepth) { "project import exceeds depth limit" }
                val pathBytes = TomlSupport.strictUtf8(projectedPath.value).size
                require(pathBytes <= limits.pathUtf8Bytes) { "project import path exceeds byte limit" }
                metadataBytes = Math.addExact(metadataBytes, pathBytes.toLong() + PROJECT_TREE_ENTRY_METADATA_BYTES)
                require(metadataBytes <= limits.treeMetadataBytes) { "project import exceeds metadata limit" }

                if (entry is ProjectImportEntry.File) {
                    val bytes = entry.ownedBytes().size
                    require(bytes <= limits.projectFileBytes) { "project import file exceeds byte limit" }
                    aggregateBytes = Math.addExact(aggregateBytes, bytes.toLong())
                    require(aggregateBytes <= limits.projectBytes) { "project import exceeds aggregate byte limit" }
                }
            }
            return ProjectImport(destination, replace, owned)
        }
    }
}
