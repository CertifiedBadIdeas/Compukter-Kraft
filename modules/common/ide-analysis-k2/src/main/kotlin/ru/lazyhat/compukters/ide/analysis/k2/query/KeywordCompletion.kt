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

internal object KeywordCompletion {
    fun candidates(context: CompletionContext): List<String> {
        if (context.prefix.isEmpty()) return emptyList()
        val candidates =
            when (context.keywordContext) {
                KeywordContext.File -> FILE_KEYWORDS
                KeywordContext.ClassBody -> CLASS_BODY_KEYWORDS
                KeywordContext.Block -> BLOCK_KEYWORDS
                KeywordContext.None -> emptySet()
            }
        return candidates.filter { it.startsWith(context.prefix) }
    }

    private val VISIBILITY_MODIFIERS = setOf("internal", "private", "protected", "public")
    private val INHERITANCE_MODIFIERS = setOf("abstract", "final", "open", "override", "sealed")
    private val CALLABLE_MODIFIERS = setOf("external", "infix", "inline", "operator", "suspend", "tailrec")

    private val FILE_KEYWORDS =
        sortedSetOf(
            "annotation",
            "class",
            "const",
            "data",
            "enum",
            "fun",
            "import",
            "interface",
            "object",
            "package",
            "typealias",
            "val",
            "var",
            *VISIBILITY_MODIFIERS.toTypedArray(),
            *INHERITANCE_MODIFIERS.toTypedArray(),
            *CALLABLE_MODIFIERS.toTypedArray(),
        )

    private val CLASS_BODY_KEYWORDS =
        sortedSetOf(
            "class",
            "companion",
            "const",
            "constructor",
            "data",
            "enum",
            "fun",
            "init",
            "interface",
            "lateinit",
            "object",
            "val",
            "var",
            *VISIBILITY_MODIFIERS.toTypedArray(),
            *INHERITANCE_MODIFIERS.toTypedArray(),
            *CALLABLE_MODIFIERS.toTypedArray(),
        )

    private val BLOCK_KEYWORDS =
        sortedSetOf(
            "as",
            "break",
            "catch",
            "continue",
            "do",
            "else",
            "false",
            "finally",
            "for",
            "fun",
            "if",
            "in",
            "is",
            "null",
            "object",
            "return",
            "super",
            "this",
            "throw",
            "true",
            "try",
            "val",
            "var",
            "when",
            "while",
        )
}
