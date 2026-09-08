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
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AnalysisProtocolRoundTripTest {
    private val snapshot = snapshot("src/main.kt" to "val answer = 42")
    private val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(snapshot), AnalysisProfileIdentity(hash(2)))
    private val context = AnalysisProtocolContext.of(snapshot)
    private val requestId = RequestId.of(7uL)

    @Test
    fun `handshake snapshot lifecycle queries cancellation and failure round trip`() {
        val updatedSnapshot = snapshot("src/main.kt" to "val answer = 43")
        val updatedIdentity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(updatedSnapshot), identity.profile)
        val changedSources = updatedSnapshot.sources
        val profile =
            AdmittedAnalysisProfile(
                identity.profile,
                AdmittedAnalysisPlatform(
                    hash(5),
                    listOf(AdmittedAnalysisModule(AnalysisModuleIdentity("std", hash(3)))),
                    "/safe/std-sources.jar",
                ),
            )
        val messages =
            listOf<AnalysisMessage>(
                AnalysisHandshake(
                    ANALYSIS_PROTOCOL_VERSION,
                    AnalysisWorkerIdentity("2.4.10", "2.4", hash(4), hash(5)),
                    AnalysisFeature.entries.toSet(),
                    AnalysisLimits(),
                ),
                OpenSnapshotRequest(requestId, identity, snapshot, profile, AnalysisLimits()),
                SnapshotReady(requestId, identity),
                UpdateSnapshotRequest(requestId, identity, updatedIdentity, changedSources),
                SnapshotUpdated(requestId, updatedIdentity),
                SnapshotReopenRequired(requestId, updatedIdentity, "workspace mutation failed"),
                AnalysisQueryRequest(requestId, AnalysisQuery.Presentation(identity, path())),
                AnalysisQueryRequest(
                    requestId,
                    AnalysisQuery.Completion(identity, path(), 3, CompletionTrigger.Automatic),
                ),
                AnalysisQueryRequest(requestId, AnalysisQuery.ExpressionInfo(identity, path(), 4)),
                AnalysisQueryRequest(requestId, AnalysisQuery.ParameterInfo(identity, path(), 5)),
                AnalysisQueryRequest(requestId, AnalysisQuery.Declaration(identity, path(), 5)),
                AnalysisQueryRequest(requestId, AnalysisQuery.References(identity, path(), 6)),
                AnalysisQueryRequest(requestId, AnalysisQuery.Format(identity, path(), "fun main(){}", 11)),
                CancelAnalysisRequest(requestId),
                AnalysisCancelled(requestId, identity),
                CloseSnapshotRequest(requestId, identity),
                SnapshotClosed(requestId, identity),
                AnalysisFailure(requestId, identity, AnalysisFailureKind.Timeout, "deadline exceeded"),
            )

        messages.forEach { message -> assertEquals(message, roundTrip(message)) }
    }

    @Test
    fun `snapshot update defensively owns its changed sources`() {
        val changed = mutableListOf(ProjectSource(path(), BinaryValue.of("val answer = 43".encodeToByteArray())))
        val targetSnapshot = snapshot("src/main.kt" to "val answer = 43")
        val targetIdentity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(targetSnapshot), identity.profile)
        val request = UpdateSnapshotRequest(requestId, identity, targetIdentity, changed)

        changed.clear()

        assertEquals(targetSnapshot.sources, request.changedSources)
    }

    @Test
    fun `handshake defensively owns its feature set`() {
        val features = mutableSetOf(AnalysisFeature.Presentation)
        val handshake =
            AnalysisHandshake(
                ANALYSIS_PROTOCOL_VERSION,
                AnalysisWorkerIdentity("2.4.10", "2.4", hash(4), hash(5)),
                features,
                AnalysisLimits(),
            )

        features.clear()

        assertEquals(setOf(AnalysisFeature.Presentation), handshake.features)
    }

    @Test
    fun `all stable result variants round trip`() {
        val origin = DeclarationOrigin.Platform(AnalysisModuleIdentity("std", hash(3)))
        val results =
            listOf(
                AnalysisQuery.Completion(identity, path(), 6, CompletionTrigger.Manual) to
                    AnalysisResult.Completion.create(
                        identity,
                        EditorRange(4, 6),
                        listOf(
                            CompletionItem(
                                "answer",
                                "answer",
                                CompletionKind.Property,
                                "kotlin.Int",
                                origin,
                                CompletionSymbol("sample.answer", "sample.answer"),
                                listOf(CompletionTextEdit(EditorRange(0, 0), "import sample.answer\n\n")),
                            ),
                        ),
                        sourceLengths().getValue(path()),
                    ),
                AnalysisQuery.ExpressionInfo(identity, path(), 6) to
                    AnalysisResult.ExpressionInfo.create(
                        identity,
                        EditorExpressionInfo(path(), EditorRange(4, 10), "kotlin.Int", "val answer: kotlin.Int", origin),
                        sourceLengths(),
                    ),
                AnalysisQuery.ParameterInfo(identity, path(), 6) to
                    AnalysisResult.ParameterInfo.create(
                        identity,
                        EditorParameterInfo(
                            path(),
                            EditorRange(4, 12),
                            listOf(ParameterInfoItem("answer(value: Int): Unit", EditorRange(7, 17), true)),
                        ),
                        sourceLengths(),
                    ),
                AnalysisQuery.Declaration(identity, path(), 6) to
                    AnalysisResult.Declaration.create(
                        identity,
                        listOf(DeclarationLocation.Source(DeclarationOrigin.Project, path(), EditorRange(4, 10))),
                        sourceLengths(),
                    ),
                AnalysisQuery.References(identity, path(), 6) to
                    AnalysisResult.References.create(
                        identity,
                        listOf(DeclarationLocation.Source(DeclarationOrigin.Project, path(), EditorRange(4, 10))),
                        sourceLengths(),
                    ),
                AnalysisQuery.Format(identity, path(), "fun main(){}", 11) to
                    AnalysisResult.Format.create(identity, "fun main() {\n}\n", 13),
            )

        results.forEach { (query, result) ->
            val message = AnalysisQuerySuccess(requestId, result)
            assertEquals(message, roundTrip(message, context.forQuery(query)))
        }
    }

    @Test
    fun `attached platform declaration round trips against the admitted source archive`() {
        val sourcePath = VirtualSourcePath.kotlin("api/Terminal.kt")
        val sourceText = "package api\nobject Terminal { fun write(value: String) = Unit }"
        val sourceArchive = Files.createTempFile("compukters-analysis-sources-", ".jar")
        try {
            ZipOutputStream(Files.newOutputStream(sourceArchive)).use { output ->
                output.putNextEntry(ZipEntry(sourcePath.value))
                output.write(sourceText.encodeToByteArray())
                output.closeEntry()
            }
            val bundle = AnalysisModuleIdentity("std", hash(3))
            val profile =
                AdmittedAnalysisProfile(
                    identity.profile,
                    AdmittedAnalysisPlatform(hash(5), listOf(AdmittedAnalysisModule(bundle)), sourceArchive.toString()),
                )
            val protocolContext = AnalysisProtocolContext.of(snapshot, profile).forQuery(AnalysisQuery.Declaration(identity, path(), 6))
            val start = sourceText.indexOf("write")
            val result =
                AnalysisResult.Declaration.create(
                    identity,
                    listOf(
                        DeclarationLocation.Source(
                            DeclarationOrigin.Platform(bundle),
                            sourcePath,
                            EditorRange(start, start + "write".length),
                        ),
                    ),
                    sourceLengths(),
                    platformSourceLengthsUtf16 = mapOf(bundle to mapOf(sourcePath to sourceText.length)),
                )

            assertEquals(AnalysisQuerySuccess(requestId, result), roundTrip(AnalysisQuerySuccess(requestId, result), protocolContext))
        } finally {
            Files.deleteIfExists(sourceArchive)
        }
    }

    @Test
    fun `presentation result round trips without leaking implementation objects`() {
        val presentation =
            SnapshotPresentation.create(
                identity,
                sourceLengths(),
                diagnostics = listOf(EditorDiagnostic(EditorDiagnosticSeverity.Warning, "warning", path(), EditorRange(0, 3))),
                semanticTokens = listOf(SemanticToken(path(), EditorRange(4, 10), SemanticCategory.Property, isMutable = true)),
                locations = listOf(SourceLocation(path(), EditorRange(4, 10))),
                limits = EditorPresentationLimits(),
            )

        val decoded =
            assertIs<AnalysisQuerySuccess>(
                roundTrip(
                    AnalysisQuerySuccess(requestId, AnalysisResult.Presentation(identity, presentation)),
                    context.forQuery(AnalysisQuery.Presentation(identity, path())),
                ),
            )
        val decodedPresentation = assertIs<AnalysisResult.Presentation>(decoded.result).value
        val active = assertIs<SnapshotPresentationAcceptance.Active>(decodedPresentation.accept(identity))

        assertEquals(listOf(EditorDiagnostic(EditorDiagnosticSeverity.Warning, "warning", path(), EditorRange(0, 3))), active.diagnostics)
        assertEquals(
            listOf(SemanticToken(path(), EditorRange(4, 10), SemanticCategory.Property, isMutable = true)),
            active.semanticTokens,
        )
        assertEquals(listOf(SourceLocation(path(), EditorRange(4, 10))), active.locations)
    }

    @Test
    fun `presentation query and result stay inside the active source`() {
        val closedPath = VirtualSourcePath.kotlin("src/closed.kt")
        val sources = snapshot(closedPath.value to "val closed = 2", path().value to "val active = 1")
        val scopedIdentity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), identity.profile)
        val scopedContext = AnalysisProtocolContext.of(sources)

        assertFailsWith<IllegalArgumentException> {
            scopedContext.forQuery(AnalysisQuery.Presentation(scopedIdentity, VirtualSourcePath.kotlin("src/missing.kt")))
        }

        val presentation =
            SnapshotPresentation.create(
                scopedIdentity,
                mapOf(path() to "val active = 1".length, closedPath to "val closed = 2".length),
                diagnostics = listOf(EditorDiagnostic(EditorDiagnosticSeverity.Warning, "closed", closedPath, EditorRange(0, 3))),
            )
        assertFailsWith<IllegalArgumentException> {
            roundTrip(
                AnalysisQuerySuccess(requestId, AnalysisResult.Presentation(scopedIdentity, presentation)),
                scopedContext.forQuery(AnalysisQuery.Presentation(scopedIdentity, path())),
            )
        }
    }

    @Test
    fun `format query and result stay inside the negotiated source-file limit`() {
        val limited = AnalysisProtocolContext.of(snapshot, AnalysisLimits(sourceFileBytes = 3))
        val query = AnalysisQuery.Format(identity, path(), "1234", 0)

        assertFailsWith<IllegalArgumentException> {
            limited.forQuery(query)
        }

        val acceptedQuery = AnalysisQuery.Format(identity, path(), "123", 0)
        assertFailsWith<IllegalArgumentException> {
            roundTrip(
                AnalysisQuerySuccess(requestId, AnalysisResult.Format.create(identity, "1234", 0)),
                limited.forQuery(acceptedQuery),
            )
        }
    }

    private fun roundTrip(
        message: AnalysisMessage,
        protocolContext: AnalysisProtocolContext = context,
    ): AnalysisMessage {
        val frame = AnalysisMessageCodec.encode(message, protocolContext)
        val bytes = AnalysisFrameCodec.encode(frame)
        return AnalysisMessageCodec.decode(AnalysisFrameCodec.decode(bytes, ProtocolLimits.MAX_FRAME_PAYLOAD_BYTES), protocolContext)
    }

    private fun path() = VirtualSourcePath.kotlin("src/main.kt")

    private fun sourceLengths() = mapOf(path() to "val answer = 42".length)

    private fun hash(value: Int) = Hash256.of(ByteArray(32) { value.toByte() })

    private fun snapshot(vararg sources: Pair<String, String>): ProjectSnapshot =
        ProjectSnapshot.of(
            sources.map { (path, source) ->
                ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(source.encodeToByteArray()))
            },
            WorkerLimits(),
        )
}
