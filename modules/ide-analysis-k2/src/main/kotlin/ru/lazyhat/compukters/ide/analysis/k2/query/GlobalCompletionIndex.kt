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

import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformCompletionKind

internal data class GlobalCompletionDeclaration(
    val fqName: String,
    val shortName: String,
    val signature: String,
    val kind: CompletionKind,
    val origin: DeclarationOrigin,
    val sourcePath: VirtualSourcePath,
    val sourceRange: EditorRange,
    val defaultImport: Boolean,
)

internal class GlobalCompletionIndex private constructor(
    private val declarationsByPath: MutableMap<VirtualSourcePath, List<GlobalCompletionDeclaration>>,
    declarations: List<GlobalCompletionDeclaration>,
) {
    private var ordered = declarations.sortedWith(ORDER)

    fun lookup(
        prefix: String,
        limit: Int,
    ): List<GlobalCompletionDeclaration> {
        require(limit >= 0) { "completion lookup limit must be non-negative" }
        if (limit == 0) return emptyList()
        var low = 0
        var high = ordered.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ordered[middle].shortName < prefix) low = middle + 1 else high = middle
        }
        val result = ArrayList<GlobalCompletionDeclaration>(minOf(limit, ordered.size - low))
        var index = low
        while (index < ordered.size && result.size < limit) {
            val declaration = ordered[index++]
            if (!declaration.shortName.startsWith(prefix)) break
            result += declaration
        }
        return result
    }

    fun updateProjectFile(
        path: VirtualSourcePath,
        file: org.jetbrains.kotlin.psi.KtFile,
    ) {
        require(path in declarationsByPath) { "completion index does not contain ${path.value}" }
        declarationsByPath[path] = projectDeclarations(path, file)
        ordered = declarationsByPath.values.flatten().sortedWith(ORDER)
    }

    companion object {
        fun project(files: Map<VirtualSourcePath, org.jetbrains.kotlin.psi.KtFile>): GlobalCompletionIndex {
            val byPath = files.mapValuesTo(linkedMapOf()) { (path, file) -> projectDeclarations(path, file) }
            return GlobalCompletionIndex(byPath, byPath.values.flatten())
        }

        fun platform(bundle: PlatformBundle): GlobalCompletionIndex {
            val declarations =
                (listOf(bundle.builtins) + bundle.modules).flatMap { module ->
                    val identity =
                        AnalysisModuleIdentity(
                            module.id.toString(),
                            Hash256.of(PlatformBundleCodec.moduleContentHash(module).toByteArray()),
                        )
                    module.completionDeclarations.map { declaration ->
                        GlobalCompletionDeclaration(
                            fqName = declaration.symbol,
                            shortName = declaration.shortName,
                            signature = declaration.signature,
                            kind = declaration.kind.completionKind(),
                            origin = DeclarationOrigin.Platform(identity),
                            sourcePath = VirtualSourcePath.kotlin(declaration.sourcePath),
                            sourceRange = EditorRange(declaration.startUtf16, declaration.endUtf16),
                            defaultImport = declaration.defaultImport,
                        )
                    }
                }
            return GlobalCompletionIndex(linkedMapOf(), declarations)
        }

        private fun projectDeclarations(
            path: VirtualSourcePath,
            file: org.jetbrains.kotlin.psi.KtFile,
        ): List<GlobalCompletionDeclaration> =
            file.declarations.mapNotNull { declaration ->
                if (
                    declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) ||
                    declaration.hasModifier(KtTokens.INTERNAL_KEYWORD) ||
                    declaration.hasModifier(KtTokens.PROTECTED_KEYWORD)
                ) {
                    return@mapNotNull null
                }
                if ((declaration as? KtNamedFunction)?.receiverTypeReference != null) return@mapNotNull null
                if ((declaration as? KtProperty)?.receiverTypeReference != null) return@mapNotNull null
                val shortName = (declaration as? KtNamedDeclaration)?.name ?: return@mapNotNull null
                val kind = declaration.completionKind() ?: return@mapNotNull null
                val packageName = file.packageFqName.asString()
                GlobalCompletionDeclaration(
                    fqName = listOf(packageName, shortName).filter(String::isNotEmpty).joinToString("."),
                    shortName = shortName,
                    signature = declaration.presentationSignature(),
                    kind = kind,
                    origin = DeclarationOrigin.Project,
                    sourcePath = path,
                    sourceRange = EditorRange(declaration.textRange.startOffset, declaration.textRange.endOffset),
                    defaultImport = false,
                )
            }

        private val ORDER =
            compareBy<GlobalCompletionDeclaration>(
                GlobalCompletionDeclaration::shortName,
                GlobalCompletionDeclaration::fqName,
                GlobalCompletionDeclaration::signature,
                { it.sourcePath.value },
                { it.sourceRange.startUtf16 },
            )
    }
}

private fun KtDeclaration.completionKind(): CompletionKind? =
    when (this) {
        is KtObjectDeclaration -> CompletionKind.Object
        is KtClass -> if (isInterface()) CompletionKind.Interface else CompletionKind.Class
        is KtNamedFunction -> CompletionKind.Function
        is KtProperty -> CompletionKind.Property
        is KtTypeAlias -> CompletionKind.TypeAlias
        else -> null
    }

private fun KtDeclaration.presentationSignature(): String =
    text
        .substringBefore('{')
        .substringBefore('=')
        .trim()
        .replace(Regex("\\s+"), " ")

private fun PlatformCompletionKind.completionKind(): CompletionKind =
    when (this) {
        PlatformCompletionKind.CLASS -> CompletionKind.Class
        PlatformCompletionKind.INTERFACE -> CompletionKind.Interface
        PlatformCompletionKind.FUNCTION -> CompletionKind.Function
        PlatformCompletionKind.PROPERTY -> CompletionKind.Property
        PlatformCompletionKind.OBJECT -> CompletionKind.Object
        PlatformCompletionKind.TYPE_ALIAS -> CompletionKind.TypeAlias
    }
