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

package ru.lazyhat.compukters.ide.project.document

import ru.lazyhat.compukters.compiler.project.ProjectSnapshotLoader
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.TomlSupport
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFiles
import ru.lazyhat.compukters.ide.project.fs.SecureWriteResult
import ru.lazyhat.compukters.ide.project.tree.PROJECT_TREE_ENTRY_METADATA_BYTES
import ru.lazyhat.compukters.ide.project.tree.ProjectFileKind
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeStore
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest

class ProjectDocumentException(
    message: String,
    cause: Throwable? = null,
    val reason: ProjectDocumentFailure = ProjectDocumentFailure.IO,
) : IllegalStateException(message, cause)

enum class ProjectDocumentFailure {
    MISSING,
    BINARY,
    IO,
}

class ProjectDocumentStore internal constructor(
    private val handle: ProjectHandle,
    private val limits: ProjectLimits,
    private val writeHook: (ProjectWriteStep) -> Unit,
) {
    constructor(
        handle: ProjectHandle,
        limits: ProjectLimits = ProjectLimits(),
    ) : this(handle, limits, {})

    fun open(path: ProjectPath): DocumentSnapshot =
        try {
            val content =
                SecureProjectFiles.readFile(handle.identity, path, limits.projectFileBytes)
                    ?: throw ProjectDocumentException(
                        "project file does not exist: ${path.value}",
                        reason = ProjectDocumentFailure.MISSING,
                    )
            val text =
                try {
                    TomlSupport.decodeStrictUtf8(content)
                } catch (exception: CharacterCodingException) {
                    throw ProjectDocumentException(
                        "project file is binary: ${path.value}",
                        exception,
                        ProjectDocumentFailure.BINARY,
                    )
                }
            DocumentSnapshot(path, text, FileRevision.Present(hash(content)))
        } catch (exception: ProjectDocumentException) {
            throw exception
        } catch (exception: Exception) {
            throw ProjectDocumentException("failed to open project file: ${path.value}", exception)
        }

    fun save(
        path: ProjectPath,
        expected: FileRevision,
        text: String,
    ): DocumentSaveResult {
        if (!handle.isValid()) return DocumentSaveResult.ProjectInvalidated
        return try {
            val content =
                try {
                    TomlSupport.strictUtf8(text)
                } catch (exception: Exception) {
                    throw ProjectDocumentException("project text must be strict UTF-8", exception)
                }
            if (content.size > limits.projectFileBytes) throw ProjectDocumentException("project file exceeds byte limit")
            if (path.isKotlinSource && content.size > limits.sourceFileBytes) {
                throw ProjectDocumentException("source exceeds per-file byte limit")
            }

            val currentBytes = SecureProjectFiles.readFile(handle.identity, path, limits.projectFileBytes)
            val currentRevision = currentBytes?.let { FileRevision.Present(hash(it)) } ?: FileRevision.Absent
            if (currentRevision != expected) return DocumentSaveResult.Conflict(expected, currentRevision)
            validateProjectBounds(path, content.size, currentBytes?.size)

            when (
                val result =
                    SecureProjectFiles.writeFile(
                        handle.identity,
                        path,
                        expected,
                        content,
                        limits.projectFileBytes,
                        writeHook,
                    )
            ) {
                is SecureWriteResult.Saved -> {
                    DocumentSaveResult.Saved(DocumentSnapshot(path, text, result.revision))
                }

                is SecureWriteResult.Conflict -> {
                    DocumentSaveResult.Conflict(expected, result.actual)
                }
            }
        } catch (exception: ProjectDocumentException) {
            throw exception
        } catch (exception: Exception) {
            if (!handle.isValid()) {
                DocumentSaveResult.ProjectInvalidated
            } else {
                throw ProjectDocumentException("failed to save project file: ${path.value}", exception)
            }
        }
    }

    private fun validateProjectBounds(
        path: ProjectPath,
        newBytes: Int,
        oldBytes: Int?,
    ) {
        val tree = ProjectTreeStore(handle, limits).scan()
        val existingEntry = tree.flatten().singleOrNull { it.path == path }
        if (existingEntry?.kind == ProjectFileKind.Directory) {
            throw ProjectDocumentException("project file path names a directory: ${path.value}")
        }
        check((oldBytes == null) == (existingEntry == null)) { "project tree changed during save admission" }
        val entryCount = tree.flatten().size + if (existingEntry == null) 1 else 0
        if (entryCount > limits.treeEntries) throw ProjectDocumentException("project tree exceeds entry limit")
        if (path.components.size > limits.treeDepth) throw ProjectDocumentException("project path exceeds depth limit")
        val pathBytes = TomlSupport.strictUtf8(path.value).size
        if (pathBytes > limits.pathUtf8Bytes) throw ProjectDocumentException("project path exceeds byte limit")
        val metadataBytes =
            tree.flatten().sumOf {
                TomlSupport.strictUtf8(it.path.value).size.toLong() + PROJECT_TREE_ENTRY_METADATA_BYTES
            } +
                if (existingEntry == null) {
                    pathBytes.toLong() + PROJECT_TREE_ENTRY_METADATA_BYTES
                } else {
                    0L
                }
        if (metadataBytes > limits.treeMetadataBytes.toLong()) {
            throw ProjectDocumentException("project tree exceeds metadata byte limit")
        }
        val currentProjectBytes =
            tree.flatten().sumOf { entry ->
                when (val kind = entry.kind) {
                    ProjectFileKind.Directory -> 0L
                    is ProjectFileKind.Text -> kind.utf8Bytes.toLong()
                    is ProjectFileKind.Binary -> kind.bytes
                }
            }
        val projectBytes = Math.addExact(currentProjectBytes - (oldBytes ?: 0), newBytes.toLong())
        if (projectBytes > limits.projectBytes) throw ProjectDocumentException("project exceeds total byte limit")

        if (!path.isKotlinSource) return
        val snapshot =
            ProjectSnapshotLoader.loadSourceSet(
                handle.canonicalPath,
                WorkerLimits(
                    sourceFiles = limits.sourceFiles,
                    sourceFileBytes = limits.sourceFileBytes,
                    sourceBytes = limits.sourceBytes,
                ),
            )
        val existingSource = snapshot.sources.singleOrNull { it.path.value == path.value }
        check((oldBytes == null) == (existingSource == null)) { "source set changed during save admission" }
        val newCount = snapshot.sources.size + if (existingSource == null) 1 else 0
        if (newCount > limits.sourceFiles) throw ProjectDocumentException("project source count exceeds limit")
        val total = Math.addExact(snapshot.totalSourceBytes - (oldBytes ?: 0), newBytes.toLong())
        if (total > limits.sourceBytes.toLong()) throw ProjectDocumentException("project source bytes exceed limit")
    }

    private fun hash(content: ByteArray): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(content))
}
