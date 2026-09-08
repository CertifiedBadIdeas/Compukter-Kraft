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

package ru.lazyhat.compukters.ide.analysis

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.util.Collections

data class ParameterInfoItem(
    val signature: String,
    val activeParameter: EditorRange?,
    val bestCandidate: Boolean,
) {
    init {
        require(signature.isNotEmpty()) { "parameter-info signature must not be empty" }
        strictUtf8Size(signature)
        activeParameter?.let { range ->
            require(range.length > 0) { "active parameter range must not be empty" }
            require(range.endUtf16 <= signature.length) { "active parameter range exceeds its signature" }
            requireUtf16Boundary(signature, range.startUtf16, "active parameter start")
            requireUtf16Boundary(signature, range.endUtf16, "active parameter end")
        }
    }
}

class EditorParameterInfo(
    val path: VirtualSourcePath,
    val callRange: EditorRange,
    items: List<ParameterInfoItem>,
) {
    val items: List<ParameterInfoItem> = Collections.unmodifiableList(items.toList())

    init {
        VirtualSourcePath.kotlin(path.value)
        require(callRange.length > 0) { "parameter-info call range must not be empty" }
        require(this.items.isNotEmpty()) { "parameter-info items must not be empty" }
    }

    override fun equals(other: Any?): Boolean =
        other is EditorParameterInfo && path == other.path && callRange == other.callRange && items == other.items

    override fun hashCode(): Int = listOf(path, callRange, items).hashCode()
}
