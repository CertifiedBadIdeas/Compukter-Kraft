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

import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectImport

data class IdeComputerDrop(
    val source: IdeTargetVirtualPath,
    val destinationDirectory: ProjectPath,
)

data class IdeTransferProgress(
    val filesComplete: Int,
    val filesTotal: Int,
    val bytesComplete: Long,
    val bytesTotal: Long,
) {
    init {
        require(filesComplete in 0..filesTotal) { "completed file count is outside transfer total" }
        require(bytesComplete in 0..bytesTotal) { "completed byte count is outside transfer total" }
    }
}

sealed interface IdeComputerTransferState {
    data object Idle : IdeComputerTransferState

    data class Downloading(
        val drop: IdeComputerDrop,
        val progress: IdeTransferProgress,
    ) : IdeComputerTransferState

    data class ConfirmationRequired(
        val drop: IdeComputerDrop,
        val import: ProjectImport,
    ) : IdeComputerTransferState

    data class Failed(
        val detail: String,
    ) : IdeComputerTransferState
}
