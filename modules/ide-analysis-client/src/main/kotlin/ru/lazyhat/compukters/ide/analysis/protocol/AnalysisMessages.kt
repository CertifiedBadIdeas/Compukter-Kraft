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

package ru.lazyhat.compukters.ide.analysis.protocol

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

const val ANALYSIS_PROTOCOL_VERSION: UInt = 7u

data class AnalysisWorkerIdentity(
    val compilerVersion: String,
    val languageVersion: String,
    val payloadHash: Hash256,
    val platformAbi: Hash256,
) {
    init {
        validateWireText("compiler version", compilerVersion)
        validateWireText("language version", languageVersion)
    }
}

enum class AnalysisFeature {
    Presentation,
    Completion,
    ExpressionInfo,
    Declaration,
    References,
    Format,
}

data class AdmittedAnalysisModule(
    val identity: AnalysisModuleIdentity,
)

class AdmittedAnalysisPlatform(
    val abi: Hash256,
    modules: List<AdmittedAnalysisModule>,
    val sourceRoot: String? = null,
) {
    val modules: List<AdmittedAnalysisModule> = immutableCopy(modules)

    init {
        require(modules.size <= ProtocolLimits.MAX_MODULES) { "analysis module count exceeds protocol limit" }
        requireCanonicalModules(this.modules)
        sourceRoot?.let { validateRoot("platform source root", it) }
    }

    override fun equals(other: Any?): Boolean =
        other is AdmittedAnalysisPlatform && abi == other.abi && modules == other.modules && sourceRoot == other.sourceRoot

    override fun hashCode(): Int = listOf(abi, modules, sourceRoot).hashCode()
}

class AdmittedAnalysisProfile(
    val identity: AnalysisProfileIdentity,
    val platform: AdmittedAnalysisPlatform,
) {
    override fun equals(other: Any?): Boolean = other is AdmittedAnalysisProfile && identity == other.identity && platform == other.platform

    override fun hashCode(): Int = 31 * identity.hashCode() + platform.hashCode()
}

sealed interface AnalysisMessage

class AnalysisHandshake(
    val protocol: UInt,
    val workerIdentity: AnalysisWorkerIdentity,
    features: Set<AnalysisFeature>,
    val limits: AnalysisLimits,
) : AnalysisMessage {
    val features: Set<AnalysisFeature> = Collections.unmodifiableSet(features.toSet())

    init {
        require(protocol == ANALYSIS_PROTOCOL_VERSION) { "unsupported analysis protocol version" }
    }

    override fun equals(other: Any?): Boolean =
        other is AnalysisHandshake &&
            protocol == other.protocol &&
            workerIdentity == other.workerIdentity &&
            features == other.features &&
            limits == other.limits

    override fun hashCode(): Int = listOf(protocol, workerIdentity, features, limits).hashCode()
}

data class OpenSnapshotRequest(
    val requestId: RequestId,
    val identity: AnalysisSnapshotIdentity,
    val sources: ProjectSnapshot,
    val profile: AdmittedAnalysisProfile,
    val limits: AnalysisLimits,
) : AnalysisMessage {
    init {
        require(SourceSnapshotIdentity.of(sources) == identity.source) { "source snapshot identity does not match sources" }
        require(profile.identity == identity.profile) { "analysis profile identity does not match snapshot" }
        require(sources.sources.size <= limits.sourceFiles) { "source count exceeds analysis limit" }
        require(sources.totalSourceBytes <= limits.sourceBytes.toLong()) { "source bytes exceed analysis limit" }
        require(sources.sources.all { it.content.size <= limits.sourceFileBytes }) { "source file exceeds analysis limit" }
        sources.sources.forEach { source -> validateProtocolSourcePath(source.path.value) }
        require(profile.platform.modules.size <= limits.modules) { "module count exceeds analysis limit" }
    }
}

data class AnalysisQueryRequest(
    val requestId: RequestId,
    val query: AnalysisQuery,
) : AnalysisMessage

class UpdateSnapshotRequest(
    val requestId: RequestId,
    val baseIdentity: AnalysisSnapshotIdentity,
    val targetIdentity: AnalysisSnapshotIdentity,
    changedSources: List<ProjectSource>,
) : AnalysisMessage {
    val changedSources: List<ProjectSource> = immutableSourceCopy(changedSources)

    init {
        require(baseIdentity != targetIdentity) { "snapshot update identities must differ" }
        require(baseIdentity.profile == targetIdentity.profile) { "snapshot update profile identities must match" }
        require(this.changedSources.isNotEmpty()) { "snapshot update must contain a changed source" }
        require(this.changedSources.size <= ProtocolLimits.MAX_SOURCE_FILES) { "changed source count exceeds protocol limit" }
        requireCanonicalSources(this.changedSources)
        var totalBytes = 0L
        this.changedSources.forEach { source ->
            require(source.content.size <= ProtocolLimits.MAX_SOURCE_FILE_BYTES) { "changed source exceeds protocol limit" }
            requireStrictUtf8(source.content)
            totalBytes = Math.addExact(totalBytes, source.content.size.toLong())
            require(totalBytes <= ProtocolLimits.MAX_SOURCE_BYTES) { "changed sources exceed protocol limit" }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is UpdateSnapshotRequest &&
            requestId == other.requestId &&
            baseIdentity == other.baseIdentity &&
            targetIdentity == other.targetIdentity &&
            changedSources == other.changedSources

    override fun hashCode(): Int = listOf(requestId, baseIdentity, targetIdentity, changedSources).hashCode()
}

data class SnapshotUpdated(
    val requestId: RequestId,
    val targetIdentity: AnalysisSnapshotIdentity,
) : AnalysisMessage

data class SnapshotReopenRequired(
    val requestId: RequestId,
    val targetIdentity: AnalysisSnapshotIdentity,
    val reason: String,
) : AnalysisMessage {
    init {
        require(reason.isNotEmpty()) { "snapshot reopen reason must not be empty" }
        require(strictUtf8Size(reason) <= ProtocolLimits.MAX_TEXT_BYTES) { "snapshot reopen reason exceeds protocol limit" }
    }
}

data class CancelAnalysisRequest(
    val requestId: RequestId,
) : AnalysisMessage

data class CloseSnapshotRequest(
    val requestId: RequestId,
    val identity: AnalysisSnapshotIdentity,
) : AnalysisMessage

data class SnapshotReady(
    val requestId: RequestId,
    val identity: AnalysisSnapshotIdentity,
) : AnalysisMessage

data class SnapshotClosed(
    val requestId: RequestId,
    val identity: AnalysisSnapshotIdentity,
) : AnalysisMessage

data class AnalysisQuerySuccess(
    val requestId: RequestId,
    val result: AnalysisResult,
) : AnalysisMessage

data class AnalysisCancelled(
    val requestId: RequestId,
    val identity: AnalysisSnapshotIdentity?,
) : AnalysisMessage

enum class AnalysisFailureKind {
    Startup,
    Protocol,
    InvalidSnapshot,
    UnsupportedFeature,
    Timeout,
    MemoryLimit,
    Cancelled,
    OutputLimit,
    InternalAnalysis,
    WorkerExit,
}

data class AnalysisFailure(
    val requestId: RequestId,
    val identity: AnalysisSnapshotIdentity?,
    val failure: AnalysisFailureKind,
    val detail: String,
) : AnalysisMessage {
    init {
        require(detail.isNotEmpty()) { "analysis failure detail must not be empty" }
        require(strictUtf8Size(detail) <= ProtocolLimits.MAX_TEXT_BYTES) { "analysis failure detail exceeds protocol limit" }
    }
}

private fun validateRoot(
    label: String,
    value: String,
) {
    require(value.isNotEmpty()) { "$label must not be empty" }
    require('\u0000' !in value) { "$label contains NUL" }
    require(strictUtf8Size(value) <= ProtocolLimits.MAX_PATH_BYTES) { "$label exceeds protocol limit" }
}

private fun validateWireText(
    label: String,
    value: String,
) {
    require(value.isNotEmpty()) { "$label must not be empty" }
    require(strictUtf8Size(value) <= ProtocolLimits.MAX_TEXT_BYTES) { "$label exceeds protocol limit" }
}

internal fun strictUtf8Size(value: String): Int =
    try {
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
            .remaining()
    } catch (exception: CharacterCodingException) {
        throw IllegalArgumentException("protocol text must be strict UTF-8", exception)
    }

private fun requireStrictUtf8(value: BinaryValue) {
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value.toByteArray()))
    } catch (exception: CharacterCodingException) {
        throw IllegalArgumentException("changed source must be strict UTF-8", exception)
    }
}

internal fun validateProtocolSourcePath(value: String) {
    require(strictUtf8Size(value) <= ProtocolLimits.MAX_PATH_BYTES) { "virtual source path exceeds protocol limit" }
}

private fun requireCanonicalModules(modules: List<AdmittedAnalysisModule>) {
    modules.zipWithNext().forEach { (left, right) ->
        require(compareUnsigned(left.identity.name.encodeToByteArray(), right.identity.name.encodeToByteArray()) < 0) {
            "analysis modules must be uniquely ordered by UTF-8 name"
        }
    }
}

private fun requireCanonicalSources(sources: List<ProjectSource>) {
    sources.forEach { source -> validateProtocolSourcePath(source.path.value) }
    sources.zipWithNext().forEach { (left, right) ->
        require(compareUnsigned(left.path.value.encodeToByteArray(), right.path.value.encodeToByteArray()) < 0) {
            "changed sources must be uniquely ordered by UTF-8 path"
        }
    }
}

private fun compareUnsigned(
    left: ByteArray,
    right: ByteArray,
): Int {
    repeat(minOf(left.size, right.size)) { index ->
        val result = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
        if (result != 0) return result
    }
    return left.size.compareTo(right.size)
}

private fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

private fun immutableSourceCopy(values: List<ProjectSource>): List<ProjectSource> =
    Collections.unmodifiableList(
        values.map { source -> ProjectSource(source.path, BinaryValue.of(source.content.toByteArray())) },
    )
