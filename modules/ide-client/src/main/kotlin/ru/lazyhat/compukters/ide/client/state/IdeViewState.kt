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

import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.files.IdeComputerTransferState
import ru.lazyhat.compukters.ide.client.files.IdeComputerTreeState
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeTargetState
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectTree
import java.util.Collections

data class IdeProjectSummary(
    val directoryName: String,
    val displayName: String,
) {
    init {
        require(directoryName.isNotBlank()) { "project directory name must not be blank" }
        require(displayName.isNotBlank()) { "project display name must not be blank" }
    }
}

enum class IdeProblemSeverity {
    Info,
    Warning,
    Error,
}

data class IdeProblem(
    val message: String,
    val severity: IdeProblemSeverity,
)

data class IdeBuildSummary(
    val artifactHash: String,
    val artifactBytes: Long,
    val cacheHit: Boolean,
) {
    init {
        require(artifactHash.isNotEmpty()) { "artifact hash must not be empty" }
        require(artifactBytes >= 0) { "artifact size must be non-negative" }
    }
}

data class IdeWorkspaceView(
    val project: IdeProjectSummary,
    val tree: ProjectTree,
    val activeFile: ProjectPath?,
    val editor: IdeEditorView,
    val status: IdeProblem?,
    val build: IdeBuildState,
    val computerTree: IdeComputerTreeState = IdeComputerTreeState.NoTarget,
    val computerTransfer: IdeComputerTransferState = IdeComputerTransferState.Idle,
    val projects: List<IdeProjectSummary> = emptyList(),
)

sealed interface IdePageState {
    class Start(
        projects: List<IdeProjectSummary>,
        val error: IdeProblem?,
    ) : IdePageState {
        val projects: List<IdeProjectSummary> = Collections.unmodifiableList(projects.toList())

        override fun equals(other: Any?): Boolean = other is Start && projects == other.projects && error == other.error

        override fun hashCode(): Int = 31 * projects.hashCode() + (error?.hashCode() ?: 0)
    }

    data class Workspace(
        val value: IdeWorkspaceView,
    ) : IdePageState
}

sealed interface IdeDialogState {
    data class Confirmation(
        val title: String,
        val message: String,
        val actionId: Long,
    ) : IdeDialogState

    data class FileConflict(
        val path: ProjectPath,
        val closing: Boolean,
    ) : IdeDialogState

    data class LockUpdate(
        val projectDirectory: String,
    ) : IdeDialogState

    data class TargetOverwrite(
        val path: IdeDeploymentPath,
        val revision: IdeExecutableRevision.Present,
    ) : IdeDialogState

    data class ComputerImport(
        val destination: ProjectPath,
    ) : IdeDialogState
}

enum class IdeBusyOperation {
    Catalog,
    Project,
    Save,
    Format,
    Resolve,
    Build,
    Analysis,
}

sealed interface IdeToolingState {
    data object Preparing : IdeToolingState

    data object Ready : IdeToolingState

    data class Unavailable(
        val detail: String,
    ) : IdeToolingState
}

class IdeViewState(
    val generation: Long,
    val page: IdePageState,
    val dialog: IdeDialogState?,
    busy: Set<IdeBusyOperation>,
    val target: IdeTargetState = IdeTargetState.LocalOnly,
    val tooling: IdeToolingState = IdeToolingState.Unavailable("Kotlin tooling is unavailable"),
) {
    val busy: Set<IdeBusyOperation> = Collections.unmodifiableSet(busy.toSet())

    init {
        require(generation >= 0) { "IDE generation must be non-negative" }
    }

    fun copy(
        generation: Long = this.generation,
        page: IdePageState = this.page,
        dialog: IdeDialogState? = this.dialog,
        busy: Set<IdeBusyOperation> = this.busy,
        target: IdeTargetState = this.target,
        tooling: IdeToolingState = this.tooling,
    ): IdeViewState = IdeViewState(generation, page, dialog, busy, target, tooling)

    companion object {
        fun startPage(projects: List<IdeProjectSummary>): IdeViewState =
            IdeViewState(
                generation = 0,
                page = IdePageState.Start(projects, error = null),
                dialog = null,
                busy = emptySet(),
            )
    }
}
