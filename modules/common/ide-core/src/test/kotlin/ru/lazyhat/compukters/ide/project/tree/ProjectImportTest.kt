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

import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ProjectImportTest {
    @Test
    fun `admission owns bytes and requires one parent-before-child subtree`() {
        val bytes = "hello".encodeToByteArray()
        val admitted =
            ProjectImport.admit(
                ProjectPath.file("src/copied"),
                replace = false,
                entries =
                    listOf(
                        ProjectImportEntry.Directory("copied"),
                        ProjectImportEntry.File("copied/main.kt", bytes),
                    ),
                limits = ProjectLimits(),
            )
        bytes.fill(0)

        assertContentEquals(
            "hello".encodeToByteArray(),
            (admitted.entries.last() as ProjectImportEntry.File).bytes(),
        )
        assertFailsWith<IllegalArgumentException> {
            ProjectImport.admit(
                ProjectPath.file("src/copied"),
                false,
                listOf(ProjectImportEntry.File("copied/main.kt", byteArrayOf(1))),
                ProjectLimits(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectImport.admit(
                ProjectPath.file("src/copied"),
                false,
                listOf(ProjectImportEntry.Directory("copied"), ProjectImportEntry.Directory("other")),
                ProjectLimits(),
            )
        }
    }

    @Test
    fun `admission enforces destination and project limits`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectImport.admit(
                ProjectPath.file("src/wrong"),
                false,
                listOf(ProjectImportEntry.File("right", byteArrayOf())),
                ProjectLimits(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectImport.admit(
                ProjectPath.file("large"),
                false,
                listOf(ProjectImportEntry.File("large", ByteArray(2))),
                ProjectLimits(projectFileBytes = 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectImport.admit(
                ProjectPath.file("tree"),
                false,
                listOf(ProjectImportEntry.Directory("tree"), ProjectImportEntry.File("tree/a", byteArrayOf())),
                ProjectLimits(treeEntries = 1),
            )
        }
    }
}
