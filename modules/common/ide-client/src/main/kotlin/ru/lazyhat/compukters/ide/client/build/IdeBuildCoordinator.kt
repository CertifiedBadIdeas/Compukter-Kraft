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

package ru.lazyhat.compukters.ide.client.build

import ru.lazyhat.compukters.compiler.worker.controller.WorkerQueueFullException
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailureClass
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.controller.IdeControllerClock
import ru.lazyhat.compukters.ide.client.workspace.IdeBuildInput
import ru.lazyhat.compukters.ide.compiler.ClientBuildResult
import ru.lazyhat.compukters.ide.compiler.ClientBuildSnapshot
import ru.lazyhat.compukters.ide.compiler.ClientCompilationService
import ru.lazyhat.compukters.ide.compiler.ClientCompileRequestFactory
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfileResolver
import ru.lazyhat.compukters.ide.compiler.profile.ProfileResolution
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ProjectDependencyReceipt
import ru.lazyhat.compukters.ide.project.ProjectDependencyRollback
import ru.lazyhat.compukters.ide.project.ProjectDependencyService
import ru.lazyhat.compukters.ide.project.ProjectDependencyUpdate
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.ProjectLockCodec
import ru.lazyhat.compukters.ide.project.ProjectLockService
import ru.lazyhat.compukters.ide.project.ProjectManifestCodec
import ru.lazyhat.compukters.ide.project.ProjectResolution
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun interface ProjectLockServiceFactory {
    fun create(project: ProjectHandle): ProjectLockService
}

class IdeBuildServices(
    val localResolution: ProjectResolution,
    val profileResolver: CompileProfileResolver,
    val lockServices: ProjectLockServiceFactory,
    val compilation: ClientCompilationService,
)

class IdeBuildJob internal constructor(
    val started: CompletableFuture<IdeBuildState.Compiling>,
    val result: CompletableFuture<IdeBuildState>,
    private val cancellation: () -> Boolean,
) {
    fun cancel(): Boolean = cancellation()
}

class IdeBuildCoordinator(
    private val services: IdeBuildServices,
    private val clock: IdeControllerClock,
    limits: IdeClientLimits = IdeClientLimits(),
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val builds = mutableSetOf<BuildControl>()
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(limits.buildPreparationQueue),
            namedDaemonThreadFactory(),
            ThreadPoolExecutor.AbortPolicy(),
        )

    fun resolve(
        input: IdeBuildInput,
        updateExisting: Boolean,
        target: TargetCompileProfile? = null,
    ): CompletableFuture<IdeResolveResult> =
        submit(
            action = { resolveNow(input, updateExisting, target) },
            rejected = IdeResolveResult.Failed(if (closed.get()) "build coordinator is closed" else "build queue is full"),
        )

    fun build(
        operationId: Long,
        input: IdeBuildInput,
        target: TargetCompileProfile? = null,
    ): IdeBuildJob {
        require(operationId >= 0) { "build operation ID must be non-negative" }
        val control = BuildControl()
        synchronized(builds) { builds += control }
        val task =
            try {
                executor.submit { beginBuild(operationId, input, target, control) }
            } catch (_: RejectedExecutionException) {
                control.result.complete(
                    IdeBuildState.Failed(
                        if (closed.get()) IdeBuildFailureKind.Closed else IdeBuildFailureKind.QueueFull,
                        if (closed.get()) "build coordinator is closed" else "build queue is full",
                    ),
                )
                null
            }
        control.preparation.set(task)
        control.result.whenComplete { _, _ ->
            if (!control.started.isDone) {
                control.started.completeExceptionally(IllegalStateException("build did not reach compilation"))
            }
            synchronized(builds) { builds -= control }
        }
        return IdeBuildJob(control.started, control.result) { cancel(control) }
    }

    fun enableModule(
        project: ProjectHandle,
        id: ModuleId,
        major: ApiMajor,
        target: TargetCompileProfile? = null,
    ): CompletableFuture<ProjectDependencyUpdate> =
        submit(
            action = {
                ProjectDependencyService(project, services.localResolution).enableModule(id, major) { proposed ->
                    val admitted = target?.let { services.profileResolver.resolveTarget(proposed, it) }
                    if (admitted == null || admitted is ProfileResolution.Resolved) {
                        null
                    } else {
                        "target compile profile is unsatisfied: ${admitted::class.simpleName}"
                    }
                }
            },
            rejected =
                ProjectDependencyUpdate.Conflict(
                    if (closed.get()) "build coordinator is closed" else "build queue is full",
                ),
        )

    fun rollbackModule(
        project: ProjectHandle,
        receipt: ProjectDependencyReceipt,
    ): CompletableFuture<ProjectDependencyRollback> =
        submit(
            action = { rollbackModuleNow(project, receipt) },
            rejected =
                ProjectDependencyRollback.Conflict(
                    if (closed.get()) "build coordinator is closed" else "build queue is full",
                ),
        )

    internal fun rollbackModuleNow(
        project: ProjectHandle,
        receipt: ProjectDependencyReceipt,
    ): ProjectDependencyRollback = ProjectDependencyService(project, services.localResolution).rollback(receipt)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = synchronized(builds) { builds.toList() }
        active.forEach(::cancel)
        executor.shutdownNow()
        services.compilation.close()
    }

    private fun resolveNow(
        input: IdeBuildInput,
        updateExisting: Boolean,
        target: TargetCompileProfile?,
    ): IdeResolveResult =
        try {
            val manifest = ProjectManifestCodec.decode(decodeStrict(input.manifestBytes))
            val resolution = services.localResolution
            val lockService = services.lockServices.create(input.project)
            val proposed = lockService.resolve(manifest, resolution)
            if (target != null) {
                val admitted = services.profileResolver.resolveTarget(proposed, target)
                if (admitted !is ProfileResolution.Resolved) {
                    return IdeResolveResult.Failed("target compile profile is unsatisfied: ${admitted::class.simpleName}")
                }
            }
            val proposedBytes = ProjectLockCodec.encode(proposed).encodeToByteArray()
            val current = input.lockBytes
            when {
                current == null -> {
                    lockService.createLock(manifest, resolution)
                    IdeResolveResult.Created
                }

                current.contentEquals(proposedBytes) -> {
                    IdeResolveResult.UpToDate
                }

                !updateExisting -> {
                    IdeResolveResult.ConfirmationRequired
                }

                else -> {
                    lockService.updateLock(manifest, resolution)
                    IdeResolveResult.Updated
                }
            }
        } catch (failure: Throwable) {
            IdeResolveResult.Failed(detail(failure))
        }

    private fun beginBuild(
        operationId: Long,
        input: IdeBuildInput,
        target: TargetCompileProfile?,
        control: BuildControl,
    ) {
        if (control.cancelled.get()) return
        val prepared =
            try {
                prepare(input, target)
            } catch (failure: BuildPreparationFailure) {
                control.result.complete(IdeBuildState.Failed(failure.kind, detail(failure)))
                return
            } catch (failure: Throwable) {
                control.result.complete(IdeBuildState.Failed(IdeBuildFailureKind.Platform, detail(failure)))
                return
            }
        if (control.cancelled.get()) return
        control.started.complete(
            IdeBuildState.Compiling(operationId, prepared.identity, prepared.sourceSnapshotId),
        )
        val compilation =
            try {
                services.compilation.build(prepared.snapshot)
            } catch (failure: Throwable) {
                control.result.complete(mapFailure(failure))
                return
            }
        control.compilation.set(compilation)
        if (control.cancelled.get()) services.compilation.cancel(compilation)
        compilation.whenComplete { result, failure ->
            if (control.result.isDone) return@whenComplete
            if (failure != null) {
                control.result.complete(mapFailure(failure))
            } else {
                try {
                    control.result.complete(mapResult(checkNotNull(result), prepared))
                } catch (mappingFailure: Throwable) {
                    control.result.complete(IdeBuildState.Failed(IdeBuildFailureKind.Platform, detail(mappingFailure)))
                }
            }
        }
    }

    private fun prepare(
        input: IdeBuildInput,
        target: TargetCompileProfile?,
    ): PreparedBuild {
        val manifest =
            try {
                ProjectManifestCodec.decode(decodeStrict(input.manifestBytes))
            } catch (failure: Throwable) {
                throw BuildPreparationFailure(IdeBuildFailureKind.InvalidManifest, "invalid manifest", failure)
            }
        val lockBytes = input.lockBytes ?: throw BuildPreparationFailure(IdeBuildFailureKind.MissingLock, "project lock is missing")
        val lock =
            try {
                ProjectLockCodec.decode(decodeStrict(lockBytes))
            } catch (failure: Throwable) {
                throw BuildPreparationFailure(IdeBuildFailureKind.InvalidLock, "invalid project lock", failure)
            }
        val resolution =
            try {
                services.localResolution
            } catch (failure: Throwable) {
                throw BuildPreparationFailure(IdeBuildFailureKind.UnsatisfiedProfile, "local Guest API profile is unsatisfied", failure)
            }
        val mismatches = services.lockServices.create(input.project).validate(manifest, lock, resolution)
        if (mismatches.isNotEmpty()) {
            throw BuildPreparationFailure(IdeBuildFailureKind.UnsatisfiedProfile, "project lock does not match the local profile")
        }
        val profile =
            when (
                val resolved =
                    target?.let { services.profileResolver.resolveTarget(lock, it) } ?: services.profileResolver.resolveLocal(lock)
            ) {
                is ProfileResolution.Resolved -> {
                    resolved.profile
                }

                is ProfileResolution.Failure -> {
                    throw BuildPreparationFailure(
                        IdeBuildFailureKind.UnsatisfiedProfile,
                        "local compile profile is unsatisfied: ${resolved::class.simpleName}",
                    )
                }
            }
        val snapshot =
            ClientBuildSnapshot(
                input.sources,
                BinaryValue.of(input.manifestBytes),
                BinaryValue.of(lockBytes),
                profile,
            )
        val prepared = ClientCompileRequestFactory.prepare(snapshot)
        return PreparedBuild(snapshot, prepared.identity, prepared.sourceSnapshotId, manifest.name)
    }

    private fun mapResult(
        result: ClientBuildResult,
        prepared: PreparedBuild,
    ): IdeBuildState =
        when (result) {
            is ClientBuildResult.Success -> {
                if (result.identity != prepared.identity) {
                    IdeBuildState.Failed(IdeBuildFailureKind.Platform, "compiler returned a mismatched build identity")
                } else {
                    IdeBuildState.Succeeded(
                        result.identity,
                        IdeBuiltArtifact.admit(result.artifactHash, result.artifact),
                        prepared.programName,
                        result.cacheHit,
                        clock.nowMillis().coerceAtLeast(0),
                    )
                }
            }

            is ClientBuildResult.Diagnostics -> {
                if (result.identity != prepared.identity) {
                    IdeBuildState.Failed(IdeBuildFailureKind.Platform, "compiler returned a mismatched build identity")
                } else {
                    IdeBuildState.Diagnostics(
                        result.identity,
                        prepared.sourceSnapshotId,
                        diagnostics(result.values, prepared.snapshot),
                    )
                }
            }

            is ClientBuildResult.PlatformFailure -> {
                val kind =
                    if (result.failureClass == PlatformFailureClass.CANCELLED) {
                        IdeBuildFailureKind.Cancelled
                    } else {
                        IdeBuildFailureKind.Platform
                    }
                IdeBuildState.Failed(kind, result.detail)
            }
        }

    private fun diagnostics(
        values: List<WorkerDiagnostic>,
        snapshot: ClientBuildSnapshot,
    ): List<EditorDiagnostic> {
        val sourceLengths =
            snapshot.sources.sources.associate { source ->
                source.path to decodeStrict(source.content.toByteArray()).length
            }
        return values.take(snapshot.profile.limits.diagnostics).map { value ->
            val path = value.path?.takeIf(sourceLengths::containsKey)
            val range = admittedRange(value, path?.let(sourceLengths::get))
            EditorDiagnostic(
                when (value.severity) {
                    DiagnosticSeverity.INFO -> EditorDiagnosticSeverity.Info
                    DiagnosticSeverity.WARNING -> EditorDiagnosticSeverity.Warning
                    DiagnosticSeverity.ERROR -> EditorDiagnosticSeverity.Error
                },
                boundedMessage(value.message, snapshot.profile.limits.diagnosticTextBytes),
                path,
                range,
            )
        }
    }

    private fun admittedRange(
        value: WorkerDiagnostic,
        sourceLength: Int?,
    ): EditorRange? {
        val start = value.startUtf16?.toLong() ?: return null
        val end = value.endUtf16?.toLong() ?: return null
        if (sourceLength == null || start < 0 || end <= start || end > sourceLength || end > Int.MAX_VALUE) return null
        return EditorRange(start.toInt(), end.toInt())
    }

    private fun cancel(control: BuildControl): Boolean {
        if (control.result.isDone || !control.cancelled.compareAndSet(false, true)) return false
        control.preparation.get()?.cancel(false)
        control.compilation.get()?.let(services.compilation::cancel)
        control.result.complete(IdeBuildState.Failed(IdeBuildFailureKind.Cancelled, "client compilation cancelled"))
        return true
    }

    private fun mapFailure(failure: Throwable): IdeBuildState.Failed {
        val actual = unwrap(failure)
        return IdeBuildState.Failed(
            when (actual) {
                is WorkerQueueFullException,
                is RejectedExecutionException,
                -> IdeBuildFailureKind.QueueFull

                else -> if (closed.get()) IdeBuildFailureKind.Closed else IdeBuildFailureKind.Platform
            },
            detail(actual),
        )
    }

    private fun <T> submit(
        action: () -> T,
        rejected: T,
    ): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        try {
            executor.execute {
                try {
                    result.complete(action())
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        } catch (_: RejectedExecutionException) {
            result.complete(rejected)
        }
        return result
    }

    private class BuildControl {
        val started = CompletableFuture<IdeBuildState.Compiling>()
        val result = CompletableFuture<IdeBuildState>()
        val cancelled = AtomicBoolean()
        val preparation = AtomicReference<Future<*>?>()
        val compilation = AtomicReference<CompletableFuture<ClientBuildResult>?>()
    }

    private data class PreparedBuild(
        val snapshot: ClientBuildSnapshot,
        val identity: ru.lazyhat.compukters.compiler.worker.protocol.Hash256,
        val sourceSnapshotId: ru.lazyhat.compukters.ide.analysis.SourceSnapshotId,
        val programName: String,
    )

    private class BuildPreparationFailure(
        val kind: IdeBuildFailureKind,
        message: String,
        cause: Throwable? = null,
    ) : IllegalArgumentException(message, cause)
}

private fun decodeStrict(bytes: ByteArray): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

private fun boundedMessage(
    value: String,
    maximumBytes: Int,
): String {
    val admitted = value.takeIf(String::isWellFormedUtf16)?.ifEmpty { null } ?: "Compiler diagnostic"
    if (maximumBytes <= 0) return "Compiler diagnostic"
    val result = StringBuilder()
    var offset = 0
    var bytes = 0
    while (offset < admitted.length) {
        val codePoint = admitted.codePointAt(offset)
        val scalar = String(Character.toChars(codePoint))
        val scalarBytes = scalar.encodeToByteArray().size
        if (bytes + scalarBytes > maximumBytes) break
        result.append(scalar)
        bytes += scalarBytes
        offset += Character.charCount(codePoint)
    }
    return result.toString().ifEmpty { "Compiler diagnostic" }
}

private fun unwrap(failure: Throwable): Throwable =
    when (failure) {
        is java.util.concurrent.CompletionException,
        is java.util.concurrent.ExecutionException,
        -> failure.cause ?: failure

        else -> failure
    }

private fun detail(failure: Throwable): String =
    (failure.message ?: failure::class.simpleName ?: "build failed")
        .takeIf(String::isWellFormedUtf16)
        ?.take(4 * 1024)
        ?: "build failed"

private fun namedDaemonThreadFactory(): ThreadFactory =
    ThreadFactory { task -> Thread(task, "compukters-ide-build").apply { isDaemon = true } }

private fun String.isWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        val value = this[index]
        when {
            Character.isHighSurrogate(value) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                index += 2
            }

            Character.isLowSurrogate(value) -> {
                return false
            }

            else -> {
                index++
            }
        }
    }
    return true
}
