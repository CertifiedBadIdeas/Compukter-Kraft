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

package ru.lazyhat.compukters.compiler.runtime

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits

data class CompilerTarget(
    val owner: Long,
    val vmEpoch: Long,
    val token: Long,
) {
    init {
        require(owner != 0L && vmEpoch > 0 && token != 0L) { "compiler target values must be non-zero" }
    }
}

data class CompilerServiceConfiguration(
    val workerIdentity: WorkerIdentity,
    val limits: WorkerLimits,
    val target: TargetSettings = TargetSettings.KOTLIN_2_4_JVM_17,
    val platformModules: List<TrustedBundleIdentity> = emptyList(),
)

data class CompilerServicePolicy(
    val maximumOutstandingTargets: Int = 256,
    val maximumWaitersPerIdentity: Int = 64,
    val maximumDistinctCompilations: Int = 16,
    val maximumSnapshotBytes: Long = 256L * 1024,
    val maximumQueuedSnapshotBytes: Long = 16L * 1024 * 1024,
    val maximumCompletionArtifactBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(maximumOutstandingTargets > 0) { "outstanding compiler target limit must be positive" }
        require(maximumWaitersPerIdentity > 0) { "compiler waiter limit must be positive" }
        require(maximumDistinctCompilations > 0) { "distinct compilation limit must be positive" }
        require(maximumSnapshotBytes > 0) { "compiler snapshot byte limit must be positive" }
        require(maximumQueuedSnapshotBytes >= maximumSnapshotBytes) {
            "queued compiler snapshot byte limit must cover one snapshot"
        }
        require(maximumCompletionArtifactBytes > 0) { "compiler completion artifact byte limit must be positive" }
    }
}

enum class CompilerSubmissionResult { ACCEPTED, BUSY, CLOSED }

sealed interface CompilerOutcome {
    class Success(
        artifact: ByteArray,
        val artifactHash: Hash256,
        val cacheHit: Boolean,
    ) : CompilerOutcome {
        val artifact = BinaryValue.of(artifact)
    }

    data class Rejected(
        val result: CompileResult,
    ) : CompilerOutcome

    data class PlatformFailure(
        val detail: String,
    ) : CompilerOutcome

    data object Busy : CompilerOutcome
}

data class CompilerCompletion(
    val target: CompilerTarget,
    val outcome: CompilerOutcome,
)

interface CompilerServicePort {
    fun submit(
        target: CompilerTarget,
        snapshot: ProjectSnapshot,
    ): CompilerSubmissionResult

    fun drain(maximum: Int): List<CompilerCompletion>

    fun cancel(target: CompilerTarget): Boolean
}
