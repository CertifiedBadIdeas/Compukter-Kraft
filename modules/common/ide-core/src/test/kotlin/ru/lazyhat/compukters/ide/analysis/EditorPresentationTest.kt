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
import kotlin.test.assertIs

class EditorPresentationTest {
    private val main = VirtualSourcePath.kotlin("src/main.kt")
    private val util = VirtualSourcePath.kotlin("src/util.kt")
    private val snapshotId = SourceSnapshotId(Hash256.of(ByteArray(32) { 1 }))
    private val profileId = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 2 }))
    private val identity = AnalysisSnapshotIdentity(snapshotId, profileId)

    @Test
    fun `matching snapshot exposes defensive positional data while mismatch is stale`() {
        val diagnostics = mutableListOf(EditorDiagnostic(EditorDiagnosticSeverity.Error, "broken", main, EditorRange(1, 2)))
        val tokens = mutableListOf(SemanticToken(main, EditorRange(0, 3), SemanticCategory.Function))
        val locations = mutableListOf(SourceLocation(util, EditorRange(2, 4)))
        val presentation =
            SnapshotPresentation.create(
                identity,
                mapOf(main to 10, util to 5),
                diagnostics,
                tokens,
                locations,
            )
        diagnostics.clear()
        tokens.clear()
        locations.clear()

        val active = assertIs<SnapshotPresentationAcceptance.Active>(presentation.accept(identity))
        assertEquals(1, active.diagnostics.size)
        assertEquals(1, active.semanticTokens.size)
        assertEquals(1, active.locations.size)

        val differentSource = AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.of(ByteArray(32) { 3 })), profileId)
        val differentProfile = AnalysisSnapshotIdentity(snapshotId, AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 4 })))
        assertEquals(SnapshotPresentationAcceptance.Stale, presentation.accept(differentSource))
        assertEquals(SnapshotPresentationAcceptance.Stale, presentation.accept(differentProfile))
    }

    @Test
    fun `presentation rejects unknown paths invalid ranges and exceeded bounds`() {
        val sources = mapOf(main to 4)
        assertFailsWith<IllegalArgumentException> {
            SnapshotPresentation.create(
                identity,
                sources,
                semanticTokens = listOf(SemanticToken(util, EditorRange(0, 1), SemanticCategory.Class)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotPresentation.create(
                identity,
                sources,
                locations = listOf(SourceLocation(main, EditorRange(3, 5))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticToken(main, EditorRange(1, 1), SemanticCategory.Property)
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotPresentation.create(
                identity,
                sources,
                diagnostics = listOf(EditorDiagnostic(EditorDiagnosticSeverity.Warning, "too long")),
                limits = EditorPresentationLimits(maxDiagnostics = 0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotPresentation.create(
                identity,
                sources,
                diagnostics = listOf(EditorDiagnostic(EditorDiagnosticSeverity.Info, "😀")),
                limits = EditorPresentationLimits(maxDiagnosticMessageUtf8Bytes = 3),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EditorDiagnostic(EditorDiagnosticSeverity.Error, "\uD800")
        }
    }
}
