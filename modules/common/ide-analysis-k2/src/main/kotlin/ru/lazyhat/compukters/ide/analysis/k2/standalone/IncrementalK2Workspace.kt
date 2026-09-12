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

package ru.lazyhat.compukters.ide.analysis.k2.standalone

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationLocality
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.psi.KtFile
import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.k2.query.GlobalCompletionIndex
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.UpdateSnapshotRequest
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.k2.CompuktersAnalysisPlatformContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path

internal class IncrementalK2Workspace(
    initialIdentity: AnalysisSnapshotIdentity,
    private val root: Path,
    private val environment: K2ProjectEnvironment,
    files: Map<VirtualSourcePath, KtFile>,
    initialSources: ProjectSnapshot,
    initialSourceLengthsUtf16: Map<VirtualSourcePath, Int>,
    moduleIdentities: Map<PlatformModuleId, AnalysisModuleIdentity>,
    platformSourceFiles: Map<VirtualSourcePath, KtFile>,
    private val platform: CompuktersAnalysisPlatformContext,
    private val sourceUpdater: K2SourceUpdater,
    private val projectCompletionIndex: GlobalCompletionIndex,
    private val platformCompletionIndex: GlobalCompletionIndex,
) : AutoCloseable {
    private var currentIdentity = initialIdentity
    private val files = files.toMap()
    private var sources = initialSources
    private var sourceLengthsUtf16 = initialSourceLengthsUtf16.toMap()
    private val moduleIdentities = moduleIdentities.toMap()
    private val platformSourceFiles = platformSourceFiles.toMap()
    private var poisoned = false
    private var closed = false

    val identity: AnalysisSnapshotIdentity
        get() {
            checkHealthy()
            return currentIdentity
        }

    fun view(): AdmittedK2Snapshot {
        checkHealthy()
        return AdmittedK2Snapshot(
            currentIdentity,
            environment,
            files,
            sourceLengthsUtf16,
            moduleIdentities,
            platformSourceFiles,
            platform,
            projectCompletionIndex,
            platformCompletionIndex,
        )
    }

    fun update(
        request: UpdateSnapshotRequest,
        limits: AnalysisLimits,
    ) {
        checkHealthy()
        require(request.baseIdentity == currentIdentity) { "snapshot update base identity is not active" }
        require(request.targetIdentity.profile == currentIdentity.profile) { "snapshot update profile identity changed" }
        require(request.changedSources.size <= limits.sourceFiles) { "changed source count exceeds analysis limit" }
        require(request.changedSources.all { it.path in files }) { "snapshot update contains an unknown source path" }
        require(request.changedSources.all { it.content.size <= limits.sourceFileBytes }) {
            "changed source file exceeds analysis limit"
        }
        require(request.changedSources.sumOf { it.content.size.toLong() } <= limits.sourceBytes) {
            "changed source bytes exceed analysis limit"
        }

        val changedSources = request.changedSources.associateBy(ProjectSource::path)
        val candidateSources =
            sources.sources.map { source ->
                changedSources[source.path] ?: source
            }
        val candidate = ProjectSnapshot.of(candidateSources, limits.workerLimits())
        require(SourceSnapshotIdentity.of(candidate) == request.targetIdentity.source) {
            "snapshot update target identity does not match candidate sources"
        }
        val changedTexts =
            request.changedSources.associate { source ->
                source.path to decodeStrict(source.content)
            }
        val candidateLengths = sourceLengthsUtf16.toMutableMap()
        changedTexts.forEach { (path, text) -> candidateLengths[path] = text.length }

        try {
            sourceUpdater.update(environment, files, changedTexts)
            ReadAction.run<RuntimeException> {
                changedTexts.forEach { (path, text) ->
                    val file = files.getValue(path)
                    check(file.text == text) { "updated Kotlin PSI differs from candidate source: ${path.value}" }
                    projectCompletionIndex.updateProjectFile(path, file)
                }
            }
        } catch (exception: Exception) {
            poisoned = true
            dispose()
            val detail =
                exception.message
                    ?.takeIf(String::isNotBlank)
                    ?.let { ": $it" }
                    .orEmpty()
            throw K2WorkspaceReopenRequiredException("incremental K2 workspace mutation failed$detail", exception)
        }

        sources = candidate
        sourceLengthsUtf16 = candidateLengths.toMap()
        currentIdentity = request.targetIdentity
    }

    override fun close() {
        dispose()
    }

    private fun checkHealthy() {
        check(!poisoned) { "K2 workspace requires a full reopen" }
        check(!closed) { "K2 workspace is closed" }
    }

    private fun dispose() {
        if (closed) return
        closed = true
        try {
            environment.close()
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

internal fun interface K2SourceUpdater {
    fun update(
        environment: K2ProjectEnvironment,
        files: Map<VirtualSourcePath, KtFile>,
        changedTexts: Map<VirtualSourcePath, String>,
    )
}

internal enum class K2SourceUpdatePhase {
    PsiSynchronization,
    SourceReindex,
    K2Invalidation,
}

internal data class K2SourceUpdatePhaseSample(
    val phase: K2SourceUpdatePhase,
    val durationNanos: Long,
)

internal fun interface K2SourceUpdateObserver {
    fun recorded(sample: K2SourceUpdatePhaseSample)
}

@OptIn(KaPlatformInterface::class)
internal object DocumentK2SourceUpdater : K2SourceUpdater {
    override fun update(
        environment: K2ProjectEnvironment,
        files: Map<VirtualSourcePath, KtFile>,
        changedTexts: Map<VirtualSourcePath, String>,
    ) = update(environment, files, changedTexts, clock = null, observer = null)

    fun measured(
        clock: () -> Long,
        observer: K2SourceUpdateObserver,
    ): K2SourceUpdater =
        K2SourceUpdater { environment, files, changedTexts ->
            update(environment, files, changedTexts, clock, observer)
        }

    private fun update(
        environment: K2ProjectEnvironment,
        files: Map<VirtualSourcePath, KtFile>,
        changedTexts: Map<VirtualSourcePath, String>,
        clock: (() -> Long)?,
        observer: K2SourceUpdateObserver?,
    ) {
        environment.session.application.runWriteAction {
            val documents =
                PsiDocumentManager.getInstance(environment.session.project) as StandalonePsiDocumentManager
            val modifications = KaSourceModificationService.getInstance(environment.session.project)
            changedTexts.forEach { (path, text) ->
                val file = files.getValue(path)
                val locality = modifications.detectLocality(file, KaElementModificationType.Unknown)
                check(locality is KaSourceModificationLocality.OutOfBlock) {
                    "whole-file modification was not classified as out-of-block"
                }
                val document = requireNotNull(documents.getDocument(file)) { "Kotlin PSI has no document: ${path.value}" }
                val baseline = StandaloneDocumentSynchronizer.capture(file)
                measure(K2SourceUpdatePhase.PsiSynchronization, clock, observer) {
                    CommandProcessor
                        .getInstance()
                        .executeCommand(
                            environment.session.project,
                            { document.replaceString(0, document.textLength, text) },
                            "Compukters incremental analysis update",
                            null,
                        )
                    documents.runStandaloneCommit {
                        StandaloneDocumentSynchronizer.synchronize(
                            document,
                            environment.session.project,
                            file,
                            baseline,
                        )
                    }
                }
                measure(K2SourceUpdatePhase.SourceReindex, clock, observer) {
                    environment.reindex(file)
                }
                measure(K2SourceUpdatePhase.K2Invalidation, clock, observer) {
                    modifications.handleInvalidation(file, locality)
                }
            }
        }
    }

    private inline fun measure(
        phase: K2SourceUpdatePhase,
        noinline clock: (() -> Long)?,
        observer: K2SourceUpdateObserver?,
        operation: () -> Unit,
    ) {
        if (observer == null || clock == null) {
            operation()
            return
        }
        val startedNanos = clock()
        operation()
        val durationNanos = clock() - startedNanos
        check(durationNanos >= 0) { "K2 source update phase duration must not be negative" }
        observer.recorded(K2SourceUpdatePhaseSample(phase, durationNanos))
    }
}

internal class K2WorkspaceReopenRequiredException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

private fun decodeStrict(value: BinaryValue): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value.toByteArray()))
        .toString()

private fun AnalysisLimits.workerLimits(): WorkerLimits =
    WorkerLimits(
        sourceFiles = sourceFiles,
        sourceFileBytes = sourceFileBytes,
        sourceBytes = sourceBytes,
    )
