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

package ru.lazyhat.compukters.ide.analysis.protocol

object ProtocolLimits {
    const val MAX_FRAME_PAYLOAD_BYTES = 20 * 1024 * 1024
    const val MAX_SOURCE_FILES = 4 * 1024
    const val MAX_SOURCE_FILE_BYTES = 4 * 1024 * 1024
    const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    const val MAX_MODULES = 1024
    const val MAX_DIAGNOSTICS = 4 * 1024
    const val MAX_SEMANTIC_TOKENS = 64 * 1024
    const val MAX_COMPLETION_ITEMS = 256
    const val MAX_PARAMETER_INFO_ITEMS = 64
    const val MAX_DECLARATION_LOCATIONS = 1024
    const val MAX_REFERENCES = 64 * 1024
    const val MAX_TEXT_BYTES = 256 * 1024
    const val MAX_PATH_BYTES = 16 * 1024
}

data class AnalysisLimits(
    val sourceFiles: Int = 64,
    val sourceFileBytes: Int = 256 * 1024,
    val sourceBytes: Int = 1024 * 1024,
    val frameBytes: Int = ProtocolLimits.MAX_FRAME_PAYLOAD_BYTES,
    val modules: Int = 128,
    val diagnostics: Int = 64,
    val diagnosticTextBytes: Int = 64 * 1024,
    val semanticTokens: Int = 16 * 1024,
    val completionItems: Int = ProtocolLimits.MAX_COMPLETION_ITEMS,
    val parameterInfoItems: Int = 32,
    val declarationLocations: Int = 64,
    val references: Int = 4 * 1024,
    val detailTextBytes: Int = 64 * 1024,
) {
    init {
        requireBounded("source file count", sourceFiles, ProtocolLimits.MAX_SOURCE_FILES)
        requireBounded("source file bytes", sourceFileBytes, ProtocolLimits.MAX_SOURCE_FILE_BYTES)
        requireBounded("source bytes", sourceBytes, ProtocolLimits.MAX_SOURCE_BYTES)
        requireBounded("frame bytes", frameBytes, ProtocolLimits.MAX_FRAME_PAYLOAD_BYTES)
        requireBounded("module count", modules, ProtocolLimits.MAX_MODULES)
        requireBounded("diagnostic count", diagnostics, ProtocolLimits.MAX_DIAGNOSTICS)
        requireBounded("diagnostic text bytes", diagnosticTextBytes, ProtocolLimits.MAX_TEXT_BYTES)
        requireBounded("semantic token count", semanticTokens, ProtocolLimits.MAX_SEMANTIC_TOKENS)
        requireBounded("completion-item count", completionItems, ProtocolLimits.MAX_COMPLETION_ITEMS)
        requireBounded("parameter-info item count", parameterInfoItems, ProtocolLimits.MAX_PARAMETER_INFO_ITEMS)
        requireBounded("declaration-location count", declarationLocations, ProtocolLimits.MAX_DECLARATION_LOCATIONS)
        requireBounded("reference count", references, ProtocolLimits.MAX_REFERENCES)
        requireBounded("detail text bytes", detailTextBytes, ProtocolLimits.MAX_TEXT_BYTES)
    }

    fun supports(requested: AnalysisLimits): Boolean =
        sourceFiles >= requested.sourceFiles &&
            sourceFileBytes >= requested.sourceFileBytes &&
            sourceBytes >= requested.sourceBytes &&
            frameBytes >= requested.frameBytes &&
            modules >= requested.modules &&
            diagnostics >= requested.diagnostics &&
            diagnosticTextBytes >= requested.diagnosticTextBytes &&
            semanticTokens >= requested.semanticTokens &&
            completionItems >= requested.completionItems &&
            parameterInfoItems >= requested.parameterInfoItems &&
            declarationLocations >= requested.declarationLocations &&
            references >= requested.references &&
            detailTextBytes >= requested.detailTextBytes
}

private fun requireBounded(
    name: String,
    value: Int,
    maximum: Int,
) {
    require(value in 0..maximum) { "$name must be between zero and $maximum" }
}
