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

import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisResultLimits
import ru.lazyhat.compukters.ide.analysis.k2.formatter.KotlinSourceFormatter
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits

internal object FormatQuery {
    fun execute(
        query: AnalysisQuery.Format,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
        formatter: KotlinSourceFormatter,
    ): AnalysisResult.Format {
        require(query.identity == snapshot.identity) { "analysis query identity is not active" }
        require(query.path in snapshot.files) { "analysis source path is not active" }
        val formatted = formatter.format(query.path.value, query.source)
        if (formatted.encodeToByteArray().size > limits.sourceFileBytes) {
            throw AnalysisOutputLimitException("formatted source exceeds negotiated limit")
        }
        val caret = mapCaret(query.source, formatted, query.caretOffsetUtf16)
        return AnalysisResult.Format.create(
            query.identity,
            formatted,
            caret,
            AnalysisResultLimits(maxSourceFileUtf8Bytes = limits.sourceFileBytes),
        )
    }

    internal fun mapCaret(
        source: String,
        formatted: String,
        caretOffsetUtf16: Int,
    ): Int {
        if (source == formatted) return caretOffsetUtf16
        val mapped = mapByNonWhitespaceOrdinal(source, formatted, caretOffsetUtf16)
        return avoidSplitSurrogate(formatted, mapped.coerceIn(0, formatted.length))
    }

    private fun mapByNonWhitespaceOrdinal(
        source: String,
        formatted: String,
        caretOffsetUtf16: Int,
    ): Int {
        val sourceSignificant = source.filterNot(Char::isWhitespace)
        val formattedSignificant = formatted.filterNot(Char::isWhitespace)
        if (sourceSignificant != formattedSignificant) {
            return mapThroughCommonEdges(source, formatted, caretOffsetUtf16)
        }
        val significantBeforeCaret = source.take(caretOffsetUtf16).count { !it.isWhitespace() }
        var significant = 0
        var offset = 0
        while (offset < formatted.length && significant < significantBeforeCaret) {
            if (!formatted[offset].isWhitespace()) {
                significant += 1
            }
            offset += 1
        }
        while (offset < formatted.length && formatted[offset].isWhitespace()) {
            offset += 1
        }
        return offset
    }

    private fun mapThroughCommonEdges(
        source: String,
        formatted: String,
        caretOffsetUtf16: Int,
    ): Int {
        val prefix = source.commonPrefixWith(formatted).length
        if (caretOffsetUtf16 <= prefix) return caretOffsetUtf16
        val suffix = source.commonSuffixWith(formatted).length
        if (caretOffsetUtf16 >= source.length - suffix) {
            return formatted.length - (source.length - caretOffsetUtf16)
        }
        return prefix
    }

    private fun avoidSplitSurrogate(
        text: String,
        offsetUtf16: Int,
    ): Int =
        if (
            offsetUtf16 > 0 &&
            offsetUtf16 < text.length &&
            Character.isHighSurrogate(text[offsetUtf16 - 1]) &&
            Character.isLowSurrogate(text[offsetUtf16])
        ) {
            offsetUtf16 - 1
        } else {
            offsetUtf16
        }
}
