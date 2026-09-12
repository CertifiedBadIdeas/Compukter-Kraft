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

import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectDescriptor
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFileException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectTreeMutationTest {
    @Test
    fun `create rename and confirmed recursive delete stay inside the project`() =
        withProject { project ->
            val store = ProjectTreeStore(project.handle, ProjectLimits())
            assertIs<ProjectMutationResult.Changed>(store.createDirectory(ProjectPath.file("notes")))
            assertIs<ProjectMutationResult.Changed>(store.createText(ProjectPath.file("notes/a.txt")))
            project.handle.canonicalPath
                .resolve("notes/a.txt")
                .writeText("hello")
            assertIs<ProjectMutationResult.Changed>(
                store.rename(ProjectPath.file("notes/a.txt"), ProjectPath.file("notes/b.txt")),
            )
            assertEquals(
                "hello",
                project.handle.canonicalPath
                    .resolve("notes/b.txt")
                    .readText(),
            )

            val admitted = store.admitDelete(ProjectPath.file("notes"))
            assertEquals(2, admitted.entries)
            assertIs<ProjectMutationResult.Changed>(store.delete(admitted))
            assertFalse(
                project.handle.canonicalPath
                    .resolve("notes")
                    .exists(),
            )
        }

    @Test
    fun `create and rename never overwrite existing entries`() =
        withProject { project ->
            val store = ProjectTreeStore(project.handle)
            val main = ProjectPath.file("src/main.kt")
            val manifest = ProjectPath.file("compukter.toml")

            assertEquals(ProjectMutationResult.Conflict(main), store.createText(main))
            assertEquals(ProjectMutationResult.Conflict(manifest), store.createDirectory(manifest))
            assertEquals(ProjectMutationResult.Conflict(manifest), store.rename(main, manifest))
            assertTrue(
                project.handle.canonicalPath
                    .resolve(main.value)
                    .exists(),
            )
        }

    @Test
    fun `delete admission becomes stale after content or subtree changes`() =
        withProject { project ->
            val store = ProjectTreeStore(project.handle)
            assertIs<ProjectMutationResult.Changed>(store.createDirectory(ProjectPath.file("notes")))
            assertIs<ProjectMutationResult.Changed>(store.createText(ProjectPath.file("notes/a.txt")))
            val admitted = store.admitDelete(ProjectPath.file("notes"))
            project.handle.canonicalPath
                .resolve("notes/a.txt")
                .writeText("changed")

            assertEquals(ProjectMutationResult.Conflict(ProjectPath.file("notes/a.txt")), store.delete(admitted))
            assertTrue(
                project.handle.canonicalPath
                    .resolve("notes/a.txt")
                    .exists(),
            )

            val readmitted = store.admitDelete(ProjectPath.file("notes"))
            project.handle.canonicalPath
                .resolve("notes/b.txt")
                .writeText("new")
            assertEquals(ProjectMutationResult.Conflict(ProjectPath.file("notes")), store.delete(readmitted))
            assertTrue(
                project.handle.canonicalPath
                    .resolve("notes/b.txt")
                    .exists(),
            )
        }

    @Test
    fun `delete rejects a symlink race without touching its target`() =
        withProject { project ->
            val store = ProjectTreeStore(project.handle)
            assertIs<ProjectMutationResult.Changed>(store.createText(ProjectPath.file("victim.txt")))
            val admitted = store.admitDelete(ProjectPath.file("victim.txt"))
            val target =
                project.handle.canonicalPath.parent
                    .resolve("outside.txt")
            target.writeText("outside")
            Files.delete(project.handle.canonicalPath.resolve("victim.txt"))
            Files.createSymbolicLink(project.handle.canonicalPath.resolve("victim.txt"), target)

            assertEquals(ProjectMutationResult.Conflict(ProjectPath.file("victim.txt")), store.delete(admitted))
            assertEquals("outside", target.readText())
        }

    @Test
    fun `mutation enforces recursive bounds and invalidated roots`() =
        withProject { project ->
            val bounded = ProjectTreeStore(project.handle, ProjectLimits(treeEntries = 3))
            assertFailsWith<SecureProjectFileException> {
                bounded.createText(ProjectPath.file("too-many.txt"))
            }

            val store = ProjectTreeStore(project.handle)
            val admitted = store.admitDelete(ProjectPath.file("src"))
            val moved = project.handle.canonicalPath.resolveSibling("moved")
            Files.move(project.handle.canonicalPath, moved)

            assertEquals(ProjectMutationResult.ProjectInvalidated, store.createText(ProjectPath.file("new.txt")))
            assertEquals(ProjectMutationResult.ProjectInvalidated, store.delete(admitted))
        }

    private fun withProject(action: (ProjectDescriptor) -> Unit) {
        action(ProjectCatalog.open(createTempDirectory("compukters-tree-mutation-")).create("hello"))
    }
}
