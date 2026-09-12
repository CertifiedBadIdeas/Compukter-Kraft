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

import ru.lazyhat.compukters.ide.client.files.IdeComputerChildren
import ru.lazyhat.compukters.ide.client.files.IdeComputerNode
import ru.lazyhat.compukters.ide.client.files.IdeComputerTreeState
import ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeEntry

sealed interface IdeExplorerRow {
    data class ProjectRoot(
        val name: String,
    ) : IdeExplorerRow

    data class ProjectEntry(
        val entry: ProjectTreeEntry,
    ) : IdeExplorerRow

    data class ComputerRoot(
        val state: IdeComputerTreeState,
    ) : IdeExplorerRow

    data class ComputerEntry(
        val node: IdeComputerNode,
        val depth: Int,
    ) : IdeExplorerRow
}

internal fun IdeWorkspaceView.explorerRows(): List<IdeExplorerRow> =
    buildList {
        add(IdeExplorerRow.ProjectRoot(project.displayName))
        tree.flatten().forEach { add(IdeExplorerRow.ProjectEntry(it)) }
        add(IdeExplorerRow.ComputerRoot(computerTree))
        val available = computerTree as? IdeComputerTreeState.Available ?: return@buildList
        appendComputerChildren(available.root, depth = 1)
    }

private fun MutableList<IdeExplorerRow>.appendComputerChildren(
    directory: IdeComputerNode.Directory,
    depth: Int,
) {
    val children = (directory.children as? IdeComputerChildren.Loaded)?.nodes ?: return
    children.forEach { node ->
        add(IdeExplorerRow.ComputerEntry(node, depth))
        if (node is IdeComputerNode.Directory) appendComputerChildren(node, depth + 1)
    }
}
