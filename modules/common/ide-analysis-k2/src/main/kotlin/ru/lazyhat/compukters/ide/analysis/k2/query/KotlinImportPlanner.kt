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

import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import ru.lazyhat.compukters.ide.analysis.CompletionSymbol
import ru.lazyhat.compukters.ide.analysis.CompletionTextEdit
import ru.lazyhat.compukters.ide.editor.EditorRange

internal data class KotlinImportPlan(
    val insertText: String,
    val symbol: CompletionSymbol,
    val additionalEdits: List<CompletionTextEdit>,
)

internal object KotlinImportPlanner {
    fun plan(
        file: KtFile,
        declaration: GlobalCompletionDeclaration,
        exactNameCandidates: List<GlobalCompletionDeclaration>,
    ): KotlinImportPlan {
        if (isVisible(file, declaration)) {
            return KotlinImportPlan(declaration.shortName, CompletionSymbol(declaration.fqName, null), emptyList())
        }
        if (hasConflict(file, declaration, exactNameCandidates)) {
            return KotlinImportPlan(declaration.fqName, CompletionSymbol(declaration.fqName, null), emptyList())
        }
        return KotlinImportPlan(
            declaration.shortName,
            CompletionSymbol(declaration.fqName, declaration.fqName),
            listOf(importEdit(file, declaration.fqName)),
        )
    }

    private fun isVisible(
        file: KtFile,
        declaration: GlobalCompletionDeclaration,
    ): Boolean {
        if (declaration.defaultImport) return true
        if (declaration.fqName.substringBeforeLast('.', "") == file.packageFqName.asString()) return true
        return file.importDirectives.any { directive ->
            val path = directive.importPath ?: return@any false
            if (path.alias !=
                null
            ) {
                return@any path.alias?.asString() == declaration.shortName && path.fqName.asString() == declaration.fqName
            }
            if (path.isAllUnder) {
                declaration.fqName.substringBeforeLast('.', "") == path.fqName.asString()
            } else {
                path.fqName.asString() == declaration.fqName
            }
        }
    }

    private fun hasConflict(
        file: KtFile,
        declaration: GlobalCompletionDeclaration,
        exactNameCandidates: List<GlobalCompletionDeclaration>,
    ): Boolean {
        val directImportConflict =
            file.importDirectives.any { directive ->
                val path = directive.importPath ?: return@any false
                val visibleName = path.alias?.asString() ?: path.fqName.shortName().asString()
                !path.isAllUnder && visibleName == declaration.shortName && path.fqName.asString() != declaration.fqName
            }
        if (directImportConflict) return true
        val ownDeclarationConflict =
            file.declarations
                .filterIsInstance<KtNamedDeclaration>()
                .any { own ->
                    own.name == declaration.shortName &&
                        declaration.fqName.substringBeforeLast('.', "") != file.packageFqName.asString()
                }
        if (ownDeclarationConflict) return true
        return exactNameCandidates.any { other ->
            other.fqName != declaration.fqName && isVisible(file, other)
        }
    }

    private fun importEdit(
        file: KtFile,
        fqName: String,
    ): CompletionTextEdit {
        val separator = if ("\r\n" in file.text) "\r\n" else "\n"
        val lastImport = file.importDirectives.lastOrNull()
        if (lastImport != null) {
            val offset = lastImport.textRange.endOffset
            return CompletionTextEdit(EditorRange(offset, offset), "${separator}import $fqName")
        }
        val packageDirective = file.packageDirective
        if (packageDirective != null && !file.packageFqName.isRoot) {
            val offset = packageDirective.textRange.endOffset
            return CompletionTextEdit(EditorRange(offset, offset), "$separator${separator}import $fqName")
        }
        return CompletionTextEdit(EditorRange(0, 0), "import $fqName$separator$separator")
    }
}
