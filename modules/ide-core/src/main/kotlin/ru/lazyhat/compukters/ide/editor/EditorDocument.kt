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

package ru.lazyhat.compukters.ide.editor

class EditorDocument(
    initial: String,
    val limits: EditorLimits = EditorLimits(),
) {
    private val buffer = EditorBuffer(initial, limits)
    private val lines = EditorLineIndex(buffer, limits.tabWidth)
    private val history = EditorHistory(limits)
    private val listeners = linkedMapOf<Long, (EditorChange) -> Unit>()
    private var nextListenerId = 1L
    private var selection = EditorSelection(0, 0)
    private var preferredColumn: Int? = null
    private var closed = false

    var revision: Long = 0
        private set

    val length: Int
        get() = buffer.length

    val caretOffset: Int
        get() = selection.caretUtf16

    val selectionRange: EditorRange?
        get() = selection.range.takeUnless { it.length == 0 }

    val caretVisualColumn: Int
        get() = lines.visualColumn(caretOffset)

    val lineCount: Int
        get() = lines.lineCount

    val preferredLineSeparator: String
        get() = lines.preferredSeparator

    internal val undoEntryCount: Int
        get() = history.undoEntryCount

    fun materialize(): String = buffer.materialize()

    /** Copies one logical line without its line separator. */
    fun materializeLine(index: Int): String {
        val line = lines.line(index)
        return buffer.copyRange(line.startUtf16, line.contentEndUtf16).concatToString()
    }

    /** Returns the exact UTF-16 offset of a logical line, preserving LF versus CRLF accounting. */
    fun lineStartOffset(index: Int): Int = lines.line(index).startUtf16

    fun maximumVisualWidth(): Int {
        var maximum = 0
        repeat(lineCount) { maximum = maxOf(maximum, lines.visualWidth(it)) }
        return maximum
    }

    fun contentEquals(value: String): Boolean = buffer.contentEquals(value)

    fun charAt(offset: Int): Char = buffer.charAt(offset)

    fun copyRange(range: EditorRange): String = buffer.copyRange(range.startUtf16, range.endUtf16).concatToString()

    fun addChangeListener(listener: (EditorChange) -> Unit): EditorChangeSubscription {
        if (closed) return EditorChangeSubscription {}
        val id = nextListenerId++
        listeners[id] = listener
        return EditorChangeSubscription { listeners.remove(id) }
    }

    fun setCaret(
        offset: Int,
        extendSelection: Boolean = false,
    ): Boolean {
        if (closed || !isCaretBoundary(offset)) return false
        moveCaret(offset, extendSelection, keepPreferredColumn = false)
        return true
    }

    fun moveLeft(extendSelection: Boolean = false): Boolean {
        if (closed) return false
        val range = selectionRange
        val target =
            if (!extendSelection && range != null) {
                range.startUtf16
            } else {
                previousCaretBoundary(caretOffset)
            }
        if (target == caretOffset && range == null) return false
        moveCaret(target, extendSelection, keepPreferredColumn = false)
        return true
    }

    fun moveRight(extendSelection: Boolean = false): Boolean {
        if (closed) return false
        val range = selectionRange
        val target =
            if (!extendSelection && range != null) {
                range.endUtf16
            } else {
                nextCaretBoundary(caretOffset)
            }
        if (target == caretOffset && range == null) return false
        moveCaret(target, extendSelection, keepPreferredColumn = false)
        return true
    }

    fun moveWordLeft(extendSelection: Boolean = false): Boolean =
        moveWord(::previousWordBoundary, extendSelection, selectionRange?.startUtf16)

    fun moveWordRight(extendSelection: Boolean = false): Boolean = moveWord(::nextWordBoundary, extendSelection, selectionRange?.endUtf16)

    fun moveUp(extendSelection: Boolean = false): Boolean = moveVertical(-1, extendSelection)

    fun moveDown(extendSelection: Boolean = false): Boolean = moveVertical(1, extendSelection)

    fun moveHome(extendSelection: Boolean = false): Boolean {
        if (closed) return false
        val target = lines.line(lines.lineOfOffset(caretOffset)).startUtf16
        if (target == caretOffset && (!extendSelection || selectionRange == null)) return false
        moveCaret(target, extendSelection, keepPreferredColumn = false)
        return true
    }

    fun moveEnd(extendSelection: Boolean = false): Boolean {
        if (closed) return false
        val target = lines.line(lines.lineOfOffset(caretOffset)).contentEndUtf16
        if (target == caretOffset && (!extendSelection || selectionRange == null)) return false
        moveCaret(target, extendSelection, keepPreferredColumn = false)
        return true
    }

    fun selectAll() {
        if (closed) return
        history.breakGroup()
        selection = EditorSelection(0, length)
        preferredColumn = null
    }

    fun selectToken(offset: Int): Boolean {
        if (closed || offset !in 0..<length || !isCaretBoundary(offset)) return false
        val category = wordCategory(codePointAt(offset))
        var start = offset
        var end = nextCaretBoundary(offset)
        if (category == WordCategory.Punctuation) {
            moveCaretSelection(EditorSelection(start, end))
            return true
        }
        while (start > 0) {
            val previous = previousCaretBoundary(start)
            if (wordCategory(codePointAt(previous)) != category) break
            start = previous
        }
        while (end < length && wordCategory(codePointAt(end)) == category) end = nextCaretBoundary(end)
        moveCaretSelection(EditorSelection(start, end))
        return true
    }

    fun copySelection(): String? = selectionRange?.let(::copyRange)

    fun type(text: String): EditorEditResult =
        replaceSelection(
            text,
            if ('\r' in text || '\n' in text) EditorHistoryKind.Atomic else EditorHistoryKind.Typing,
        )

    fun paste(text: String): EditorEditResult = replaceSelection(text, EditorHistoryKind.Atomic)

    fun replaceRange(
        range: EditorRange,
        text: String,
    ): EditorEditResult = replace(range, text, EditorHistoryKind.Atomic, EditorChangeOrigin.User)

    fun replaceRanges(
        primaryRange: EditorRange,
        primaryText: String,
        additionalEdits: List<EditorTextEdit>,
    ): EditorEditResult {
        if (additionalEdits.isEmpty()) return replaceRange(primaryRange, primaryText)
        if (closed) return EditorEditResult.Rejected(EditorRejection.Closed)
        val edits = listOf(EditorTextEdit(primaryRange, primaryText)) + additionalEdits
        if (
            edits.any { edit -> !isCaretBoundary(edit.range.startUtf16) || !isCaretBoundary(edit.range.endUtf16) } ||
            edits.indices.any { left ->
                (left + 1 until edits.size).any { right -> edits[left].range.conflictsWith(edits[right].range) }
            }
        ) {
            return EditorEditResult.Rejected(EditorRejection.InvalidRange)
        }
        val ordered = edits.sortedBy { it.range.startUtf16 }
        val preview = EditorBuffer(materialize(), limits)
        for (edit in ordered.asReversed()) {
            when (val result = preview.replace(edit.range.startUtf16, edit.range.endUtf16, edit.text)) {
                BufferReplaceResult.Applied -> Unit
                is BufferReplaceResult.Rejected -> return EditorEditResult.Rejected(result.reason)
            }
        }
        var delta = 0
        val historyEdits =
            ordered.map { edit ->
                val removed = buffer.copyRange(edit.range.startUtf16, edit.range.endUtf16).concatToString()
                EditorHistoryEdit(edit.range.startUtf16, edit.range.startUtf16 + delta, removed, edit.text).also {
                    delta += edit.text.length - edit.range.length
                }
            }
        val primaryIndex = ordered.indexOfFirst { it === edits.first() }
        val primary = historyEdits[primaryIndex]
        val before = selection
        val caret = primary.afterStartUtf16 + primary.inserted.length
        val after = EditorSelection(caret, caret)
        val entry = EditorHistoryEntry(historyEdits, before, after, EditorHistoryKind.Atomic)
        if (!history.canRecord(entry)) return EditorEditResult.Rejected(EditorRejection.UndoLimit)
        val oldStart = historyEdits.minOf(EditorHistoryEdit::beforeStartUtf16)
        val oldEnd = historyEdits.maxOf { it.beforeStartUtf16 + it.removed.length }
        val oldRange = EditorRange(oldStart, oldEnd)
        val oldLines = lines.affectedLines(oldRange)
        historyEdits.asReversed().forEach { edit ->
            check(
                buffer.replace(
                    edit.beforeStartUtf16,
                    edit.beforeStartUtf16 + edit.removed.length,
                    edit.inserted,
                ) == BufferReplaceResult.Applied,
            )
        }
        lines.rebuild()
        selection = after
        preferredColumn = null
        history.record(entry)
        val newEnd = historyEdits.maxOf { it.afterStartUtf16 + it.inserted.length }
        return publish(oldRange, newEnd - oldStart, oldLines, lines.affectedLines(EditorRange(oldStart, newEnd)), EditorChangeOrigin.User)
    }

    fun cut(): EditorEditResult {
        val range = selectionRange ?: return EditorEditResult.NoChange
        return replace(range, "", EditorHistoryKind.Atomic, EditorChangeOrigin.User)
    }

    fun backspace(): EditorEditResult {
        selectionRange?.let { return replace(it, "", EditorHistoryKind.Atomic, EditorChangeOrigin.User) }
        if (caretOffset == 0) return EditorEditResult.NoChange
        return replace(
            EditorRange(previousCaretBoundary(caretOffset), caretOffset),
            "",
            EditorHistoryKind.Backspace,
            EditorChangeOrigin.User,
        )
    }

    fun delete(): EditorEditResult {
        selectionRange?.let { return replace(it, "", EditorHistoryKind.Atomic, EditorChangeOrigin.User) }
        if (caretOffset == length) return EditorEditResult.NoChange
        return replace(
            EditorRange(caretOffset, nextCaretBoundary(caretOffset)),
            "",
            EditorHistoryKind.Atomic,
            EditorChangeOrigin.User,
        )
    }

    fun deleteWordBackward(): EditorEditResult {
        selectionRange?.let { return replace(it, "", EditorHistoryKind.Atomic, EditorChangeOrigin.User) }
        if (caretOffset == 0) return EditorEditResult.NoChange
        return replace(
            EditorRange(previousWordDeletionBoundary(caretOffset), caretOffset),
            "",
            EditorHistoryKind.Atomic,
            EditorChangeOrigin.User,
        )
    }

    fun deleteWordForward(): EditorEditResult {
        selectionRange?.let { return replace(it, "", EditorHistoryKind.Atomic, EditorChangeOrigin.User) }
        if (caretOffset == length) return EditorEditResult.NoChange
        return replace(
            EditorRange(caretOffset, nextWordBoundary(caretOffset)),
            "",
            EditorHistoryKind.Atomic,
            EditorChangeOrigin.User,
        )
    }

    fun enter(): EditorEditResult {
        if (closed) return EditorEditResult.Rejected(EditorRejection.Closed)
        val line = lines.line(lines.lineOfOffset(caretOffset))
        var indentEnd = line.startUtf16
        while (indentEnd < line.contentEndUtf16) {
            val char = buffer.charAt(indentEnd)
            if (char != ' ' && char != '\t') break
            indentEnd++
        }
        val indent = buffer.copyRange(line.startUtf16, indentEnd).concatToString()
        return replaceSelection(preferredLineSeparator + indent, EditorHistoryKind.Atomic)
    }

    fun tab(): EditorEditResult {
        val spaces = limits.tabWidth - caretVisualColumn % limits.tabWidth
        return replaceSelection(" ".repeat(spaces), EditorHistoryKind.Atomic)
    }

    fun indent(): EditorEditResult {
        if (selectionRange == null) return tab()
        return transformLinePrefixes(indent = true)
    }

    fun outdent(): EditorEditResult = transformLinePrefixes(indent = false)

    fun undo(): EditorEditResult {
        if (closed) return EditorEditResult.Rejected(EditorRejection.Closed)
        val entry = history.popUndo() ?: return EditorEditResult.NoChange
        return applyHistory(entry, undo = true)
    }

    fun redo(): EditorEditResult {
        if (closed) return EditorEditResult.Rejected(EditorRejection.Closed)
        val entry = history.popRedo() ?: return EditorEditResult.NoChange
        return applyHistory(entry, undo = false)
    }

    fun breakUndoGroup() = history.breakGroup()

    internal fun reset(text: String) {
        check(!closed) { "editor is closed" }
        val oldRange = EditorRange(0, length)
        val oldLines = 0..lines.lineCount - 1
        check(buffer.replace(0, length, text) == BufferReplaceResult.Applied)
        lines.rebuild()
        history.clear()
        selection = EditorSelection(0, 0)
        preferredColumn = null
        publish(oldRange, text.length, oldLines, 0..lines.lineCount - 1, EditorChangeOrigin.ExternalReset)
    }

    fun close() {
        if (closed) return
        closed = true
        history.clear()
        listeners.clear()
    }

    internal fun line(index: Int): EditorLine = lines.line(index)

    internal fun lineOfOffset(offset: Int): Int = lines.lineOfOffset(offset)

    internal fun offsetAtVisualColumn(
        line: Int,
        column: Int,
    ): Int = lines.offsetAtVisualColumn(line, column)

    internal fun lineVisualWidth(line: Int): Int = lines.visualWidth(line)

    private fun replaceSelection(
        text: String,
        kind: EditorHistoryKind,
    ): EditorEditResult = replace(selection.range, text, kind, EditorChangeOrigin.User)

    private fun replace(
        range: EditorRange,
        text: String,
        kind: EditorHistoryKind,
        origin: EditorChangeOrigin,
        afterSelection: EditorSelection = EditorSelection(range.startUtf16 + text.length, range.startUtf16 + text.length),
    ): EditorEditResult {
        if (closed) return EditorEditResult.Rejected(EditorRejection.Closed)
        if (!isCaretBoundary(range.startUtf16) || !isCaretBoundary(range.endUtf16)) {
            return EditorEditResult.Rejected(EditorRejection.InvalidRange)
        }
        if (range.length == 0 && text.isEmpty()) return EditorEditResult.NoChange
        val removed = buffer.copyRange(range.startUtf16, range.endUtf16).concatToString()
        val before = selection
        val entry =
            EditorHistoryEntry(
                listOf(EditorHistoryEdit(range.startUtf16, range.startUtf16, removed, text)),
                before,
                afterSelection,
                kind,
            )
        if (!history.canRecord(entry)) return EditorEditResult.Rejected(EditorRejection.UndoLimit)
        val oldLines = lines.affectedLines(range)
        when (val result = buffer.replace(range.startUtf16, range.endUtf16, text)) {
            BufferReplaceResult.Applied -> Unit
            is BufferReplaceResult.Rejected -> return EditorEditResult.Rejected(result.reason)
        }
        lines.rebuildFrom(oldLines.first)
        selection = afterSelection
        preferredColumn = null
        history.record(entry)
        return publish(
            range,
            text.length,
            oldLines,
            lines.affectedLines(EditorRange(range.startUtf16, range.startUtf16 + text.length)),
            origin,
        )
    }

    private fun applyHistory(
        entry: EditorHistoryEntry,
        undo: Boolean,
    ): EditorEditResult {
        val applied =
            entry.edits.asReversed().map { edit ->
                if (undo) {
                    EditorTextEdit(EditorRange(edit.afterStartUtf16, edit.afterStartUtf16 + edit.inserted.length), edit.removed)
                } else {
                    EditorTextEdit(EditorRange(edit.beforeStartUtf16, edit.beforeStartUtf16 + edit.removed.length), edit.inserted)
                }
            }
        val range = EditorRange(applied.minOf { it.range.startUtf16 }, applied.maxOf { it.range.endUtf16 })
        val oldLines = lines.affectedLines(range)
        applied.forEach { edit ->
            check(buffer.replace(edit.range.startUtf16, edit.range.endUtf16, edit.text) == BufferReplaceResult.Applied)
        }
        lines.rebuild()
        selection = if (undo) entry.beforeSelection else entry.afterSelection
        preferredColumn = null
        val insertedEnd =
            if (undo) {
                entry.edits.maxOf { it.beforeStartUtf16 + it.removed.length }
            } else {
                entry.edits.maxOf { it.afterStartUtf16 + it.inserted.length }
            }
        return publish(
            range,
            insertedEnd - range.startUtf16,
            oldLines,
            lines.affectedLines(EditorRange(range.startUtf16, insertedEnd)),
            EditorChangeOrigin.UndoRedo,
        )
    }

    private fun publish(
        oldRange: EditorRange,
        insertedCodeUnits: Int,
        oldLines: IntRange,
        newLines: IntRange,
        origin: EditorChangeOrigin,
    ): EditorEditResult.Applied {
        val oldRevision = revision
        revision = Math.incrementExact(revision)
        val change = EditorChange(oldRevision, revision, oldRange, insertedCodeUnits, oldLines, newLines, origin)
        listeners.values.toList().forEach { it(change) }
        return EditorEditResult.Applied(change)
    }

    private fun moveVertical(
        delta: Int,
        extendSelection: Boolean,
    ): Boolean {
        if (closed) return false
        val currentLine = lines.lineOfOffset(caretOffset)
        val targetLine = (currentLine + delta).coerceIn(0, lines.lineCount - 1)
        if (targetLine == currentLine) return false
        val desired = preferredColumn ?: caretVisualColumn
        val target = lines.offsetAtVisualColumn(targetLine, desired)
        preferredColumn = desired
        moveCaret(target, extendSelection, keepPreferredColumn = true)
        return true
    }

    private fun moveWord(
        boundary: (Int) -> Int,
        extendSelection: Boolean,
        collapsedTarget: Int?,
    ): Boolean {
        if (closed) return false
        val target = if (!extendSelection && collapsedTarget != null) collapsedTarget else boundary(caretOffset)
        if (target == caretOffset && selectionRange == null) return false
        moveCaret(target, extendSelection, keepPreferredColumn = false)
        return true
    }

    private fun previousWordBoundary(offset: Int): Int {
        var cursor = offset
        while (cursor > 0) {
            val previous = previousCaretBoundary(cursor)
            if (wordCategory(codePointAt(previous)) != WordCategory.Whitespace) break
            cursor = previous
        }
        if (cursor == 0) return 0
        val category = wordCategory(codePointAt(previousCaretBoundary(cursor)))
        while (cursor > 0) {
            val previous = previousCaretBoundary(cursor)
            if (wordCategory(codePointAt(previous)) != category) break
            cursor = previous
        }
        return cursor
    }

    private fun previousWordDeletionBoundary(offset: Int): Int {
        val lineIndex = lines.lineOfOffset(offset)
        val line = lines.line(lineIndex)
        if (offset == line.startUtf16 && lineIndex > 0) return lines.line(lineIndex - 1).contentEndUtf16
        return previousWordBoundary(offset)
    }

    private fun nextWordBoundary(offset: Int): Int {
        if (offset == length) return length
        var cursor = offset
        val category = wordCategory(codePointAt(cursor))
        while (cursor < length && wordCategory(codePointAt(cursor)) == category) cursor = nextCaretBoundary(cursor)
        if (category != WordCategory.Whitespace) {
            while (cursor < length && wordCategory(codePointAt(cursor)) == WordCategory.Whitespace) cursor = nextCaretBoundary(cursor)
        }
        return cursor
    }

    private fun transformLinePrefixes(indent: Boolean): EditorEditResult {
        if (closed) return EditorEditResult.Rejected(EditorRejection.Closed)
        val range = selectionRange
        val firstLine = lines.lineOfOffset(range?.startUtf16 ?: caretOffset)
        var lastLine = lines.lineOfOffset(range?.endUtf16 ?: caretOffset)
        if (range != null && range.length > 0 && lastLine > firstLine && range.endUtf16 == lines.line(lastLine).startUtf16) lastLine--
        val edits =
            (firstLine..lastLine).mapNotNull { lineIndex ->
                val line = lines.line(lineIndex)
                val text = buffer.copyRange(line.startUtf16, line.contentEndUtf16).concatToString()
                val removed =
                    when {
                        indent -> ""
                        text.startsWith('\t') -> "\t"
                        else -> " ".repeat(text.take(limits.tabWidth).takeWhile { it == ' ' }.length)
                    }
                if (indent) {
                    PrefixEdit(line.startUtf16, line.startUtf16, " ".repeat(limits.tabWidth))
                } else {
                    if (removed.isEmpty()) return@mapNotNull null
                    PrefixEdit(line.startUtf16, line.startUtf16 + removed.length, "")
                }
            }
        if (edits.isEmpty()) return EditorEditResult.NoChange
        val transformStart = lines.line(firstLine).startUtf16
        val transformEnd = lines.line(lastLine).contentEndUtf16
        val transformed = StringBuilder(buffer.copyRange(transformStart, transformEnd).concatToString())
        edits.asReversed().forEach { edit ->
            transformed.replace(edit.start - transformStart, edit.end - transformStart, edit.text)
        }
        val before = selection
        val after = EditorSelection(mapOffset(before.anchorUtf16, edits), mapOffset(before.caretUtf16, edits))
        return replace(
            EditorRange(transformStart, transformEnd),
            transformed.toString(),
            EditorHistoryKind.Atomic,
            EditorChangeOrigin.User,
            after,
        )
    }

    private fun mapOffset(
        offset: Int,
        edits: List<PrefixEdit>,
    ): Int {
        var delta = 0
        edits.forEach { edit ->
            if (offset < edit.start) return offset + delta
            if (offset <= edit.end) return edit.start + delta + edit.text.length
            delta += edit.text.length - (edit.end - edit.start)
        }
        return offset + delta
    }

    private fun codePointAt(offset: Int): Int {
        val first = buffer.charAt(offset)
        return if (Character.isHighSurrogate(first) && offset + 1 < length && Character.isLowSurrogate(buffer.charAt(offset + 1))) {
            Character.toCodePoint(first, buffer.charAt(offset + 1))
        } else {
            first.code
        }
    }

    private fun wordCategory(codePoint: Int): WordCategory =
        when {
            Character.isWhitespace(codePoint) -> WordCategory.Whitespace
            Character.isUnicodeIdentifierPart(codePoint) || codePoint == '_'.code -> WordCategory.Identifier
            else -> WordCategory.Punctuation
        }

    private fun moveCaretSelection(value: EditorSelection) {
        history.breakGroup()
        selection = value
        preferredColumn = null
    }

    private fun moveCaret(
        target: Int,
        extendSelection: Boolean,
        keepPreferredColumn: Boolean,
    ) {
        history.breakGroup()
        selection =
            if (extendSelection) {
                EditorSelection(selection.anchorUtf16, target)
            } else {
                EditorSelection(target, target)
            }
        if (!keepPreferredColumn) preferredColumn = null
    }

    private fun previousCaretBoundary(offset: Int): Int =
        if (offset >= 2 && buffer.charAt(offset - 2) == '\r' && buffer.charAt(offset - 1) == '\n') {
            offset - 2
        } else {
            buffer.previousScalarBoundary(offset)
        }

    private fun nextCaretBoundary(offset: Int): Int =
        if (offset + 1 < length && buffer.charAt(offset) == '\r' && buffer.charAt(offset + 1) == '\n') {
            offset + 2
        } else {
            buffer.nextScalarBoundary(offset)
        }

    private fun isCaretBoundary(offset: Int): Boolean {
        if (offset !in 0..length) return false
        if (offset == 0 || offset == length) return true
        if (buffer.charAt(offset - 1) == '\r' && buffer.charAt(offset) == '\n') return false
        return !(Character.isHighSurrogate(buffer.charAt(offset - 1)) && Character.isLowSurrogate(buffer.charAt(offset)))
    }

    private data class PrefixEdit(
        val start: Int,
        val end: Int,
        val text: String,
    )

    private enum class WordCategory { Whitespace, Identifier, Punctuation }
}

private fun EditorRange.conflictsWith(other: EditorRange): Boolean =
    (startUtf16 < other.endUtf16 && other.startUtf16 < endUtf16) ||
        (startUtf16 == other.startUtf16 && (length == 0 || other.length == 0))
