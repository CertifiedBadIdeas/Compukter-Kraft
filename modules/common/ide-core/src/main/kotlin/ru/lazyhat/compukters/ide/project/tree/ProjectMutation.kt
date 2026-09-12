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
import ru.lazyhat.compukters.ide.project.fs.ProjectRootIdentity
import java.util.Collections

sealed interface ProjectMutationResult {
    data class Changed(
        val tree: ProjectTree,
    ) : ProjectMutationResult

    data class Conflict(
        val path: ProjectPath,
    ) : ProjectMutationResult

    data object ProjectInvalidated : ProjectMutationResult
}

class AdmittedProjectDelete internal constructor(
    val path: ProjectPath,
    val rootIdentity: ProjectRootIdentity,
    val entries: Int,
    internal val revisions: Map<ProjectPath, FileRevision?>,
) {
    internal companion object {
        fun create(
            path: ProjectPath,
            rootIdentity: ProjectRootIdentity,
            revisions: Map<ProjectPath, FileRevision?>,
        ): AdmittedProjectDelete =
            AdmittedProjectDelete(
                path,
                rootIdentity,
                revisions.size,
                Collections.unmodifiableMap(LinkedHashMap(revisions)),
            )
    }
}
