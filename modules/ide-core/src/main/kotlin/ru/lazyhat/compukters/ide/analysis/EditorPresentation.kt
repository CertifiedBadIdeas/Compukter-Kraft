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

package ru.lazyhat.compukters.ide.analysis

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

data class EditorPresentationLimits(
    val maxDiagnostics: Int = 64,
    val maxDiagnosticMessageUtf8Bytes: Int = 64 * 1024,
    val maxSemanticTokens: Int = 64 * 1024,
    val maxLocations: Int = 4 * 1024,
) {
    init {
        require(
            maxDiagnostics >= 0 &&
                maxDiagnosticMessageUtf8Bytes >= 0 &&
                maxSemanticTokens >= 0 &&
                maxLocations >= 0,
        ) { "editor presentation limits must be non-negative" }
    }
}

enum class EditorDiagnosticSeverity {
    Info,
    Warning,
    Error,
}

data class EditorDiagnostic(
    val severity: EditorDiagnosticSeverity,
    val message: String,
    val path: VirtualSourcePath? = null,
    val range: EditorRange? = null,
) {
    init {
        require(message.isNotEmpty()) { "diagnostic message must not be empty" }
        strictUtf8Size(message)
        path?.let { VirtualSourcePath.kotlin(it.value) }
        require(range == null || path != null) { "a ranged diagnostic must name its source" }
        require(range == null || range.length > 0) { "diagnostic range must not be empty" }
    }
}

enum class SemanticCategory {
    Class,
    Interface,
    TypeParameter,
    Function,
    ExtensionFunction,
    Property,
    LocalVariable,
    Parameter,
    Object,
    EnumEntry,
    InferredExpression,
    SmartCastExpression,
}

data class SemanticToken(
    val path: VirtualSourcePath,
    val range: EditorRange,
    val category: SemanticCategory,
    val isMutable: Boolean = false,
) {
    init {
        VirtualSourcePath.kotlin(path.value)
        require(range.length > 0) { "semantic token range must not be empty" }
    }
}

data class SourceLocation(
    val path: VirtualSourcePath,
    val range: EditorRange,
) {
    init {
        VirtualSourcePath.kotlin(path.value)
        require(range.length > 0) { "source location range must not be empty" }
    }
}

sealed interface SnapshotPresentationAcceptance {
    data class Active(
        val diagnostics: List<EditorDiagnostic>,
        val semanticTokens: List<SemanticToken>,
        val locations: List<SourceLocation>,
    ) : SnapshotPresentationAcceptance

    data object Stale : SnapshotPresentationAcceptance
}

class SnapshotPresentation private constructor(
    val identity: AnalysisSnapshotIdentity,
    private val diagnostics: List<EditorDiagnostic>,
    private val semanticTokens: List<SemanticToken>,
    private val locations: List<SourceLocation>,
) {
    fun accept(currentIdentity: AnalysisSnapshotIdentity): SnapshotPresentationAcceptance =
        if (currentIdentity == identity) {
            SnapshotPresentationAcceptance.Active(diagnostics, semanticTokens, locations)
        } else {
            SnapshotPresentationAcceptance.Stale
        }

    companion object {
        fun create(
            identity: AnalysisSnapshotIdentity,
            sourceLengthsUtf16: Map<VirtualSourcePath, Int>,
            diagnostics: List<EditorDiagnostic> = emptyList(),
            semanticTokens: List<SemanticToken> = emptyList(),
            locations: List<SourceLocation> = emptyList(),
            limits: EditorPresentationLimits = EditorPresentationLimits(),
        ): SnapshotPresentation {
            require(diagnostics.size <= limits.maxDiagnostics) { "diagnostic count exceeds presentation limit" }
            require(semanticTokens.size <= limits.maxSemanticTokens) { "semantic-token count exceeds presentation limit" }
            require(locations.size <= limits.maxLocations) { "source-location count exceeds presentation limit" }
            val sourceLengths = sourceLengthsUtf16.toMap()
            sourceLengths.forEach { (path, length) ->
                VirtualSourcePath.kotlin(path.value)
                require(length >= 0) { "source UTF-16 length must be non-negative" }
            }
            diagnostics.forEach { diagnostic ->
                require(strictUtf8Size(diagnostic.message) <= limits.maxDiagnosticMessageUtf8Bytes) {
                    "diagnostic message exceeds presentation limit"
                }
                diagnostic.path?.let { path -> validateRange(sourceLengths, path, diagnostic.range) }
            }
            semanticTokens.forEach { token -> validateRange(sourceLengths, token.path, token.range) }
            locations.forEach { location -> validateRange(sourceLengths, location.path, location.range) }
            return SnapshotPresentation(
                identity,
                immutableCopy(diagnostics),
                immutableCopy(semanticTokens),
                immutableCopy(locations),
            )
        }

        private fun validateRange(
            sourceLengths: Map<VirtualSourcePath, Int>,
            path: VirtualSourcePath,
            range: EditorRange?,
        ) {
            val length = requireNotNull(sourceLengths[path]) { "presentation path does not belong to the source snapshot" }
            require(range == null || range.endUtf16 <= length) { "presentation range exceeds its source" }
        }

        private fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
    }
}

internal fun strictUtf8Size(value: String): Int =
    try {
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
            .remaining()
    } catch (exception: CharacterCodingException) {
        throw IllegalArgumentException("presentation text must be strict UTF-8", exception)
    }
