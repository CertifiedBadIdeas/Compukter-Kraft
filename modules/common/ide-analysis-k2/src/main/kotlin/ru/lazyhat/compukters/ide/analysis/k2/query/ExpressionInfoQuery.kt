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
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.resolveSymbol
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtVariableDeclaration
import org.jetbrains.kotlin.types.Variance
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisResultLimits
import ru.lazyhat.compukters.ide.analysis.EditorExpressionInfo
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange

internal object ExpressionInfoQuery {
    @OptIn(KaExperimentalApi::class)
    fun execute(
        query: AnalysisQuery.ExpressionInfo,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): AnalysisResult.ExpressionInfo {
        val file = requireNotNull(snapshot.files[query.path]) { "analysis source path is not active" }
        val sourceLength = snapshot.sourceLengthsUtf16.getValue(query.path)
        require(query.offsetUtf16 <= sourceLength) { "analysis cursor exceeds source" }
        val element = if (sourceLength == 0) null else file.findElementAt(query.offsetUtf16.coerceAtMost(sourceLength - 1))
        val expressions =
            generateSequence(element) { it.parent }
                .filterIsInstance<KtExpression>()
                .filter { it.textRange.length > 0 }
                .toList()
        val declaration =
            generateSequence(element) { it.parent }
                .filterIsInstance<KtVariableDeclaration>()
                .firstOrNull { candidate ->
                    val range = candidate.nameIdentifier?.textRange
                    range != null && query.offsetUtf16 in range.startOffset until range.endOffset
                }
        val info =
            element?.let {
                analyze(file) {
                    val declarationSymbol = declaration?.symbol as? KaCallableSymbol
                    if (declarationSymbol != null) {
                        val nameRange = requireNotNull(declaration.nameIdentifier).textRange
                        return@analyze EditorExpressionInfo(
                            query.path,
                            EditorRange(nameRange.startOffset, nameRange.endOffset),
                            requiredBoundedUtf8(
                                declarationSymbol.returnType.render(position = Variance.INVARIANT),
                                limits.detailTextBytes,
                                "rendered type",
                            ),
                            signature =
                                declarationSymbol
                                    .render(KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES)
                                    .let { value -> requiredBoundedUtf8(value, limits.detailTextBytes, "callable signature") },
                            origin =
                                DeclarationOriginMapper
                                    .run { map(declarationSymbol, snapshot) }
                                    ?.let { mapped -> DeclarationOriginMapper.run { mapped.origin() } },
                        )
                    }
                    val expression = expressions.firstOrNull { candidate -> candidate.expressionType != null } ?: return@analyze null
                    val rendered =
                        requiredBoundedUtf8(
                            requireNotNull(expression.expressionType).render(position = Variance.INVARIANT),
                            limits.detailTextBytes,
                            "rendered type",
                        )
                    val symbol = expressions.filterIsInstance<KtNameReferenceExpression>().firstOrNull()?.resolveSymbol()
                    val signature =
                        symbol
                            ?.render(KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES)
                            ?.let { value -> requiredBoundedUtf8(value, limits.detailTextBytes, "callable signature") }
                    val origin =
                        symbol
                            ?.let { resolved -> DeclarationOriginMapper.run { map(resolved, snapshot) } }
                            ?.let { mapped -> DeclarationOriginMapper.run { mapped.origin() } }
                    EditorExpressionInfo(
                        query.path,
                        EditorRange(expression.textRange.startOffset, expression.textRange.endOffset),
                        rendered,
                        signature = signature,
                        origin = origin,
                    )
                }
            }
        return AnalysisResult.ExpressionInfo.create(
            query.identity,
            info,
            snapshot.sourceLengthsUtf16,
            AnalysisResultLimits(maxDetailUtf8Bytes = limits.detailTextBytes),
        )
    }
}
