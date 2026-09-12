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
import java.util.Collections

data class AnalysisResultLimits(
    val maxCompletionItems: Int = 256,
    val maxParameterInfoItems: Int = 32,
    val maxDeclarationLocations: Int = 64,
    val maxReferences: Int = 4 * 1024,
    val maxDetailUtf8Bytes: Int = 64 * 1024,
    val maxSourceFileUtf8Bytes: Int = 4 * 1024 * 1024,
) {
    init {
        require(maxCompletionItems >= 0) { "completion-item limit must be non-negative" }
        require(maxParameterInfoItems >= 0) { "parameter-info item limit must be non-negative" }
        require(maxDeclarationLocations >= 0) { "declaration-location limit must be non-negative" }
        require(maxReferences >= 0) { "reference limit must be non-negative" }
        require(maxDetailUtf8Bytes >= 0) { "analysis detail limit must be non-negative" }
        require(maxSourceFileUtf8Bytes >= 0) { "source-file limit must be non-negative" }
    }
}

data class EditorExpressionInfo(
    val path: VirtualSourcePath,
    val range: EditorRange,
    val renderedType: String,
    val signature: String?,
    val origin: DeclarationOrigin?,
) {
    init {
        VirtualSourcePath.kotlin(path.value)
        require(range.length > 0) { "expression range must not be empty" }
        require(renderedType.isNotEmpty()) { "rendered expression type must not be empty" }
        strictUtf8Size(renderedType)
        signature?.let(::strictUtf8Size)
    }
}

sealed interface AnalysisResult {
    val identity: AnalysisSnapshotIdentity

    data class Presentation(
        override val identity: AnalysisSnapshotIdentity,
        val value: SnapshotPresentation,
    ) : AnalysisResult {
        init {
            require(value.identity == identity) { "presentation identity does not match its result" }
        }
    }

    @ConsistentCopyVisibility
    data class Completion private constructor(
        override val identity: AnalysisSnapshotIdentity,
        val replacement: EditorRange,
        val items: List<CompletionItem>,
    ) : AnalysisResult {
        companion object {
            fun create(
                identity: AnalysisSnapshotIdentity,
                replacement: EditorRange,
                items: List<CompletionItem>,
                sourceLengthUtf16: Int,
                limits: AnalysisResultLimits = AnalysisResultLimits(),
            ): Completion {
                require(sourceLengthUtf16 >= 0) { "source UTF-16 length must be non-negative" }
                require(replacement.endUtf16 <= sourceLengthUtf16) { "completion replacement exceeds its source" }
                require(items.size <= limits.maxCompletionItems) { "completion-item count exceeds limit" }
                items.forEach { item ->
                    require(strictUtf8Size(item.label) <= limits.maxDetailUtf8Bytes) { "completion label exceeds limit" }
                    require(strictUtf8Size(item.insertText) <= limits.maxDetailUtf8Bytes) { "completion insert text exceeds limit" }
                    item.detail?.let { detail ->
                        require(strictUtf8Size(detail) <= limits.maxDetailUtf8Bytes) { "completion detail exceeds limit" }
                    }
                    item.symbol?.let { symbol ->
                        require(strictUtf8Size(symbol.fqName) <= limits.maxDetailUtf8Bytes) { "completion symbol name exceeds limit" }
                        symbol.importFqName?.let { importName ->
                            require(strictUtf8Size(importName) <= limits.maxDetailUtf8Bytes) { "completion import name exceeds limit" }
                            require(item.additionalEdits.isNotEmpty()) { "completion import requires an additional edit" }
                        }
                    }
                    require(item.additionalEdits.size <= 1) { "completion additional-edit count exceeds limit" }
                    item.additionalEdits.forEach { edit ->
                        require(edit.range.endUtf16 <= sourceLengthUtf16) { "completion additional edit exceeds its source" }
                        require(!edit.range.overlaps(replacement)) { "completion additional edit overlaps its replacement" }
                        require(strictUtf8Size(edit.text) <= limits.maxDetailUtf8Bytes) { "completion additional edit text exceeds limit" }
                    }
                }
                return Completion(identity, replacement, immutableCopy(items))
            }
        }
    }

    @ConsistentCopyVisibility
    data class ExpressionInfo private constructor(
        override val identity: AnalysisSnapshotIdentity,
        val value: EditorExpressionInfo?,
    ) : AnalysisResult {
        companion object {
            fun create(
                identity: AnalysisSnapshotIdentity,
                value: EditorExpressionInfo?,
                sourceLengthsUtf16: Map<VirtualSourcePath, Int>,
                limits: AnalysisResultLimits = AnalysisResultLimits(),
            ): ExpressionInfo {
                val sourceLengths = validateSourceLengths(sourceLengthsUtf16)
                value?.let { info ->
                    validateSourceRange(sourceLengths, info.path, info.range)
                    require(strictUtf8Size(info.renderedType) <= limits.maxDetailUtf8Bytes) { "rendered type exceeds limit" }
                    info.signature?.let { signature ->
                        require(strictUtf8Size(signature) <= limits.maxDetailUtf8Bytes) { "expression signature exceeds limit" }
                    }
                }
                return ExpressionInfo(identity, value)
            }
        }
    }

    @ConsistentCopyVisibility
    data class ParameterInfo private constructor(
        override val identity: AnalysisSnapshotIdentity,
        val value: EditorParameterInfo?,
    ) : AnalysisResult {
        companion object {
            fun create(
                identity: AnalysisSnapshotIdentity,
                value: EditorParameterInfo?,
                sourceLengthsUtf16: Map<VirtualSourcePath, Int>,
                limits: AnalysisResultLimits = AnalysisResultLimits(),
            ): ParameterInfo {
                val sourceLengths = validateSourceLengths(sourceLengthsUtf16)
                value?.let { info ->
                    validateSourceRange(sourceLengths, info.path, info.callRange)
                    require(info.items.size <= limits.maxParameterInfoItems) { "parameter-info item count exceeds limit" }
                    info.items.forEach { item ->
                        require(strictUtf8Size(item.signature) <= limits.maxDetailUtf8Bytes) {
                            "parameter-info signature exceeds limit"
                        }
                    }
                }
                return ParameterInfo(identity, value)
            }
        }
    }

    @ConsistentCopyVisibility
    data class Declaration private constructor(
        override val identity: AnalysisSnapshotIdentity,
        val locations: List<DeclarationLocation>,
    ) : AnalysisResult {
        companion object {
            fun create(
                identity: AnalysisSnapshotIdentity,
                locations: List<DeclarationLocation>,
                sourceLengthsUtf16: Map<VirtualSourcePath, Int>,
                limits: AnalysisResultLimits = AnalysisResultLimits(),
                platformSourceLengthsUtf16: Map<AnalysisModuleIdentity, Map<VirtualSourcePath, Int>> = emptyMap(),
            ): Declaration {
                require(locations.size <= limits.maxDeclarationLocations) { "declaration-location count exceeds limit" }
                validateLocations(locations, sourceLengthsUtf16, platformSourceLengthsUtf16, allowUnavailable = true)
                return Declaration(identity, immutableCopy(locations))
            }
        }
    }

    @ConsistentCopyVisibility
    data class References private constructor(
        override val identity: AnalysisSnapshotIdentity,
        val locations: List<DeclarationLocation>,
    ) : AnalysisResult {
        companion object {
            fun create(
                identity: AnalysisSnapshotIdentity,
                locations: List<DeclarationLocation>,
                sourceLengthsUtf16: Map<VirtualSourcePath, Int>,
                limits: AnalysisResultLimits = AnalysisResultLimits(),
            ): References {
                require(locations.size <= limits.maxReferences) { "reference count exceeds limit" }
                validateLocations(locations, sourceLengthsUtf16, emptyMap(), allowUnavailable = false)
                return References(identity, immutableCopy(locations))
            }
        }
    }

    @ConsistentCopyVisibility
    data class Format private constructor(
        override val identity: AnalysisSnapshotIdentity,
        val source: String,
        val caretOffsetUtf16: Int,
    ) : AnalysisResult {
        companion object {
            fun create(
                identity: AnalysisSnapshotIdentity,
                source: String,
                caretOffsetUtf16: Int,
                limits: AnalysisResultLimits = AnalysisResultLimits(),
            ): Format {
                require(caretOffsetUtf16 in 0..source.length) { "format caret offset exceeds its source" }
                requireUtf16Boundary(source, caretOffsetUtf16, "format caret offset")
                require(strictUtf8Size(source) <= limits.maxSourceFileUtf8Bytes) {
                    "formatted source exceeds limit"
                }
                return Format(identity, source, caretOffsetUtf16)
            }
        }
    }
}

private fun EditorRange.overlaps(other: EditorRange): Boolean = startUtf16 < other.endUtf16 && other.startUtf16 < endUtf16

private fun validateLocations(
    locations: List<DeclarationLocation>,
    sourceLengthsUtf16: Map<VirtualSourcePath, Int>,
    platformSourceLengthsUtf16: Map<AnalysisModuleIdentity, Map<VirtualSourcePath, Int>>,
    allowUnavailable: Boolean,
) {
    val sourceLengths = validateSourceLengths(sourceLengthsUtf16)
    val platformSourceLengths =
        platformSourceLengthsUtf16.mapValues { (_, lengths) -> validateSourceLengths(lengths) }
    locations.forEach { location ->
        when (location) {
            is DeclarationLocation.Source -> {
                when (val origin = location.origin) {
                    DeclarationOrigin.Project -> {
                        validateSourceRange(sourceLengths, location.path, location.range)
                    }

                    is DeclarationOrigin.Platform -> {
                        val lengths =
                            requireNotNull(platformSourceLengths[origin.identity]) {
                                "analysis platform module has no attached sources"
                            }
                        validateSourceRange(lengths, location.path, location.range)
                    }
                }
            }

            is DeclarationLocation.SourceUnavailable -> {
                require(allowUnavailable) { "a reference must have available project source" }
            }
        }
    }
}

private fun validateSourceLengths(sourceLengthsUtf16: Map<VirtualSourcePath, Int>): Map<VirtualSourcePath, Int> =
    sourceLengthsUtf16.toMap().also { sourceLengths ->
        sourceLengths.forEach { (path, length) ->
            VirtualSourcePath.kotlin(path.value)
            require(length >= 0) { "source UTF-16 length must be non-negative" }
        }
    }

private fun validateSourceRange(
    sourceLengthsUtf16: Map<VirtualSourcePath, Int>,
    path: VirtualSourcePath,
    range: EditorRange,
) {
    val length = requireNotNull(sourceLengthsUtf16[path]) { "analysis path does not belong to the source snapshot" }
    require(range.endUtf16 <= length) { "analysis range exceeds its source" }
}

private fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
