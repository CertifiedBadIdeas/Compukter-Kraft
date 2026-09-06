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
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.EditorPresentationLimits
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.analysis.SemanticToken
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentation
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalysisProtocolHostileInputTest {
    private val snapshot = snapshot("main.kt", "val value = 1")
    private val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(snapshot), AnalysisProfileIdentity(hash(1)))
    private val context = AnalysisProtocolContext.of(snapshot)

    @Test
    fun `frame decoder rejects malformed version type lengths and trailing bytes`() {
        val encoded = AnalysisFrameCodec.encode(AnalysisMessageCodec.encode(CancelAnalysisRequest(RequestId.of(1uL)), context))

        assertEquals(AnalysisProtocolError.TruncatedFrame, frameFailure(encoded.copyOf(11)).error)
        assertEquals(AnalysisProtocolError.FrameTooLarge, frameFailure(encoded, 0).error)
        assertEquals(AnalysisProtocolError.TrailingBytes, frameFailure(encoded + 0).error)
        assertEquals(AnalysisProtocolError.WrongVersion, frameFailure(encoded.copyOf().also { it[4] = 0x7f }).error)
        assertEquals(
            AnalysisProtocolError.WrongVersion,
            frameFailure(
                encoded.copyOf().also { bytes ->
                    bytes[4] = 1
                    bytes[5] = 0
                },
            ).error,
        )
        assertEquals(AnalysisProtocolError.UnknownMessageType, frameFailure(encoded.copyOf().also { it[6] = 0x7f }).error)
    }

    @Test
    fun `message decoder rejects truncation trailing bytes invalid request IDs enums and utf8`() {
        val completion =
            AnalysisQueryRequest(
                RequestId.of(1uL),
                AnalysisQuery.Completion(identity, VirtualSourcePath.kotlin("main.kt"), 3, CompletionTrigger.Manual),
            )
        val frame = AnalysisMessageCodec.encode(completion, context)

        assertEquals(
            AnalysisProtocolError.TruncatedMessage,
            messageFailure(frame.copy(payload = frame.payload.copyOf(frame.payload.size - 1))).error,
        )
        assertEquals(AnalysisProtocolError.TrailingMessageBytes, messageFailure(frame.copy(payload = frame.payload + 0)).error)
        assertEquals(
            AnalysisProtocolError.InvalidMessageValue,
            messageFailure(frame.copy(payload = frame.payload.copyOf().also { bytes -> repeat(8) { bytes[it] = 0 } })).error,
        )
        assertEquals(
            AnalysisProtocolError.UnknownEnumValue,
            messageFailure(frame.copy(payload = frame.payload.copyOf().also { bytes -> bytes[89] = 0x7f })).error,
        )

        val failure = AnalysisMessageCodec.encode(AnalysisFailure(RequestId.of(1uL), identity, AnalysisFailureKind.Protocol, "ok"), context)
        val invalidUtf8 = failure.copy(payload = failure.payload.copyOf().also { it[it.lastIndex] = 0x80.toByte() })
        assertEquals(AnalysisProtocolError.InvalidUtf8, messageFailure(invalidUtf8).error)

        val handshake =
            AnalysisMessageCodec.encode(
                AnalysisHandshake(
                    ANALYSIS_PROTOCOL_VERSION,
                    AnalysisWorkerIdentity("2.4.10", "2.4", hash(3), hash(4)),
                    emptySet(),
                    AnalysisLimits(),
                ),
                context,
            )
        val wrongProtocol = handshake.copy(payload = handshake.payload.copyOf().also { it[0] = 1 })
        assertEquals(AnalysisProtocolError.WrongVersion, messageFailure(wrongProtocol).error)
    }

    @Test
    fun `presentation decoder rejects non-canonical mutability flag`() {
        val query = AnalysisQuery.Presentation(identity, VirtualSourcePath.kotlin("main.kt"))
        val presentation =
            SnapshotPresentation.create(
                identity,
                mapOf(VirtualSourcePath.kotlin("main.kt") to "val value = 1".length),
                diagnostics = emptyList(),
                semanticTokens =
                    listOf(
                        SemanticToken(
                            VirtualSourcePath.kotlin("main.kt"),
                            EditorRange(4, 9),
                            SemanticCategory.LocalVariable,
                        ),
                    ),
                locations = emptyList(),
                limits = EditorPresentationLimits(),
            )
        val encoded =
            AnalysisMessageCodec.encode(
                AnalysisQuerySuccess(RequestId.of(1uL), AnalysisResult.Presentation(identity, presentation)),
                context.forQuery(query),
            )
        val malformed = encoded.copy(payload = encoded.payload.copyOf().also { it[it.lastIndex - 4] = 2 })

        assertEquals(AnalysisProtocolError.InvalidMessageValue, messageFailure(malformed, context.forQuery(query)).error)
    }

    @Test
    fun `snapshot update rejects invalid identities sources ordering utf8 and reopen reasons`() {
        val target = identity.copy(source = SourceSnapshotId(hash(2)))
        val otherProfile = target.copy(profile = AnalysisProfileIdentity(hash(3)))
        val first = ProjectSource(VirtualSourcePath.kotlin("a.kt"), BinaryValue.of("a".encodeToByteArray()))
        val second = ProjectSource(VirtualSourcePath.kotlin("b.kt"), BinaryValue.of("b".encodeToByteArray()))

        assertFailsWith<IllegalArgumentException> { UpdateSnapshotRequest(RequestId.of(1uL), identity, identity, listOf(first)) }
        assertFailsWith<IllegalArgumentException> { UpdateSnapshotRequest(RequestId.of(1uL), identity, otherProfile, listOf(first)) }
        assertFailsWith<IllegalArgumentException> { UpdateSnapshotRequest(RequestId.of(1uL), identity, target, emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            UpdateSnapshotRequest(RequestId.of(1uL), identity, target, listOf(second, first))
        }
        assertFailsWith<IllegalArgumentException> {
            UpdateSnapshotRequest(RequestId.of(1uL), identity, target, listOf(first, first))
        }
        assertFailsWith<IllegalArgumentException> {
            UpdateSnapshotRequest(
                RequestId.of(1uL),
                identity,
                target,
                listOf(ProjectSource(VirtualSourcePath.kotlin("a.kt"), BinaryValue.of(byteArrayOf(0x80.toByte())))),
            )
        }
        assertFailsWith<IllegalArgumentException> { SnapshotReopenRequired(RequestId.of(1uL), target, "") }
        assertFailsWith<IllegalArgumentException> { SnapshotReopenRequired(RequestId.of(1uL), target, "bad\uD800") }
        assertFailsWith<IllegalArgumentException> {
            SnapshotReopenRequired(RequestId.of(1uL), target, "x".repeat(ProtocolLimits.MAX_TEXT_BYTES + 1))
        }
    }

    @Test
    fun `snapshot update codec enforces active changed-source limits and strict utf8`() {
        val target = identity.copy(source = SourceSnapshotId(hash(2)))
        val twoSources =
            listOf(
                ProjectSource(VirtualSourcePath.kotlin("a.kt"), BinaryValue.of("aaa".encodeToByteArray())),
                ProjectSource(VirtualSourcePath.kotlin("b.kt"), BinaryValue.of("bbb".encodeToByteArray())),
            )
        val request = UpdateSnapshotRequest(RequestId.of(1uL), identity, target, twoSources)
        val encoded = AnalysisMessageCodec.encode(request, AnalysisProtocolContext.unchecked())

        assertFailsWith<IllegalArgumentException> {
            AnalysisMessageCodec.encode(request, AnalysisProtocolContext.of(snapshot, AnalysisLimits(sourceFiles = 1)))
        }
        assertEquals(
            AnalysisProtocolError.CountLimit,
            messageFailure(encoded, AnalysisProtocolContext.unbound(AnalysisLimits(sourceFiles = 1))).error,
        )
        assertEquals(
            AnalysisProtocolError.CountLimit,
            messageFailure(
                encoded,
                AnalysisProtocolContext.unbound(AnalysisLimits(sourceFiles = 2, sourceFileBytes = 3, sourceBytes = 5)),
            ).error,
        )

        val oneSource = UpdateSnapshotRequest(RequestId.of(2uL), identity, target, listOf(twoSources.first()))
        val malformed =
            AnalysisMessageCodec
                .encode(oneSource, AnalysisProtocolContext.unchecked())
                .let { frame -> frame.copy(payload = frame.payload.copyOf().also { it[it.lastIndex] = 0x80.toByte() }) }
        assertEquals(AnalysisProtocolError.InvalidUtf8, messageFailure(malformed).error)
    }

    @Test
    fun `snapshot reopen reason obeys the active detail limit`() {
        val target = identity.copy(source = SourceSnapshotId(hash(2)))
        val response = SnapshotReopenRequired(RequestId.of(1uL), target, "abcde")
        val limits = AnalysisLimits(detailTextBytes = 4)

        assertFailsWith<IllegalArgumentException> {
            AnalysisMessageCodec.encode(response, AnalysisProtocolContext.of(snapshot, limits))
        }
        val encoded = AnalysisMessageCodec.encode(response, AnalysisProtocolContext.unchecked())
        assertEquals(
            AnalysisProtocolError.CountLimit,
            messageFailure(encoded, AnalysisProtocolContext.unbound(limits)).error,
        )
    }

    @Test
    fun `completion replacement is checked against its correlated query source`() {
        val multiple =
            ProjectSnapshot.of(
                listOf(
                    ProjectSource(VirtualSourcePath.kotlin("long.kt"), BinaryValue.of("x".repeat(100).encodeToByteArray())),
                    ProjectSource(VirtualSourcePath.kotlin("main.kt"), BinaryValue.of("val value = 1".encodeToByteArray())),
                ),
                WorkerLimits(),
            )
        val multipleIdentity =
            AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(multiple), AnalysisProfileIdentity(hash(4)))
        val query =
            AnalysisQuery.Completion(
                multipleIdentity,
                VirtualSourcePath.kotlin("main.kt"),
                3,
                CompletionTrigger.Manual,
            )
        val hostile =
            AnalysisMessageCodec.encode(
                AnalysisQuerySuccess(
                    RequestId.of(2uL),
                    AnalysisResult.Completion.create(multipleIdentity, EditorRange(0, 20), emptyList(), 20),
                ),
                AnalysisProtocolContext.unchecked(),
            )

        val failure =
            assertFailsWith<AnalysisProtocolException> {
                AnalysisMessageCodec.decode(hostile, AnalysisProtocolContext.of(multiple).forQuery(query))
            }
        assertEquals(AnalysisProtocolError.InvalidRange, failure.error)
    }

    @Test
    fun `decoder rejects traversal out of source positions and hostile counts`() {
        val query =
            AnalysisQueryRequest(
                RequestId.of(1uL),
                AnalysisQuery.Completion(identity, VirtualSourcePath.kotlin("main.kt"), 3, CompletionTrigger.Manual),
            )
        val frame = AnalysisMessageCodec.encode(query, context)
        val traversal = frame.copy(payload = replaceAscii(frame.payload, "main.kt", "../a.kt"))
        assertEquals(AnalysisProtocolError.InvalidPath, messageFailure(traversal).error)

        val excessiveOffset =
            AnalysisMessageCodec.encode(
                query.copy(query = AnalysisQuery.Completion(identity, VirtualSourcePath.kotlin("main.kt"), 999, CompletionTrigger.Manual)),
                AnalysisProtocolContext.unchecked(),
            )
        assertEquals(AnalysisProtocolError.InvalidRange, messageFailure(excessiveOffset).error)

        val open =
            OpenSnapshotRequest(
                RequestId.of(1uL),
                identity,
                snapshot,
                AdmittedAnalysisProfile(
                    identity.profile,
                    ru.lazyhat.compukters.ide.analysis.protocol
                        .AdmittedAnalysisPlatform(Hash256.zero(), emptyList()),
                ),
                AnalysisLimits(),
            )
        val openFrame = AnalysisMessageCodec.encode(open, context)
        val hostileSourceCount =
            openFrame.copy(
                payload =
                    openFrame.payload.copyOf().also { bytes ->
                        bytes[120] = 65
                        bytes[121] = 0
                        bytes[122] = 0
                        bytes[123] = 0
                    },
            )
        assertEquals(AnalysisProtocolError.CountLimit, messageFailure(hostileSourceCount).error)

        assertFailsWith<IllegalArgumentException> { AnalysisLimits(completionItems = ProtocolLimits.MAX_COMPLETION_ITEMS + 1) }
        assertFailsWith<IllegalArgumentException> {
            AdmittedAnalysisProfile(
                identity.profile,
                AdmittedAnalysisPlatform(
                    hash(3),
                    List(ProtocolLimits.MAX_MODULES + 1) { index ->
                        AdmittedAnalysisModule(
                            ru.lazyhat.compukters.ide.analysis
                                .AnalysisModuleIdentity("b$index", hash(index)),
                        )
                    },
                ),
            )
        }
    }

    @Test
    fun `snapshot opening rejects mismatched identity malformed source and unordered bundles`() {
        val profile = AnalysisProfileIdentity(hash(2))
        assertFailsWith<IllegalArgumentException> {
            OpenSnapshotRequest(
                RequestId.of(1uL),
                identity.copy(
                    source =
                        ru.lazyhat.compukters.ide.analysis
                            .SourceSnapshotId(hash(9)),
                ),
                snapshot,
                AdmittedAnalysisProfile(
                    profile,
                    ru.lazyhat.compukters.ide.analysis.protocol
                        .AdmittedAnalysisPlatform(Hash256.zero(), emptyList()),
                ),
                AnalysisLimits(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AdmittedAnalysisProfile(
                profile,
                AdmittedAnalysisPlatform(
                    hash(3),
                    listOf(
                        AdmittedAnalysisModule(
                            ru.lazyhat.compukters.ide.analysis
                                .AnalysisModuleIdentity("z", hash(1)),
                        ),
                        AdmittedAnalysisModule(
                            ru.lazyhat.compukters.ide.analysis
                                .AnalysisModuleIdentity("a", hash(2)),
                        ),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AnalysisFailure(RequestId.of(1uL), identity, AnalysisFailureKind.Protocol, "bad\uD800")
        }

        val oversizedPathSnapshot = snapshot("a".repeat(ProtocolLimits.MAX_PATH_BYTES) + ".kt", "x")
        assertFailsWith<IllegalArgumentException> {
            OpenSnapshotRequest(
                RequestId.of(2uL),
                AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(oversizedPathSnapshot), profile),
                oversizedPathSnapshot,
                AdmittedAnalysisProfile(
                    profile,
                    ru.lazyhat.compukters.ide.analysis.protocol
                        .AdmittedAnalysisPlatform(Hash256.zero(), emptyList()),
                ),
                AnalysisLimits(),
            )
        }
    }

    private fun messageFailure(
        frame: AnalysisFrame,
        protocolContext: AnalysisProtocolContext = context,
    ): AnalysisProtocolException = assertFailsWith { AnalysisMessageCodec.decode(frame, protocolContext) }

    private fun frameFailure(
        bytes: ByteArray,
        maximum: Int = ProtocolLimits.MAX_FRAME_PAYLOAD_BYTES,
    ): AnalysisProtocolException = assertFailsWith { AnalysisFrameCodec.decode(bytes, maximum) }

    private fun replaceAscii(
        bytes: ByteArray,
        old: String,
        new: String,
    ): ByteArray {
        require(old.length == new.length)
        val result = bytes.copyOf()
        val offset = result.toList().windowed(old.length).indexOf(old.encodeToByteArray().toList())
        require(offset >= 0)
        new.encodeToByteArray().copyInto(result, offset)
        return result
    }

    private fun snapshot(
        path: String,
        text: String,
    ): ProjectSnapshot =
        ProjectSnapshot.of(
            listOf(ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(text.encodeToByteArray()))),
            WorkerLimits(),
        )

    private fun hash(value: Int) = Hash256.of(ByteArray(32) { value.toByte() })
}
