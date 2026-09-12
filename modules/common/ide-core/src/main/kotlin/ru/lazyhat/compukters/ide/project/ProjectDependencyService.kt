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

package ru.lazyhat.compukters.ide.project

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.project.document.FileRevision
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.fs.SecureBatchReplacement
import ru.lazyhat.compukters.ide.project.fs.SecureBatchWriteResult
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFiles
import java.security.MessageDigest

sealed interface ProjectDependencyUpdate {
    data object AlreadyDirect : ProjectDependencyUpdate

    data class Published(
        val receipt: ProjectDependencyReceipt,
    ) : ProjectDependencyUpdate

    data class Conflict(
        val detail: String,
    ) : ProjectDependencyUpdate
}

sealed interface ProjectDependencyRollback {
    data object Restored : ProjectDependencyRollback

    data class Conflict(
        val detail: String,
    ) : ProjectDependencyRollback
}

class ProjectDependencyReceipt internal constructor(
    internal val captured: Map<ProjectPath, CapturedDependencyFile>,
    internal val published: Map<ProjectPath, FileRevision>,
)

internal data class CapturedDependencyFile(
    val content: ByteArray?,
    val revision: FileRevision,
    val maximumBytes: Int,
)

class ProjectDependencyService(
    private val handle: ProjectHandle,
    private val resolution: ProjectResolution,
    private val limits: ProjectLimits = ProjectLimits(),
) {
    fun enableModule(
        id: ModuleId,
        major: ApiMajor,
        validate: (ProjectLock) -> String? = { null },
    ): ProjectDependencyUpdate =
        try {
            val captured = capture()
            val manifestFile = captured.getValue(MANIFEST)
            val manifestBytes = manifestFile.content ?: return ProjectDependencyUpdate.Conflict("project manifest is missing")
            val manifest = ProjectManifestCodec.decode(TomlSupport.decodeStrictUtf8(manifestBytes), limits)
            manifest.modules[id]?.let { existing ->
                return if (existing == major) {
                    ProjectDependencyUpdate.AlreadyDirect
                } else {
                    ProjectDependencyUpdate.Conflict("module ${id.value} already requires API ${existing.value}")
                }
            }
            val proposed = ProjectManifest.of(manifest.name, manifest.modules + (id to major), limits)
            val lock = ProjectLockService(NOOP_LOCK_WRITER).resolve(proposed, resolution)
            validate(lock)?.let { return ProjectDependencyUpdate.Conflict(it) }
            val replacements =
                listOf(
                    SecureBatchReplacement(
                        MANIFEST,
                        manifestFile.revision,
                        ProjectManifestCodec.encode(proposed).encodeToByteArray(),
                        limits.manifestBytes,
                    ),
                    SecureBatchReplacement(
                        LOCK,
                        captured.getValue(LOCK).revision,
                        ProjectLockCodec.encode(lock).encodeToByteArray(),
                        limits.lockBytes,
                    ),
                )
            when (val result = SecureProjectFiles.replaceBatch(handle.identity, replacements)) {
                is SecureBatchWriteResult.Conflict -> {
                    ProjectDependencyUpdate.Conflict("${result.path.value} changed before dependency publication")
                }

                is SecureBatchWriteResult.Published -> {
                    ProjectDependencyUpdate.Published(ProjectDependencyReceipt(captured, result.revisions))
                }
            }
        } catch (failure: Exception) {
            ProjectDependencyUpdate.Conflict(failure.message ?: "dependency update failed")
        }

    fun rollback(receipt: ProjectDependencyReceipt): ProjectDependencyRollback =
        try {
            val replacements =
                receipt.captured.map { (path, captured) ->
                    SecureBatchReplacement(path, receipt.published.getValue(path), captured.content, captured.maximumBytes)
                }
            when (val result = SecureProjectFiles.replaceBatch(handle.identity, replacements)) {
                is SecureBatchWriteResult.Published -> {
                    ProjectDependencyRollback.Restored
                }

                is SecureBatchWriteResult.Conflict -> {
                    ProjectDependencyRollback.Conflict("${result.path.value} changed after dependency publication")
                }
            }
        } catch (failure: Exception) {
            ProjectDependencyRollback.Conflict(failure.message ?: "dependency rollback failed")
        }

    private fun capture(): Map<ProjectPath, CapturedDependencyFile> =
        linkedMapOf(
            MANIFEST to capture(MANIFEST, limits.manifestBytes),
            LOCK to capture(LOCK, limits.lockBytes),
        )

    private fun capture(
        path: ProjectPath,
        maximumBytes: Int,
    ): CapturedDependencyFile {
        val content = SecureProjectFiles.readFile(handle.identity, path, maximumBytes)
        val revision = content?.let { FileRevision.Present(hash(it)) } ?: FileRevision.Absent
        return CapturedDependencyFile(content?.copyOf(), revision, maximumBytes)
    }

    private fun hash(content: ByteArray): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(content))

    private companion object {
        val MANIFEST = ProjectPath.direct("compukter.toml")
        val LOCK = ProjectPath.direct("compukter.lock")
        val NOOP_LOCK_WRITER =
            object : LockFileWriter {
                override fun create(content: ByteArray) = Unit

                override fun update(content: ByteArray) = Unit
            }
    }
}
