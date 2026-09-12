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

package ru.lazyhat.compukters.platform.k2

enum class CompuktersPlatformDiagnosticCode {
    FOREIGN_PLATFORM_REFERENCE,
    JVM_INLINE,
    GUEST_EXTERNAL_DECLARATION,
}

data class CompuktersPlatformDiagnostic(
    val code: CompuktersPlatformDiagnosticCode,
    val startUtf16: Int,
    val endUtf16: Int,
)

object CompuktersPlatformCheckers {
    fun checkGuestSource(source: String): List<CompuktersPlatformDiagnostic> =
        buildList {
            val searchable = maskCommentsAndStrings(source)
            FOREIGN_REFERENCE.findAll(searchable).forEach { match ->
                add(diagnostic(CompuktersPlatformDiagnosticCode.FOREIGN_PLATFORM_REFERENCE, match.range))
            }
            JVM_INLINE.findAll(searchable).forEach { match ->
                add(diagnostic(CompuktersPlatformDiagnosticCode.JVM_INLINE, match.range))
            }
            EXTERNAL_DECLARATION.findAll(searchable).forEach { match ->
                add(diagnostic(CompuktersPlatformDiagnosticCode.GUEST_EXTERNAL_DECLARATION, match.range))
            }
        }.sortedWith(compareBy(CompuktersPlatformDiagnostic::startUtf16, CompuktersPlatformDiagnostic::code))

    private fun diagnostic(
        code: CompuktersPlatformDiagnosticCode,
        range: IntRange,
    ) = CompuktersPlatformDiagnostic(code, range.first, range.last + 1)

    private fun maskCommentsAndStrings(source: String): String {
        val result = source.toCharArray()
        var index = 0
        while (index < result.size) {
            when {
                source.startsWith("//", index) -> index = maskUntil(source, result, index, "\n")
                source.startsWith("/*", index) -> index = maskUntil(source, result, index, "*/")
                source.startsWith("\"\"\"", index) -> index = maskUntil(source, result, index, "\"\"\"")
                result[index] == '\"' -> index = maskQuoted(source, result, index, '\"')
                result[index] == '\'' -> index = maskQuoted(source, result, index, '\'')
                else -> index++
            }
        }
        return result.concatToString()
    }

    private fun maskUntil(
        source: String,
        result: CharArray,
        start: Int,
        terminator: String,
    ): Int {
        val found = source.indexOf(terminator, start + terminator.length)
        val terminatorIndex = if (found < 0) source.length else found + terminator.length
        for (index in start until terminatorIndex) if (result[index] != '\n') result[index] = ' '
        return terminatorIndex
    }

    private fun maskQuoted(
        source: String,
        result: CharArray,
        start: Int,
        quote: Char,
    ): Int {
        var index = start + 1
        while (index < source.length) {
            if (source[index] == '\\') {
                index += 2
            } else if (source[index] == quote) {
                index++
                break
            } else {
                index++
            }
        }
        for (masked in start until minOf(index, result.size)) if (result[masked] != '\n') result[masked] = ' '
        return index
    }

    private val FOREIGN_REFERENCE = Regex("\\b(?:java|javax|kotlin\\.(?:jvm|js|wasm|native))\\.")
    private val JVM_INLINE = Regex("@\\s*(?:kotlin\\.jvm\\.)?JvmInline\\b")
    private val EXTERNAL_DECLARATION = Regex("\\bexternal\\s+(?:fun|val|var|class|interface)\\b")
}
