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
import org.jetbrains.kotlin.analysis.api.components.resolveSymbol
import org.jetbrains.kotlin.analysis.api.components.smartCastInfo
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeParameter
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.analysis.SemanticToken
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange

internal object SemanticTokenQuery {
    @OptIn(KaExperimentalApi::class)
    fun collect(
        session: KaSession,
        path: VirtualSourcePath,
        file: KtFile,
        limits: AnalysisLimits,
    ): List<SemanticToken> {
        val result = mutableListOf<SemanticToken>()
        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                    declaration.nameIdentifier?.let { identifier ->
                        declaration.category()?.let { category ->
                            result.addBounded(
                                path,
                                identifier.textRange.startOffset,
                                identifier.textRange.endOffset,
                                category,
                                limits,
                                declaration.isMutableDeclaration(),
                            )
                        }
                    }
                    super.visitNamedDeclaration(declaration)
                }

                override fun visitProperty(property: KtProperty) {
                    property.initializer?.takeIf { property.typeReference == null }?.let { initializer ->
                        result.addBounded(
                            path,
                            initializer.textRange.startOffset,
                            initializer.textRange.endOffset,
                            SemanticCategory.InferredExpression,
                            limits,
                        )
                    }
                    super.visitProperty(property)
                }

                override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                    if (expression is KtNameReferenceExpression) {
                        with(session) { expression.resolveSymbol() }
                            ?.semanticClassification()
                            ?.let { classification ->
                                result.addBounded(
                                    path,
                                    expression.textRange.startOffset,
                                    expression.textRange.endOffset,
                                    classification.category,
                                    limits,
                                    classification.isMutable,
                                )
                            }
                        if (with(session) { expression.smartCastInfo } != null) {
                            result.addBounded(
                                path,
                                expression.textRange.startOffset,
                                expression.textRange.endOffset,
                                SemanticCategory.SmartCastExpression,
                                limits,
                            )
                        }
                    }
                    super.visitSimpleNameExpression(expression)
                }
            },
        )
        return result
            .distinct()
            .sortedWith(compareBy({ it.path.value }, { it.range.startUtf16 }, { it.category.ordinal }))
    }
}

private data class SemanticClassification(
    val category: SemanticCategory,
    val isMutable: Boolean,
)

private fun KaSymbol.semanticClassification(): SemanticClassification? {
    val category =
        when (this) {
            is KaEnumEntrySymbol -> {
                SemanticCategory.EnumEntry
            }

            is KaClassSymbol -> {
                when (classKind) {
                    KaClassKind.INTERFACE -> SemanticCategory.Interface

                    KaClassKind.OBJECT,
                    KaClassKind.COMPANION_OBJECT,
                    KaClassKind.ANONYMOUS_OBJECT,
                    -> SemanticCategory.Object

                    else -> SemanticCategory.Class
                }
            }

            is KaNamedFunctionSymbol -> {
                if (isExtension) SemanticCategory.ExtensionFunction else SemanticCategory.Function
            }

            is KaLocalVariableSymbol -> {
                SemanticCategory.LocalVariable
            }

            is KaPropertySymbol -> {
                SemanticCategory.Property
            }

            is KaValueParameterSymbol -> {
                SemanticCategory.Parameter
            }

            is KaTypeParameterSymbol -> {
                SemanticCategory.TypeParameter
            }

            else -> {
                null
            }
        }
    return category?.let { SemanticClassification(it, this is KaVariableSymbol && !isVal) }
}

private fun MutableList<SemanticToken>.addBounded(
    path: ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath,
    start: Int,
    end: Int,
    category: SemanticCategory,
    limits: AnalysisLimits,
    isMutable: Boolean = false,
) {
    if (size >= limits.semanticTokens) throw AnalysisOutputLimitException("semantic-token count exceeds analysis limit")
    this += SemanticToken(path, EditorRange(start, end), category, isMutable)
}

private fun KtNamedDeclaration.category(): SemanticCategory? =
    when (this) {
        is KtEnumEntry -> SemanticCategory.EnumEntry
        is KtObjectDeclaration -> SemanticCategory.Object
        is KtClass -> if (isInterface()) SemanticCategory.Interface else SemanticCategory.Class
        is KtClassOrObject -> SemanticCategory.Class
        is KtNamedFunction -> if (receiverTypeReference != null) SemanticCategory.ExtensionFunction else SemanticCategory.Function
        is KtProperty -> if (isLocal) SemanticCategory.LocalVariable else SemanticCategory.Property
        is KtParameter -> if (hasValOrVar()) SemanticCategory.Property else SemanticCategory.Parameter
        is KtTypeParameter -> SemanticCategory.TypeParameter
        else -> null
    }

private fun KtNamedDeclaration.isMutableDeclaration(): Boolean =
    when (this) {
        is KtProperty -> isVar
        is KtParameter -> valOrVarKeyword?.text == "var"
        else -> false
    }
