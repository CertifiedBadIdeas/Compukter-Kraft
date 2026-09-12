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

import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import ru.lazyhat.compukters.ide.analysis.k2.query.K2QueryFixture
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.UpdateSnapshotRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IncrementalK2WorkspaceTest {
    @Test
    fun `source declaration index follows incremental object additions and renames`() {
        val revisions =
            listOf(
                "object lд\nfun main() = Unit",
                "object A\nfun main() = Unit",
                "object B\nfun main() = Unit",
                "object A\nfun main() = Unit",
            )

        K2QueryFixture.source("main.kt" to "object l\nfun main() = Unit").use { incremental ->
            incremental.execute(incremental.presentation()).presentation()
            revisions.forEach { source ->
                incremental.update("main.kt" to source)
                incremental.execute(
                    AnalysisQuery.Completion(
                        incremental.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.indexOf('\n'),
                        CompletionTrigger.Automatic,
                    ),
                )
                K2QueryFixture.source("main.kt" to source).use { fresh ->
                    assertEquals(
                        fresh.execute(fresh.presentation()).presentation(),
                        incremental.execute(incremental.presentation()).presentation(),
                        source,
                    )
                }
            }
        }
    }

    @Test
    fun `body signature import and invalid syntax updates match fresh K2 snapshots`() {
        val main =
            """
            package sample

            fun main() {
                val result = value()
                result.sub
            }
            """.trimIndent()
        val revisions =
            listOf(
                "package sample\nfun value(): String = \"changed\"",
                "package sample\nfun value(prefix: String = \"\"): String = prefix + \"changed\"",
                "package sample\nimport kotlin.text.*\nfun value(): String = emptyList<String>().joinToString()",
                "package sample\nfun value(): String = listOf(",
            )

        K2QueryFixture
            .source(
                "sample/Main.kt" to main,
                "sample/Value.kt" to "package sample\nfun value(): String = \"initial\"",
            ).use { incremental ->
                revisions.forEach { valueSource ->
                    incremental.update("sample/Value.kt" to valueSource)
                    K2QueryFixture
                        .source(
                            "sample/Main.kt" to main,
                            "sample/Value.kt" to valueSource,
                        ).use { fresh ->
                            assertEquivalent(incremental, fresh, main)
                        }
                }
            }
    }

    @Test
    fun `validation failures happen before mutation and preserve the active snapshot`() {
        K2QueryFixture.source("main.kt" to "fun value(): Int = 1").use { fixture ->
            val originalIdentity = fixture.identity
            val originalPresentation = fixture.execute(fixture.presentation()).presentation()
            val valid = fixture.updateRequest("main.kt" to "fun value(): Int = 2")
            val stale =
                UpdateSnapshotRequest(
                    RequestId.of(91uL),
                    AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.zero()), originalIdentity.profile),
                    valid.targetIdentity,
                    valid.changedSources,
                )
            assertFailsWith<IllegalArgumentException> { fixture.workspace.update(stale, AnalysisLimits()) }

            val wrongTarget =
                UpdateSnapshotRequest(
                    RequestId.of(92uL),
                    originalIdentity,
                    AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.zero()), originalIdentity.profile),
                    valid.changedSources,
                )
            assertFailsWith<IllegalArgumentException> { fixture.workspace.update(wrongTarget, AnalysisLimits()) }

            val unknown =
                ProjectSource(
                    VirtualSourcePath.kotlin("unknown.kt"),
                    BinaryValue.of("fun unknown() = Unit".encodeToByteArray()),
                )
            val unknownTarget =
                UpdateSnapshotRequest(
                    RequestId.of(93uL),
                    originalIdentity,
                    valid.targetIdentity,
                    listOf(unknown),
                )
            assertFailsWith<IllegalArgumentException> { fixture.workspace.update(unknownTarget, AnalysisLimits()) }

            assertEquals(originalIdentity, fixture.workspace.view().identity)
            assertEquals(originalPresentation, fixture.execute(fixture.presentation()).presentation())
        }
    }

    @Test
    fun `mutation failure poisons and closes the workspace`() {
        val updater = K2SourceUpdater { _, _, _ -> error("synthetic mutation failure") }
        K2QueryFixture.sourceWithUpdater(updater, "main.kt" to "fun value(): Int = 1").use { fixture ->
            val request = fixture.updateRequest("main.kt" to "fun value(): Int = 2")

            assertFailsWith<K2WorkspaceReopenRequiredException> {
                fixture.workspace.update(request, AnalysisLimits())
            }
            assertFailsWith<IllegalStateException> { fixture.workspace.view() }
        }
    }

    private fun assertEquivalent(
        incremental: K2QueryFixture,
        fresh: K2QueryFixture,
        main: String,
    ) {
        val incrementalPresentation =
            incremental.execute(incremental.presentation("sample/Main.kt")).presentation()
        val freshPresentation = fresh.execute(fresh.presentation("sample/Main.kt")).presentation()
        assertEquals(freshPresentation, incrementalPresentation)

        val offset = main.indexOf("result.sub") + "result.sub".length
        val incrementalCompletion =
            incremental.execute(
                AnalysisQuery.Completion(
                    incremental.identity,
                    VirtualSourcePath.kotlin("sample/Main.kt"),
                    offset,
                    CompletionTrigger.Automatic,
                ),
            ) as AnalysisResult.Completion
        val freshCompletion =
            fresh.execute(
                AnalysisQuery.Completion(
                    fresh.identity,
                    VirtualSourcePath.kotlin("sample/Main.kt"),
                    offset,
                    CompletionTrigger.Automatic,
                ),
            ) as AnalysisResult.Completion
        assertEquals(freshCompletion, incrementalCompletion)
    }
}

private fun AnalysisResult.presentation(): SnapshotPresentationAcceptance.Active {
    val result = this as AnalysisResult.Presentation
    return result.value.accept(result.identity) as SnapshotPresentationAcceptance.Active
}
