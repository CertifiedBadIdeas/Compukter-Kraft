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

package ru.lazyhat.compukters.ide.project.document

sealed interface DocumentSaveResult {
    data class Saved(
        val snapshot: DocumentSnapshot,
    ) : DocumentSaveResult

    data class Conflict(
        val expected: FileRevision,
        val actual: FileRevision,
    ) : DocumentSaveResult

    data object ProjectInvalidated : DocumentSaveResult
}

enum class ProjectWriteStep {
    TEMPORARY_CREATED,
    TEMPORARY_WRITTEN,
    BEFORE_PUBLISH,
}
