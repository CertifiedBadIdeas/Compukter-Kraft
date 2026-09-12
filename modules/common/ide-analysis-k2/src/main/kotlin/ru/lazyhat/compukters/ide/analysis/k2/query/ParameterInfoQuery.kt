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
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.resolveToCallCandidates
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.signatures.KaFunctionSignature
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.types.Variance
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisResultLimits
import ru.lazyhat.compukters.ide.analysis.EditorParameterInfo
import ru.lazyhat.compukters.ide.analysis.ParameterInfoItem
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange

@OptIn(KaExperimentalApi::class)
internal object ParameterInfoQuery {
    fun execute(
        query: AnalysisQuery.ParameterInfo,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): AnalysisResult.ParameterInfo {
        val file = requireNotNull(snapshot.files[query.path]) { "analysis source path is not active" }
        val sourceLength = snapshot.sourceLengthsUtf16.getValue(query.path)
        require(query.offsetUtf16 <= sourceLength) { "analysis cursor exceeds source" }
        val arguments = argumentListAt(file, query.offsetUtf16, sourceLength)
        val value =
            arguments?.let { list ->
                val call = list.parent as? KtCallElement ?: return@let null
                val activeArgument = activeArgument(list, query.offsetUtf16)
                val activeName =
                    list.arguments
                        .getOrNull(activeArgument)
                        ?.getArgumentName()
                        ?.asName
                        ?.asString()
                val candidates =
                    analyze(file) {
                        (call as KtElement)
                            .resolveToCallCandidates()
                            .mapNotNull { candidate ->
                                val function = candidate.candidate as? KaFunctionCall<*> ?: return@mapNotNull null
                                renderCandidate(
                                    call,
                                    function.partiallyAppliedSymbol.signature,
                                    activeArgument,
                                    activeName,
                                    candidate.isInBestCandidates,
                                    limits,
                                )
                            }.sortedWith(
                                compareByDescending<ParameterInfoItem>(
                                    ParameterInfoItem::bestCandidate,
                                ).thenBy(ParameterInfoItem::signature),
                            ).distinctBy(ParameterInfoItem::signature)
                            .take(limits.parameterInfoItems)
                    }
                candidates.takeIf(List<*>::isNotEmpty)?.let { items ->
                    val range = call.textRange
                    EditorParameterInfo(query.path, EditorRange(range.startOffset, range.endOffset), items)
                }
            }
        return AnalysisResult.ParameterInfo.create(
            query.identity,
            value,
            snapshot.sourceLengthsUtf16,
            AnalysisResultLimits(
                maxParameterInfoItems = limits.parameterInfoItems,
                maxDetailUtf8Bytes = limits.detailTextBytes,
            ),
        )
    }

    private fun argumentListAt(
        file: org.jetbrains.kotlin.psi.KtFile,
        offsetUtf16: Int,
        sourceLength: Int,
    ): KtValueArgumentList? {
        if (sourceLength == 0) return null
        val probes = listOf(offsetUtf16.coerceAtMost(sourceLength - 1), (offsetUtf16 - 1).coerceAtLeast(0)).distinct()
        return probes
            .asSequence()
            .flatMap { probe -> generateSequence(file.findElementAt(probe)) { it.parent }.filterIsInstance<KtValueArgumentList>() }
            .filter { list ->
                val left = list.leftParenthesis?.textRange?.endOffset ?: return@filter false
                val right = list.rightParenthesis?.textRange?.startOffset ?: list.textRange.endOffset
                offsetUtf16 in left..right
            }.minByOrNull { it.textRange.length }
    }

    private fun activeArgument(
        list: KtValueArgumentList,
        offsetUtf16: Int,
    ): Int = list.node.getChildren(null).count { child -> child.elementType == KtTokens.COMMA && child.startOffset < offsetUtf16 }

    private fun KaSession.renderCandidate(
        call: KtCallElement,
        signature: KaFunctionSignature<*>,
        activeArgument: Int,
        activeName: String?,
        bestCandidate: Boolean,
        limits: AnalysisLimits,
    ): ParameterInfoItem {
        val parameters = signature.valueParameters
        val activeIndex =
            activeName?.let { name -> parameters.indexOfFirst { parameter -> parameter.name.asString() == name } }
                ?: when {
                    activeArgument in parameters.indices -> activeArgument
                    parameters.lastOrNull()?.symbol?.isVararg == true -> parameters.lastIndex
                    else -> -1
                }
        var activeRange: EditorRange? = null
        val rendered =
            buildString {
                append(call.calleeExpression?.text ?: "<call>")
                append('(')
                parameters.forEachIndexed { index, parameter ->
                    if (index > 0) append(", ")
                    val start = length
                    if (parameter.symbol.isVararg) append("vararg ")
                    append(parameter.name.asString())
                    append(": ")
                    append(parameter.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT))
                    if (parameter.symbol.hasDefaultValue) append(" = …")
                    if (index == activeIndex) activeRange = EditorRange(start, length)
                }
                append("): ")
                append(signature.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT))
            }.let { requiredBoundedUtf8(it, limits.detailTextBytes, "parameter-info signature") }
        return ParameterInfoItem(rendered, activeRange, bestCandidate)
    }
}
