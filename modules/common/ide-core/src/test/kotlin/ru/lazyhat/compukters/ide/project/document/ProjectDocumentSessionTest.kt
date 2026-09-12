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

import ru.lazyhat.compukters.ide.editor.EditorEditResult
import ru.lazyhat.compukters.ide.editor.EditorRejection
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectMutationResult
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeStore
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectDocumentSessionTest {
    @Test
    fun `session edits an ordinary text file with project-sized editor bounds`() {
        val project = ProjectCatalog.open(createTempDirectory("compukters-session-text-")).create("hello")
        val path = ProjectPath.file("notes.txt")
        project.handle.canonicalPath
            .resolve(path.value)
            .writeText("12345")
        val limits = ProjectLimits(sourceFileBytes = 4, projectFileBytes = 1_024)
        val session = ProjectDocumentSession.open(project.handle, path, { 0L }, limits)

        session.replaceText("12345678")

        assertIs<ProjectSessionEvent.Saved>(session.mouseActivity())
        assertEquals(
            "12345678",
            project.handle.canonicalPath
                .resolve(path.value)
                .readText(),
        )
    }

    @Test
    fun `successful active-file rename updates the session before its next save`() {
        val fixture = fixture()
        fixture.session.replaceText("renamed\n")
        val renamed = ProjectPath.file("src/renamed.kt")
        assertIs<ProjectMutationResult.Changed>(
            ProjectTreeStore(fixture.project.handle).rename(ProjectPath.file("src/main.kt"), renamed),
        )

        fixture.session.fileRenamed(ProjectPath.file("src/main.kt"), renamed)

        assertIs<ProjectSessionEvent.Saved>(fixture.session.mouseActivity())
        assertEquals(
            "renamed\n",
            fixture.project.handle.canonicalPath
                .resolve(renamed.value)
                .readText(),
        )
    }

    @Test
    fun `deleted or externally missing active file closes with dirty recovery`() {
        val explicit = fixture()
        explicit.session.replaceText("recover explicit\n")
        val path = ProjectPath.file("src/main.kt")
        val tree = ProjectTreeStore(explicit.project.handle)
        assertIs<ProjectMutationResult.Changed>(tree.delete(tree.admitDelete(path)))

        val explicitClosed = explicit.session.fileDeleted(path)

        assertEquals("recover explicit\n", assertIs<ProjectSessionEvent.Closed>(explicitClosed).recovery?.text)

        val external = fixture()
        external.session.replaceText("recover external\n")
        Files.delete(external.source)

        val externalClosed = assertIs<ProjectSessionEvent.Closed>(external.session.poll())
        assertEquals("recover external\n", externalClosed.recovery?.text)
    }

    @Test
    fun `edit autosaves after delay and build observes persisted state`() {
        val fixture = fixture()
        fixture.session.replaceText("fun main() = Unit\n")
        fixture.clock.advanceMillis(499)
        assertEquals(ProjectSessionEvent.NoAction, fixture.session.poll())
        fixture.clock.advanceMillis(1)
        assertIs<ProjectSessionEvent.Saved>(fixture.session.poll())

        val ready = assertIs<ProjectSessionEvent.ReadyToBuild>(fixture.session.prepareBuild())
        assertEquals("fun main() = Unit\n", ready.snapshot.text)
        assertEquals("fun main() = Unit\n", fixture.source.readText())
    }

    @Test
    fun `mouse focus file build and close boundaries flush immediately`() {
        val actions =
            listOf<(ProjectDocumentSession) -> ProjectSessionEvent>(
                ProjectDocumentSession::mouseActivity,
                ProjectDocumentSession::focusLost,
                ProjectDocumentSession::activeFileChanging,
                ProjectDocumentSession::prepareBuild,
                { it.close(CloseDecision.Save) },
            )
        actions.forEach { action ->
            val fixture = fixture()
            fixture.session.replaceText("changed\n")
            val event = action(fixture.session)
            assertTrue(
                event is ProjectSessionEvent.Saved || event is ProjectSessionEvent.ReadyToBuild || event is ProjectSessionEvent.Closed,
            )
            assertEquals("changed\n", fixture.source.readText())
        }
    }

    @Test
    fun `clean external edit reloads while dirty external edit conflicts`() {
        val clean = fixture()
        clean.source.writeText("external\n")
        val reloaded = assertIs<ProjectSessionEvent.Reloaded>(clean.session.poll())
        assertEquals("external\n", reloaded.snapshot.text)

        val dirty = fixture()
        dirty.session.replaceText("local\n")
        dirty.source.writeText("external\n")
        assertIs<ProjectSessionEvent.Conflict>(dirty.session.poll())
        assertIs<ProjectSessionEvent.Conflict>(dirty.session.prepareBuild())
        assertEquals("external\n", dirty.source.readText())

        val restored = assertIs<ProjectSessionEvent.Reloaded>(dirty.session.reloadFromDisk())
        assertEquals("external\n", restored.snapshot.text)
    }

    @Test
    fun `save as preserves conflicting source and switches active document`() {
        val fixture = fixture()
        val editor = fixture.session.editor
        fixture.session.replaceText("local\n")
        fixture.source.writeText("external\n")
        assertIs<ProjectSessionEvent.Conflict>(fixture.session.poll())
        val alternate = ProjectPath.source("src/recovered.kt")

        val saved = assertIs<ProjectSessionEvent.Saved>(fixture.session.saveAs(alternate))

        assertSame(editor, fixture.session.editor)
        assertEquals(alternate, saved.snapshot.path)
        assertEquals("external\n", fixture.source.readText())
        assertEquals(
            "local\n",
            fixture.project.handle.canonicalPath
                .resolve(alternate.value)
                .readText(),
        )
    }

    @Test
    fun `root invalidation closes with dirty recovery and close supports cancel discard save`() {
        val invalidated = fixture()
        invalidated.session.replaceText("recover me\n")
        invalidated.project.handle.canonicalPath
            .moveTo(
                invalidated.project.handle.canonicalPath
                    .resolveSibling("moved"),
            )
        val closed = assertIs<ProjectSessionEvent.Closed>(invalidated.session.poll())
        assertEquals("recover me\n", closed.recovery?.text)

        val cancelled = fixture()
        cancelled.session.replaceText("dirty\n")
        assertEquals(ProjectSessionEvent.CloseCancelled, cancelled.session.close(CloseDecision.Cancel))
        assertTrue(cancelled.session.isOpen)

        val discarded = fixture()
        discarded.session.replaceText("discarded\n")
        assertEquals(ProjectSessionEvent.Closed(null), discarded.session.close(CloseDecision.Discard))

        val saved = fixture()
        saved.session.replaceText("saved\n")
        assertEquals(ProjectSessionEvent.Closed(null), saved.session.close(CloseDecision.Save))
        assertEquals("saved\n", saved.source.readText())
    }

    @Test
    fun `closed session ignores persistence boundaries and rejects build`() {
        val fixture = fixture()
        fixture.session.replaceText("discarded\n")
        assertEquals(ProjectSessionEvent.Closed(null), fixture.session.close(CloseDecision.Discard))

        assertEquals(ProjectSessionEvent.NoAction, fixture.session.mouseActivity())
        assertEquals(ProjectSessionEvent.NoAction, fixture.session.focusLost())
        assertEquals(ProjectSessionEvent.NoAction, fixture.session.activeFileChanging())
        assertEquals(ProjectSessionEvent.Closed(null), fixture.session.prepareBuild())
        assertEquals(ProjectSessionEvent.Closed(null), fixture.session.reloadFromDisk())
        assertEquals(
            ProjectSessionEvent.Closed(null),
            fixture.session.saveAs(ProjectPath.source("src/recovered.kt")),
        )
        assertEquals("fun main() {\n}\n", fixture.source.readText())
        assertEquals(EditorEditResult.Rejected(EditorRejection.Closed), fixture.session.editor.type("x"))
    }

    @Test
    fun `undoing to persisted content disarms autosave`() {
        val fixture = fixture()
        fixture.session.replaceText("changed\n")

        assertIs<EditorEditResult.Applied>(fixture.session.editor.undo())
        fixture.clock.advanceMillis(1_000)

        assertEquals(ProjectSessionEvent.NoAction, fixture.session.poll())
        assertEquals("fun main() {\n}\n", fixture.source.readText())
    }

    @Test
    fun `successful autosave closes the current typing undo group`() {
        val fixture = fixture()
        val editor = fixture.session.editor
        assertTrue(editor.setCaret(editor.length))
        assertIs<EditorEditResult.Applied>(editor.type("a"))
        fixture.clock.advanceMillis(500)
        assertIs<ProjectSessionEvent.Saved>(fixture.session.poll())

        assertIs<EditorEditResult.Applied>(editor.type("b"))
        assertIs<EditorEditResult.Applied>(editor.undo())

        assertEquals("fun main() {\n}\na", editor.materialize())
    }

    @Test
    fun `clean external reload resets the same editor and clears its history`() {
        val fixture = fixture()
        val editor = fixture.session.editor
        fixture.session.replaceText("temporary\n")
        editor.undo()
        assertTrue(editor.setCaret(editor.length))
        fixture.source.writeText("external\n")

        assertIs<ProjectSessionEvent.Reloaded>(fixture.session.poll())

        assertSame(editor, fixture.session.editor)
        assertEquals("external\n", editor.materialize())
        assertEquals(0, editor.caretOffset)
        assertEquals(EditorEditResult.NoChange, editor.undo())
    }

    private fun ProjectDocumentSession.replaceText(text: String) {
        editor.selectAll()
        assertIs<EditorEditResult.Applied>(editor.type(text))
    }

    private fun fixture(): Fixture {
        val project = ProjectCatalog.open(createTempDirectory("compukters-session-")).create("hello")
        val clock = FakeClock()
        val session = ProjectDocumentSession.open(project.handle, ProjectPath.source("src/main.kt"), clock::now)
        return Fixture(project, project.handle.canonicalPath.resolve("src/main.kt"), clock, session)
    }

    private data class Fixture(
        val project: ru.lazyhat.compukters.ide.project.ProjectDescriptor,
        val source: java.nio.file.Path,
        val clock: FakeClock,
        val session: ProjectDocumentSession,
    )

    private class FakeClock {
        private var nanos = 0L

        fun now(): Long = nanos

        fun advanceMillis(milliseconds: Long) {
            nanos += milliseconds * 1_000_000L
        }
    }
}
