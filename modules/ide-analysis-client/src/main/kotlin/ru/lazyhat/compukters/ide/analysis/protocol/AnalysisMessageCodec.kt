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
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisResultLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.CompletionSymbol
import ru.lazyhat.compukters.ide.analysis.CompletionTextEdit
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.EditorExpressionInfo
import ru.lazyhat.compukters.ide.analysis.EditorParameterInfo
import ru.lazyhat.compukters.ide.analysis.EditorPresentationLimits
import ru.lazyhat.compukters.ide.analysis.ParameterInfoItem
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.analysis.SemanticToken
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentation
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance
import ru.lazyhat.compukters.ide.analysis.SourceLocation
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipFile

class AnalysisProtocolContext private constructor(
    internal val sourceLengthsUtf16: Map<VirtualSourcePath, Int>?,
    internal val platformSourceLengthsUtf16: Map<AnalysisModuleIdentity, Map<VirtualSourcePath, Int>>,
    internal val limits: AnalysisLimits,
    private val validatePositions: Boolean,
    internal val expectedQuery: AnalysisQuery?,
) {
    internal fun validate(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ) {
        VirtualSourcePath.kotlin(path.value)
        if (!validatePositions) return
        val lengths = requireNotNull(sourceLengthsUtf16) { "analysis protocol context has no open snapshot" }
        val sourceLength = requireNotNull(lengths[path]) { "analysis path does not belong to the open snapshot" }
        require(offsetUtf16 <= sourceLength) { "analysis offset exceeds its source" }
    }

    internal fun validate(
        path: VirtualSourcePath,
        range: EditorRange,
    ) {
        validate(path, range.endUtf16)
    }

    internal fun requireSourceLengths(): Map<VirtualSourcePath, Int> =
        sourceLengthsUtf16 ?: throw IllegalArgumentException("analysis protocol context has no open snapshot")

    fun forQuery(query: AnalysisQuery): AnalysisProtocolContext {
        require(validatePositions) { "an unchecked analysis context cannot correlate a query" }
        validateQuery(query, this)
        return AnalysisProtocolContext(sourceLengthsUtf16, platformSourceLengthsUtf16, limits, true, query)
    }

    internal fun isUnchecked(): Boolean = !validatePositions

    companion object {
        fun of(
            snapshot: ProjectSnapshot,
            limits: AnalysisLimits = AnalysisLimits(),
        ): AnalysisProtocolContext {
            val lengths =
                snapshot.sources.associate { source ->
                    validateProtocolSourcePath(source.path.value)
                    source.path to decodeStrictUtf8(source.content).length
                }
            return AnalysisProtocolContext(lengths, emptyMap(), limits, true, null)
        }

        fun of(
            snapshot: ProjectSnapshot,
            profile: AdmittedAnalysisProfile,
            limits: AnalysisLimits = AnalysisLimits(),
        ): AnalysisProtocolContext {
            val project = of(snapshot, limits)
            return AnalysisProtocolContext(
                project.sourceLengthsUtf16,
                loadPlatformSourceLengths(profile, limits),
                limits,
                true,
                null,
            )
        }

        internal fun unchecked(limits: AnalysisLimits = AnalysisLimits()): AnalysisProtocolContext =
            AnalysisProtocolContext(null, emptyMap(), limits, false, null)

        fun unbound(limits: AnalysisLimits = AnalysisLimits()): AnalysisProtocolContext =
            AnalysisProtocolContext(null, emptyMap(), limits, false, null)
    }
}

private fun loadPlatformSourceLengths(
    profile: AdmittedAnalysisProfile,
    limits: AnalysisLimits,
): Map<AnalysisModuleIdentity, Map<VirtualSourcePath, Int>> {
    val sourceRoot = profile.platform.sourceRoot ?: return emptyMap()
    var sourceCount = 0
    var totalBytes = 0L
    val path = Path.of(sourceRoot)
    require(path.isAbsolute && path.normalize() == path) { "platform source root must be absolute and normalized" }
    require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        "platform source root is missing or is not a regular file"
    }
    val lengths = linkedMapOf<VirtualSourcePath, Int>()
    ZipFile(path.toFile()).use { archive ->
        archive.entries().asSequence().filterNot { it.isDirectory }.filter { it.name.endsWith(".kt") }.forEach { entry ->
            require(sourceCount < limits.sourceFiles) { "platform source count exceeds analysis limit" }
            val bytes = archive.getInputStream(entry).use { it.readNBytes(limits.sourceFileBytes + 1) }
            require(bytes.size <= limits.sourceFileBytes) { "platform source file exceeds analysis limit" }
            sourceCount += 1
            totalBytes += bytes.size
            require(totalBytes <= limits.sourceBytes) { "platform source bytes exceed analysis limit" }
            val sourcePath = VirtualSourcePath.kotlin(entry.name)
            require(lengths.put(sourcePath, decodeStrictUtf8(BinaryValue.of(bytes)).length) == null) {
                "duplicate platform source path: ${entry.name}"
            }
        }
    }
    val immutableLengths = lengths.toMap()
    return profile.platform.modules.associate { module -> module.identity to immutableLengths }
}

object AnalysisMessageCodec {
    fun encode(
        message: AnalysisMessage,
        context: AnalysisProtocolContext,
    ): AnalysisFrame {
        validateForWire(message, context)
        val sink = MessageSink()
        val type =
            when (message) {
                is AnalysisHandshake -> {
                    sink.u32(message.protocol)
                    sink.workerIdentity(message.workerIdentity)
                    sink.u64(message.features.fold(0uL) { bits, feature -> bits or (1uL shl feature.ordinal) })
                    sink.limits(message.limits)
                    AnalysisMessageType.Handshake
                }

                is OpenSnapshotRequest -> {
                    sink.requestId(message.requestId)
                    sink.identity(message.identity)
                    sink.limits(message.limits)
                    sink.sources(message.sources)
                    sink.profile(message.profile)
                    AnalysisMessageType.OpenSnapshot
                }

                is UpdateSnapshotRequest -> {
                    sink.requestId(message.requestId)
                    sink.identity(message.baseIdentity)
                    sink.identity(message.targetIdentity)
                    sink.changedSources(message.changedSources)
                    AnalysisMessageType.UpdateSnapshot
                }

                is SnapshotReady -> {
                    sink.snapshotResponse(message.requestId, message.identity, AnalysisMessageType.SnapshotReady)
                }

                is SnapshotUpdated -> {
                    sink.snapshotResponse(message.requestId, message.targetIdentity, AnalysisMessageType.SnapshotUpdated)
                }

                is SnapshotReopenRequired -> {
                    sink.requestId(message.requestId)
                    sink.identity(message.targetIdentity)
                    sink.string(message.reason)
                    AnalysisMessageType.SnapshotReopenRequired
                }

                is AnalysisQueryRequest -> {
                    sink.requestId(message.requestId)
                    sink.query(message.query)
                    AnalysisMessageType.Query
                }

                is AnalysisQuerySuccess -> {
                    sink.requestId(message.requestId)
                    sink.result(message.result)
                    AnalysisMessageType.QuerySuccess
                }

                is CancelAnalysisRequest -> {
                    sink.requestId(message.requestId)
                    AnalysisMessageType.Cancel
                }

                is AnalysisCancelled -> {
                    sink.requestId(message.requestId)
                    sink.nullableIdentity(message.identity)
                    AnalysisMessageType.Cancelled
                }

                is CloseSnapshotRequest -> {
                    sink.snapshotResponse(message.requestId, message.identity, AnalysisMessageType.CloseSnapshot)
                }

                is SnapshotClosed -> {
                    sink.snapshotResponse(message.requestId, message.identity, AnalysisMessageType.SnapshotClosed)
                }

                is AnalysisFailure -> {
                    sink.requestId(message.requestId)
                    sink.nullableIdentity(message.identity)
                    sink.enum(message.failure)
                    sink.string(message.detail)
                    AnalysisMessageType.Failure
                }
            }
        return AnalysisFrame(type, sink.result())
    }

    fun decode(
        frame: AnalysisFrame,
        context: AnalysisProtocolContext,
    ): AnalysisMessage {
        val source = MessageSource(frame.payload, context)
        val message =
            try {
                when (frame.type) {
                    AnalysisMessageType.Handshake -> {
                        AnalysisHandshake(source.u32Bits(), source.workerIdentity(), source.features(), source.limits())
                    }

                    AnalysisMessageType.OpenSnapshot -> {
                        source.openSnapshot()
                    }

                    AnalysisMessageType.UpdateSnapshot -> {
                        source.updateSnapshot()
                    }

                    AnalysisMessageType.SnapshotReady -> {
                        SnapshotReady(source.requestId(), source.identity())
                    }

                    AnalysisMessageType.SnapshotUpdated -> {
                        SnapshotUpdated(source.requestId(), source.identity())
                    }

                    AnalysisMessageType.SnapshotReopenRequired -> {
                        SnapshotReopenRequired(source.requestId(), source.identity(), source.string(context.limits.detailTextBytes))
                    }

                    AnalysisMessageType.Query -> {
                        AnalysisQueryRequest(source.requestId(), source.query())
                    }

                    AnalysisMessageType.QuerySuccess -> {
                        AnalysisQuerySuccess(source.requestId(), source.result())
                    }

                    AnalysisMessageType.Cancel -> {
                        CancelAnalysisRequest(source.requestId())
                    }

                    AnalysisMessageType.Cancelled -> {
                        AnalysisCancelled(source.requestId(), source.nullableIdentity())
                    }

                    AnalysisMessageType.CloseSnapshot -> {
                        CloseSnapshotRequest(source.requestId(), source.identity())
                    }

                    AnalysisMessageType.SnapshotClosed -> {
                        SnapshotClosed(source.requestId(), source.identity())
                    }

                    AnalysisMessageType.Failure -> {
                        AnalysisFailure(source.requestId(), source.nullableIdentity(), source.enumValue(), source.string())
                    }
                }
            } catch (exception: AnalysisProtocolException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                source.fail(classify(exception), exception.message ?: "invalid analysis message")
            }
        source.requireEnd()
        try {
            validateForWire(message, context)
        } catch (exception: IllegalArgumentException) {
            source.fail(classify(exception), exception.message ?: "invalid analysis message")
        }
        return message
    }
}

private fun validateForWire(
    message: AnalysisMessage,
    context: AnalysisProtocolContext,
) {
    if (context.isUnchecked()) return
    when (message) {
        is AnalysisQueryRequest -> {
            validateQuery(message.query, context)
        }

        is AnalysisQuerySuccess -> {
            validateResult(message.result, context)
        }

        is SnapshotReopenRequired -> {
            require(strictUtf8Size(message.reason) <= context.limits.detailTextBytes) {
                "snapshot reopen reason exceeds analysis limit"
            }
        }

        is UpdateSnapshotRequest -> {
            validateChangedSources(message.changedSources, context.limits)
        }

        else -> {}
    }
}

private fun validateChangedSources(
    sources: List<ProjectSource>,
    limits: AnalysisLimits,
) {
    require(sources.size <= limits.sourceFiles) { "changed source count exceeds analysis limit" }
    var totalBytes = 0L
    sources.forEach { source ->
        require(source.content.size <= limits.sourceFileBytes) { "changed source file exceeds analysis limit" }
        totalBytes = Math.addExact(totalBytes, source.content.size.toLong())
        require(totalBytes <= limits.sourceBytes.toLong()) { "changed source bytes exceed analysis limit" }
    }
}

private fun validateQuery(
    query: AnalysisQuery,
    context: AnalysisProtocolContext,
) {
    when (query) {
        is AnalysisQuery.Presentation -> {
            context.validate(query.path, 0)
        }

        is AnalysisQuery.Completion -> {
            context.validate(query.path, query.offsetUtf16)
        }

        is AnalysisQuery.ExpressionInfo -> {
            context.validate(query.path, query.offsetUtf16)
        }

        is AnalysisQuery.ParameterInfo -> {
            context.validate(query.path, query.offsetUtf16)
        }

        is AnalysisQuery.Declaration -> {
            context.validate(query.path, query.offsetUtf16)
        }

        is AnalysisQuery.References -> {
            context.validate(query.path, query.offsetUtf16)
        }

        is AnalysisQuery.Format -> {
            context.validate(query.path, 0)
            require(strictUtf8Size(query.source) <= context.limits.sourceFileBytes) {
                "format source exceeds analysis limit"
            }
        }
    }
}

private fun validateResult(
    result: AnalysisResult,
    context: AnalysisProtocolContext,
) {
    val sourceLengths = context.requireSourceLengths()
    val limits = context.limits
    val query = requireNotNull(context.expectedQuery) { "analysis result has no correlated query" }
    require(query.identity == result.identity) { "analysis result identity does not match its query" }
    when (result) {
        is AnalysisResult.Presentation -> {
            require(query is AnalysisQuery.Presentation) { "analysis result kind does not match its query" }
            val active = result.value.accept(result.identity)
            require(active is SnapshotPresentationAcceptance.Active) { "presentation result is stale" }
            require(active.diagnostics.all { it.path == null || it.path == query.path }) {
                "presentation diagnostic does not belong to the active source"
            }
            require(active.semanticTokens.all { it.path == query.path }) {
                "presentation semantic token does not belong to the active source"
            }
            SnapshotPresentation.create(
                result.identity,
                sourceLengths,
                active.diagnostics,
                active.semanticTokens,
                active.locations,
                limits.presentationLimits(),
            )
        }

        is AnalysisResult.Completion -> {
            require(query is AnalysisQuery.Completion) { "analysis result kind does not match its query" }
            context.validate(query.path, result.replacement)
            AnalysisResult.Completion.create(
                result.identity,
                result.replacement,
                result.items,
                sourceLengths.getValue(query.path),
                limits.resultLimits(),
            )
        }

        is AnalysisResult.ExpressionInfo -> {
            require(query is AnalysisQuery.ExpressionInfo) { "analysis result kind does not match its query" }
            AnalysisResult.ExpressionInfo.create(result.identity, result.value, sourceLengths, limits.resultLimits())
        }

        is AnalysisResult.ParameterInfo -> {
            require(query is AnalysisQuery.ParameterInfo) { "analysis result kind does not match its query" }
            AnalysisResult.ParameterInfo.create(result.identity, result.value, sourceLengths, limits.resultLimits())
        }

        is AnalysisResult.Declaration -> {
            require(query is AnalysisQuery.Declaration) { "analysis result kind does not match its query" }
            AnalysisResult.Declaration.create(
                result.identity,
                result.locations,
                sourceLengths,
                limits.resultLimits(),
                context.platformSourceLengthsUtf16,
            )
        }

        is AnalysisResult.References -> {
            require(query is AnalysisQuery.References) { "analysis result kind does not match its query" }
            AnalysisResult.References.create(result.identity, result.locations, sourceLengths, limits.resultLimits())
        }

        is AnalysisResult.Format -> {
            require(query is AnalysisQuery.Format) { "analysis result kind does not match its query" }
            AnalysisResult.Format.create(result.identity, result.source, result.caretOffsetUtf16, limits.resultLimits())
        }
    }
}

private fun AnalysisLimits.presentationLimits() =
    EditorPresentationLimits(diagnostics, diagnosticTextBytes, semanticTokens, declarationLocations)

private fun AnalysisLimits.resultLimits() =
    AnalysisResultLimits(
        maxCompletionItems = completionItems,
        maxParameterInfoItems = parameterInfoItems,
        maxDeclarationLocations = declarationLocations,
        maxReferences = references,
        maxDetailUtf8Bytes = detailTextBytes,
        maxSourceFileUtf8Bytes = sourceFileBytes,
    )

private class MessageSink {
    private val output = ByteArrayOutputStream()

    fun result(): ByteArray = output.toByteArray()

    fun u8(value: Int) = output.write(value)

    fun u16(value: Int) = repeat(2) { output.write(value ushr (it * 8)) }

    fun u32(value: Int) {
        require(value >= 0)
        u32(value.toUInt())
    }

    fun u32(value: UInt) = repeat(4) { output.write((value shr (it * 8)).toInt()) }

    fun u64(value: ULong) = repeat(8) { output.write((value shr (it * 8)).toInt()) }

    fun bytes(value: BinaryValue) {
        u32(value.size)
        output.write(value.toByteArray())
    }

    fun string(value: String) = bytes(BinaryValue.of(encodeStrictUtf8(value)))

    fun hash(value: Hash256) = output.write(value.toByteArray())

    fun requestId(value: RequestId) = u64(value.value)

    fun identity(value: AnalysisSnapshotIdentity) {
        hash(value.source.hash)
        hash(value.profile.hash)
    }

    fun nullableIdentity(value: AnalysisSnapshotIdentity?) {
        u8(if (value == null) 0 else 1)
        value?.let(::identity)
    }

    fun workerIdentity(value: AnalysisWorkerIdentity) {
        string(value.compilerVersion)
        string(value.languageVersion)
        hash(value.payloadHash)
        hash(value.platformAbi)
    }

    fun limits(value: AnalysisLimits) {
        u32(value.sourceFiles)
        u32(value.sourceFileBytes)
        u32(value.sourceBytes)
        u32(value.frameBytes)
        u32(value.modules)
        u32(value.diagnostics)
        u32(value.diagnosticTextBytes)
        u32(value.semanticTokens)
        u32(value.completionItems)
        u32(value.parameterInfoItems)
        u32(value.declarationLocations)
        u32(value.references)
        u32(value.detailTextBytes)
    }

    fun sources(value: ProjectSnapshot) {
        u32(value.sources.size)
        value.sources.forEach { source ->
            string(source.path.value)
            bytes(source.content)
        }
    }

    fun changedSources(value: List<ProjectSource>) {
        u32(value.size)
        value.forEach { source ->
            string(source.path.value)
            bytes(source.content)
        }
    }

    fun profile(value: AdmittedAnalysisProfile) {
        hash(value.identity.hash)
        hash(value.platform.abi)
        u32(value.platform.modules.size)
        value.platform.modules.forEach { module ->
            moduleIdentity(module.identity)
        }
        nullableString(value.platform.sourceRoot)
    }

    fun query(value: AnalysisQuery) {
        enum(
            when (value) {
                is AnalysisQuery.Presentation -> QueryKind.Presentation
                is AnalysisQuery.Completion -> QueryKind.Completion
                is AnalysisQuery.ExpressionInfo -> QueryKind.ExpressionInfo
                is AnalysisQuery.ParameterInfo -> QueryKind.ParameterInfo
                is AnalysisQuery.Declaration -> QueryKind.Declaration
                is AnalysisQuery.References -> QueryKind.References
                is AnalysisQuery.Format -> QueryKind.Format
            },
        )
        identity(value.identity)
        when (value) {
            is AnalysisQuery.Presentation -> {
                string(value.path.value)
            }

            is AnalysisQuery.Completion -> {
                string(value.path.value)
                u32(value.offsetUtf16)
                enum(value.trigger)
            }

            is AnalysisQuery.ExpressionInfo -> {
                cursor(value.path, value.offsetUtf16)
            }

            is AnalysisQuery.ParameterInfo -> {
                cursor(value.path, value.offsetUtf16)
            }

            is AnalysisQuery.Declaration -> {
                cursor(value.path, value.offsetUtf16)
            }

            is AnalysisQuery.References -> {
                cursor(value.path, value.offsetUtf16)
            }

            is AnalysisQuery.Format -> {
                string(value.path.value)
                string(value.source)
                u32(value.caretOffsetUtf16)
            }
        }
    }

    fun result(value: AnalysisResult) {
        enum(
            when (value) {
                is AnalysisResult.Presentation -> ResultKind.Presentation
                is AnalysisResult.Completion -> ResultKind.Completion
                is AnalysisResult.ExpressionInfo -> ResultKind.ExpressionInfo
                is AnalysisResult.ParameterInfo -> ResultKind.ParameterInfo
                is AnalysisResult.Declaration -> ResultKind.Declaration
                is AnalysisResult.References -> ResultKind.References
                is AnalysisResult.Format -> ResultKind.Format
            },
        )
        identity(value.identity)
        when (value) {
            is AnalysisResult.Presentation -> {
                presentation(value.value, value.identity)
            }

            is AnalysisResult.Completion -> {
                range(value.replacement)
                u32(value.items.size)
                value.items.forEach(::completionItem)
            }

            is AnalysisResult.ExpressionInfo -> {
                nullableExpressionInfo(value.value)
            }

            is AnalysisResult.ParameterInfo -> {
                nullableParameterInfo(value.value)
            }

            is AnalysisResult.Declaration -> {
                locations(value.locations)
            }

            is AnalysisResult.References -> {
                locations(value.locations)
            }

            is AnalysisResult.Format -> {
                string(value.source)
                u32(value.caretOffsetUtf16)
            }
        }
    }

    fun presentation(
        value: SnapshotPresentation,
        identity: AnalysisSnapshotIdentity,
    ) {
        val active = value.accept(identity) as SnapshotPresentationAcceptance.Active
        u32(active.diagnostics.size)
        active.diagnostics.forEach(::diagnostic)
        u32(active.semanticTokens.size)
        active.semanticTokens.forEach { token ->
            string(token.path.value)
            range(token.range)
            enum(token.category)
            boolean(token.isMutable)
        }
        u32(active.locations.size)
        active.locations.forEach { location ->
            string(location.path.value)
            range(location.range)
        }
    }

    fun diagnostic(value: EditorDiagnostic) {
        enum(value.severity)
        string(value.message)
        nullablePath(value.path)
        nullableRange(value.range)
    }

    fun completionItem(value: CompletionItem) {
        string(value.label)
        string(value.insertText)
        enum(value.kind)
        nullableString(value.detail)
        nullableOrigin(value.origin)
        u8(if (value.symbol == null) 0 else 1)
        value.symbol?.let { symbol ->
            string(symbol.fqName)
            nullableString(symbol.importFqName)
        }
        u32(value.additionalEdits.size)
        value.additionalEdits.forEach { edit ->
            range(edit.range)
            string(edit.text)
        }
    }

    fun nullableExpressionInfo(value: EditorExpressionInfo?) {
        u8(if (value == null) 0 else 1)
        value?.let { info ->
            string(info.path.value)
            range(info.range)
            string(info.renderedType)
            nullableString(info.signature)
            nullableOrigin(info.origin)
        }
    }

    fun nullableParameterInfo(value: EditorParameterInfo?) {
        u8(if (value == null) 0 else 1)
        value?.let { info ->
            string(info.path.value)
            range(info.callRange)
            u32(info.items.size)
            info.items.forEach { item ->
                string(item.signature)
                nullableRange(item.activeParameter)
                boolean(item.bestCandidate)
            }
        }
    }

    fun locations(values: List<DeclarationLocation>) {
        u32(values.size)
        values.forEach { location ->
            when (location) {
                is DeclarationLocation.Source -> {
                    enum(LocationKind.Source)
                    origin(location.origin)
                    string(location.path.value)
                    range(location.range)
                }

                is DeclarationLocation.SourceUnavailable -> {
                    enum(LocationKind.SourceUnavailable)
                    origin(location.origin)
                }
            }
        }
    }

    fun origin(value: DeclarationOrigin) {
        when (value) {
            DeclarationOrigin.Project -> {
                enum(OriginKind.Project)
            }

            is DeclarationOrigin.Platform -> {
                enum(OriginKind.Platform)
                moduleIdentity(value.identity)
            }
        }
    }

    fun nullableOrigin(value: DeclarationOrigin?) {
        u8(if (value == null) 0 else 1)
        value?.let(::origin)
    }

    fun moduleIdentity(value: AnalysisModuleIdentity) {
        string(value.name)
        hash(value.hash)
    }

    fun cursor(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ) {
        string(path.value)
        u32(offsetUtf16)
    }

    fun range(value: EditorRange) {
        u32(value.startUtf16)
        u32(value.endUtf16)
    }

    fun nullableRange(value: EditorRange?) {
        u8(if (value == null) 0 else 1)
        value?.let(::range)
    }

    fun nullablePath(value: VirtualSourcePath?) {
        u8(if (value == null) 0 else 1)
        value?.let { string(it.value) }
    }

    fun nullableString(value: String?) {
        u8(if (value == null) 0 else 1)
        value?.let(::string)
    }

    fun <T : Enum<T>> enum(value: T) = u16(value.ordinal)

    fun boolean(value: Boolean) = u8(if (value) 1 else 0)

    fun snapshotResponse(
        requestId: RequestId,
        identity: AnalysisSnapshotIdentity,
        type: AnalysisMessageType,
    ): AnalysisMessageType {
        requestId(requestId)
        identity(identity)
        return type
    }
}

private class MessageSource(
    private val bytes: ByteArray,
    private val context: AnalysisProtocolContext,
) {
    private var offset = 0

    fun requireEnd() {
        if (offset != bytes.size) fail(AnalysisProtocolError.TrailingMessageBytes, "message contains trailing bytes")
    }

    fun u8(): Int {
        requireBytes(1)
        return bytes[offset++].toInt() and 0xff
    }

    fun u16(): Int = read(2).toInt()

    fun u32(): Int {
        val value = u32Bits()
        if (value > Int.MAX_VALUE.toUInt()) fail(AnalysisProtocolError.CountLimit, "wire value exceeds JVM range")
        return value.toInt()
    }

    fun u32Bits(): UInt = read(4).toUInt()

    fun u64(): ULong {
        requireBytes(8)
        var result = 0uL
        repeat(8) { index -> result = result or ((bytes[offset++].toULong() and 0xffu) shl (index * 8)) }
        return result
    }

    fun bytes(maximum: Int = ProtocolLimits.MAX_SOURCE_FILE_BYTES): BinaryValue {
        val size = u32()
        if (size > maximum) fail(AnalysisProtocolError.CountLimit, "byte value exceeds protocol limit")
        requireBytes(size)
        return BinaryValue.of(bytes.copyOfRange(offset, offset + size)).also { offset += size }
    }

    fun string(maximum: Int = ProtocolLimits.MAX_TEXT_BYTES): String =
        try {
            decodeStrictUtf8(bytes(maximum))
        } catch (_: CharacterCodingException) {
            fail(AnalysisProtocolError.InvalidUtf8, "invalid UTF-8")
        }

    fun hash(): Hash256 {
        requireBytes(32)
        return Hash256.of(bytes.copyOfRange(offset, offset + 32)).also { offset += 32 }
    }

    fun requestId(): RequestId =
        try {
            RequestId.of(u64())
        } catch (exception: IllegalArgumentException) {
            fail(AnalysisProtocolError.InvalidMessageValue, exception.message ?: "invalid request ID")
        }

    fun identity() = AnalysisSnapshotIdentity(SourceSnapshotId(hash()), AnalysisProfileIdentity(hash()))

    fun nullableIdentity(): AnalysisSnapshotIdentity? = optional(::identity)

    fun workerIdentity() = AnalysisWorkerIdentity(string(), string(), hash(), hash())

    fun features(): Set<AnalysisFeature> {
        val bits = u64()
        val known = AnalysisFeature.entries.fold(0uL) { result, feature -> result or (1uL shl feature.ordinal) }
        if (bits and known.inv() != 0uL) fail(AnalysisProtocolError.InvalidMessageValue, "unknown analysis feature")
        return AnalysisFeature.entries.filterTo(linkedSetOf()) { bits and (1uL shl it.ordinal) != 0uL }
    }

    fun limits() =
        AnalysisLimits(
            sourceFiles = u32(),
            sourceFileBytes = u32(),
            sourceBytes = u32(),
            frameBytes = u32(),
            modules = u32(),
            diagnostics = u32(),
            diagnosticTextBytes = u32(),
            semanticTokens = u32(),
            completionItems = u32(),
            parameterInfoItems = u32(),
            declarationLocations = u32(),
            references = u32(),
            detailTextBytes = u32(),
        )

    fun openSnapshot(): OpenSnapshotRequest {
        val requestId = requestId()
        val identity = identity()
        val limits = limits()
        val sources = sources(limits)
        val profile = profile(identity.profile, limits)
        return OpenSnapshotRequest(requestId, identity, sources, profile, limits)
    }

    fun updateSnapshot(): UpdateSnapshotRequest {
        val requestId = requestId()
        val baseIdentity = identity()
        val targetIdentity = identity()
        val changedSources = changedSources(context.limits)
        return UpdateSnapshotRequest(requestId, baseIdentity, targetIdentity, changedSources)
    }

    fun changedSources(limits: AnalysisLimits): List<ProjectSource> {
        val count = boundedCount(limits.sourceFiles, "changed source")
        var totalBytes = 0L
        return List(count) {
            val path = kotlinPath()
            val content = bytes(limits.sourceFileBytes)
            totalBytes = Math.addExact(totalBytes, content.size.toLong())
            if (totalBytes > limits.sourceBytes) fail(AnalysisProtocolError.CountLimit, "changed sources exceed analysis limit")
            try {
                decodeStrictUtf8(content)
            } catch (_: CharacterCodingException) {
                fail(AnalysisProtocolError.InvalidUtf8, "changed source is not strict UTF-8")
            }
            ProjectSource(path, content)
        }
    }

    fun sources(limits: AnalysisLimits): ProjectSnapshot {
        val count = boundedCount(limits.sourceFiles, "source")
        val sources =
            List(count) {
                val path = kotlinPath()
                val content = bytes(limits.sourceFileBytes)
                try {
                    decodeStrictUtf8(content)
                } catch (_: CharacterCodingException) {
                    fail(AnalysisProtocolError.InvalidUtf8, "source is not strict UTF-8")
                }
                ProjectSource(path, content)
            }
        return ProjectSnapshot.of(
            sources,
            WorkerLimits(
                sourceFiles = limits.sourceFiles,
                sourceFileBytes = limits.sourceFileBytes,
                sourceBytes = limits.sourceBytes,
            ),
        )
    }

    fun profile(
        identity: AnalysisProfileIdentity,
        limits: AnalysisLimits,
    ): AdmittedAnalysisProfile {
        val wireIdentity = AnalysisProfileIdentity(hash())
        if (wireIdentity != identity) fail(AnalysisProtocolError.InvalidMessageValue, "profile identity mismatch")
        val platformAbi = hash()
        val count = boundedCount(limits.modules, "module")
        val modules =
            List(count) {
                AdmittedAnalysisModule(moduleIdentity())
            }
        val sourceRoot = nullableString(ProtocolLimits.MAX_PATH_BYTES)
        return AdmittedAnalysisProfile(wireIdentity, AdmittedAnalysisPlatform(platformAbi, modules, sourceRoot))
    }

    fun query(): AnalysisQuery {
        val kind = enumValue<QueryKind>()
        val identity = identity()
        return when (kind) {
            QueryKind.Presentation -> {
                AnalysisQuery.Presentation(identity, kotlinPath())
            }

            QueryKind.Completion -> {
                val path = kotlinPath()
                val offset = u32()
                AnalysisQuery.Completion(identity, path, offset, enumValue())
            }

            QueryKind.ExpressionInfo -> {
                cursor(identity, AnalysisQuery::ExpressionInfo)
            }

            QueryKind.ParameterInfo -> {
                cursor(identity, AnalysisQuery::ParameterInfo)
            }

            QueryKind.Declaration -> {
                cursor(identity, AnalysisQuery::Declaration)
            }

            QueryKind.References -> {
                cursor(identity, AnalysisQuery::References)
            }

            QueryKind.Format -> {
                AnalysisQuery.Format(identity, kotlinPath(), string(context.limits.sourceFileBytes), u32())
            }
        }
    }

    fun result(): AnalysisResult {
        val kind = enumValue<ResultKind>()
        val identity = identity()
        val sourceLengths = context.requireSourceLengths()
        return when (kind) {
            ResultKind.Presentation -> {
                presentation(identity, sourceLengths)
            }

            ResultKind.Completion -> {
                val query = context.expectedQuery as? AnalysisQuery.Completion ?: error("completion result has no completion query")
                AnalysisResult.Completion.create(
                    identity,
                    range(),
                    List(boundedCount(context.limits.completionItems, "completion item")) { completionItem() },
                    sourceLengths.getValue(query.path),
                    context.limits.resultLimits(),
                )
            }

            ResultKind.ExpressionInfo -> {
                AnalysisResult.ExpressionInfo.create(identity, nullableExpressionInfo(), sourceLengths, context.limits.resultLimits())
            }

            ResultKind.ParameterInfo -> {
                AnalysisResult.ParameterInfo.create(identity, nullableParameterInfo(), sourceLengths, context.limits.resultLimits())
            }

            ResultKind.Declaration -> {
                AnalysisResult.Declaration.create(
                    identity,
                    locations(context.limits.declarationLocations),
                    sourceLengths,
                    context.limits.resultLimits(),
                    context.platformSourceLengthsUtf16,
                )
            }

            ResultKind.References -> {
                AnalysisResult.References.create(
                    identity,
                    locations(context.limits.references),
                    sourceLengths,
                    context.limits.resultLimits(),
                )
            }

            ResultKind.Format -> {
                AnalysisResult.Format.create(
                    identity,
                    string(context.limits.sourceFileBytes),
                    u32(),
                    context.limits.resultLimits(),
                )
            }
        }
    }

    fun presentation(
        identity: AnalysisSnapshotIdentity,
        sourceLengths: Map<VirtualSourcePath, Int>,
    ): AnalysisResult.Presentation {
        val diagnostics = List(boundedCount(context.limits.diagnostics, "diagnostic")) { diagnostic() }
        val tokens =
            List(boundedCount(context.limits.semanticTokens, "semantic token")) {
                SemanticToken(kotlinPath(), range(), enumValue(), boolean())
            }
        val locations =
            List(boundedCount(context.limits.declarationLocations, "source location")) {
                SourceLocation(kotlinPath(), range())
            }
        val value =
            SnapshotPresentation.create(
                identity,
                sourceLengths,
                diagnostics,
                tokens,
                locations,
                context.limits.presentationLimits(),
            )
        return AnalysisResult.Presentation(identity, value)
    }

    fun diagnostic(): EditorDiagnostic =
        EditorDiagnostic(enumValue(), string(context.limits.diagnosticTextBytes), nullablePath(), nullableRange())

    fun completionItem(): CompletionItem =
        CompletionItem(
            string(context.limits.detailTextBytes),
            string(context.limits.detailTextBytes),
            enumValue(),
            nullableString(context.limits.detailTextBytes),
            nullableOrigin(),
            optional {
                CompletionSymbol(
                    string(context.limits.detailTextBytes),
                    nullableString(context.limits.detailTextBytes),
                )
            },
            List(boundedCount(1, "completion additional edit")) {
                CompletionTextEdit(range(), string(context.limits.detailTextBytes))
            },
        )

    fun nullableExpressionInfo(): EditorExpressionInfo? =
        optional {
            EditorExpressionInfo(
                kotlinPath(),
                range(),
                string(context.limits.detailTextBytes),
                nullableString(context.limits.detailTextBytes),
                nullableOrigin(),
            )
        }

    fun nullableParameterInfo(): EditorParameterInfo? =
        optional {
            EditorParameterInfo(
                kotlinPath(),
                range(),
                List(boundedCount(context.limits.parameterInfoItems, "parameter-info item")) {
                    ParameterInfoItem(
                        string(context.limits.detailTextBytes),
                        nullableRange(),
                        boolean(),
                    )
                },
            )
        }

    fun locations(maximum: Int): List<DeclarationLocation> =
        List(boundedCount(maximum, "location")) {
            when (enumValue<LocationKind>()) {
                LocationKind.Source -> DeclarationLocation.Source(origin(), kotlinPath(), range())
                LocationKind.SourceUnavailable -> DeclarationLocation.SourceUnavailable(origin())
            }
        }

    fun origin(): DeclarationOrigin =
        when (enumValue<OriginKind>()) {
            OriginKind.Project -> DeclarationOrigin.Project
            OriginKind.Platform -> DeclarationOrigin.Platform(moduleIdentity())
        }

    fun nullableOrigin(): DeclarationOrigin? = optional(::origin)

    fun moduleIdentity() = AnalysisModuleIdentity(string(), hash())

    fun kotlinPath(): VirtualSourcePath =
        try {
            VirtualSourcePath.kotlin(string(ProtocolLimits.MAX_PATH_BYTES))
        } catch (exception: IllegalArgumentException) {
            fail(AnalysisProtocolError.InvalidPath, exception.message ?: "invalid Kotlin source path")
        }

    fun nullablePath(): VirtualSourcePath? = optional(::kotlinPath)

    fun range() = EditorRange(u32(), u32())

    fun nullableRange(): EditorRange? = optional(::range)

    fun nullableString(maximum: Int = ProtocolLimits.MAX_TEXT_BYTES): String? = optional { string(maximum) }

    fun boolean(): Boolean =
        when (u8()) {
            0 -> false
            1 -> true
            else -> fail(AnalysisProtocolError.InvalidMessageValue, "non-canonical boolean value")
        }

    fun <T> optional(reader: () -> T): T? =
        when (u8()) {
            0 -> null
            1 -> reader()
            else -> fail(AnalysisProtocolError.InvalidMessageValue, "non-canonical optional value")
        }

    inline fun <reified T : Enum<T>> enumValue(): T {
        val index = u16()
        return enumValues<T>().getOrNull(index)
            ?: fail(AnalysisProtocolError.UnknownEnumValue, "unknown enum value")
    }

    fun fail(
        error: AnalysisProtocolError,
        message: String,
    ): Nothing = throw AnalysisProtocolException(error, message)

    private fun cursor(
        identity: AnalysisSnapshotIdentity,
        create: (AnalysisSnapshotIdentity, VirtualSourcePath, Int) -> AnalysisQuery,
    ): AnalysisQuery = create(identity, kotlinPath(), u32())

    private fun boundedCount(
        maximum: Int,
        label: String,
    ): Int {
        val count = u32()
        if (count > maximum) fail(AnalysisProtocolError.CountLimit, "$label count exceeds protocol limit")
        return count
    }

    private fun read(count: Int): Long {
        requireBytes(count)
        var result = 0L
        repeat(count) { index -> result = result or ((bytes[offset++].toLong() and 0xff) shl (index * 8)) }
        return result
    }

    private fun requireBytes(count: Int) {
        if (count < 0 || offset > bytes.size - count) fail(AnalysisProtocolError.TruncatedMessage, "message is truncated")
    }
}

private enum class QueryKind { Presentation, Completion, ExpressionInfo, ParameterInfo, Declaration, References, Format }

private enum class ResultKind { Presentation, Completion, ExpressionInfo, ParameterInfo, Declaration, References, Format }

private enum class OriginKind { Project, Platform }

private enum class LocationKind { Source, SourceUnavailable }

private fun encodeStrictUtf8(value: String): ByteArray {
    val encoded =
        try {
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
        } catch (exception: CharacterCodingException) {
            throw IllegalArgumentException("protocol text must be strict UTF-8", exception)
        }
    return ByteArray(encoded.remaining()).also(encoded::get)
}

@Throws(CharacterCodingException::class)
private fun decodeStrictUtf8(value: BinaryValue): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value.toByteArray()))
        .toString()

private fun classify(exception: IllegalArgumentException): AnalysisProtocolError {
    val message = exception.message.orEmpty()
    return when {
        "protocol version" in message -> AnalysisProtocolError.WrongVersion
        "path" in message -> AnalysisProtocolError.InvalidPath
        "range" in message || "offset" in message || "source" in message -> AnalysisProtocolError.InvalidRange
        "count" in message || "limit" in message || "bytes" in message -> AnalysisProtocolError.CountLimit
        else -> AnalysisProtocolError.InvalidMessageValue
    }
}
