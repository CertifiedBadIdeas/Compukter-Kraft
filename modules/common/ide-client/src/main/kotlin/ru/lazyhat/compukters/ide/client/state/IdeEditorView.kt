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

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalSnapshot
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.util.Collections

sealed interface IdeEditorSource {
    data class Project(
        val path: ProjectPath,
    ) : IdeEditorSource

    data class Computer(
        val path: IdeTargetVirtualPath,
        val targetId: IdeTargetId,
        val generation: Long,
    ) : IdeEditorSource {
        init {
            require(generation >= 0) { "computer editor generation must not be negative" }
        }
    }

    data class AttachedApi(
        val module: AnalysisModuleIdentity,
        val path: VirtualSourcePath,
    ) : IdeEditorSource
}

sealed interface IdeEditorView {
    data object Empty : IdeEditorView

    class Text(
        val path: ProjectPath?,
        visibleLines: List<String>,
        visibleLineStartsUtf16: List<Int>,
        val firstVisibleLine: Int,
        val firstVisibleColumn: Int,
        val totalLines: Int,
        val caretUtf16: Int,
        val selectionStartUtf16: Int?,
        val selectionEndUtf16: Int?,
        val contentRevision: Long,
        val persistedContentRevision: Long,
        val dirty: Boolean,
        val conflict: Boolean,
        val lexical: KotlinLexicalSnapshot,
        val analysis: IdeAnalysisState,
        val source: IdeEditorSource = IdeEditorSource.Project(requireNotNull(path)),
        val readOnly: Boolean = false,
    ) : IdeEditorView {
        val visibleLines: List<String> = Collections.unmodifiableList(visibleLines.toList())
        val visibleLineStartsUtf16: List<Int> = Collections.unmodifiableList(visibleLineStartsUtf16.toList())
        val title: String =
            when (source) {
                is IdeEditorSource.Project -> source.path.value
                is IdeEditorSource.Computer -> "Computer · ${source.path.value} · Read-only"
                is IdeEditorSource.AttachedApi -> "${source.module.name} · ${source.path.value} · Read-only"
            }

        init {
            require(visibleLines.size == visibleLineStartsUtf16.size) { "visible editor lines and offsets must align" }
            require(visibleLineStartsUtf16.all { it >= 0 }) { "visible line offsets must be non-negative" }
            require(visibleLineStartsUtf16.zipWithNext().all { (left, right) -> left < right }) {
                "visible line offsets must be strictly increasing"
            }
            require(firstVisibleLine >= 0) { "first visible line must be non-negative" }
            require(firstVisibleColumn >= 0) { "first visible column must be non-negative" }
            require(totalLines > 0) { "text editor must contain at least one line" }
            require(caretUtf16 >= 0) { "caret must be non-negative" }
            require(contentRevision >= 0) { "content revision must be non-negative" }
            require(persistedContentRevision in 0..contentRevision) { "persisted revision must name admitted content" }
            when (source) {
                is IdeEditorSource.Project -> require(path == source.path && !readOnly) { "project editor source must be writable" }
                is IdeEditorSource.Computer -> require(path == null && readOnly) { "computer editor source must be read-only" }
                is IdeEditorSource.AttachedApi -> require(path == null && readOnly) { "attached API source must be read-only" }
            }
        }
    }

    data class Binary(
        val path: ProjectPath,
        val bytes: Long,
    ) : IdeEditorView {
        init {
            require(bytes >= 0) { "binary size must be non-negative" }
        }
    }
}
