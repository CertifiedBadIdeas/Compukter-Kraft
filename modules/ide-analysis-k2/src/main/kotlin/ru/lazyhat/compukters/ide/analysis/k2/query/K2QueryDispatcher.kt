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

package ru.lazyhat.compukters.ide.analysis.k2.query

import com.intellij.openapi.application.ReadAction
import org.jetbrains.kotlin.analysis.api.analyze
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.EditorPresentationLimits
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentation
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits

internal object K2QueryDispatcher {
    fun execute(
        query: AnalysisQuery,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): AnalysisResult {
        require(query.identity == snapshot.identity) { "analysis query identity is not active" }
        return ReadAction.compute<AnalysisResult, RuntimeException> {
            when (query) {
                is AnalysisQuery.Presentation -> presentation(query, snapshot, limits)
                is AnalysisQuery.Completion -> CompletionQuery.execute(query, snapshot, limits)
                is AnalysisQuery.ExpressionInfo -> ExpressionInfoQuery.execute(query, snapshot, limits)
                is AnalysisQuery.Declaration -> DeclarationQuery.execute(query, snapshot, limits)
                is AnalysisQuery.References -> ReferenceQuery.execute(query, snapshot, limits)
                is AnalysisQuery.Format -> error("format queries execute outside K2 read actions")
            }
        }
    }

    private fun presentation(
        query: AnalysisQuery.Presentation,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): AnalysisResult.Presentation {
        val file = requireNotNull(snapshot.files[query.path]) { "analysis source path is not active" }
        val collected =
            analyze(file) {
                PresentationParts(
                    DiagnosticQuery.collect(this, query.path, file, limits),
                    SemanticTokenQuery.collect(this, query.path, file, limits),
                )
            }
        val presentation =
            SnapshotPresentation.create(
                query.identity,
                snapshot.sourceLengthsUtf16,
                diagnostics = collected.diagnostics,
                semanticTokens = collected.semanticTokens,
                limits =
                    EditorPresentationLimits(
                        maxDiagnostics = limits.diagnostics,
                        maxDiagnosticMessageUtf8Bytes = limits.diagnosticTextBytes,
                        maxSemanticTokens = limits.semanticTokens,
                    ),
            )
        return AnalysisResult.Presentation(query.identity, presentation)
    }
}

private data class PresentationParts(
    val diagnostics: List<ru.lazyhat.compukters.ide.analysis.EditorDiagnostic>,
    val semanticTokens: List<ru.lazyhat.compukters.ide.analysis.SemanticToken>,
)

internal class AnalysisOutputLimitException(
    message: String,
) : RuntimeException(message)
