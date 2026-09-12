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

import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectDocumentStoreTest {
    @Test
    fun `any strict UTF-8 project file opens and saves with revisions`() {
        val project = project()
        val path = ProjectPath.file("compukter.lock")
        project.handle.canonicalPath
            .resolve(path.value)
            .writeText("old\n")
        val store = ProjectDocumentStore(project.handle, ProjectLimits())

        val opened = store.open(path)
        val saved = store.save(path, opened.revision, "edited\n")

        assertEquals("edited\n", assertIs<DocumentSaveResult.Saved>(saved).snapshot.text)
    }

    @Test
    fun `binary project files are rejected without replacement decoding`() {
        val project = project()
        val path = ProjectPath.file("blob.bin")
        project.handle.canonicalPath
            .resolve(path.value)
            .writeBytes(byteArrayOf(0xC3.toByte(), 0x28))

        val failure = assertFailsWith<ProjectDocumentException> { ProjectDocumentStore(project.handle).open(path) }

        assertEquals("project file is binary: blob.bin", failure.message)
    }

    @Test
    fun `ordinary documents use project limits while compiler sources retain source limits`() {
        val project = project()
        val ordinary = ProjectPath.file("notes.txt")
        val source = ProjectPath.file("src/large.kt")
        val limits = ProjectLimits(sourceFileBytes = 4, sourceBytes = 4, projectFileBytes = 1_024, projectBytes = 4_096)
        val store = ProjectDocumentStore(project.handle, limits)

        assertIs<DocumentSaveResult.Saved>(store.save(ordinary, FileRevision.Absent, "12345678"))
        assertFailsWith<ProjectDocumentException> { store.save(source, FileRevision.Absent, "12345678") }
    }

    @Test
    fun `store opens and atomically replaces strict UTF-8 source`() {
        val project = project()
        val store = ProjectDocumentStore(project.handle)
        val path = ProjectPath.source("src/main.kt")
        val opened = store.open(path)

        val result = assertIs<DocumentSaveResult.Saved>(store.save(path, opened.revision, "fun main() = Unit\n"))

        assertEquals("fun main() = Unit\n", result.snapshot.text)
        assertEquals(
            "fun main() = Unit\n",
            project.handle.canonicalPath
                .resolve("src/main.kt")
                .readText(),
        )
        assertEquals(result.snapshot, store.open(path))
        assertTrue(
            project.handle.canonicalPath.resolve("src").listDirectoryEntries().none {
                it.fileName.toString().startsWith(".compukter-save-")
            },
        )
    }

    @Test
    fun `save as creates a new source only when it remains absent`() {
        val project = project()
        val store = ProjectDocumentStore(project.handle)
        val path = ProjectPath.source("src/nested/util.kt")
        project.handle.canonicalPath
            .resolve("src/nested")
            .toFile()
            .mkdir()

        assertIs<DocumentSaveResult.Saved>(store.save(path, FileRevision.Absent, "val answer = 42\n"))
        assertIs<DocumentSaveResult.Conflict>(store.save(path, FileRevision.Absent, "val answer = 43\n"))
        assertEquals(
            "val answer = 42\n",
            project.handle.canonicalPath
                .resolve(path.value)
                .readText(),
        )
    }

    @Test
    fun `external replacement and deletion return conflicts without overwriting`() {
        val project = project()
        val store = ProjectDocumentStore(project.handle)
        val path = ProjectPath.source("src/main.kt")
        val opened = store.open(path)
        val disk = project.handle.canonicalPath.resolve(path.value)

        disk.writeText("external\n")
        val changed = assertIs<DocumentSaveResult.Conflict>(store.save(path, opened.revision, "local\n"))
        assertIs<FileRevision.Present>(changed.actual)
        assertEquals("external\n", disk.readText())

        val externalRevision = store.open(path).revision
        Files.delete(disk)
        val deleted = assertIs<DocumentSaveResult.Conflict>(store.save(path, externalRevision, "local\n"))
        assertEquals(FileRevision.Absent, deleted.actual)
        assertTrue(Files.notExists(disk))
    }

    @Test
    fun `store rejects malformed text unsafe files and source limits`() {
        val project = project()
        val path = ProjectPath.source("src/main.kt")
        val opened = ProjectDocumentStore(project.handle).open(path)
        assertFailsWith<ProjectDocumentException> {
            ProjectDocumentStore(project.handle).save(path, opened.revision, "\ud800")
        }
        assertFailsWith<ProjectDocumentException> {
            ProjectDocumentStore(project.handle, ProjectLimits(sourceFileBytes = 4)).save(path, opened.revision, "12345")
        }
        assertFailsWith<ProjectDocumentException> {
            ProjectDocumentStore(project.handle, ProjectLimits(sourceBytes = 4)).save(path, opened.revision, "12345")
        }
        project.handle.canonicalPath
            .resolve("src/nested")
            .toFile()
            .mkdir()
        assertFailsWith<ProjectDocumentException> {
            ProjectDocumentStore(project.handle, ProjectLimits(sourceFiles = 1)).save(
                ProjectPath.source("src/nested/extra.kt"),
                FileRevision.Absent,
                "val extra = 1",
            )
        }

        val outside =
            project.handle.canonicalPath
                .resolve("outside")
                .also { it.writeText("outside") }
        val linked = project.handle.canonicalPath.resolve("src/link.kt")
        Files.createSymbolicLink(linked, outside)
        assertFailsWith<ProjectDocumentException> { ProjectDocumentStore(project.handle).open(ProjectPath.source("src/link.kt")) }
        assertEquals("outside", outside.readText())
    }

    @Test
    fun `root invalidation is a typed non-writing result`() {
        val project = project()
        val store = ProjectDocumentStore(project.handle)
        val path = ProjectPath.source("src/main.kt")
        val opened = store.open(path)
        project.handle.canonicalPath.moveTo(project.handle.canonicalPath.resolveSibling("moved"))

        assertEquals(DocumentSaveResult.ProjectInvalidated, store.save(path, opened.revision, "local\n"))
    }

    @Test
    fun `failure before publication preserves original bytes and removes staging`() {
        ProjectWriteStep.entries.forEach { failingStep ->
            val project = project()
            val source = project.handle.canonicalPath.resolve("src/main.kt")
            val original = source.readText()
            val store =
                ProjectDocumentStore(project.handle, ProjectLimits()) { step ->
                    if (step == failingStep) error("injected $step")
                }
            val opened = store.open(ProjectPath.source("src/main.kt"))

            assertFailsWith<ProjectDocumentException>(failingStep.name) {
                store.save(opened.path, opened.revision, "replacement\n")
            }
            assertEquals(original, source.readText())
            assertTrue(source.parent.listDirectoryEntries().none { it.fileName.toString().startsWith(".compukter-save-") })
        }
    }

    @Test
    fun `last revision check catches an edit made immediately before publication`() {
        val project = project()
        val source = project.handle.canonicalPath.resolve("src/main.kt")
        lateinit var store: ProjectDocumentStore
        store =
            ProjectDocumentStore(project.handle, ProjectLimits()) { step ->
                if (step == ProjectWriteStep.BEFORE_PUBLISH) source.writeText("racing external edit\n")
            }
        val opened = store.open(ProjectPath.source("src/main.kt"))

        assertIs<DocumentSaveResult.Conflict>(store.save(opened.path, opened.revision, "local\n"))
        assertEquals("racing external edit\n", source.readText())
    }

    private fun project() = ProjectCatalog.open(createTempDirectory("compukters-document-")).create("hello")
}
