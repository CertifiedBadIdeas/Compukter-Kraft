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

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import ru.lazyhat.compukters.ide.editor.EditorRange

internal enum class KeywordContext {
    File,
    ClassBody,
    Block,
    None,
}

internal data class CompletionContext(
    val prefix: String,
    val replacement: EditorRange,
    val position: KtElement,
    val receiver: org.jetbrains.kotlin.psi.KtExpression?,
    val keywordContext: KeywordContext,
) {
    companion object {
        fun parse(
            file: KtFile,
            source: String,
            offsetUtf16: Int,
        ): CompletionContext {
            var start = offsetUtf16
            while (start > 0) {
                val codePoint = source.codePointBefore(start)
                if (!Character.isJavaIdentifierPart(codePoint)) break
                start -= Character.charCount(codePoint)
            }
            val prefix = source.substring(start, offsetUtf16)
            val leafOffset = (offsetUtf16 - 1).coerceAtLeast(0).coerceAtMost((source.length - 1).coerceAtLeast(0))
            val leaf = file.findElementAt(leafOffset)
            val position = leaf?.let { generateSequence(it) { element -> element.parent }.filterIsInstance<KtElement>().first() } ?: file
            val name =
                generateSequence(position as com.intellij.psi.PsiElement?) { it.parent }
                    .filterIsInstance<KtNameReferenceExpression>()
                    .firstOrNull { it.textRange.startOffset == start }
            val qualified =
                (name?.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == name }
                    ?: if (start > 0 && source[start - 1] == '.') {
                        generateSequence(file.findElementAt(start - 1)) { it.parent }
                            .filterIsInstance<KtQualifiedExpression>()
                            .firstOrNull()
                    } else {
                        null
                    }
            val receiver = qualified?.receiverExpression
            val keywordContext = keywordContext(leaf, receiver)
            return CompletionContext(prefix, EditorRange(start, offsetUtf16), position, receiver, keywordContext)
        }

        private fun keywordContext(
            leaf: PsiElement?,
            receiver: org.jetbrains.kotlin.psi.KtExpression?,
        ): KeywordContext {
            if (leaf == null || receiver != null) return KeywordContext.None
            val ancestors = generateSequence(leaf) { it.parent }.toList()
            if (ancestors.any { it is PsiComment || it is KtImportDirective || it is KtPackageDirective }) {
                return KeywordContext.None
            }
            val insideString = ancestors.any { it is KtStringTemplateExpression }
            val insideInterpolation = ancestors.any { it is KtBlockStringTemplateEntry }
            if (insideString && !insideInterpolation) return KeywordContext.None
            return when (ancestors.firstOrNull { it is KtBlockExpression || it is KtClassBody || it is KtDeclarationWithBody }) {
                is KtBlockExpression,
                is KtDeclarationWithBody,
                -> KeywordContext.Block

                is KtClassBody -> KeywordContext.ClassBody

                else -> KeywordContext.File
            }
        }
    }
}
