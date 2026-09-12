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

package ru.lazyhat.compukters.ide.client.workspace

import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectImport
import ru.lazyhat.compukters.ide.project.tree.ProjectImportEntry
import ru.lazyhat.compukters.ide.project.tree.ProjectMutationResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DefaultIdeWorkspaceTest {
    @Test
    fun `workspace creates its missing project catalog root`() {
        val parent = createTempDirectory("compukters-workspace-first-open-")
        val projects = parent.resolve("projects")
        assertFalse(projects.exists())

        val workspace = DefaultIdeWorkspace(projects)
        try {
            assertTrue(projects.isDirectory())
            assertTrue(workspace.projects().get(5, TimeUnit.SECONDS).isEmpty())
        } finally {
            workspace.close()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `workspace operations run serially in admission order off the caller`() {
        val observed = mutableListOf<Pair<String, String>>()
        val workspace = workspace { operation -> observed += operation to Thread.currentThread().name }

        val first = workspace.createProject("first")
        val second = workspace.createProject("second")
        first.get(5, TimeUnit.SECONDS)
        second.get(5, TimeUnit.SECONDS)

        assertEquals(listOf("createProject", "createProject"), observed.map { it.first })
        assertEquals(1, observed.map { it.second }.distinct().size)
        assertTrue(observed.first().second.startsWith("compukters-ide-workspace"))
        assertNotEquals(Thread.currentThread().name, observed.first().second)
        workspace.close()
    }

    @Test
    fun `workspace bounds queued calls and rejects calls after close`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workspace =
            workspace(IdeClientLimits(workspaceQueue = 1)) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        val running = workspace.projects()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val queued = workspace.projects()
        val rejected = workspace.projects()
        assertIs<IdeWorkspaceFailure.Busy>(failure(rejected))
        release.countDown()
        running.get(5, TimeUnit.SECONDS)
        queued.get(5, TimeUnit.SECONDS)
        workspace.close()

        assertIs<IdeWorkspaceFailure.Closed>(failure(workspace.projects()))
    }

    @Test
    fun `build input contains copied canonical compiler files only`() {
        val workspace = workspace()
        val project = workspace.createProject("demo").get(5, TimeUnit.SECONDS)
        val root = project.handle.canonicalPath
        root.resolve("compukter.lock").toFile().writeText("lock")
        root.resolve("notes.kt").toFile().writeText("ignored")
        root.resolve("src/z.kt").toFile().writeText("fun z() = Unit")
        root.resolve("src/readme.txt").toFile().writeText("ignored too")

        val input = workspace.buildInput(project.handle).get(5, TimeUnit.SECONDS)
        root.resolve("src/z.kt").toFile().writeText("changed")

        assertEquals(listOf("src/main.kt", "src/z.kt"), input.sources.sources.map { it.path.value })
        val lastSource =
            input.sources.sources
                .last()
                .content
                .toByteArray()
                .decodeToString()
        assertEquals("fun z() = Unit", lastSource)
        assertEquals("lock", input.lockBytes?.decodeToString())
        input.manifestBytes.fill(0)
        input.lockBytes?.fill(0)
        assertTrue(input.manifestBytes.any { it != 0.toByte() })
        assertEquals("lock", input.lockBytes?.decodeToString())
        workspace.close()
    }

    @Test
    fun `workspace serializes and publishes admitted imports`() {
        val workspace = workspace()
        val project = workspace.createProject("demo").get(5, TimeUnit.SECONDS)
        val admitted =
            ProjectImport.admit(
                ProjectPath.file("src/copied.kt"),
                false,
                listOf(ProjectImportEntry.File("copied.kt", "fun copied() = Unit".encodeToByteArray())),
                ProjectLimits(),
            )

        val result = workspace.importTree(project.handle, admitted).get(5, TimeUnit.SECONDS)

        assertIs<ProjectMutationResult.Changed>(result)
        assertEquals(
            "fun copied() = Unit",
            project.handle.canonicalPath
                .resolve("src/copied.kt")
                .toFile()
                .readText(),
        )
        workspace.close()
    }

    private fun workspace(
        limits: IdeClientLimits = IdeClientLimits(),
        observer: (String) -> Unit = {},
    ): DefaultIdeWorkspace {
        val root = createTempDirectory("compukters-workspace-")
        return DefaultIdeWorkspace(
            ProjectCatalog.open(root, ProjectLimits()),
            ProjectLimits(),
            WorkerLimits(),
            limits,
            observer,
        )
    }

    private fun failure(future: java.util.concurrent.CompletableFuture<*>): Throwable {
        val exception = assertFailsWith<ExecutionException> { future.get(5, TimeUnit.SECONDS) }
        return requireNotNull(exception.cause)
    }
}
