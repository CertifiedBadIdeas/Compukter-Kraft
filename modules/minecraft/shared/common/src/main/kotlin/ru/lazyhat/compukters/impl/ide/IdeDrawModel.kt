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

import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile

enum class IdePanelKind { Main, Header, Toolbar, ToolStripe, Tree, Editor, Diagnostics, Status, Control, Dialog, ProjectSwitcher, Tooltip }

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
    ParameterInfo,
    Hover,
    DeclarationChoice,
    ProjectChoice,
    ProjectAction,
    Tooltip,
}

enum class IdeTextRotation { None, Clockwise90 }

enum class IdeFillKind { Background, Border, Selection, DropTarget, Caret, Splitter, HyperlinkUnderline, MutableUnderline, DialogScrim }

enum class IdeScissorKind { Tree, Editor, Diagnostics, Completion, SemanticPopup, ProjectSwitcher, Tooltip }

enum class IdeHitAction {
    CreateProject,
    OpenProject,
    ProjectSwitcher,
    ProjectChoice,
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

sealed interface IdeTerminalStatus {
    data object Closed : IdeTerminalStatus

    data object Opening : IdeTerminalStatus

    data object Active : IdeTerminalStatus

    data object Resyncing : IdeTerminalStatus

    data class Failed(
        val detail: String,
        val retryable: Boolean,
    ) : IdeTerminalStatus
}

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
    val icons: List<IdeIconDraw>,
)
