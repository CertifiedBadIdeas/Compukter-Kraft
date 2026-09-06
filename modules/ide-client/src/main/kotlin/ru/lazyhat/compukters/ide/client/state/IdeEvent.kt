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

import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.client.analysis.IdeCompletionSelection
import ru.lazyhat.compukters.ide.client.analysis.IdeDeclarationOutcome
import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.build.IdeResolveResult
import ru.lazyhat.compukters.ide.client.controller.IdeClientTooling
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.workspace.IdeBuildInput
import ru.lazyhat.compukters.ide.client.workspace.IdeMutationRequest
import ru.lazyhat.compukters.ide.client.workspace.IdeSaveResult
import ru.lazyhat.compukters.ide.client.workspace.ProjectFileOpenResult
import ru.lazyhat.compukters.ide.project.ProjectDependencyRollback
import ru.lazyhat.compukters.ide.project.ProjectDependencyUpdate
import ru.lazyhat.compukters.ide.project.ProjectDescriptor
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.AdmittedProjectDelete
import ru.lazyhat.compukters.ide.project.tree.ProjectImport
import ru.lazyhat.compukters.ide.project.tree.ProjectMutationResult
import ru.lazyhat.compukters.ide.project.tree.ProjectTree
import java.util.Collections

sealed interface IdeEvent {
    data class ToolingReady(
        val tooling: IdeClientTooling,
    ) : IdeEvent

    data class ToolingFailed(
        val detail: String,
    ) : IdeEvent

    data class BuildInputLoaded(
        val generation: Long,
        val operationId: Long,
        val action: IdeBuildAction,
        val input: IdeBuildInput,
        val target: IdeAttachedTarget?,
    ) : IdeEvent

    data class BuildStateChanged(
        val generation: Long,
        val operationId: Long,
        val state: IdeBuildState,
    ) : IdeEvent

    data class ResolveCompleted(
        val generation: Long,
        val operationId: Long,
        val result: IdeResolveResult,
    ) : IdeEvent

    data class CompletionModuleEnabled(
        val generation: Long,
        val operationId: Long,
        val project: ProjectHandle,
        val target: IdeAttachedTarget?,
        val selection: IdeCompletionSelection,
        val result: ProjectDependencyUpdate,
    ) : IdeEvent

    data class CompletionModuleRollbackCompleted(
        val operationId: Long,
        val clearBusy: Boolean,
        val result: ProjectDependencyRollback,
    ) : IdeEvent

    data class ProjectCatalogLoaded(
        val generation: Long,
        val projects: List<ProjectDescriptor>,
    ) : IdeEvent

    data class ProjectOpened(
        val generation: Long,
        val operationId: Long,
        val project: ProjectDescriptor,
        val tree: ProjectTree,
    ) : IdeEvent

    data class FileOpened(
        val generation: Long,
        val operationId: Long,
        val path: ProjectPath,
        val result: ProjectFileOpenResult,
    ) : IdeEvent

    data class DeclarationResolved(
        val generation: Long,
        val operationId: Long,
        val outcome: IdeDeclarationOutcome,
    ) : IdeEvent

    data class SaveCompleted(
        val generation: Long,
        val operationId: Long,
        val path: ProjectPath,
        val editorRevision: Long,
        val result: IdeSaveResult,
    ) : IdeEvent

    data class FormatCompleted(
        val generation: Long,
        val operationId: Long,
        val path: ProjectPath,
        val editorRevision: Long,
        val result: AnalysisClientResult,
    ) : IdeEvent

    data class DeleteAdmitted(
        val generation: Long,
        val operationId: Long,
        val actionId: Long,
        val admitted: AdmittedProjectDelete,
    ) : IdeEvent

    data class MutationCompleted(
        val generation: Long,
        val operationId: Long,
        val request: IdeMutationRequest,
        val result: ProjectMutationResult,
    ) : IdeEvent

    data class ComputerImportCompleted(
        val generation: Long,
        val operationId: Long,
        val import: ProjectImport,
        val result: ProjectMutationResult,
    ) : IdeEvent

    data class ComputerImportFailed(
        val generation: Long,
        val operationId: Long,
        val detail: String,
    ) : IdeEvent

    data class CatalogLoaded(
        val generation: Long,
        val projects: List<IdeProjectSummary>,
    ) : IdeEvent,
        ReplaceableIdeEvent {
        override val replacementKey: IdeEventReplacementKey = IdeEventReplacementKey.Catalog(generation)
    }

    data class PollCompleted(
        val generation: Long,
        val tree: ProjectTree,
    ) : IdeEvent,
        ReplaceableIdeEvent {
        override val replacementKey: IdeEventReplacementKey = IdeEventReplacementKey.Poll(generation)
    }

    data class BuildCompleted(
        val generation: Long,
        val result: IdeBuildSummary,
    ) : IdeEvent

    data class Failed(
        val generation: Long,
        val operation: IdeBusyOperation,
        val problem: IdeProblem,
    ) : IdeEvent
}

sealed interface IdeEventReplacementKey {
    data class Catalog(
        val generation: Long,
    ) : IdeEventReplacementKey

    data class Poll(
        val generation: Long,
    ) : IdeEventReplacementKey
}

interface ReplaceableIdeEvent {
    val replacementKey: IdeEventReplacementKey
}

internal fun IdeEvent.copyForQueue(): IdeEvent =
    when (this) {
        is IdeEvent.ProjectCatalogLoaded -> copy(projects = Collections.unmodifiableList(projects.toList()))

        is IdeEvent.CatalogLoaded -> copy(projects = Collections.unmodifiableList(projects.toList()))

        is IdeEvent.BuildInputLoaded,
        is IdeEvent.ToolingReady,
        is IdeEvent.ToolingFailed,
        is IdeEvent.BuildStateChanged,
        is IdeEvent.ResolveCompleted,
        is IdeEvent.CompletionModuleEnabled,
        is IdeEvent.CompletionModuleRollbackCompleted,
        is IdeEvent.ProjectOpened,
        is IdeEvent.FileOpened,
        is IdeEvent.DeclarationResolved,
        is IdeEvent.SaveCompleted,
        is IdeEvent.FormatCompleted,
        is IdeEvent.DeleteAdmitted,
        is IdeEvent.MutationCompleted,
        is IdeEvent.ComputerImportCompleted,
        is IdeEvent.ComputerImportFailed,
        is IdeEvent.PollCompleted,
        is IdeEvent.BuildCompleted,
        is IdeEvent.Failed,
        -> this
    }

enum class IdeBuildAction {
    Build,
    Verify,
    Deploy,
    Run,
    Resolve,
    UpdateLock,
}
