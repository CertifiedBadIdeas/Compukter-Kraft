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

package ru.lazyhat.compukters.ide.client.analysis

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.util.Collections

const val IDE_COMPLETION_VISIBLE_ROWS = 8

data class IdeCompletionSelection(
    val identity: AnalysisSnapshotIdentity,
    val path: VirtualSourcePath,
    val documentRevision: Long,
    val targetRevision: Long,
    val replacement: EditorRange,
    val entry: IdeCompletionEntry,
)

@ConsistentCopyVisibility
data class IdeCompletionState private constructor(
    val identity: AnalysisSnapshotIdentity,
    val path: VirtualSourcePath,
    val documentRevision: Long,
    val targetRevision: Long,
    val replacement: EditorRange,
    val entries: List<IdeCompletionEntry>,
    val selectedIndex: Int,
    val firstVisibleIndex: Int,
) {
    val selectedEntry: IdeCompletionEntry
        get() = entries[selectedIndex]

    val visibleEntries: List<IdeCompletionEntry>
        get() = entries.subList(firstVisibleIndex, minOf(entries.size, firstVisibleIndex + IDE_COMPLETION_VISIBLE_ROWS))

    fun move(delta: Int): IdeCompletionState = withSelection(selectedIndex.toLong() + delta)

    fun movePage(
        pages: Int,
        pageSize: Int,
    ): IdeCompletionState {
        require(pageSize > 0) { "completion page size must be positive" }
        return withSelection(selectedIndex.toLong() + pages.toLong() * pageSize)
    }

    fun select(
        currentIdentity: AnalysisSnapshotIdentity,
        currentPath: VirtualSourcePath,
        currentDocumentRevision: Long,
        currentTargetRevision: Long,
    ): IdeCompletionSelection? {
        if (
            identity != currentIdentity || path != currentPath || documentRevision != currentDocumentRevision ||
            targetRevision != currentTargetRevision
        ) {
            return null
        }
        return IdeCompletionSelection(identity, path, documentRevision, targetRevision, replacement, selectedEntry)
    }

    private fun withSelection(index: Long): IdeCompletionState {
        val selected = index.coerceIn(0, entries.lastIndex.toLong()).toInt()
        val first =
            when {
                selected < firstVisibleIndex -> selected
                selected >= firstVisibleIndex + IDE_COMPLETION_VISIBLE_ROWS -> selected - IDE_COMPLETION_VISIBLE_ROWS + 1
                else -> firstVisibleIndex
            }
        return copy(selectedIndex = selected, firstVisibleIndex = first)
    }

    companion object {
        fun create(
            identity: AnalysisSnapshotIdentity,
            path: VirtualSourcePath,
            documentRevision: Long,
            targetRevision: Long,
            replacement: EditorRange,
            entries: List<IdeCompletionEntry>,
            selectedIndex: Int = 0,
        ): IdeCompletionState {
            require(documentRevision >= 0 && targetRevision >= 0) { "completion revisions must not be negative" }
            require(entries.isNotEmpty()) { "completion popup must contain at least one entry" }
            require(selectedIndex in entries.indices) { "completion selection exceeds entry range" }
            return IdeCompletionState(
                identity,
                VirtualSourcePath.kotlin(path.value),
                documentRevision,
                targetRevision,
                replacement,
                Collections.unmodifiableList(entries.toList()),
                selectedIndex,
                (selectedIndex - IDE_COMPLETION_VISIBLE_ROWS + 1).coerceAtLeast(0),
            )
        }
    }
}
