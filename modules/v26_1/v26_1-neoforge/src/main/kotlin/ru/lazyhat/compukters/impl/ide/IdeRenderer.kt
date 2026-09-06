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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.analysis.IdeDeclarationTarget
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticAnchor
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticInteraction
import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.files.IdeComputerChildren
import ru.lazyhat.compukters.ide.client.files.IdeComputerNode
import ru.lazyhat.compukters.ide.client.files.IdeComputerTransferState
import ru.lazyhat.compukters.ide.client.files.IdeComputerTreeState
import ru.lazyhat.compukters.ide.client.state.IdeBusyOperation
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeToolingState
import ru.lazyhat.compukters.ide.client.state.IdeViewState
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeTargetState
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalState
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile

enum class IdePanelKind {
    Main,
    Header,
    Toolbar,
    ToolStripe,
    Tree,
    Editor,
    Diagnostics,
    Status,
    Control,
    Dialog,
}

enum class IdeTextKind {
    Header,
    Toolbar,
    ToolStripe,
    StartProject,
    TreeRow,
    DragGhost,
    LineNumber,
    Source,
    Diagnostic,
    Status,
    Binary,
    Dialog,
    Completion,
    Hover,
    DeclarationChoice,
}

enum class IdeTextRotation { None, Clockwise90 }

enum class IdeFillKind { Background, Border, Selection, DropTarget, Caret, Splitter, HyperlinkUnderline, MutableUnderline, DialogScrim }

enum class IdeScissorKind { Tree, Editor, Diagnostics, Completion, SemanticPopup }

enum class IdeHitAction {
    CreateProject,
    OpenProject,
    CreateText,
    CreateDirectory,
    Rename,
    Delete,
    Resolve,
    Format,
    Build,
    Cancel,
    Verify,
    Deploy,
    Run,
    Terminal,
    RefreshComputer,
    Confirm,
    Dismiss,
    DeclarationChoice,
}

enum class IdeFocusGroup { Page, Dialog }

sealed interface IdeTextStyle {
    data object Ui : IdeTextStyle

    data object Plain : IdeTextStyle

    data class Lexical(
        val kind: KotlinLexicalKind,
    ) : IdeTextStyle

    data class Semantic(
        val category: SemanticCategory,
        val isMutable: Boolean = false,
    ) : IdeTextStyle
}

data class IdePanelDraw(
    val kind: IdePanelKind,
    val bounds: IdeRect,
    val color: Int,
    val zIndex: Int,
)

data class IdeTextDraw(
    val kind: IdeTextKind,
    val value: String,
    val x: Int,
    val y: Int,
    val color: Int,
    val style: IdeTextStyle,
    val codeFont: TerminalFontProfile?,
    val clip: IdeRect?,
    val sourceRange: EditorRange?,
    val zIndex: Int,
    val rotation: IdeTextRotation = IdeTextRotation.None,
)

data class IdeFillDraw(
    val kind: IdeFillKind,
    val bounds: IdeRect,
    val color: Int,
    val zIndex: Int,
)

data class IdeScissorDraw(
    val kind: IdeScissorKind,
    val bounds: IdeRect,
    val zIndex: Int,
)

data class IdeHitTarget(
    val action: IdeHitAction,
    val bounds: IdeRect,
    val enabled: Boolean,
    val tooltip: String?,
    val focusGroup: IdeFocusGroup,
    val zIndex: Int,
    val selected: Boolean = false,
    val choiceIndex: Int? = null,
)

data class IdeDrawModel(
    val panels: List<IdePanelDraw>,
    val text: List<IdeTextDraw>,
    val fills: List<IdeFillDraw>,
    val scissors: List<IdeScissorDraw>,
    val hitTargets: List<IdeHitTarget>,
)

internal object IdeRenderer {
    fun extract(
        state: IdeViewState,
        geometry: IdeRenderGeometry,
        caretVisible: Boolean = true,
        prompt: IdePromptState? = null,
        treeFirstRow: Int = 0,
        selectedTreePath: ProjectPath? = null,
        terminalState: IdeTargetTerminalState = IdeTargetTerminalState.Closed,
        terminalVisible: Boolean = false,
        explorerDrag: IdeExplorerDragVisual? = null,
    ): IdeDrawModel {
        val output = Builder(geometry, geometry.font, treeFirstRow, selectedTreePath, terminalState, terminalVisible, explorerDrag)
        output.base()
        if (!geometry.supported) {
            output.ui(IdeTextKind.Status, geometry.unsupportedMessage, 8, 8, IdeColors.ERROR)
            return output.build()
        }
        when (val page = state.page) {
            is IdePageState.Start -> output.start(page, state.target)
            is IdePageState.Workspace -> output.workspace(page.value, state.target, state.tooling, state.busy, caretVisible)
        }
        output.terminalTool(state.target)
        if (prompt != null) {
            output.prompt(prompt)
        } else {
            state.dialog?.let(output::dialog)
        }
        return output.build()
    }

    private class Builder(
        private val geometry: IdeRenderGeometry,
        private val font: TerminalFontProfile,
        private val treeFirstRow: Int,
        private val selectedTreePath: ProjectPath?,
        private val terminalState: IdeTargetTerminalState,
        private val terminalVisible: Boolean,
        private val explorerDrag: IdeExplorerDragVisual?,
    ) {
        private val panels = mutableListOf<IdePanelDraw>()
        private val text = mutableListOf<IdeTextDraw>()
        private val fills = mutableListOf<IdeFillDraw>()
        private val scissors = mutableListOf<IdeScissorDraw>()
        private val hitTargets = mutableListOf<IdeHitTarget>()

        fun base() {
            fills += IdeFillDraw(IdeFillKind.Background, geometry.viewport, IdeColors.DIM, Z_BACKGROUND)
            if (!geometry.supported) return
            fills += IdeFillDraw(IdeFillKind.Border, expand(geometry.panel, 1), IdeColors.BORDER, Z_PANEL)
            panel(IdePanelKind.Main, geometry.panel, IdeColors.PANEL)
            panel(IdePanelKind.Header, geometry.header, IdeColors.PANEL_ALT)
            panel(IdePanelKind.Toolbar, geometry.toolbar, IdeColors.PANEL)
            panel(IdePanelKind.ToolStripe, geometry.toolStripe, IdeColors.PANEL_ALT)
            panel(IdePanelKind.Status, geometry.status, IdeColors.PANEL_ALT)
            geometry.tree?.let { panel(IdePanelKind.Tree, it, IdeColors.PANEL_ALT) }
            panel(IdePanelKind.Editor, geometry.editor, IdeColors.EDITOR)
            geometry.diagnostics?.let { panel(IdePanelKind.Diagnostics, it, IdeColors.PANEL_ALT) }
            geometry.treeSplitter?.let { fills += IdeFillDraw(IdeFillKind.Splitter, it, IdeColors.BORDER, Z_CONTENT) }
            geometry.diagnosticsSplitter?.let { fills += IdeFillDraw(IdeFillKind.Splitter, it, IdeColors.BORDER, Z_CONTENT) }
        }

        fun start(
            page: IdePageState.Start,
            targetState: IdeTargetState,
        ) {
            ui(IdeTextKind.Header, "Compukters IDE · ${targetLabel(targetState)}", geometry.header.left + 6, geometry.header.top + 7)
            val create =
                IdeRect(
                    geometry.toolbar.left + 6,
                    geometry.toolbar.top + 3,
                    geometry.toolbar.left + 106,
                    geometry.toolbar.bottom - 3,
                )
            val open = IdeRect(create.right + 4, create.top, create.right + 104, create.bottom)
            target(IdeHitAction.CreateProject, create, true)
            target(IdeHitAction.OpenProject, open, true)
            ui(IdeTextKind.Toolbar, "Create project", create.left + 4, create.top + 4)
            ui(IdeTextKind.Toolbar, "Open project", open.left + 4, open.top + 4)
            val maximumRows = geometry.editor.height / UI_LINE_HEIGHT
            page.projects.take(maximumRows).forEachIndexed { index, project ->
                ui(
                    IdeTextKind.StartProject,
                    project.displayName,
                    geometry.editor.left + 8,
                    geometry.editor.top + 6 + index * UI_LINE_HEIGHT,
                )
            }
            page.error?.let {
                ui(
                    IdeTextKind.Status,
                    it.message,
                    geometry.status.left + 6,
                    geometry.status.top + 5,
                    problemColor(it.severity.name),
                )
            }
        }

        fun workspace(
            workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView,
            targetState: IdeTargetState,
            toolingState: IdeToolingState,
            busy: Set<IdeBusyOperation>,
            caretVisible: Boolean,
        ) {
            val active = workspace.activeFile?.value ?: "No file"
            ui(
                IdeTextKind.Header,
                "${workspace.project.displayName} · $active · ${targetLabel(targetState)}",
                geometry.header.left + 6,
                geometry.header.top + 7,
            )
            toolbar(workspace, targetState, toolingState, busy, workspace.activeFile != null || selectedTreePath != null)
            tree(workspace)
            when (val editor = workspace.editor) {
                IdeEditorView.Empty -> {
                    ui(IdeTextKind.Source, "Open a file", geometry.editor.left + 8, geometry.editor.top + 8, IdeColors.MUTED)
                }

                is IdeEditorView.Binary -> {
                    ui(
                        IdeTextKind.Binary,
                        "Binary file · ${editor.bytes} bytes",
                        geometry.editor.left + 8,
                        geometry.editor.top + 8,
                        IdeColors.MUTED,
                    )
                }

                is IdeEditorView.Text -> {
                    editor(editor, caretVisible)
                }
            }
            diagnostics(workspace)
            status(workspace, targetState, toolingState, busy)
        }

        private fun toolbar(
            workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView,
            targetState: IdeTargetState,
            toolingState: IdeToolingState,
            busy: Set<IdeBusyOperation>,
            hasActiveEntry: Boolean,
        ) {
            var left = geometry.toolbar.left + 6

            fun action(
                label: String,
                action: IdeHitAction,
                enabled: Boolean = true,
                tooltip: String? = null,
                selected: Boolean = false,
            ) {
                val width = maxOf(44, label.length * 6 + 10)
                val bounds = IdeRect(left, geometry.toolbar.top + 3, left + width, geometry.toolbar.bottom - 3)
                target(action, bounds, enabled, tooltip, selected = selected)
                ui(IdeTextKind.Toolbar, label, bounds.left + 4, bounds.top + 4, if (enabled) IdeColors.TEXT else IdeColors.DISABLED)
                left = bounds.right + 4
            }
            val toolingReady = toolingState == IdeToolingState.Ready
            val toolingTooltip = if (toolingReady) null else "Kotlin tooling is not ready"
            val build = workspace.build
            val editor = workspace.editor as? IdeEditorView.Text
            val formatBusy = IdeBusyOperation.Format in busy
            val writableKotlin = editor?.readOnly == false && editor.path?.value?.endsWith(".kt") == true
            val formatTooltip =
                when {
                    !toolingReady -> toolingTooltip
                    !writableKotlin -> "Open a writable Kotlin source"
                    formatBusy -> "Kotlin formatting is already running"
                    else -> "Reformat Code (Ctrl+Alt+L)"
                }
            action(
                "Format",
                IdeHitAction.Format,
                toolingReady && writableKotlin && !formatBusy,
                formatTooltip,
                selected = formatBusy,
            )
            action("Resolve", IdeHitAction.Resolve, toolingReady, toolingTooltip)
            if (build is IdeBuildState.Compiling || build is IdeBuildState.Saving) {
                action("Cancel", IdeHitAction.Cancel)
            } else {
                action("Build", IdeHitAction.Build, toolingReady, toolingTooltip)
            }
            val targetReady =
                toolingReady && targetState.isReadyForAction && build !is IdeBuildState.Compiling && build !is IdeBuildState.Saving
            val targetTooltip =
                when {
                    !toolingReady -> toolingTooltip
                    targetState.attachedTarget == null -> NO_TARGET
                    !targetReady -> "Target operation in progress"
                    else -> null
                }
            action("Verify", IdeHitAction.Verify, targetReady, targetTooltip)
            action("Deploy", IdeHitAction.Deploy, targetReady, targetTooltip)
            action("Run", IdeHitAction.Run, targetReady, targetTooltip)
            action("+File", IdeHitAction.CreateText)
            action("+Dir", IdeHitAction.CreateDirectory)
            action("Rename", IdeHitAction.Rename, hasActiveEntry)
            action("Delete", IdeHitAction.Delete, hasActiveEntry)
        }

        private fun tree(workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView) {
            val bounds = geometry.tree ?: return
            scissors += IdeScissorDraw(IdeScissorKind.Tree, bounds, Z_CLIP)
            val rows = bounds.height / UI_LINE_HEIGHT
            workspace.explorerRows().drop(treeFirstRow).take(rows).forEachIndexed { index, row ->
                val y = bounds.top + 4 + index * UI_LINE_HEIGHT
                if (
                    row is IdeExplorerRow.ProjectEntry &&
                    row.entry.kind is ru.lazyhat.compukters.ide.project.tree.ProjectFileKind.Directory &&
                    row.entry.path == explorerDrag?.destination
                ) {
                    fills +=
                        IdeFillDraw(
                            IdeFillKind.DropTarget,
                            IdeRect(bounds.left, y - 2, bounds.right, y + UI_LINE_HEIGHT - 1),
                            IdeColors.DROP_TARGET,
                            Z_SELECTION,
                        )
                }
                val (label, depth, color) =
                    when (row) {
                        is IdeExplorerRow.ProjectRoot -> {
                            Triple("Project · ${row.name}", 0, IdeColors.TEXT)
                        }

                        is IdeExplorerRow.ProjectEntry -> {
                            val entry = row.entry
                            val marker = if (entry.kind is ru.lazyhat.compukters.ide.project.tree.ProjectFileKind.Directory) "▸ " else "  "
                            val selected = entry.path == selectedTreePath || entry.path == workspace.activeFile
                            Triple(
                                marker + entry.path.value.substringAfterLast('/'),
                                entry.path.value.count {
                                    it == '/'
                                } + 1,
                                if (selected) IdeColors.ACCENT else IdeColors.TEXT,
                            )
                        }

                        is IdeExplorerRow.ComputerRoot -> {
                            Triple(computerRootLabel(row.state), 0, IdeColors.COMPUTER)
                        }

                        is IdeExplorerRow.ComputerEntry -> {
                            val marker =
                                when (val node = row.node) {
                                    is IdeComputerNode.File -> "  "
                                    is IdeComputerNode.Directory -> if (node.children is IdeComputerChildren.Unloaded) "▸ " else "▾ "
                                }
                            Triple(marker + row.node.name, row.depth, IdeColors.COMPUTER)
                        }
                    }
                ui(
                    IdeTextKind.TreeRow,
                    label,
                    bounds.left + 5 + depth * 8,
                    y,
                    color,
                    bounds,
                )
                if (row is IdeExplorerRow.ComputerRoot && row.state !is IdeComputerTreeState.NoTarget) {
                    val refresh = IdeRect(bounds.right - 48, y - 2, bounds.right - 4, y + UI_LINE_HEIGHT - 1)
                    target(IdeHitAction.RefreshComputer, refresh, row.state !is IdeComputerTreeState.Loading, "Refresh target filesystem")
                    ui(IdeTextKind.TreeRow, "Refresh", refresh.left + 3, y, IdeColors.MUTED, bounds)
                }
            }
            explorerDrag?.let { drag ->
                ui(
                    IdeTextKind.DragGhost,
                    drag.source.name,
                    drag.x.toInt() + 7,
                    drag.y.toInt() + 7,
                    if (drag.destination == null) IdeColors.MUTED else IdeColors.ACCENT,
                    geometry.viewport,
                    Z_DRAG,
                )
            }
        }

        private fun computerRootLabel(state: IdeComputerTreeState): String =
            when (state) {
                IdeComputerTreeState.NoTarget -> "Computer · No target"
                IdeComputerTreeState.Loading -> "Computer · Loading…"
                is IdeComputerTreeState.Available -> "Computer"
                is IdeComputerTreeState.Unavailable -> "Computer · Unavailable"
                is IdeComputerTreeState.TargetLost -> "Computer · Target lost"
            }

        private fun terminalTooltip(): String? =
            when (val state = terminalState) {
                is IdeTargetTerminalState.Opening -> "Opening target terminal…"
                is IdeTargetTerminalState.Failed -> state.detail
                else -> null
            }

        fun terminalTool(targetState: IdeTargetState) {
            val attached = targetState.attachedTarget
            val enabled = attached?.capabilities?.terminal == true
            val tooltip =
                when {
                    attached == null -> NO_TARGET
                    !enabled -> TERMINAL_UNAVAILABLE
                    else -> terminalTooltip()
                }
            val bounds =
                IdeRect(
                    geometry.toolStripe.left,
                    geometry.toolbar.bottom,
                    geometry.toolStripe.right,
                    geometry.toolbar.bottom + TERMINAL_TOOL_HEIGHT,
                )
            target(IdeHitAction.Terminal, bounds, enabled, tooltip, selected = terminalVisible)
            ui(
                IdeTextKind.ToolStripe,
                "Terminal",
                bounds.right - TERMINAL_TOOL_TEXT_RIGHT_INSET,
                bounds.top + TERMINAL_TOOL_TEXT_TOP_INSET,
                if (enabled) IdeColors.TEXT else IdeColors.DISABLED,
                rotation = IdeTextRotation.Clockwise90,
            )
        }

        private fun editor(
            editor: IdeEditorView.Text,
            caretVisible: Boolean,
        ) {
            val bounds = geometry.editor
            scissors += IdeScissorDraw(IdeScissorKind.Editor, bounds, Z_CLIP)
            val rows = minOf(geometry.codeRows, editor.visibleLines.size)
            val gutterDigits =
                editor.totalLines
                    .toString()
                    .length
                    .coerceAtLeast(2)
            val codeLeft = bounds.left + (gutterDigits + 2) * font.cellWidth
            repeat(rows) { visibleIndex ->
                val lineNumber = editor.firstVisibleLine + visibleIndex
                val line = editor.visibleLines[visibleIndex]
                val lineStart = editor.visibleLineStartsUtf16[visibleIndex]
                val rowTop = bounds.top + visibleIndex * font.cellHeight
                val y = rowTop + font.glyphDrawOffsetY
                code(
                    IdeTextKind.LineNumber,
                    (lineNumber + 1).toString().padStart(gutterDigits),
                    bounds.left,
                    y,
                    IdeColors.LINE_NUMBER,
                    bounds,
                )
                selection(editor, line, lineStart, codeLeft, rowTop)
                styledLine(editor, lineNumber, line, lineStart, codeLeft, y)
                val nextLineStart = editor.visibleLineStartsUtf16.getOrNull(visibleIndex + 1)
                val caretBelongsToLine =
                    editor.caretUtf16 >= lineStart &&
                        (nextLineStart?.let { editor.caretUtf16 < it } ?: (editor.caretUtf16 <= lineStart + line.length))
                if (caretVisible && caretBelongsToLine) {
                    val local = (editor.caretUtf16 - lineStart).coerceAtMost(line.length)
                    val x = codeLeft + (visualColumns(line.substring(0, local)) - editor.firstVisibleColumn) * font.cellWidth
                    fills += IdeFillDraw(IdeFillKind.Caret, IdeRect(x, rowTop, x + 1, rowTop + font.cellHeight), IdeColors.CARET, Z_CARET)
                }
            }
            val active = editor.analysis as? IdeAnalysisState.Active
            when (val interaction = active?.interaction) {
                is IdeSemanticInteraction.Chooser -> {
                    semanticChooser(editor, codeLeft, interaction)
                }

                else -> {
                    completion(editor, codeLeft)
                    if (active?.completion == null && interaction is IdeSemanticInteraction.Hover) {
                        semanticHover(editor, codeLeft, interaction)
                    }
                }
            }
        }

        private fun selection(
            editor: IdeEditorView.Text,
            line: String,
            lineStart: Int,
            codeLeft: Int,
            rowTop: Int,
        ) {
            val start = editor.selectionStartUtf16 ?: return
            val end = editor.selectionEndUtf16 ?: return
            val localStart = (start - lineStart).coerceIn(0, line.length)
            val localEnd = (end - lineStart).coerceIn(0, line.length)
            if (localEnd <= localStart) return
            val left = codeLeft + (visualColumns(line.substring(0, localStart)) - editor.firstVisibleColumn) * font.cellWidth
            val right = codeLeft + (visualColumns(line.substring(0, localEnd)) - editor.firstVisibleColumn) * font.cellWidth
            fills +=
                IdeFillDraw(
                    IdeFillKind.Selection,
                    IdeRect(left, rowTop, right, rowTop + font.cellHeight),
                    IdeColors.SELECTION,
                    Z_SELECTION,
                )
        }

        private fun styledLine(
            editor: IdeEditorView.Text,
            lineIndex: Int,
            line: String,
            lineStart: Int,
            codeLeft: Int,
            y: Int,
        ) {
            if (line.isEmpty()) return
            val lexical = editor.lexical.lines.getOrNull(lineIndex)
            val semantic = (editor.analysis as? IdeAnalysisState.Active)?.presentation
            val link =
                ((editor.analysis as? IdeAnalysisState.Active)?.interaction as? IdeSemanticInteraction.Link)
                    ?.takeIf { it.anchor.path.value == editor.path?.value }
                    ?.anchor
            val projectPath = editor.path
            val boundaries = sortedSetOf(0, line.length)
            lexical?.spans?.forEach { span ->
                boundaries += span.startUtf16.coerceIn(0, line.length)
                boundaries += span.endUtf16.coerceIn(0, line.length)
            }
            (editor.analysis as? IdeAnalysisState.Active)
                ?.presentation
                ?.semanticTokens
                ?.filter { projectPath != null && it.path.value == projectPath.value }
                ?.forEach { token ->
                    boundaries += (token.range.startUtf16 - lineStart).coerceIn(0, line.length)
                    boundaries += (token.range.endUtf16 - lineStart).coerceIn(0, line.length)
                }
            link?.tokenRange?.let { range ->
                boundaries += (range.startUtf16 - lineStart).coerceIn(0, line.length)
                boundaries += (range.endUtf16 - lineStart).coerceIn(0, line.length)
            }
            boundaries.zipWithNext().forEach { (start, end) ->
                if (end <= start) return@forEach
                val lexicalKind = lexical?.spans?.firstOrNull { start >= it.startUtf16 && start < it.endUtf16 }?.kind
                val semanticToken =
                    semantic
                        ?.semanticTokens
                        ?.firstOrNull { token ->
                            projectPath != null &&
                                token.path.value == projectPath.value &&
                                token.category.contributesTextStyle() &&
                                lineStart + start in token.range.startUtf16 until token.range.endUtf16
                        }
                val resolved =
                    semanticToken?.let { IdeTextStyle.Semantic(it.category, it.isMutable) }
                        ?: lexicalKind?.let(IdeTextStyle::Lexical)
                        ?: IdeTextStyle.Plain
                val x = codeLeft + (visualColumns(line.substring(0, start)) - editor.firstVisibleColumn) * font.cellWidth
                val absoluteRange = EditorRange(lineStart + start, lineStart + end)
                val linked =
                    link?.tokenRange?.let { absoluteRange.startUtf16 >= it.startUtf16 && absoluteRange.endUtf16 <= it.endUtf16 } == true
                code(
                    IdeTextKind.Source,
                    projectGlyphs(line.substring(start, end)),
                    x,
                    y,
                    if (linked) IdeColors.HYPERLINK else styleColor(resolved),
                    geometry.editor,
                    resolved,
                    absoluteRange,
                )
                if (linked) {
                    underline(IdeFillKind.HyperlinkUnderline, x, line.substring(start, end), y, IdeColors.HYPERLINK)
                } else if ((resolved as? IdeTextStyle.Semantic)?.isMutable == true) {
                    underline(IdeFillKind.MutableUnderline, x, line.substring(start, end), y, IdeColors.MUTABLE_UNDERLINE)
                }
            }
        }

        private fun underline(
            kind: IdeFillKind,
            x: Int,
            value: String,
            textY: Int,
            color: Int,
        ) {
            val left = maxOf(x, geometry.editor.left)
            val right = minOf(x + visualColumns(value) * font.cellWidth, geometry.editor.right)
            if (right <= left) return
            val top = (textY - font.glyphDrawOffsetY + font.cellHeight - 1).coerceIn(geometry.editor.top, geometry.editor.bottom - 1)
            fills +=
                IdeFillDraw(
                    kind,
                    IdeRect(left, top, right, top + 1),
                    color,
                    Z_TEXT,
                )
        }

        private fun semanticHover(
            editor: IdeEditorView.Text,
            codeLeft: Int,
            hover: IdeSemanticInteraction.Hover,
        ) {
            val anchor = sourceAnchor(editor, codeLeft, hover.anchor) ?: return
            val lines =
                buildList {
                    hover.info.signature?.let(::add)
                    add("Type: ${hover.info.renderedType}")
                    hover.info.origin?.let { origin ->
                        add(
                            when (origin) {
                                DeclarationOrigin.Project -> "Origin: Project"
                                is DeclarationOrigin.Platform -> "Origin: ${origin.identity.name}"
                            },
                        )
                    }
                }.map(::popupText)
            if (lines.isEmpty()) return
            val requestedWidth =
                maxOf(
                    SEMANTIC_POPUP_MINIMUM_WIDTH,
                    lines.maxOf(::visualColumns) * font.cellWidth + POPUP_HORIZONTAL_PADDING,
                )
            val popup = geometry.anchoredPopup(anchor, requestedWidth, lines.size * UI_LINE_HEIGHT + POPUP_VERTICAL_PADDING)
            semanticPopupPanel(popup.bounds)
            lines.forEachIndexed { index, value ->
                ui(
                    IdeTextKind.Hover,
                    value,
                    popup.bounds.left + 4,
                    popup.bounds.top + 3 + index * UI_LINE_HEIGHT,
                    IdeColors.TEXT,
                    popup.bounds,
                    Z_POPUP_TEXT,
                )
            }
        }

        private fun semanticChooser(
            editor: IdeEditorView.Text,
            codeLeft: Int,
            chooser: IdeSemanticInteraction.Chooser,
        ) {
            val anchor = sourceAnchor(editor, codeLeft, chooser.anchor) ?: return
            val labels = chooser.targets.map(::declarationLabel).map(::popupText)
            val requestedWidth =
                maxOf(
                    SEMANTIC_POPUP_MINIMUM_WIDTH,
                    labels.maxOf(::visualColumns) * font.cellWidth + POPUP_HORIZONTAL_PADDING,
                )
            val requestedRows = minOf(DECLARATION_VISIBLE_ROWS, labels.size)
            val popup = geometry.anchoredPopup(anchor, requestedWidth, requestedRows * UI_LINE_HEIGHT + POPUP_VERTICAL_PADDING)
            val visibleRows = ((popup.bounds.height - POPUP_VERTICAL_PADDING) / UI_LINE_HEIGHT).coerceAtLeast(1)
            val first = (chooser.selectedIndex - visibleRows + 1).coerceIn(0, (labels.size - visibleRows).coerceAtLeast(0))
            val last = minOf(labels.size, first + visibleRows)
            semanticPopupPanel(popup.bounds)
            for (index in first until last) {
                val row = index - first
                val rowBounds =
                    IdeRect(
                        popup.bounds.left,
                        popup.bounds.top + 2 + row * UI_LINE_HEIGHT,
                        popup.bounds.right,
                        minOf(popup.bounds.bottom, popup.bounds.top + 2 + (row + 1) * UI_LINE_HEIGHT),
                    )
                if (rowBounds.height <= 0) continue
                val selected = index == chooser.selectedIndex
                if (selected) {
                    fills += IdeFillDraw(IdeFillKind.Selection, rowBounds, IdeColors.SELECTION, Z_POPUP)
                }
                ui(
                    IdeTextKind.DeclarationChoice,
                    labels[index],
                    popup.bounds.left + 4,
                    popup.bounds.top + 3 + row * UI_LINE_HEIGHT,
                    if (selected) IdeColors.ACCENT else IdeColors.TEXT,
                    popup.bounds,
                    Z_POPUP_TEXT,
                )
                hitTargets +=
                    IdeHitTarget(
                        IdeHitAction.DeclarationChoice,
                        rowBounds,
                        enabled = true,
                        tooltip = null,
                        focusGroup = IdeFocusGroup.Page,
                        zIndex = Z_POPUP_TEXT,
                        selected = selected,
                        choiceIndex = index,
                    )
            }
        }

        private fun semanticPopupPanel(bounds: IdeRect) {
            panel(IdePanelKind.Dialog, bounds, IdeColors.PANEL_ALT, Z_POPUP)
            scissors += IdeScissorDraw(IdeScissorKind.SemanticPopup, bounds, Z_POPUP)
        }

        private fun sourceAnchor(
            editor: IdeEditorView.Text,
            codeLeft: Int,
            anchor: IdeSemanticAnchor,
        ): IdeRect? {
            val visibleIndex = editor.visibleLineStartsUtf16.indexOfLast { it <= anchor.tokenRange.startUtf16 }
            if (visibleIndex !in editor.visibleLines.indices) return null
            val line = editor.visibleLines[visibleIndex]
            val lineStart = editor.visibleLineStartsUtf16[visibleIndex]
            val localStart = anchor.tokenRange.startUtf16 - lineStart
            if (localStart !in 0..line.length) return null
            val localEnd = (anchor.tokenRange.endUtf16 - lineStart).coerceIn(localStart, line.length)
            val left = codeLeft + (visualColumns(line.substring(0, localStart)) - editor.firstVisibleColumn) * font.cellWidth
            val right =
                maxOf(
                    left + font.cellWidth,
                    codeLeft + (visualColumns(line.substring(0, localEnd)) - editor.firstVisibleColumn) * font.cellWidth,
                )
            val top = geometry.editor.top + visibleIndex * font.cellHeight
            return IdeRect(left, top, right, top + font.cellHeight)
        }

        private fun declarationLabel(target: IdeDeclarationTarget): String =
            when (target) {
                is IdeDeclarationTarget.Project -> "Project · ${target.path.value}"
                is IdeDeclarationTarget.AttachedSource -> "${target.module.name} · ${target.path.value}"
            }

        private fun popupText(value: String): String {
            val maximumColumns = (geometry.editor.width / font.cellWidth - 2).coerceAtLeast(1)
            if (visualColumns(value) <= maximumColumns) return value
            val result = StringBuilder(minOf(value.length, maximumColumns))
            var offset = 0
            var columns = 0
            while (offset < value.length && columns < maximumColumns - 1) {
                val codePoint = value.codePointAt(offset)
                result.appendCodePoint(codePoint)
                offset += Character.charCount(codePoint)
                columns++
            }
            return result.append('…').toString()
        }

        private fun completion(
            editor: IdeEditorView.Text,
            codeLeft: Int,
        ) {
            val completion = (editor.analysis as? IdeAnalysisState.Active)?.completion ?: return
            val visibleIndex = editor.visibleLineStartsUtf16.indexOfLast { it <= editor.caretUtf16 }
            if (visibleIndex !in editor.visibleLines.indices) return
            val line = editor.visibleLines[visibleIndex]
            val local = (editor.caretUtf16 - editor.visibleLineStartsUtf16[visibleIndex]).coerceIn(0, line.length)
            val caret =
                IdeRect(
                    codeLeft + (visualColumns(line.substring(0, local)) - editor.firstVisibleColumn) * font.cellWidth,
                    geometry.editor.top + visibleIndex * font.cellHeight,
                    codeLeft + (visualColumns(line.substring(0, local)) - editor.firstVisibleColumn) * font.cellWidth + font.cellWidth,
                    geometry.editor.top + (visibleIndex + 1) * font.cellHeight,
                )
            val visibleItems = completion.visibleEntries
            val rows = visibleItems.map(::completionRow)
            val contentWidth = rows.maxOf(::visualColumns) * font.cellWidth + COMPLETION_HORIZONTAL_PADDING
            val popup =
                geometry.completionPopup(
                    caret,
                    maxOf(COMPLETION_MINIMUM_WIDTH, contentWidth),
                    visibleItems.size * UI_LINE_HEIGHT + 4,
                )
            panel(IdePanelKind.Dialog, popup.bounds, IdeColors.PANEL_ALT, Z_POPUP)
            scissors += IdeScissorDraw(IdeScissorKind.Completion, popup.bounds, Z_POPUP)
            rows.forEachIndexed { index, row ->
                ui(
                    IdeTextKind.Completion,
                    row,
                    popup.bounds.left + 4,
                    popup.bounds.top + 3 + index * UI_LINE_HEIGHT,
                    if (completion.firstVisibleIndex + index == completion.selectedIndex) IdeColors.ACCENT else IdeColors.TEXT,
                    popup.bounds,
                    Z_POPUP_TEXT,
                )
            }
        }

        private fun completionRow(entry: ru.lazyhat.compukters.ide.client.analysis.IdeCompletionEntry): String =
            buildString {
                append(entry.proposal.label)
                append(" · ")
                append(
                    entry.proposal.kind.name
                        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                        .lowercase(),
                )
                entry.actionText?.let {
                    append(" · ")
                    append(it)
                }
            }

        private fun diagnostics(workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView) {
            val bounds = geometry.diagnostics ?: return
            scissors += IdeScissorDraw(IdeScissorKind.Diagnostics, bounds, Z_CLIP)
            val values = mutableListOf<EditorDiagnostic>()
            val analysis = (workspace.editor as? IdeEditorView.Text)?.analysis as? IdeAnalysisState.Active
            values += analysis?.presentation?.diagnostics.orEmpty()
            val build = workspace.build
            if (build is IdeBuildState.Diagnostics) values += build.values
            values.take(bounds.height / UI_LINE_HEIGHT).forEachIndexed { index, diagnostic ->
                ui(
                    IdeTextKind.Diagnostic,
                    diagnostic.message,
                    bounds.left + 6,
                    bounds.top + 4 + index * UI_LINE_HEIGHT,
                    diagnosticColor(diagnostic.severity),
                    bounds,
                )
            }
        }

        private fun status(
            workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView,
            targetState: IdeTargetState,
            toolingState: IdeToolingState,
            busy: Set<IdeBusyOperation>,
        ) {
            val parts = linkedSetOf<String>()
            (workspace.editor as? IdeEditorView.Text)?.let { editor ->
                parts +=
                    if (editor.conflict) {
                        "Conflict"
                    } else if (editor.dirty) {
                        "Modified"
                    } else {
                        "Saved"
                    }
                parts += "UTF-16 ${editor.caretUtf16}"
                when (val analysis = editor.analysis) {
                    is IdeAnalysisState.Loading -> parts += "Analysis…"
                    is IdeAnalysisState.Unavailable -> parts += analysis.status
                    else -> Unit
                }
            }
            when (val build = workspace.build) {
                is IdeBuildState.Succeeded -> parts += "Artifact ${build.bytes} B · ${if (build.cacheHit) "cache hit" else "compiled"}"
                is IdeBuildState.Failed -> parts += build.detail
                is IdeBuildState.Compiling -> parts += "Building…"
                is IdeBuildState.Saving -> parts += "Saving…"
                else -> Unit
            }
            if (IdeBusyOperation.Format in busy) parts += "Formatting…"
            targetStatus(targetState)?.let(parts::add)
            when (toolingState) {
                IdeToolingState.Preparing -> parts += "Kotlin tooling is starting…"
                is IdeToolingState.Unavailable -> parts += toolingState.detail
                IdeToolingState.Ready -> Unit
            }
            workspace.status?.let { parts += it.message }
            when (val transfer = workspace.computerTransfer) {
                IdeComputerTransferState.Idle -> {
                    Unit
                }

                is IdeComputerTransferState.Downloading -> {
                    parts +=
                        "Copying ${transfer.progress.filesComplete}/${transfer.progress.filesTotal} · ${transfer.progress.bytesComplete}/${transfer.progress.bytesTotal} B"
                }

                is IdeComputerTransferState.ConfirmationRequired -> {
                    parts += "Copy ready · confirmation required"
                }

                is IdeComputerTransferState.Failed -> {
                    parts += transfer.detail
                }
            }
            ui(
                IdeTextKind.Status,
                parts.joinToString(" · "),
                geometry.status.left + 6,
                geometry.status.top + 5,
                IdeColors.MUTED,
                geometry.status,
            )
        }

        fun dialog(dialog: IdeDialogState) {
            for (index in hitTargets.indices) hitTargets[index] = hitTargets[index].copy(enabled = false)
            fills += IdeFillDraw(IdeFillKind.DialogScrim, geometry.panel, IdeColors.DIM, Z_DIALOG_SCRIM)
            val width = minOf(360, geometry.panel.width - 24).coerceAtLeast(0)
            val height = minOf(132, geometry.panel.height - 24).coerceAtLeast(0)
            val left = geometry.panel.left + (geometry.panel.width - width) / 2
            val top = geometry.panel.top + (geometry.panel.height - height) / 2
            val bounds = IdeRect(left, top, left + width, top + height)
            panel(IdePanelKind.Dialog, bounds, IdeColors.PANEL_ALT, Z_DIALOG)
            val (title, message) =
                when (dialog) {
                    is IdeDialogState.Confirmation -> {
                        dialog.title to dialog.message
                    }

                    is IdeDialogState.FileConflict -> {
                        "File conflict" to dialog.path.value
                    }

                    is IdeDialogState.LockUpdate -> {
                        "Update lock" to dialog.projectDirectory
                    }

                    is IdeDialogState.TargetOverwrite -> {
                        "Replace target executable?" to "${dialog.path.value} changed at revision ${dialog.revision.generation}"
                    }

                    is IdeDialogState.ComputerImport -> {
                        "Replace project entry?" to dialog.destination.value
                    }
                }
            ui(IdeTextKind.Dialog, title, bounds.left + 10, bounds.top + 10, IdeColors.TEXT, bounds, Z_DIALOG_TEXT)
            ui(IdeTextKind.Dialog, message, bounds.left + 10, bounds.top + 30, IdeColors.MUTED, bounds, Z_DIALOG_TEXT)
            val dismiss = IdeRect(bounds.right - 78, bounds.bottom - 26, bounds.right - 10, bounds.bottom - 8)
            val confirm = IdeRect(dismiss.left - 76, dismiss.top, dismiss.left - 8, dismiss.bottom)
            target(IdeHitAction.Confirm, confirm, true, focusGroup = IdeFocusGroup.Dialog, z = Z_DIALOG_TARGET)
            target(IdeHitAction.Dismiss, dismiss, true, focusGroup = IdeFocusGroup.Dialog, z = Z_DIALOG_TARGET)
            ui(IdeTextKind.Dialog, "Confirm", confirm.left + 9, confirm.top + 5, z = Z_DIALOG_TEXT)
            ui(IdeTextKind.Dialog, "Cancel", dismiss.left + 12, dismiss.top + 5, z = Z_DIALOG_TEXT)
        }

        fun prompt(prompt: IdePromptState) {
            for (index in hitTargets.indices) hitTargets[index] = hitTargets[index].copy(enabled = false)
            fills += IdeFillDraw(IdeFillKind.DialogScrim, geometry.panel, IdeColors.DIM, Z_DIALOG_SCRIM)
            val width = minOf(420, geometry.panel.width - 24)
            val height = minOf(132, geometry.panel.height - 24)
            val left = geometry.panel.left + (geometry.panel.width - width) / 2
            val top = geometry.panel.top + (geometry.panel.height - height) / 2
            val bounds = IdeRect(left, top, left + width, top + height)
            panel(IdePanelKind.Dialog, bounds, IdeColors.PANEL_ALT, Z_DIALOG)
            val title =
                when (prompt.kind) {
                    IdePromptKind.CreateProject -> "Create project"
                    IdePromptKind.CreateText -> "Create text file"
                    IdePromptKind.CreateDirectory -> "Create directory"
                    is IdePromptKind.Rename -> "Rename ${prompt.kind.source.value}"
                }
            ui(IdeTextKind.Dialog, title, bounds.left + 10, bounds.top + 10, clip = bounds, z = Z_DIALOG_TEXT)
            ui(IdeTextKind.Dialog, prompt.value + "_", bounds.left + 10, bounds.top + 34, clip = bounds, z = Z_DIALOG_TEXT)
            prompt.error?.let { ui(IdeTextKind.Dialog, it, bounds.left + 10, bounds.top + 54, IdeColors.ERROR, bounds, Z_DIALOG_TEXT) }
            val dismiss = IdeRect(bounds.right - 78, bounds.bottom - 26, bounds.right - 10, bounds.bottom - 8)
            val confirm = IdeRect(dismiss.left - 76, dismiss.top, dismiss.left - 8, dismiss.bottom)
            target(IdeHitAction.Confirm, confirm, true, focusGroup = IdeFocusGroup.Dialog, z = Z_DIALOG_TARGET)
            target(IdeHitAction.Dismiss, dismiss, true, focusGroup = IdeFocusGroup.Dialog, z = Z_DIALOG_TARGET)
            ui(IdeTextKind.Dialog, "Confirm", confirm.left + 9, confirm.top + 5, z = Z_DIALOG_TEXT)
            ui(IdeTextKind.Dialog, "Cancel", dismiss.left + 12, dismiss.top + 5, z = Z_DIALOG_TEXT)
        }

        fun ui(
            kind: IdeTextKind,
            value: String,
            x: Int,
            y: Int,
            color: Int = IdeColors.TEXT,
            clip: IdeRect? = null,
            z: Int = Z_TEXT,
            rotation: IdeTextRotation = IdeTextRotation.None,
        ) {
            text += IdeTextDraw(kind, value, x, y, color, IdeTextStyle.Ui, null, clip, null, z, rotation)
        }

        private fun code(
            kind: IdeTextKind,
            value: String,
            x: Int,
            y: Int,
            color: Int,
            clip: IdeRect,
            style: IdeTextStyle = IdeTextStyle.Plain,
            sourceRange: EditorRange? = null,
        ) {
            text += IdeTextDraw(kind, value, x, y, color, style, font, clip, sourceRange, Z_TEXT)
        }

        private fun panel(
            kind: IdePanelKind,
            bounds: IdeRect,
            color: Int,
            z: Int = Z_CONTENT,
        ) {
            panels += IdePanelDraw(kind, bounds, color, z)
        }

        private fun target(
            action: IdeHitAction,
            bounds: IdeRect,
            enabled: Boolean,
            tooltip: String? = null,
            focusGroup: IdeFocusGroup = IdeFocusGroup.Page,
            z: Int = Z_TARGET,
            selected: Boolean = false,
        ) {
            panels +=
                IdePanelDraw(
                    IdePanelKind.Control,
                    bounds,
                    when {
                        selected -> IdeColors.ACCENT
                        enabled -> IdeColors.BORDER
                        else -> IdeColors.PANEL_ALT
                    },
                    z - CONTROL_BACKGROUND_OFFSET,
                )
            hitTargets += IdeHitTarget(action, bounds, enabled, tooltip, focusGroup, z, selected)
        }

        fun build(): IdeDrawModel = IdeDrawModel(panels.toList(), text.toList(), fills.toList(), scissors.toList(), hitTargets.toList())

        private fun projectGlyphs(source: String): String {
            val result = StringBuilder(source.length)
            var offset = 0
            var columns = 0
            while (offset < source.length) {
                val codePoint = source.codePointAt(offset)
                if (codePoint == '\t'.code) {
                    val spaces = TAB_WIDTH - columns % TAB_WIDTH
                    repeat(spaces) { result.append(' ') }
                    columns += spaces
                } else {
                    result.appendCodePoint(font.renderCodePoint(codePoint))
                    columns++
                }
                offset += Character.charCount(codePoint)
            }
            return result.toString()
        }

        private fun visualColumns(value: String): Int {
            var columns = 0
            var offset = 0
            while (offset < value.length) {
                val codePoint = value.codePointAt(offset)
                columns =
                    if (codePoint == '\t'.code) {
                        columns + TAB_WIDTH - columns % TAB_WIDTH
                    } else {
                        columns + 1
                    }
                offset += Character.charCount(codePoint)
            }
            return columns
        }
    }

    private fun targetLabel(state: IdeTargetState): String =
        when (state) {
            IdeTargetState.LocalOnly -> "Local only"
            is IdeTargetState.Attaching -> "Attaching…"
            is IdeTargetState.Detached -> "Target detached"
            is IdeTargetState.Failed -> state.target?.displayName ?: "Target unavailable"
            else -> checkNotNull(state.attachedTarget).displayName
        }

    private fun targetStatus(state: IdeTargetState): String? =
        when (state) {
            IdeTargetState.LocalOnly,
            is IdeTargetState.Attached,
            -> null

            is IdeTargetState.Attaching -> "Attaching target…"

            is IdeTargetState.Uploading -> "Verifying…"

            is IdeTargetState.Verified -> "Verified"

            is IdeTargetState.Observing -> "Checking destination…"

            is IdeTargetState.ConfirmationRequired -> "Overwrite confirmation required"

            is IdeTargetState.Deploying -> "Deploying…"

            is IdeTargetState.Deployed -> "Deployed ${state.path.value}"

            is IdeTargetState.Submitting -> "Submitting command…"

            is IdeTargetState.CommandSubmitted -> state.message

            is IdeTargetState.Detached -> state.failure.detail

            is IdeTargetState.Failed -> state.failure.detail
        }

    private val IdeTargetState.attachedTarget: IdeAttachedTarget?
        get() =
            when (this) {
                IdeTargetState.LocalOnly,
                is IdeTargetState.Attaching,
                is IdeTargetState.Detached,
                -> null

                is IdeTargetState.Attached -> target

                is IdeTargetState.Uploading -> target

                is IdeTargetState.Verified -> target

                is IdeTargetState.Observing -> target

                is IdeTargetState.ConfirmationRequired -> target

                is IdeTargetState.Deploying -> target

                is IdeTargetState.Deployed -> target

                is IdeTargetState.Submitting -> target

                is IdeTargetState.CommandSubmitted -> target

                is IdeTargetState.Failed -> target
            }

    private val IdeTargetState.isReadyForAction: Boolean
        get() =
            this is IdeTargetState.Attached ||
                this is IdeTargetState.Verified ||
                this is IdeTargetState.Deployed ||
                this is IdeTargetState.CommandSubmitted ||
                (this is IdeTargetState.Failed && target != null)

    private fun expand(
        bounds: IdeRect,
        amount: Int,
    ): IdeRect =
        IdeRect(
            bounds.left - amount,
            bounds.top - amount,
            bounds.right + amount,
            bounds.bottom + amount,
        )

    private fun styleColor(style: IdeTextStyle): Int =
        when (style) {
            IdeTextStyle.Ui -> IdeColors.TEXT
            IdeTextStyle.Plain -> IdeColors.EDITOR_TEXT
            is IdeTextStyle.Lexical -> lexicalColor(style.kind)
            is IdeTextStyle.Semantic -> semanticColor(style.category)
        }

    private fun lexicalColor(kind: KotlinLexicalKind): Int =
        when (kind) {
            KotlinLexicalKind.Keyword -> IdeColors.KEYWORD

            KotlinLexicalKind.String,
            KotlinLexicalKind.Character,
            KotlinLexicalKind.MultilineString,
            -> IdeColors.STRING

            KotlinLexicalKind.Escape -> IdeColors.STRING_ESCAPE

            KotlinLexicalKind.Number -> IdeColors.NUMBER

            KotlinLexicalKind.LineComment, KotlinLexicalKind.BlockComment -> IdeColors.COMMENT

            KotlinLexicalKind.TypeLike -> IdeColors.TYPE

            KotlinLexicalKind.Annotation -> IdeColors.ANNOTATION

            else -> IdeColors.EDITOR_TEXT
        }

    private fun semanticColor(category: SemanticCategory): Int =
        when (category) {
            SemanticCategory.Class,
            SemanticCategory.Interface,
            SemanticCategory.Object,
            -> IdeColors.TYPE

            SemanticCategory.TypeParameter -> IdeColors.TYPE_PARAMETER

            SemanticCategory.EnumEntry -> IdeColors.PROPERTY

            SemanticCategory.Function, SemanticCategory.ExtensionFunction -> IdeColors.FUNCTION

            SemanticCategory.Property -> IdeColors.PROPERTY

            SemanticCategory.LocalVariable, SemanticCategory.Parameter -> IdeColors.LOCAL_VARIABLE

            SemanticCategory.InferredExpression,
            SemanticCategory.SmartCastExpression,
            -> IdeColors.EDITOR_TEXT
        }

    private fun diagnosticColor(severity: EditorDiagnosticSeverity): Int =
        when (severity) {
            EditorDiagnosticSeverity.Info -> IdeColors.INFO
            EditorDiagnosticSeverity.Warning -> IdeColors.WARNING
            EditorDiagnosticSeverity.Error -> IdeColors.ERROR
        }

    private fun problemColor(name: String): Int =
        when (name) {
            "Error" -> IdeColors.ERROR
            "Warning" -> IdeColors.WARNING
            else -> IdeColors.INFO
        }

    private const val TAB_WIDTH = 4
    private const val UI_LINE_HEIGHT = 12
    private const val COMPLETION_MINIMUM_WIDTH = 220
    private const val COMPLETION_HORIZONTAL_PADDING = 8
    private const val SEMANTIC_POPUP_MINIMUM_WIDTH = 180
    private const val POPUP_HORIZONTAL_PADDING = 8
    private const val POPUP_VERTICAL_PADDING = 4
    private const val DECLARATION_VISIBLE_ROWS = 8
    private const val NO_TARGET = "No target attached"
    private const val TERMINAL_UNAVAILABLE = "Target terminal is unavailable"
    private const val TERMINAL_TOOL_HEIGHT = 72
    private const val TERMINAL_TOOL_TEXT_RIGHT_INSET = 5
    private const val TERMINAL_TOOL_TEXT_TOP_INSET = 6
    private const val CONTROL_BACKGROUND_OFFSET = 20
    private const val Z_BACKGROUND = 0
    private const val Z_PANEL = 10
    private const val Z_CONTENT = 20
    private const val Z_SELECTION = 24
    private const val Z_CLIP = 25
    private const val Z_TEXT = 30
    private const val Z_CARET = 35
    private const val Z_TARGET = 40
    private const val Z_POPUP = 50
    private const val Z_DRAG = 60
    private const val Z_POPUP_TEXT = 55
    private const val Z_DIALOG_SCRIM = 90
    private const val Z_DIALOG = 100
    private const val Z_DIALOG_TEXT = 110
    private const val Z_DIALOG_TARGET = 120
}

private fun SemanticCategory.contributesTextStyle(): Boolean =
    this != SemanticCategory.InferredExpression && this != SemanticCategory.SmartCastExpression
