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

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.resolveSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisResultLimits
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange

internal object ReferenceQuery {
    @OptIn(KaExperimentalApi::class)
    fun execute(
        query: AnalysisQuery.References,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): AnalysisResult.References {
        val queryFile = requireNotNull(snapshot.files[query.path]) { "analysis source path is not active" }
        require(query.offsetUtf16 <= snapshot.sourceLengthsUtf16.getValue(query.path)) { "analysis cursor exceeds source" }
        val locations =
            analyze(queryFile) {
                val targets = resolveCursorSymbols(queryFile, query.offsetUtf16).toSet()
                if (targets.isEmpty()) return@analyze emptyList()
                val found = ArrayList<DeclarationLocation.Source>(minOf(limits.references, 64))
                snapshot.files.entries.sortedBy { it.key.value }.forEach { (path, file) ->
                    file.accept(
                        object : KtTreeVisitorVoid() {
                            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                                if (expression !is KtNameReferenceExpression) {
                                    super.visitSimpleNameExpression(expression)
                                    return
                                }
                                val resolved: KaSymbol? = expression.resolveSymbol()
                                if (resolved != null && targets.any { target -> target == resolved }) {
                                    if (found.size >= limits.references) {
                                        throw AnalysisOutputLimitException("references exceed negotiated limit")
                                    }
                                    found +=
                                        DeclarationLocation.Source(
                                            DeclarationOrigin.Project,
                                            path,
                                            EditorRange(expression.textRange.startOffset, expression.textRange.endOffset),
                                        )
                                }
                                super.visitSimpleNameExpression(expression)
                            }
                        },
                    )
                }
                found.sortedWith(compareBy({ it.path.value }, { it.range.startUtf16 }, { it.range.endUtf16 }))
            }
        return AnalysisResult.References.create(
            query.identity,
            locations,
            snapshot.sourceLengthsUtf16,
            AnalysisResultLimits(maxReferences = limits.references),
        )
    }
}
