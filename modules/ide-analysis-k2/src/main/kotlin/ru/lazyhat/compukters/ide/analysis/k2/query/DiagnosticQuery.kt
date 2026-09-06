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

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.collectDiagnostics
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtFile
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange

internal object DiagnosticQuery {
    fun collect(
        session: KaSession,
        path: VirtualSourcePath,
        file: KtFile,
        limits: AnalysisLimits,
    ): List<EditorDiagnostic> {
        val result = mutableListOf<EditorDiagnostic>()
        file.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is PsiErrorElement) {
                        if (result.size >= limits.diagnostics) {
                            throw AnalysisOutputLimitException("diagnostic count exceeds analysis limit")
                        }
                        val textRange = element.textRange.takeUnless { it.isEmpty }
                        result +=
                            EditorDiagnostic(
                                severity = EditorDiagnosticSeverity.Error,
                                message =
                                    requiredBoundedUtf8(
                                        element.errorDescription,
                                        limits.diagnosticTextBytes,
                                        "diagnostic text",
                                    ),
                                path = path,
                                range = textRange?.let { EditorRange(it.startOffset, it.endOffset) },
                            )
                    }
                    super.visitElement(element)
                }
            },
        )
        if (result.isNotEmpty()) return result.sorted()
        val diagnostics = with(session) { file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS) }
        diagnostics.forEach { diagnostic ->
            if (result.size >= limits.diagnostics) {
                throw AnalysisOutputLimitException("diagnostic count exceeds analysis limit")
            }
            val range = diagnostic.textRanges.firstOrNull { !it.isEmpty }
            result +=
                EditorDiagnostic(
                    severity = diagnostic.severity.toEditorSeverity(),
                    message = requiredBoundedUtf8(diagnostic.defaultMessage, limits.diagnosticTextBytes, "diagnostic text"),
                    path = path,
                    range = range?.let { EditorRange(it.startOffset, it.endOffset) },
                )
        }
        return result.sorted()
    }

    private fun List<EditorDiagnostic>.sorted(): List<EditorDiagnostic> =
        sortedWith(compareBy({ it.path?.value }, { it.range?.startUtf16 ?: -1 }, { it.message }))
}

private fun KaSeverity.toEditorSeverity(): EditorDiagnosticSeverity =
    when (this) {
        KaSeverity.INFO -> EditorDiagnosticSeverity.Info
        KaSeverity.WARNING -> EditorDiagnosticSeverity.Warning
        KaSeverity.ERROR -> EditorDiagnosticSeverity.Error
    }

internal fun boundedUtf8(
    value: String,
    maximumBytes: Int,
): String {
    val result = StringBuilder()
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val text = String(Character.toChars(codePoint))
        val encoded = text.encodeToByteArray().size
        if (bytes + encoded > maximumBytes) break
        result.append(text)
        bytes += encoded
        index += Character.charCount(codePoint)
    }
    return result.toString()
}

internal fun requiredBoundedUtf8(
    value: String,
    maximumBytes: Int,
    label: String,
): String = boundedUtf8(value, maximumBytes).ifEmpty { throw AnalysisOutputLimitException("$label cannot fit analysis limit") }
