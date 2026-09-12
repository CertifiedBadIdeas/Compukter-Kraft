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

package ru.lazyhat.compukters.ide.analysis

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnalysisModelsTest {
    private val main = VirtualSourcePath.kotlin("src/main.kt")
    private val identity = AnalysisSnapshotIdentity(SourceSnapshotId(hash(1)), AnalysisProfileIdentity(hash(2)))

    @Test
    fun `queries reject invalid offsets and source paths`() {
        assertFailsWith<IllegalArgumentException> {
            AnalysisQuery.Completion(identity, main, -1, CompletionTrigger.Manual)
        }
        assertFailsWith<IllegalArgumentException> {
            AnalysisQuery.ExpressionInfo(identity, VirtualSourcePath.of("report.txt"), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AnalysisQuery.Declaration(identity, main, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            AnalysisQuery.Format(identity, main, "😀", 1)
        }
    }

    @Test
    fun `formatted sources enforce caret and UTF-8 bounds`() {
        assertEquals(
            3,
            AnalysisResult.Format
                .create(identity, "val answer = 42\n", 3)
                .caretOffsetUtf16,
        )
        assertFailsWith<IllegalArgumentException> {
            AnalysisResult.Format.create(identity, "😀", 1)
        }
        assertFailsWith<IllegalArgumentException> {
            AnalysisResult.Format.create(
                identity,
                "😀",
                0,
                AnalysisResultLimits(maxSourceFileUtf8Bytes = 3),
            )
        }
    }

    @Test
    fun `completion is bounded strict utf8 and defensively copied`() {
        val items =
            mutableListOf(
                CompletionItem("write", "write", CompletionKind.Function, "fun write(value: String)", DeclarationOrigin.Project),
            )
        val result =
            AnalysisResult.Completion.create(
                identity,
                EditorRange(2, 4),
                items,
                4,
                AnalysisResultLimits(maxCompletionItems = 1),
            )
        items.clear()

        assertEquals(1, result.items.size)
        assertFailsWith<IllegalArgumentException> {
            AnalysisResult.Completion.create(
                identity,
                EditorRange(0, 0),
                List(257) { CompletionItem("v$it", "v$it", CompletionKind.LocalVariable) },
                0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CompletionItem("bad\uD800", "bad", CompletionKind.Function)
        }
        assertFailsWith<IllegalArgumentException> {
            AnalysisResult.Completion.create(
                identity,
                EditorRange(0, 0),
                listOf(CompletionItem("name", "name", CompletionKind.Property, "😀")),
                0,
                AnalysisResultLimits(maxDetailUtf8Bytes = 3),
            )
        }
    }

    @Test
    fun `completion proposals own bounded nonoverlapping import edits`() {
        val edits = mutableListOf(CompletionTextEdit(EditorRange(0, 0), "import sample.Redstone\n\n"))
        val item =
            CompletionItem(
                label = "Redstone",
                insertText = "Redstone",
                kind = CompletionKind.Object,
                symbol = CompletionSymbol("sample.Redstone", "sample.Redstone"),
                additionalEdits = edits,
            )
        edits.clear()

        val result = AnalysisResult.Completion.create(identity, EditorRange(10, 12), listOf(item), 20)

        assertEquals(CompletionKind.TypeAlias, CompletionKind.TypeAlias)
        assertEquals(
            1,
            result.items
                .single()
                .additionalEdits.size,
        )
        assertFailsWith<UnsupportedOperationException> {
            (result.items.single().additionalEdits as MutableList).clear()
        }
        assertFailsWith<IllegalArgumentException> {
            AnalysisResult.Completion.create(
                identity,
                EditorRange(10, 12),
                listOf(
                    CompletionItem(
                        "Redstone",
                        "Redstone",
                        CompletionKind.Object,
                        symbol = CompletionSymbol("sample.Redstone", "sample.Redstone"),
                    ),
                ),
                20,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AnalysisResult.Completion.create(
                identity,
                EditorRange(10, 12),
                listOf(
                    CompletionItem(
                        "Redstone",
                        "Redstone",
                        CompletionKind.Object,
                        additionalEdits = listOf(CompletionTextEdit(EditorRange(11, 11), "import sample.Redstone\n")),
                    ),
                ),
                20,
            )
        }
        assertTrue(
            result.items
                .single()
                .symbol
                ?.fqName == "sample.Redstone",
        )
    }

    @Test
    fun `locations distinguish source availability and enforce source membership ranges and caps`() {
        val unavailable =
            DeclarationLocation
                .SourceUnavailable(DeclarationOrigin.Platform(AnalysisModuleIdentity("std.fs", hash(3))))
        val available = DeclarationLocation.Source(DeclarationOrigin.Project, main, EditorRange(1, 3))
        val sourceLengths = mapOf(main to 3)

        assertEquals(
            2,
            AnalysisResult.Declaration
                .create(identity, listOf(available, unavailable), sourceLengths)
                .locations.size,
        )
        assertFailsWith<IllegalArgumentException> {
            AnalysisResult.Declaration.create(
                identity,
                listOf(DeclarationLocation.Source(DeclarationOrigin.Project, main, EditorRange(2, 4))),
                sourceLengths,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AnalysisResult.References.create(
                identity,
                List(2) { available },
                sourceLengths,
                AnalysisResultLimits(maxReferences = 1),
            )
        }
    }

    @Test
    fun `attached platform declarations require their exact source catalog range`() {
        val bundle = AnalysisModuleIdentity("std.core", hash(4))
        val origin = DeclarationOrigin.Platform(bundle)
        val path = VirtualSourcePath.kotlin("compukter/terminal/Terminal.kt")
        val location = DeclarationLocation.Source(origin, path, EditorRange(3, 8))

        assertEquals(
            listOf(location),
            AnalysisResult.Declaration
                .create(
                    identity,
                    listOf(location),
                    sourceLengthsUtf16 = emptyMap(),
                    platformSourceLengthsUtf16 = mapOf(bundle to mapOf(path to 8)),
                ).locations,
        )
        assertFailsWith<IllegalArgumentException> {
            AnalysisResult.Declaration.create(
                identity,
                listOf(location),
                sourceLengthsUtf16 = emptyMap(),
                platformSourceLengthsUtf16 = mapOf(bundle to mapOf(path to 7)),
            )
        }
    }

    @Test
    fun `expression information enforces bounded strict text and source range`() {
        assertFailsWith<IllegalArgumentException> {
            AnalysisResult.ExpressionInfo.create(
                identity,
                EditorExpressionInfo(main, EditorRange(0, 5), "kotlin.String", null, DeclarationOrigin.Project),
                mapOf(main to 4),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EditorExpressionInfo(main, EditorRange(0, 1), "bad\uDC00", null, null)
        }
    }

    private fun hash(value: Int) = Hash256.of(ByteArray(32) { value.toByte() })
}
