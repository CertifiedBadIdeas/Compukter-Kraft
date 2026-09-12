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

package ru.lazyhat.compukters.ide.editor

data class EditorLimits(
    val maxCodeUnits: Int = 256 * 1024,
    val maxUtf8Bytes: Int = 256 * 1024,
    val initialGapCodeUnits: Int = 4 * 1024,
    val maxUndoEntries: Int = 256,
    val maxUndoCodeUnits: Int = 256 * 1024,
    val tabWidth: Int = 4,
) {
    init {
        require(maxCodeUnits >= 0) { "editor code-unit limit must be non-negative" }
        require(maxUtf8Bytes >= 0) { "editor UTF-8 byte limit must be non-negative" }
        require(initialGapCodeUnits >= 0) { "editor initial gap must be non-negative" }
        require(maxUndoEntries >= 0) { "editor undo-entry limit must be non-negative" }
        require(maxUndoCodeUnits >= 0) { "editor undo code-unit limit must be non-negative" }
        require(tabWidth > 0) { "editor tab width must be positive" }
    }
}

enum class EditorRejection {
    CodeUnitLimit,
    Utf8ByteLimit,
    UndoLimit,
    InvalidUtf16,
    InvalidRange,
    Closed,
}

internal sealed interface BufferReplaceResult {
    data object Applied : BufferReplaceResult

    data class Rejected(
        val reason: EditorRejection,
    ) : BufferReplaceResult
}
