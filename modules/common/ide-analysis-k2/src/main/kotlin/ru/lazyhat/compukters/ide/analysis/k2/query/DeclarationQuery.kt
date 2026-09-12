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
import org.jetbrains.kotlin.analysis.api.components.tryResolveSymbols
import org.jetbrains.kotlin.analysis.api.resolution.KaSymbolResolutionError
import org.jetbrains.kotlin.analysis.api.resolution.KaSymbolResolutionSuccess
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExperimentalApi
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolution.KtResolvable
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisResultLimits
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits

internal object DeclarationQuery {
    fun execute(
        query: AnalysisQuery.Declaration,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): AnalysisResult.Declaration {
        val file = requireNotNull(snapshot.files[query.path]) { "analysis source path is not active" }
        require(query.offsetUtf16 <= snapshot.sourceLengthsUtf16.getValue(query.path)) { "analysis cursor exceeds source" }
        val mapped =
            analyze(file) {
                resolveCursorSymbols(file, query.offsetUtf16)
                    .mapNotNull { symbol -> DeclarationOriginMapper.run { map(symbol, snapshot) } }
            }
        val locations =
            mapped
                .map { declaration ->
                    when (declaration) {
                        is MappedDeclaration.Location -> declaration.value
                        is MappedDeclaration.PlatformTarget -> DeclarationOriginMapper.resolvePlatformSource(declaration, snapshot)
                    }
                }.distinct()
                .sortedWith(declarationLocationOrder)
        if (locations.size > limits.declarationLocations) {
            throw AnalysisOutputLimitException("declaration locations exceed negotiated limit")
        }
        return AnalysisResult.Declaration.create(
            query.identity,
            locations,
            snapshot.sourceLengthsUtf16,
            AnalysisResultLimits(maxDeclarationLocations = limits.declarationLocations),
            platformSourceLengthsUtf16 =
                snapshot.moduleIdentities.values.associateWith {
                    snapshot.platformSourceFiles.mapValues { (_, file) -> file.textLength }
                },
        )
    }
}

@OptIn(KaExperimentalApi::class, KtExperimentalApi::class)
internal fun KaSession.resolveCursorSymbols(
    file: KtFile,
    offsetUtf16: Int,
): List<KaSymbol> {
    if (file.textLength == 0) return emptyList()
    val element = file.findElementAt(offsetUtf16.coerceAtMost(file.textLength - 1)) ?: return emptyList()
    val declaration = generateSequence(element) { it.parent }.filterIsInstance<KtDeclaration>().firstOrNull()
    if (declaration != null && declaration.nameIdentifierRangeContains(offsetUtf16)) return listOf(declaration.symbol)
    generateSequence(element) { it.parent }.filterIsInstance<KtResolvable>().forEach { resolvable ->
        val symbols =
            when (val attempt = resolvable.tryResolveSymbols()) {
                is KaSymbolResolutionSuccess -> attempt.symbols
                is KaSymbolResolutionError -> attempt.candidateSymbols
                else -> emptyList()
            }
        if (symbols.isNotEmpty()) return symbols
    }
    return emptyList()
}

private fun KtDeclaration.nameIdentifierRangeContains(offsetUtf16: Int): Boolean {
    val name = (this as? org.jetbrains.kotlin.psi.KtNamedDeclaration)?.nameIdentifier ?: return false
    return offsetUtf16 in name.textRange.startOffset until name.textRange.endOffset
}

internal val declarationLocationOrder =
    compareBy<DeclarationLocation>(
        { location -> if (location is DeclarationLocation.Source) location.path.value else "" },
        { location -> if (location is DeclarationLocation.Source) location.range.startUtf16 else -1 },
        { location -> if (location is DeclarationLocation.Source) location.range.endUtf16 else -1 },
    )
