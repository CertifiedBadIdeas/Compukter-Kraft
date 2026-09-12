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
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.findClass
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.types.Variance
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.editor.EditorRange

internal object DeclarationOriginMapper {
    fun MappedDeclaration.origin(): DeclarationOrigin =
        when (this) {
            is MappedDeclaration.Location -> value.origin
            is MappedDeclaration.PlatformTarget -> DeclarationOrigin.Platform(identity)
        }

    @OptIn(KaExperimentalApi::class)
    fun KaSession.map(
        symbol: KaSymbol,
        snapshot: AdmittedK2Snapshot,
    ): MappedDeclaration? {
        val navigableSymbol = (symbol as? KaConstructorSymbol)?.containingClassId?.let(::findClass) ?: symbol
        val declaration =
            if (navigableSymbol.origin == KaSymbolOrigin.SOURCE) {
                navigableSymbol.psi as? KtNamedDeclaration
            } else {
                null
            }
        val name = declaration?.nameIdentifier
        val path =
            snapshot.files.entries
                .firstOrNull { (_, file) -> file == declaration?.containingKtFile }
                ?.key
        if (path != null && name != null) {
            return MappedDeclaration.Location(
                DeclarationLocation.Source(
                    DeclarationOrigin.Project,
                    path,
                    EditorRange(name.textRange.startOffset, name.textRange.endOffset),
                ),
            )
        }
        val stableId = navigableSymbol.stableId()?.replace('/', '.') ?: return null
        val targetSignature =
            (navigableSymbol as? KaDeclarationSymbol)?.render(KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES)
        val canonicalSignature = (navigableSymbol as? KaFunctionSymbol)?.let(::canonicalPlatformSignature)
        val candidates = snapshot.platform.declarations.filter { it.symbol == stableId }
        val platformDeclaration =
            candidates.singleOrNull()
                ?: candidates.singleOrNull { declaration -> declaration.signature == canonicalSignature }
                ?: candidates.firstOrNull { declaration -> declaration.signature == targetSignature }
                ?: return null
        val identity = snapshot.moduleIdentities[platformDeclaration.module] ?: return null
        val sourcePath =
            snapshot.platformSourceFiles.keys.singleOrNull { path ->
                path.value == platformDeclaration.sourcePath || path.value.endsWith("/${platformDeclaration.sourcePath}")
            }
        return if (sourcePath != null) {
            MappedDeclaration.PlatformTarget(
                identity,
                sourcePath,
                platformDeclaration.startUtf16,
                platformDeclaration.endUtf16,
            )
        } else {
            MappedDeclaration.Location(DeclarationLocation.SourceUnavailable(DeclarationOrigin.Platform(identity)))
        }
    }

    @OptIn(KaExperimentalApi::class)
    fun resolvePlatformSource(
        target: MappedDeclaration.PlatformTarget,
        snapshot: AdmittedK2Snapshot,
    ): DeclarationLocation {
        val file =
            snapshot.platformSourceFiles[target.sourcePath]
                ?: return DeclarationLocation.SourceUnavailable(DeclarationOrigin.Platform(target.identity))
        val declaration =
            generateSequence(file.findElementAt(target.startUtf16.coerceAtMost(file.textLength - 1))) { it.parent }
                .filterIsInstance<KtNamedDeclaration>()
                .firstOrNull {
                    it.textRange.startOffset <= target.startUtf16 && it.textRange.endOffset >= target.endUtf16
                }
                ?: return DeclarationLocation.SourceUnavailable(DeclarationOrigin.Platform(target.identity))
        val identifier =
            declaration.nameIdentifier
                ?: return DeclarationLocation.SourceUnavailable(DeclarationOrigin.Platform(target.identity))
        return DeclarationLocation.Source(
            DeclarationOrigin.Platform(target.identity),
            target.sourcePath,
            EditorRange(identifier.textRange.startOffset, identifier.textRange.endOffset),
        )
    }
}

@OptIn(KaExperimentalApi::class)
private fun KaSession.canonicalPlatformSignature(symbol: KaFunctionSymbol): String {
    val parameters =
        listOfNotNull(symbol.receiverParameter?.returnType)
            .plus(symbol.valueParameters.map { it.returnType })
            .joinToString(",") { type ->
                type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
            }
    val result = symbol.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
    return "fun($parameters):$result"
}

private fun KaSymbol.stableId(): String? =
    when (this) {
        is KaCallableSymbol -> callableId?.toString()
        is KaClassLikeSymbol -> classId?.asString()
        else -> null
    }

internal sealed interface MappedDeclaration {
    data class Location(
        val value: DeclarationLocation,
    ) : MappedDeclaration

    data class PlatformTarget(
        val identity: ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity,
        val sourcePath: ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath,
        val startUtf16: Int,
        val endUtf16: Int,
    ) : MappedDeclaration
}
