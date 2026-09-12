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

package ru.lazyhat.compukters.ide.highlight

enum class KotlinLexicalKind {
    Keyword,
    Identifier,
    TypeLike,
    Number,
    String,
    Escape,
    Character,
    MultilineString,
    LineComment,
    BlockComment,
    Annotation,
    Operator,
}

data class LexicalSpan(
    val startUtf16: Int,
    val endUtf16: Int,
    val kind: KotlinLexicalKind,
) {
    init {
        require(startUtf16 >= 0) { "lexical span start must be non-negative" }
        require(endUtf16 > startUtf16) { "lexical span must not be empty" }
    }
}

data class KotlinLexicalState(
    val blockCommentDepth: Int = 0,
    val inMultilineString: Boolean = false,
) {
    init {
        require(blockCommentDepth >= 0) { "block-comment depth must be non-negative" }
        require(blockCommentDepth == 0 || !inMultilineString) { "lexical modes must not overlap" }
    }
}

data class KotlinLexicalLine(
    val sourceLengthUtf16: Int,
    val fingerprint: Long,
    val startState: KotlinLexicalState,
    val endState: KotlinLexicalState,
    val spans: List<LexicalSpan>,
) {
    init {
        require(sourceLengthUtf16 >= 0) { "lexical line length must be non-negative" }
        spans.forEach { span -> require(span.endUtf16 <= sourceLengthUtf16) { "lexical span exceeds its source line" } }
        spans.zipWithNext().forEach { (left, right) ->
            require(left.endUtf16 <= right.startUtf16) { "lexical spans must not overlap" }
        }
    }
}
