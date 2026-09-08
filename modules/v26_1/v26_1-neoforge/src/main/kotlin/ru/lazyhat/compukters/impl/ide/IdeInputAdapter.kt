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
 */

package ru.lazyhat.compukters.impl.ide

import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.analysis.IDE_COMPLETION_VISIBLE_ROWS
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticInteraction
import ru.lazyhat.compukters.ide.client.files.IdeComputerNode
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeConflictAction
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdeHorizontalDirection
import ru.lazyhat.compukters.ide.client.state.IdeMoveDirection
import ru.lazyhat.compukters.ide.client.state.IdeProjectSummary
import ru.lazyhat.compukters.ide.client.state.IdeVerticalDirection
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectFileKind
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeEntry

fun interface IdeCommandSink {
    fun dispatch(command: IdeCommand)
}

fun interface IdeClipboard {
    fun text(): String
}

fun interface IdeClipboardWriter {
    fun setText(value: String)
}

fun interface IdeSelectionSource {
    fun selectedText(): String?
}

fun interface IdeUiActionSink {
    fun activate(action: IdeHitAction): Boolean
}

enum class IdeFocusArea { Editor, Tree, Panel, Terminal, None }

data class IdeFocusState(
    val area: IdeFocusArea,
    val completionVisible: Boolean = false,
    val declarationChooserVisible: Boolean = false,
    val dialog: IdeDialogState? = null,
    val editorPageRows: Int = 1,
) {
    companion object {
        val Initial = IdeFocusState(IdeFocusArea.Editor)
        val Editor = IdeFocusState(IdeFocusArea.Editor)
        val Tree = IdeFocusState(IdeFocusArea.Tree)
        val Panel = IdeFocusState(IdeFocusArea.Panel)
        val None = IdeFocusState(IdeFocusArea.None)
    }
}

data class IdePointerContext(
    val geometry: IdeRenderGeometry,
    val editor: IdeEditorView.Text? = null,
    val projects: List<IdeProjectSummary> = emptyList(),
    val tree: List<ProjectTreeEntry> = emptyList(),
    val explorer: List<IdeExplorerRow> = emptyList(),
    val treeFirstRow: Int = 0,
    val hitTargets: List<IdeHitTarget> = emptyList(),
    val dialog: IdeDialogState? = null,
)

data class IdeExplorerDragVisual(
    val source: IdeComputerNode,
    val x: Double,
    val y: Double,
    val destination: ProjectPath?,
)

class IdeInputAdapter(
    private val sink: IdeCommandSink,
    private val clipboard: IdeClipboard,
    private val limits: IdeClientLimits,
    private val uiActions: IdeUiActionSink = IdeUiActionSink { false },
    private val clipboardWriter: IdeClipboardWriter = IdeClipboardWriter {},
    private val selectionSource: IdeSelectionSource = IdeSelectionSource { null },
) {
    var treeFirstRow: Int = 0
        private set
    private var explorerCapture: ExplorerCapture? = null
    val explorerDragActive: Boolean get() = explorerCapture?.active == true
    val explorerDragVisual: IdeExplorerDragVisual?
        get() =
            explorerCapture
                ?.takeIf { it.active }
                ?.let { IdeExplorerDragVisual(it.source, it.x, it.y, it.destination) }

    fun clampTree(
        entryCount: Int,
        treeHeight: Int,
    ): Int {
        val visibleRows = ((treeHeight - TREE_ROWS_TOP).coerceAtLeast(0) / UI_LINE_HEIGHT).coerceAtLeast(1)
        treeFirstRow = treeFirstRow.coerceIn(0, (entryCount - visibleRows).coerceAtLeast(0))
        return treeFirstRow
    }

    fun keyPressed(
        event: KeyEvent,
        focus: IdeFocusState,
    ): Boolean {
        focus.dialog?.let { return dialogKey(event, it) }
        if (focus.declarationChooserVisible) chooserKey(event)?.let { return dispatch(it) }
        if (focus.completionVisible) completionKey(event)?.let { return dispatch(it) }
        if (focus.area != IdeFocusArea.Editor) return false
        if (event.isPaste) return dispatchType(boundedClipboard(clipboard.text()))
        val control = event.modifiers() and GLFW.GLFW_MOD_CONTROL != 0
        val alt = event.modifiers() and GLFW.GLFW_MOD_ALT != 0
        val shift = event.modifiers() and GLFW.GLFW_MOD_SHIFT != 0
        if (control && event.key() == GLFW.GLFW_KEY_C) return copySelection()
        if (control && event.key() == GLFW.GLFW_KEY_X) return cutSelection()
        val command =
            if (control) {
                when (event.key()) {
                    GLFW.GLFW_KEY_S -> IdeCommand.Save
                    GLFW.GLFW_KEY_L -> if (alt && !shift) IdeCommand.Format else null
                    GLFW.GLFW_KEY_B -> IdeCommand.GoToDeclaration()
                    GLFW.GLFW_KEY_F9 -> IdeCommand.Build
                    GLFW.GLFW_KEY_SPACE -> IdeCommand.ManualCompletion
                    GLFW.GLFW_KEY_Z -> IdeCommand.Edit(IdeEditorInput.Undo)
                    GLFW.GLFW_KEY_Y -> IdeCommand.Edit(IdeEditorInput.Redo)
                    GLFW.GLFW_KEY_A -> IdeCommand.Edit(IdeEditorInput.SelectAll)
                    GLFW.GLFW_KEY_LEFT -> wordMove(IdeHorizontalDirection.Left, shift)
                    GLFW.GLFW_KEY_RIGHT -> wordMove(IdeHorizontalDirection.Right, shift)
                    GLFW.GLFW_KEY_BACKSPACE -> IdeCommand.Edit(IdeEditorInput.DeleteWordBackward)
                    GLFW.GLFW_KEY_DELETE -> IdeCommand.Edit(IdeEditorInput.DeleteWordForward)
                    else -> null
                }
            } else if (alt) {
                when (event.key()) {
                    GLFW.GLFW_KEY_LEFT -> IdeCommand.NavigateBack
                    GLFW.GLFW_KEY_RIGHT -> IdeCommand.NavigateForward
                    else -> null
                }
            } else {
                editorKey(event.key(), shift, focus.editorPageRows)
            }
        return command?.let(::dispatch) ?: false
    }

    fun charTyped(
        event: CharacterEvent,
        focus: IdeFocusState,
    ): Boolean {
        if (focus.dialog != null || focus.area != IdeFocusArea.Editor) return false
        return dispatchType(event.codepointAsString())
    }

    fun keyReleased(event: KeyEvent): Boolean =
        if (event.key() == GLFW.GLFW_KEY_LEFT_CONTROL || event.key() == GLFW.GLFW_KEY_RIGHT_CONTROL) {
            dispatch(IdeCommand.ControlReleased)
        } else {
            false
        }

    fun pointerActivity() {
        sink.dispatch(IdeCommand.PointerActivity)
    }

    fun explorerPressed(
        x: Double,
        y: Double,
        modifiers: Int,
        context: IdePointerContext,
    ): Boolean {
        val row = explorerRowAt(x, y, context) as? IdeExplorerRow.ComputerEntry ?: return false
        explorerCapture = ExplorerCapture(row.node, x, y, modifiers, x, y, active = false, destination = null)
        return true
    }

    fun explorerDragged(
        x: Double,
        y: Double,
        context: IdePointerContext,
    ): Boolean {
        val capture = explorerCapture ?: return false
        val active = capture.active || maxOf(kotlin.math.abs(x - capture.pressX), kotlin.math.abs(y - capture.pressY)) >= DRAG_THRESHOLD
        explorerCapture =
            capture.copy(
                x = x,
                y = y,
                active = active,
                destination = if (active) projectDirectoryAt(x, y, context) else null,
            )
        return true
    }

    fun explorerReleased(
        x: Double,
        y: Double,
        modifiers: Int,
        context: IdePointerContext,
    ): Boolean {
        val capture = explorerCapture ?: return false
        explorerCapture = null
        if (!capture.active) return pointerClicked(capture.pressX, capture.pressY, capture.modifiers, context)
        val destination = projectDirectoryAt(x, y, context) ?: return true
        sink.dispatch(IdeCommand.DropComputerEntry(capture.source.path, destination))
        pointerActivity()
        return true
    }

    fun cancelExplorerDrag(): Boolean {
        if (explorerCapture == null) return false
        explorerCapture = null
        return true
    }

    private fun explorerRowAt(
        x: Double,
        y: Double,
        context: IdePointerContext,
    ): IdeExplorerRow? {
        val bounds = context.geometry.tree ?: return null
        if (!bounds.contains(x, y)) return null
        val local = (y - bounds.top - TREE_ROWS_TOP).toInt()
        if (local < 0) return null
        val row = context.treeFirstRow + local / UI_LINE_HEIGHT
        return context.explorer.getOrNull(row)
    }

    private fun projectDirectoryAt(
        x: Double,
        y: Double,
        context: IdePointerContext,
    ): ProjectPath? {
        val row = explorerRowAt(x, y, context) as? IdeExplorerRow.ProjectEntry ?: return null
        return row.entry.path.takeIf { row.entry.kind is ProjectFileKind.Directory }
    }

    fun pointerClicked(
        x: Double,
        y: Double,
        modifiers: Int,
        context: IdePointerContext,
        doubleClick: Boolean = false,
    ): Boolean {
        val geometry = context.geometry
        context.hitTargets
            .asReversed()
            .firstOrNull { it.enabled && it.bounds.contains(x, y) }
            ?.let { target ->
                if (target.action == IdeHitAction.DeclarationChoice) {
                    val chooser =
                        ((context.editor?.analysis as? IdeAnalysisState.Active)?.interaction as? IdeSemanticInteraction.Chooser)
                    val index = target.choiceIndex
                    if (chooser != null && index != null) {
                        sink.dispatch(IdeCommand.MoveDeclarationChoice(index - chooser.selectedIndex))
                        sink.dispatch(IdeCommand.AcceptDeclarationChoice)
                        pointerActivity()
                        return true
                    }
                }
                if (target.action == IdeHitAction.ProjectChoice) {
                    val index = target.choiceIndex
                    val project = index?.let(context.projects::getOrNull)
                    if (project != null) {
                        sink.dispatch(IdeCommand.OpenProject(project.directoryName))
                        pointerActivity()
                        return true
                    }
                    return false
                }
                val handled = activate(target.action, context.dialog)
                if (handled) pointerActivity()
                return handled
            }
        if (geometry.editor.contains(x, y)) {
            val editor = context.editor
            if (editor != null) {
                val offset = editorOffset(x, y, editor, geometry) ?: return true
                val control = modifiers and GLFW.GLFW_MOD_CONTROL != 0
                val shift = modifiers and GLFW.GLFW_MOD_SHIFT != 0
                if (control && !shift) {
                    sink.dispatch(IdeCommand.GoToDeclaration(offset))
                } else if (doubleClick && !shift) {
                    sink.dispatch(IdeCommand.Edit(IdeEditorInput.SelectToken(offset)))
                } else {
                    sink.dispatch(IdeCommand.Edit(IdeEditorInput.SetCaret(offset, shift)))
                }
            } else if (context.projects.isNotEmpty()) {
                val row = ((y - geometry.editor.top - START_ROWS_TOP).toInt() / UI_LINE_HEIGHT)
                context.projects.getOrNull(row)?.let { sink.dispatch(IdeCommand.OpenProject(it.directoryName)) }
            }
            pointerActivity()
            return true
        }
        val tree = geometry.tree
        if (tree != null && tree.contains(x, y)) {
            val row = context.treeFirstRow + ((y - tree.top - TREE_ROWS_TOP).toInt() / UI_LINE_HEIGHT)
            val explorer = context.explorer.ifEmpty { context.tree.map(IdeExplorerRow::ProjectEntry) }
            when (val entry = explorer.getOrNull(row)) {
                is IdeExplorerRow.ProjectEntry -> {
                    if (entry.entry.kind !is ProjectFileKind.Directory) sink.dispatch(IdeCommand.OpenFile(entry.entry.path))
                }

                is IdeExplorerRow.ComputerEntry -> {
                    when (entry.node) {
                        is IdeComputerNode.Directory -> {
                            sink.dispatch(IdeCommand.ExpandComputerDirectory(entry.node.path))
                        }

                        is IdeComputerNode.File -> {
                            sink.dispatch(IdeCommand.OpenComputerFile(entry.node.path))
                        }
                    }
                }

                else -> {
                    Unit
                }
            }
            pointerActivity()
            return true
        }
        return false
    }

    fun pointerMoved(
        x: Double,
        y: Double,
        modifiers: Int,
        context: IdePointerContext,
    ) {
        val geometry = context.geometry
        val interaction = (context.editor?.analysis as? IdeAnalysisState.Active)?.interaction
        if (interaction is IdeSemanticInteraction.Chooser) return
        val offset =
            context.editor
                ?.takeIf { geometry.editor.contains(x, y) }
                ?.let { editorOffset(x, y, it, geometry, sourceGlyphOnly = true) }
        sink.dispatch(IdeCommand.SourcePointer(offset, modifiers and GLFW.GLFW_MOD_CONTROL != 0))
    }

    private fun activate(
        action: IdeHitAction,
        dialog: IdeDialogState?,
    ): Boolean =
        when (action) {
            IdeHitAction.Resolve -> {
                dispatch(IdeCommand.Resolve)
            }

            IdeHitAction.Build -> {
                dispatch(IdeCommand.Build)
            }

            IdeHitAction.Format -> {
                dispatch(IdeCommand.Format)
            }

            IdeHitAction.Cancel -> {
                dispatch(IdeCommand.CancelBuild)
            }

            IdeHitAction.Delete -> {
                uiActions.activate(action)
            }

            IdeHitAction.CreateProject,
            IdeHitAction.OpenProject,
            IdeHitAction.ProjectSwitcher,
            IdeHitAction.CreateText,
            IdeHitAction.CreateDirectory,
            IdeHitAction.Rename,
            IdeHitAction.Terminal,
            -> {
                uiActions.activate(action)
            }

            IdeHitAction.Confirm -> {
                when (dialog) {
                    is IdeDialogState.Confirmation -> dispatch(IdeCommand.ConfirmDialog(dialog.actionId))
                    is IdeDialogState.LockUpdate -> dispatch(IdeCommand.ConfirmLockUpdate)
                    is IdeDialogState.FileConflict -> dispatch(IdeCommand.ResolveConflict(dialog.confirmAction()))
                    is IdeDialogState.TargetOverwrite -> dispatch(IdeCommand.ConfirmTargetDeployment)
                    is IdeDialogState.ComputerImport -> dispatch(IdeCommand.ConfirmComputerImport)
                    else -> uiActions.activate(action)
                }
            }

            IdeHitAction.Dismiss -> {
                when (dialog) {
                    null -> uiActions.activate(action)
                    is IdeDialogState.TargetOverwrite -> dispatch(IdeCommand.CancelTargetDeployment)
                    is IdeDialogState.ComputerImport -> dispatch(IdeCommand.CancelComputerImport)
                    else -> dispatch(IdeCommand.CancelDialog)
                }
            }

            IdeHitAction.Verify -> {
                dispatch(IdeCommand.Verify)
            }

            IdeHitAction.Deploy -> {
                dispatch(IdeCommand.Deploy)
            }

            IdeHitAction.Run -> {
                dispatch(IdeCommand.Run)
            }

            IdeHitAction.RefreshComputer -> {
                dispatch(IdeCommand.RefreshComputerTree)
            }

            IdeHitAction.DeclarationChoice -> {
                false
            }

            IdeHitAction.ProjectChoice -> {
                false
            }
        }

    fun scroll(
        x: Double,
        y: Double,
        horizontal: Double,
        vertical: Double,
        context: IdePointerContext,
    ): Boolean {
        if (context.geometry.editor.contains(x, y) && context.editor != null) {
            val lines = (-vertical * SCROLL_ROWS).toInt()
            val columns = (horizontal * SCROLL_COLUMNS).toInt()
            if (lines != 0 || columns != 0) sink.dispatch(IdeCommand.ScrollEditor(lines, columns))
            pointerActivity()
            return true
        }
        val tree = context.geometry.tree
        if (tree != null && tree.contains(x, y)) {
            val entries = if (context.explorer.isEmpty()) context.tree.size else context.explorer.size
            clampTree(entries, tree.height)
            treeFirstRow = (treeFirstRow + (-vertical * SCROLL_ROWS).toInt()).coerceIn(0, maximumTreeRow(entries, tree.height))
            pointerActivity()
            return true
        }
        return false
    }

    private fun maximumTreeRow(
        entryCount: Int,
        treeHeight: Int,
    ): Int {
        val visibleRows = ((treeHeight - TREE_ROWS_TOP).coerceAtLeast(0) / UI_LINE_HEIGHT).coerceAtLeast(1)
        return (entryCount - visibleRows).coerceAtLeast(0)
    }

    private fun dialogKey(
        event: KeyEvent,
        dialog: IdeDialogState,
    ): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            val command =
                when (dialog) {
                    is IdeDialogState.FileConflict -> IdeCommand.ResolveConflict(IdeConflictAction.Cancel)
                    is IdeDialogState.TargetOverwrite -> IdeCommand.CancelTargetDeployment
                    is IdeDialogState.ComputerImport -> IdeCommand.CancelComputerImport
                    else -> IdeCommand.CancelDialog
                }
            return dispatch(command)
        }
        if (event.key() != GLFW.GLFW_KEY_ENTER) return true
        return when (dialog) {
            is IdeDialogState.Confirmation -> dispatch(IdeCommand.ConfirmDialog(dialog.actionId))
            is IdeDialogState.LockUpdate -> dispatch(IdeCommand.ConfirmLockUpdate)
            is IdeDialogState.FileConflict -> dispatch(IdeCommand.ResolveConflict(dialog.confirmAction()))
            is IdeDialogState.TargetOverwrite -> dispatch(IdeCommand.ConfirmTargetDeployment)
            is IdeDialogState.ComputerImport -> dispatch(IdeCommand.ConfirmComputerImport)
        }
    }

    private fun IdeDialogState.FileConflict.confirmAction(): IdeConflictAction =
        if (closing) IdeConflictAction.DiscardAndClose else IdeConflictAction.ReloadFromDisk

    private fun completionKey(event: KeyEvent): IdeCommand? =
        when (event.key()) {
            GLFW.GLFW_KEY_ESCAPE -> IdeCommand.DismissCompletion
            GLFW.GLFW_KEY_ENTER -> IdeCommand.Edit(IdeEditorInput.Enter)
            GLFW.GLFW_KEY_TAB -> IdeCommand.Edit(IdeEditorInput.Tab)
            GLFW.GLFW_KEY_UP -> IdeCommand.Edit(IdeEditorInput.Move(IdeMoveDirection.Up, false))
            GLFW.GLFW_KEY_DOWN -> IdeCommand.Edit(IdeEditorInput.Move(IdeMoveDirection.Down, false))
            GLFW.GLFW_KEY_PAGE_UP -> repeatMove(IdeMoveDirection.Up, IDE_COMPLETION_VISIBLE_ROWS)
            GLFW.GLFW_KEY_PAGE_DOWN -> repeatMove(IdeMoveDirection.Down, IDE_COMPLETION_VISIBLE_ROWS)
            else -> null
        }

    private fun chooserKey(event: KeyEvent): IdeCommand? =
        when (event.key()) {
            GLFW.GLFW_KEY_ESCAPE -> IdeCommand.DismissSemanticInteraction
            GLFW.GLFW_KEY_ENTER -> IdeCommand.AcceptDeclarationChoice
            GLFW.GLFW_KEY_UP -> IdeCommand.MoveDeclarationChoice(-1)
            GLFW.GLFW_KEY_DOWN -> IdeCommand.MoveDeclarationChoice(1)
            else -> null
        }

    private fun editorKey(
        key: Int,
        shift: Boolean,
        pageRows: Int,
    ): IdeCommand? =
        when (key) {
            GLFW.GLFW_KEY_LEFT -> {
                move(IdeMoveDirection.Left, shift)
            }

            GLFW.GLFW_KEY_RIGHT -> {
                move(IdeMoveDirection.Right, shift)
            }

            GLFW.GLFW_KEY_UP -> {
                move(IdeMoveDirection.Up, shift)
            }

            GLFW.GLFW_KEY_DOWN -> {
                move(IdeMoveDirection.Down, shift)
            }

            GLFW.GLFW_KEY_HOME -> {
                move(IdeMoveDirection.Home, shift)
            }

            GLFW.GLFW_KEY_END -> {
                move(IdeMoveDirection.End, shift)
            }

            GLFW.GLFW_KEY_BACKSPACE -> {
                IdeCommand.Edit(IdeEditorInput.Backspace)
            }

            GLFW.GLFW_KEY_DELETE -> {
                IdeCommand.Edit(IdeEditorInput.Delete)
            }

            GLFW.GLFW_KEY_ENTER -> {
                IdeCommand.Edit(IdeEditorInput.Enter)
            }

            GLFW.GLFW_KEY_TAB -> {
                IdeCommand.Edit(if (shift) IdeEditorInput.Outdent else IdeEditorInput.Tab)
            }

            GLFW.GLFW_KEY_PAGE_UP -> {
                IdeCommand.Edit(IdeEditorInput.Page(IdeVerticalDirection.Up, pageRows.coerceAtLeast(1), shift))
            }

            GLFW.GLFW_KEY_PAGE_DOWN -> {
                IdeCommand.Edit(IdeEditorInput.Page(IdeVerticalDirection.Down, pageRows.coerceAtLeast(1), shift))
            }

            GLFW.GLFW_KEY_ESCAPE -> {
                IdeCommand.CloseRequested
            }

            else -> {
                null
            }
        }

    private fun move(
        direction: IdeMoveDirection,
        selection: Boolean,
    ) = IdeCommand.Edit(IdeEditorInput.Move(direction, selection))

    private fun wordMove(
        direction: IdeHorizontalDirection,
        selection: Boolean,
    ) = IdeCommand.Edit(IdeEditorInput.MoveWord(direction, selection))

    private fun copySelection(): Boolean {
        selectionSource.selectedText()?.let { clipboardWriter.setText(boundedClipboard(it)) }
        return true
    }

    private fun cutSelection(): Boolean {
        val selected = selectionSource.selectedText() ?: return true
        clipboardWriter.setText(boundedClipboard(selected))
        return dispatch(IdeCommand.Edit(IdeEditorInput.Cut))
    }

    private fun repeatMove(
        direction: IdeMoveDirection,
        count: Int,
    ): IdeCommand {
        repeat(count - 1) { sink.dispatch(move(direction, false)) }
        return move(direction, false)
    }

    private fun dispatchType(text: String): Boolean {
        if (text.isEmpty()) return true
        return dispatch(IdeCommand.Edit(IdeEditorInput.Type(text)))
    }

    private fun dispatch(command: IdeCommand): Boolean {
        sink.dispatch(command)
        return true
    }

    private fun boundedClipboard(value: String): String {
        val result = StringBuilder(minOf(value.length, limits.clipboardCodeUnits))
        var offset = 0
        var bytes = 0
        while (offset < value.length) {
            val first = value[offset]
            val validPair =
                Character.isHighSurrogate(first) && offset + 1 < value.length && Character.isLowSurrogate(value[offset + 1])
            val codePoint =
                when {
                    validPair -> Character.toCodePoint(first, value[offset + 1])
                    Character.isSurrogate(first) -> 0xfffd
                    else -> first.code
                }
            val inputUnits = if (validPair) 2 else 1
            val outputUnits = Character.charCount(codePoint)
            val outputBytes = utf8Bytes(codePoint)
            if (result.length + outputUnits > limits.clipboardCodeUnits || bytes + outputBytes > limits.clipboardUtf8Bytes) break
            result.appendCodePoint(codePoint)
            bytes += outputBytes
            offset += inputUnits
        }
        return result.toString()
    }

    private fun utf8Bytes(codePoint: Int): Int =
        when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }

    private fun editorOffset(
        x: Double,
        y: Double,
        editor: IdeEditorView.Text,
        geometry: IdeRenderGeometry,
        sourceGlyphOnly: Boolean = false,
    ): Int? {
        val font = geometry.font
        val gutterDigits =
            editor.totalLines
                .toString()
                .length
                .coerceAtLeast(2)
        val codeLeft = geometry.editor.left + (gutterDigits + 2) * font.cellWidth
        if (sourceGlyphOnly && x < codeLeft) return null
        val row = ((y - geometry.editor.top).toInt() / font.cellHeight)
        val line = editor.visibleLines.getOrNull(row) ?: return null
        val lineStart = editor.visibleLineStartsUtf16[row]
        val requestedColumn = editor.firstVisibleColumn + ((x - codeLeft).toInt() / font.cellWidth).coerceAtLeast(0)
        if (sourceGlyphOnly && requestedColumn !in editor.firstVisibleColumn until line.visualWidth()) return null
        var offset = 0
        var column = 0
        while (offset < line.length && column < requestedColumn) {
            val codePoint = line.codePointAt(offset)
            val next = if (codePoint == '\t'.code) column + TAB_WIDTH - column % TAB_WIDTH else column + 1
            if (next > requestedColumn) break
            column = next
            offset += Character.charCount(codePoint)
        }
        return lineStart + offset
    }

    private fun String.visualWidth(): Int {
        var offset = 0
        var column = 0
        while (offset < length) {
            val codePoint = codePointAt(offset)
            column = if (codePoint == '\t'.code) column + TAB_WIDTH - column % TAB_WIDTH else column + 1
            offset += Character.charCount(codePoint)
        }
        return column
    }

    private fun IdeRect.contains(
        x: Double,
        y: Double,
    ): Boolean = x >= left && x < right && y >= top && y < bottom

    private companion object {
        const val UI_LINE_HEIGHT = 12
        const val START_ROWS_TOP = 6
        const val TREE_ROWS_TOP = 4
        const val SCROLL_ROWS = 3
        const val SCROLL_COLUMNS = 4
        const val TAB_WIDTH = 4
        const val DRAG_THRESHOLD = 4.0
    }

    private data class ExplorerCapture(
        val source: IdeComputerNode,
        val pressX: Double,
        val pressY: Double,
        val modifiers: Int,
        val x: Double,
        val y: Double,
        val active: Boolean,
        val destination: ProjectPath?,
    )
}

class IdeSplitterInteraction(
    initial: IdeLayoutSettings,
    private val persist: (IdeLayoutSettings) -> Unit,
) {
    var layout: IdeLayoutSettings = initial
        private set
    private var capture: Capture? = null
    val captured: Boolean get() = capture != null

    fun press(
        x: Int,
        y: Int,
        geometry: IdeRenderGeometry,
    ): Boolean {
        capture =
            when {
                geometry.treeSplitter?.contains(x, y) == true -> Capture.Tree
                geometry.diagnosticsSplitter?.contains(x, y) == true -> Capture.Diagnostics
                else -> null
            }
        return captured
    }

    fun drag(
        x: Int,
        y: Int,
        geometry: IdeRenderGeometry,
    ): Boolean {
        layout =
            when (capture) {
                Capture.Tree -> layout.copy(treeWidth = geometry.treeWidthAt(x))
                Capture.Diagnostics -> layout.copy(diagnosticsHeight = geometry.diagnosticsHeightAt(y))
                null -> return false
            }
        return true
    }

    fun release(): Boolean {
        if (capture == null) return false
        capture = null
        persist(layout)
        return true
    }

    fun focusLost() {
        release()
    }

    private fun IdeRect.contains(
        x: Int,
        y: Int,
    ): Boolean = x in left until right && y in top until bottom

    private enum class Capture { Tree, Diagnostics }
}
