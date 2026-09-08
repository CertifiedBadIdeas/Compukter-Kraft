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

package ru.lazyhat.compukters.ide.client.state

import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import ru.lazyhat.compukters.ide.project.fs.ProjectPath

sealed interface IdeCommand {
    data class CreateProject(
        val name: String,
    ) : IdeCommand

    data class OpenProject(
        val directoryName: String,
    ) : IdeCommand

    data class OpenFile(
        val path: ProjectPath,
    ) : IdeCommand

    data class ExpandComputerDirectory(
        val path: IdeTargetVirtualPath,
    ) : IdeCommand

    data object RefreshComputerTree : IdeCommand

    data class OpenComputerFile(
        val path: IdeTargetVirtualPath,
    ) : IdeCommand

    data class DropComputerEntry(
        val source: IdeTargetVirtualPath,
        val destinationDirectory: ProjectPath,
    ) : IdeCommand

    data object ConfirmComputerImport : IdeCommand

    data object CancelComputerImport : IdeCommand

    data class CreateText(
        val path: ProjectPath,
    ) : IdeCommand

    data class CreateDirectory(
        val path: ProjectPath,
    ) : IdeCommand

    data class Rename(
        val source: ProjectPath,
        val target: ProjectPath,
    ) : IdeCommand

    data class RequestDelete(
        val path: ProjectPath,
    ) : IdeCommand

    data class ConfirmDialog(
        val actionId: Long,
    ) : IdeCommand

    data object CancelDialog : IdeCommand

    data class Edit(
        val input: IdeEditorInput,
    ) : IdeCommand

    data class ScrollEditor(
        val lines: Int,
        val columns: Int,
    ) : IdeCommand

    data object Save : IdeCommand

    data object Format : IdeCommand

    /** Requests an admitted external-change poll. */
    data object Poll : IdeCommand

    /** Flushes a pending autosave after an ordinary pointer interaction. */
    data object PointerActivity : IdeCommand

    data object Resolve : IdeCommand

    data object ConfirmLockUpdate : IdeCommand

    data object Build : IdeCommand

    data object Verify : IdeCommand

    data object Deploy : IdeCommand

    data object Run : IdeCommand

    data object ConfirmTargetDeployment : IdeCommand

    data object CancelTargetDeployment : IdeCommand

    data object CancelBuild : IdeCommand

    data object ManualCompletion : IdeCommand

    data object DismissCompletion : IdeCommand

    data object ShowParameterInfo : IdeCommand

    data object DismissParameterInfo : IdeCommand

    data class SourcePointer(
        val offsetUtf16: Int?,
        val controlDown: Boolean,
    ) : IdeCommand

    data class GoToDeclaration(
        val offsetUtf16: Int? = null,
    ) : IdeCommand

    data object ControlReleased : IdeCommand

    data class MoveDeclarationChoice(
        val delta: Int,
    ) : IdeCommand

    data object AcceptDeclarationChoice : IdeCommand

    data object DismissSemanticInteraction : IdeCommand

    data object NavigateBack : IdeCommand

    data object NavigateForward : IdeCommand

    data object EditorFocusLost : IdeCommand

    data object CloseRequested : IdeCommand

    data class ResolveConflict(
        val action: IdeConflictAction,
    ) : IdeCommand
}

sealed interface IdeConflictAction {
    data object ReloadFromDisk : IdeConflictAction

    data class SaveAs(
        val path: ProjectPath,
    ) : IdeConflictAction

    data object DiscardAndClose : IdeConflictAction

    data object Cancel : IdeConflictAction
}
