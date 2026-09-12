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

package ru.lazyhat.compukters.ide.project

import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectCatalogTest {
    @Test
    fun `catalog creates and lists canonical projects without a lock`() {
        val root = createTempDirectory("compukters-projects-")
        val catalog = ProjectCatalog.open(root)

        val zeta = catalog.create("zeta")
        val alpha = catalog.create("alpha")

        assertEquals(listOf("alpha", "zeta"), catalog.projects().map { it.directoryName })
        assertEquals("alpha", alpha.manifest.name)
        assertEquals(
            "fun main() {\n}\n",
            alpha.handle.canonicalPath
                .resolve("src/main.kt")
                .readText(),
        )
        assertTrue(
            alpha.handle.canonicalPath
                .resolve("compukter.toml")
                .exists(),
        )
        assertFalse(
            alpha.handle.canonicalPath
                .resolve("compukter.lock")
                .exists(),
        )
        assertTrue(alpha.handle.isValid())
        assertTrue(zeta.handle.isValid())
    }

    @Test
    fun `catalog rejects collisions invalid names and unsafe entries`() {
        val root = createTempDirectory("compukters-projects-invalid-")
        val catalog = ProjectCatalog.open(root)
        catalog.create("hello")

        assertFailsWith<ProjectCatalogException> { catalog.create("hello") }
        listOf("", ".", "..", "a/b", "a\\b", "Bad Name\u0000").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { catalog.create(name) }
        }

        val outside = createTempDirectory("compukters-projects-outside-")
        Files.createSymbolicLink(root.resolve("linked"), outside)
        assertFailsWith<ProjectCatalogException> { catalog.projects() }
    }

    @Test
    fun `catalog rejects malformed project manifests`() {
        val root = createTempDirectory("compukters-projects-malformed-")
        val broken = root.resolve("broken").createDirectory()
        broken.resolve("compukter.toml").writeText("format = 99\nname = \"broken\"\n")

        assertFailsWith<ProjectCatalogException> { ProjectCatalog.open(root).projects() }
    }

    @Test
    fun `open handle is invalidated by removal rename and same-path replacement`() {
        val root = createTempDirectory("compukters-projects-identity-")
        val catalog = ProjectCatalog.open(root)
        val project = catalog.create("hello")
        val oldPath = project.handle.canonicalPath
        val moved = root.resolve("moved")

        oldPath.moveTo(moved)
        assertFalse(project.handle.isValid())

        oldPath.createDirectory()
        oldPath.resolve("compukter.toml").writeText(ProjectManifestCodec.encode(ProjectManifest.of("hello", emptyMap())))
        oldPath.resolve("src").createDirectory()
        oldPath.resolve("src/main.kt").writeText("fun main() {}")
        assertFalse(project.handle.isValid())
        assertTrue(
            catalog
                .projects()
                .single { it.directoryName == "hello" }
                .handle
                .isValid(),
        )
    }

    @Test
    fun `failed staged creation leaves no partial project`() {
        ProjectCreationStep.entries.forEach { failingStep ->
            val root = createTempDirectory("compukters-projects-failure-")
            val catalog =
                ProjectCatalog.open(root) { step ->
                    if (step == failingStep) error("injected $step")
                }

            assertFailsWith<ProjectCatalogException>(failingStep.name) { catalog.create("hello") }
            assertEquals(emptyList(), root.listDirectoryEntries())
        }
    }
}
