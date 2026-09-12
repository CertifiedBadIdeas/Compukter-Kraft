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

package ru.lazyhat.compukters.ide.project.tree

import ru.lazyhat.compukters.ide.project.document.FileRevision
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.util.Collections

sealed interface ProjectFileKind {
    data object Directory : ProjectFileKind

    data class Text(
        val utf8Bytes: Int,
    ) : ProjectFileKind

    data class Binary(
        val bytes: Long,
    ) : ProjectFileKind
}

data class ProjectTreeEntry(
    val path: ProjectPath,
    val kind: ProjectFileKind,
    val revision: FileRevision?,
)

class ProjectTree private constructor(
    private val orderedEntries: List<ProjectTreeEntry>,
) {
    fun flatten(): List<ProjectTreeEntry> = orderedEntries

    fun entry(path: ProjectPath): ProjectTreeEntry =
        requireNotNull(orderedEntries.singleOrNull { it.path == path }) { "project tree does not contain $path" }

    internal companion object {
        fun of(entries: List<ProjectTreeEntry>): ProjectTree = ProjectTree(Collections.unmodifiableList(entries.toList()))
    }
}
