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

import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeViewState

internal data class IdeVisibleFrameEvidence(
    val documentRevision: Long,
    val presentationVisible: Boolean,
    val completionVisible: Boolean,
) {
    companion object {
        fun from(
            state: IdeViewState,
            model: IdeDrawModel,
        ): IdeVisibleFrameEvidence? {
            val workspace = (state.page as? IdePageState.Workspace)?.value ?: return null
            val editor = workspace.editor as? IdeEditorView.Text ?: return null
            return IdeVisibleFrameEvidence(
                documentRevision = editor.contentRevision,
                presentationVisible =
                    model.text.any { draw ->
                        draw.kind == IdeTextKind.Source && draw.style is IdeTextStyle.Semantic
                    },
                completionVisible = model.text.any { draw -> draw.kind == IdeTextKind.Completion },
            )
        }
    }
}
