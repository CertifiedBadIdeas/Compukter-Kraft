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
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ProjectTreeImportTest {
    @Test
    fun `imports files and nested directories as one published subtree`() =
        withStore { store, project ->
            val result =
                store.importTree(
                    import(
                        "copied",
                        ProjectImportEntry.Directory("copied"),
                        ProjectImportEntry.Directory("copied/nested"),
                        ProjectImportEntry.File("copied/nested/a.txt", "hello".encodeToByteArray()),
                    ),
                )

            assertIs<ProjectMutationResult.Changed>(result)
            assertEquals("hello", project.resolve("copied/nested/a.txt").readText())
        }

    @Test
    fun `conflict is non mutating and explicit replacement is atomic`() =
        withStore { store, project ->
            project.resolve("existing.txt").toFile().writeText("old")
            assertIs<ProjectMutationResult.Conflict>(
                store.importTree(import("existing.txt", ProjectImportEntry.File("existing.txt", "new".encodeToByteArray()))),
            )
            assertEquals("old", project.resolve("existing.txt").readText())

            assertIs<ProjectMutationResult.Changed>(
                store.importTree(
                    import(
                        "existing.txt",
                        ProjectImportEntry.File("existing.txt", "new".encodeToByteArray()),
                        replace = true,
                    ),
                ),
            )
            assertEquals("new", project.resolve("existing.txt").readText())
        }

    @Test
    fun `failures before and after backup preserve the original destination`() =
        ProjectImportStep.entries.filter { it != ProjectImportStep.PUBLISHED }.forEach { failing ->
            withStore(hook = { if (it == failing) error("injected") }) { store, project ->
                project.resolve("existing.txt").toFile().writeText("old")
                assertFailsWith<IllegalStateException> {
                    store.importTree(
                        import(
                            "existing.txt",
                            ProjectImportEntry.File("existing.txt", "new".encodeToByteArray()),
                            replace = true,
                        ),
                    )
                }
                assertEquals("old", project.resolve("existing.txt").readText(), failing.name)
            }
        }

    private fun import(
        destination: String,
        vararg entries: ProjectImportEntry,
        replace: Boolean = false,
    ) = ProjectImport.admit(ProjectPath.file(destination), replace, entries.toList(), ProjectLimits())

    private fun withStore(
        hook: (ProjectImportStep) -> Unit = {},
        action: (ProjectTreeStore, java.nio.file.Path) -> Unit,
    ) {
        val descriptor = ProjectCatalog.open(createTempDirectory("compukters-import-")).create("demo")
        action(ProjectTreeStore(descriptor.handle, ProjectLimits(), hook), descriptor.handle.canonicalPath)
    }
}
