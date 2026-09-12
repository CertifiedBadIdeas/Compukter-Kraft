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

package ru.lazyhat.compukters.ide.client.files

import ru.lazyhat.compukters.ide.client.target.IdeTargetFileMetadata
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import java.util.Collections

sealed interface IdeComputerTreeState {
    data object NoTarget : IdeComputerTreeState

    data object Loading : IdeComputerTreeState

    class Available(
        val root: IdeComputerNode.Directory,
        expanded: Set<IdeTargetVirtualPath>,
    ) : IdeComputerTreeState {
        val expanded: Set<IdeTargetVirtualPath> = Collections.unmodifiableSet(expanded.toSet())

        fun copy(
            root: IdeComputerNode.Directory = this.root,
            expanded: Set<IdeTargetVirtualPath> = this.expanded,
        ): Available = Available(root, expanded)

        override fun equals(other: Any?): Boolean = other is Available && root == other.root && expanded == other.expanded

        override fun hashCode(): Int = 31 * root.hashCode() + expanded.hashCode()
    }

    data class Unavailable(
        val detail: String,
    ) : IdeComputerTreeState

    data class TargetLost(
        val detail: String,
    ) : IdeComputerTreeState
}

sealed interface IdeComputerNode {
    val path: IdeTargetVirtualPath
    val metadata: IdeTargetFileMetadata
    val name: String
        get() = path.value.substringAfterLast('/').ifEmpty { "/" }

    data class File(
        override val path: IdeTargetVirtualPath,
        override val metadata: IdeTargetFileMetadata,
    ) : IdeComputerNode

    data class Directory(
        override val path: IdeTargetVirtualPath,
        override val metadata: IdeTargetFileMetadata,
        val children: IdeComputerChildren,
    ) : IdeComputerNode
}

sealed interface IdeComputerChildren {
    data object Unloaded : IdeComputerChildren

    data object Loading : IdeComputerChildren

    class Loaded(
        nodes: List<IdeComputerNode>,
    ) : IdeComputerChildren {
        val nodes: List<IdeComputerNode> = Collections.unmodifiableList(nodes.toList())

        override fun equals(other: Any?): Boolean = other is Loaded && nodes == other.nodes

        override fun hashCode(): Int = nodes.hashCode()
    }
}

sealed interface IdeComputerPreviewState {
    data object Closed : IdeComputerPreviewState

    data class Loading(
        val path: IdeTargetVirtualPath,
    ) : IdeComputerPreviewState

    data class Available(
        val path: IdeTargetVirtualPath,
        val targetId: IdeTargetId,
        val generation: Long,
        val text: String,
    ) : IdeComputerPreviewState

    data class TooLarge(
        val path: IdeTargetVirtualPath,
        val logicalBytes: Long,
    ) : IdeComputerPreviewState

    data class Failed(
        val path: IdeTargetVirtualPath,
        val detail: String,
    ) : IdeComputerPreviewState
}
