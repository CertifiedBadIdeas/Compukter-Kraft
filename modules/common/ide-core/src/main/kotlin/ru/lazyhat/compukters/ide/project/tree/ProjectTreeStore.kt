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

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.TomlSupport
import ru.lazyhat.compukters.ide.project.document.FileRevision
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.fs.SecureImportResult
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFileException
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFiles
import java.nio.charset.CharacterCodingException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.security.MessageDigest

class ProjectTreeStore(
    private val handle: ProjectHandle,
    private val limits: ProjectLimits = ProjectLimits(),
    private val importHook: (ProjectImportStep) -> Unit = {},
) {
    fun scan(): ProjectTree = scan(ignoreImportArtifacts = false)

    private fun scan(ignoreImportArtifacts: Boolean): ProjectTree =
        SecureProjectFiles.withValidProject(handle.identity) { root ->
            val state = ScanState()
            scanDirectory(root, emptyList(), state, ignoreImportArtifacts)
            if (!SecureProjectFiles.isValid(handle.identity)) {
                throw SecureProjectFileException("project root was invalidated while scanning")
            }
            ProjectTree.of(state.entries)
        }

    fun createText(path: ProjectPath): ProjectMutationResult =
        mutate(path) {
            val current = scan()
            if (current.find(path) != null) return@mutate ProjectMutationResult.Conflict(path)
            validateCreate(current, path)
            if (!SecureProjectFiles.createFile(handle.identity, path)) return@mutate ProjectMutationResult.Conflict(path)
            ProjectMutationResult.Changed(scan())
        }

    fun createDirectory(path: ProjectPath): ProjectMutationResult =
        mutate(path) {
            val current = scan()
            if (current.find(path) != null) return@mutate ProjectMutationResult.Conflict(path)
            validateCreate(current, path)
            if (!SecureProjectFiles.createDirectory(handle.identity, path)) return@mutate ProjectMutationResult.Conflict(path)
            ProjectMutationResult.Changed(scan())
        }

    fun rename(
        source: ProjectPath,
        target: ProjectPath,
    ): ProjectMutationResult =
        mutate(source) {
            val current = scan()
            val sourceEntry = current.find(source) ?: return@mutate ProjectMutationResult.Conflict(source)
            if (current.find(target) != null) return@mutate ProjectMutationResult.Conflict(target)
            if (target.value.startsWith("${source.value}/")) return@mutate ProjectMutationResult.Conflict(target)
            requireParentDirectory(current, target)
            validateRename(current, sourceEntry, target)
            if (!SecureProjectFiles.move(handle.identity, source, target)) {
                return@mutate ProjectMutationResult.Conflict(target)
            }
            ProjectMutationResult.Changed(scan())
        }

    fun admitDelete(path: ProjectPath): AdmittedProjectDelete =
        synchronized(handle.identity) {
            val tree = scan()
            if (tree.find(path) == null) throw SecureProjectFileException("project entry does not exist: $path")
            val revisions =
                tree
                    .flatten()
                    .filter { it.path == path || it.path.value.startsWith("${path.value}/") }
                    .associateTo(linkedMapOf()) { it.path to it.revision }
            AdmittedProjectDelete.create(path, handle.identity, revisions)
        }

    fun delete(admitted: AdmittedProjectDelete): ProjectMutationResult =
        synchronized(handle.identity) {
            if (!handle.isValid() || admitted.rootIdentity != handle.identity) {
                return@synchronized ProjectMutationResult.ProjectInvalidated
            }
            val current =
                try {
                    scan()
                } catch (_: SecureProjectFileException) {
                    if (handle.isValid()) {
                        return@synchronized ProjectMutationResult.Conflict(admitted.path)
                    }
                    return@synchronized ProjectMutationResult.ProjectInvalidated
                }
            val currentRevisions =
                current
                    .flatten()
                    .filter { it.path == admitted.path || it.path.value.startsWith("${admitted.path.value}/") }
                    .associateTo(linkedMapOf()) { it.path to it.revision }
            val conflict = firstDeleteConflict(admitted.revisions, currentRevisions)
            if (conflict != null) return@synchronized ProjectMutationResult.Conflict(conflict)

            admitted.revisions.entries
                .sortedByDescending { it.key.components.size }
                .forEach { (path, revision) ->
                    val deleted =
                        if (revision == null) {
                            SecureProjectFiles.deleteEmptyDirectory(handle.identity, path)
                        } else {
                            SecureProjectFiles.deleteFile(handle.identity, path, revision as FileRevision.Present, limits.projectFileBytes)
                        }
                    if (!deleted) return@synchronized ProjectMutationResult.Conflict(path)
                }
            ProjectMutationResult.Changed(scan())
        }

    fun importTree(import: ProjectImport): ProjectMutationResult =
        mutate(import.destination) {
            val current = scan()
            val existing = current.find(import.destination)
            if ((existing != null) != import.replace) return@mutate ProjectMutationResult.Conflict(import.destination)
            requireParentDirectory(current, import.destination)
            val expected = current.subtreeRevisions(import.destination)
            when (
                val result =
                    SecureProjectFiles.importTree(
                        handle.identity,
                        import,
                        expectedStillCurrent = { scan(ignoreImportArtifacts = true).subtreeRevisions(import.destination) == expected },
                        validatePublished = { scan(ignoreImportArtifacts = true) },
                        hook = importHook,
                    )
            ) {
                SecureImportResult.Conflict -> ProjectMutationResult.Conflict(import.destination)
                is SecureImportResult.Published -> ProjectMutationResult.Changed(result.value)
            }
        }

    private fun scanDirectory(
        directory: SecureDirectoryStream<Path>,
        parent: List<String>,
        state: ScanState,
        ignoreImportArtifacts: Boolean,
    ) {
        val names =
            buildList {
                directory.forEach { entry ->
                    val name = entry.fileName
                    SecureProjectFiles.validateFilename(name)
                    add(name.toString())
                }
            }.filterNot { ignoreImportArtifacts && SecureProjectFiles.isImportArtifactName(it) }
                .sortedWith(TomlSupport.utf8Comparator)

        names.forEach { name ->
            val components = parent + name
            if (components.size > limits.treeDepth) throw SecureProjectFileException("project tree exceeds depth limit")
            val path = ProjectPath.file(components.joinToString("/"))
            val pathBytes = TomlSupport.strictUtf8(path.value).size
            if (pathBytes > limits.pathUtf8Bytes) throw SecureProjectFileException("project path exceeds byte limit")
            state.admitMetadata(pathBytes)

            val target = Path.of(name)
            val attributes = SecureProjectFiles.attributes(directory, target)
            if (attributes.isSymbolicLink) throw SecureProjectFileException("project tree contains a symbolic link: $path")
            when {
                attributes.isDirectory -> {
                    state.entries += ProjectTreeEntry(path, ProjectFileKind.Directory, revision = null)
                    directory.newDirectoryStream(target, LinkOption.NOFOLLOW_LINKS).use { child ->
                        scanDirectory(child, components, state, ignoreImportArtifacts)
                    }
                }

                attributes.isRegularFile -> {
                    if (attributes.size() > limits.projectFileBytes.toLong()) {
                        throw SecureProjectFileException("project file exceeds byte limit: $path")
                    }
                    state.requireFileBytes(attributes.size())
                    val bytes = SecureProjectFiles.readBytes(directory, target, limits.projectFileBytes)
                    state.admitFileBytes(bytes.size)
                    val kind =
                        try {
                            TomlSupport.decodeStrictUtf8(bytes)
                            ProjectFileKind.Text(bytes.size)
                        } catch (_: CharacterCodingException) {
                            ProjectFileKind.Binary(bytes.size.toLong())
                        }
                    state.entries +=
                        ProjectTreeEntry(
                            path = path,
                            kind = kind,
                            revision = FileRevision.Present(hash(bytes)),
                        )
                }

                else -> {
                    throw SecureProjectFileException("project tree contains a special file: $path")
                }
            }
        }
    }

    private fun validateCreate(
        tree: ProjectTree,
        path: ProjectPath,
    ) {
        requireParentDirectory(tree, path)
        validateProjectedPaths(tree.flatten().map { it.path } + path)
    }

    private fun validateRename(
        tree: ProjectTree,
        source: ProjectTreeEntry,
        target: ProjectPath,
    ) {
        val sourcePrefix = "${source.path.value}/"
        val moved = tree.flatten().filter { it.path == source.path || it.path.value.startsWith(sourcePrefix) }
        val untouched = tree.flatten().filterNot(moved::contains).map { it.path }
        val projected =
            moved.map { entry ->
                val suffix = entry.path.value.removePrefix(source.path.value)
                ProjectPath.file(target.value + suffix)
            }
        if (projected.any { candidate -> untouched.any { it == candidate } }) {
            throw SecureProjectFileException("renamed subtree would overwrite an existing entry")
        }
        validateProjectedPaths(untouched + projected)
    }

    private fun validateProjectedPaths(paths: List<ProjectPath>) {
        if (paths.size > limits.treeEntries) throw SecureProjectFileException("project tree exceeds entry limit")
        var metadataBytes = 0L
        paths.forEach { path ->
            if (path.components.size > limits.treeDepth) throw SecureProjectFileException("project tree exceeds depth limit")
            val pathBytes = TomlSupport.strictUtf8(path.value).size
            if (pathBytes > limits.pathUtf8Bytes) throw SecureProjectFileException("project path exceeds byte limit")
            metadataBytes = Math.addExact(metadataBytes, pathBytes.toLong() + PROJECT_TREE_ENTRY_METADATA_BYTES)
        }
        if (metadataBytes > limits.treeMetadataBytes.toLong()) {
            throw SecureProjectFileException("project tree exceeds metadata byte limit")
        }
    }

    private fun requireParentDirectory(
        tree: ProjectTree,
        path: ProjectPath,
    ) {
        if (path.components.size == 1) return
        val parent = ProjectPath.file(path.components.dropLast(1).joinToString("/"))
        if (tree.find(parent)?.kind != ProjectFileKind.Directory) {
            throw SecureProjectFileException("project entry parent is not a directory: $parent")
        }
    }

    private fun mutate(
        conflictPath: ProjectPath,
        operation: () -> ProjectMutationResult,
    ): ProjectMutationResult =
        synchronized(handle.identity) {
            if (!handle.isValid()) return@synchronized ProjectMutationResult.ProjectInvalidated
            try {
                operation()
            } catch (_: FileAlreadyExistsException) {
                ProjectMutationResult.Conflict(conflictPath)
            } catch (_: NoSuchFileException) {
                if (handle.isValid()) ProjectMutationResult.Conflict(conflictPath) else ProjectMutationResult.ProjectInvalidated
            } catch (exception: SecureProjectFileException) {
                if (handle.isValid()) throw exception else ProjectMutationResult.ProjectInvalidated
            }
        }

    private inner class ScanState {
        val entries = mutableListOf<ProjectTreeEntry>()
        private var metadataBytes = 0L
        private var fileBytes = 0L

        fun admitMetadata(pathBytes: Int) {
            if (entries.size >= limits.treeEntries) throw SecureProjectFileException("project tree exceeds entry limit")
            metadataBytes = Math.addExact(metadataBytes, pathBytes.toLong() + PROJECT_TREE_ENTRY_METADATA_BYTES)
            if (metadataBytes > limits.treeMetadataBytes.toLong()) {
                throw SecureProjectFileException("project tree exceeds metadata byte limit")
            }
        }

        fun admitFileBytes(bytes: Int) {
            fileBytes = Math.addExact(fileBytes, bytes.toLong())
            if (fileBytes > limits.projectBytes) throw SecureProjectFileException("project exceeds total byte limit")
        }

        fun requireFileBytes(bytes: Long) {
            if (bytes > limits.projectBytes - fileBytes) {
                throw SecureProjectFileException("project exceeds total byte limit")
            }
        }
    }

    private companion object {
        fun hash(content: ByteArray): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(content))
    }
}

internal const val PROJECT_TREE_ENTRY_METADATA_BYTES = 48L

private fun ProjectTree.find(path: ProjectPath): ProjectTreeEntry? = flatten().singleOrNull { it.path == path }

private fun ProjectTree.subtreeRevisions(path: ProjectPath): Map<ProjectPath, FileRevision?> =
    flatten()
        .filter { it.path == path || it.path.value.startsWith("${path.value}/") }
        .associateTo(linkedMapOf()) { it.path to it.revision }

private fun firstDeleteConflict(
    expected: Map<ProjectPath, FileRevision?>,
    actual: Map<ProjectPath, FileRevision?>,
): ProjectPath? {
    expected.forEach { (path, revision) ->
        if (!actual.containsKey(path) || actual[path] != revision) return path
    }
    if (actual.size != expected.size) return expected.keys.minByOrNull { it.components.size }
    return null
}
