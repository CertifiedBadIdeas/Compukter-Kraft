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

import ru.lazyhat.compukters.ide.editor.EditorChange
import ru.lazyhat.compukters.ide.editor.EditorDocument

data class KotlinLexicalSnapshot(
    val revision: Long,
    val lines: List<KotlinLexicalLine>,
)

class IncrementalKotlinHighlighter(
    private val document: EditorDocument,
) : AutoCloseable {
    private val cache = fullScan(document).toMutableList()
    private val subscription = document.addChangeListener(::changed)
    private var closed = false

    var revision: Long = document.revision
        private set

    var lastReusedSuffixLines: Int = 0
        private set

    fun snapshot(): KotlinLexicalSnapshot = KotlinLexicalSnapshot(revision, cache.toList())

    override fun close() {
        if (closed) return
        closed = true
        subscription.close()
    }

    private fun changed(change: EditorChange) {
        val old = cache.toList()
        val lineDelta = document.lineCount - old.size
        val requestedStart = minOf(change.oldAffectedLines.first, change.newAffectedLines.first)
        val start = (requestedStart - 1).coerceAtLeast(0).coerceAtMost(document.lineCount - 1)
        val prefixSize = minOf(start, old.size, document.lineCount)
        val rebuilt = ArrayList<KotlinLexicalLine>(document.lineCount)
        rebuilt.addAll(old.subList(0, prefixSize))
        var state = rebuilt.lastOrNull()?.endState ?: KotlinLexicalState()
        var newLine = prefixSize
        lastReusedSuffixLines = 0

        while (newLine < document.lineCount) {
            val candidate = KotlinLineLexer.scan(document, newLine, state)
            val oldLine = newLine - lineDelta
            val mayStabilize = newLine > change.newAffectedLines.last && oldLine > change.oldAffectedLines.last
            val suffixLengthsMatch =
                oldLine in old.indices && old.size - oldLine == document.lineCount - newLine
            if (mayStabilize && suffixLengthsMatch && candidate == old[oldLine]) {
                rebuilt.addAll(old.subList(oldLine, old.size))
                lastReusedSuffixLines = old.size - oldLine
                break
            }
            rebuilt += candidate
            state = candidate.endState
            newLine++
        }

        cache.clear()
        cache.addAll(rebuilt)
        revision = change.newRevision
    }

    companion object {
        fun fullScan(document: EditorDocument): List<KotlinLexicalLine> {
            val result = ArrayList<KotlinLexicalLine>(document.lineCount)
            var state = KotlinLexicalState()
            repeat(document.lineCount) { line ->
                val scanned = KotlinLineLexer.scan(document, line, state)
                result += scanned
                state = scanned.endState
            }
            return result
        }
    }
}
