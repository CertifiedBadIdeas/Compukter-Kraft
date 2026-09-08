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
import ru.lazyhat.compukters.ide.analysis.ParameterInfoItem
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.util.Collections

class IdeParameterInfoState(
    val identity: AnalysisSnapshotIdentity,
    val path: VirtualSourcePath,
    val documentRevision: Long,
    val caretOffsetUtf16: Int,
    val callRange: EditorRange,
    items: List<ParameterInfoItem>,
    maximumItems: Int,
) {
    val items: List<ParameterInfoItem> = Collections.unmodifiableList(items.toList())

    init {
        require(documentRevision >= 0) { "parameter-info document revision must not be negative" }
        require(caretOffsetUtf16 in callRange.startUtf16..callRange.endUtf16) { "parameter-info caret is outside its call" }
        require(maximumItems > 0) { "parameter-info item limit must be positive" }
        require(this.items.isNotEmpty()) { "parameter-info must not be empty" }
        require(this.items.size <= maximumItems) { "parameter-info item count exceeds limit" }
    }
}
