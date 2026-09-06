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

package ru.lazyhat.compukters.ide.client.controller

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentation
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisRequestCoordinator
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisResultSink
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisCoordinator
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisInputLoader
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisRequestFactory
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisSnapshotFactory
import ru.lazyhat.compukters.ide.client.analysis.IdeAttachedSourceCatalog
import ru.lazyhat.compukters.ide.client.analysis.IdeDeclarationOutcome
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticInteraction
import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencyKind
import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencyTrace
import ru.lazyhat.compukters.ide.client.build.IdeBuildCoordinator
import ru.lazyhat.compukters.ide.client.preferences.IdePreferences
import ru.lazyhat.compukters.ide.client.preferences.IdePreferencesStore
import ru.lazyhat.compukters.ide.client.state.BoundedIdeEventQueue
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeConflictAction
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeEditorSource
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdeEvent
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeProblemSeverity
import ru.lazyhat.compukters.ide.client.state.IdeToolingState
import ru.lazyhat.compukters.ide.client.state.IdeVerticalDirection
import ru.lazyhat.compukters.ide.client.workspace.IdeBuildInput
import ru.lazyhat.compukters.ide.client.workspace.IdeMutationRequest
import ru.lazyhat.compukters.ide.client.workspace.IdeSaveRequest
import ru.lazyhat.compukters.ide.client.workspace.IdeSaveResult
import ru.lazyhat.compukters.ide.client.workspace.IdeWorkspace
import ru.lazyhat.compukters.ide.client.workspace.ProjectFileOpenResult
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectDescriptor
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.document.DocumentSaveResult
import ru.lazyhat.compukters.ide.project.document.DocumentSnapshot
import ru.lazyhat.compukters.ide.project.document.FileRevision
import ru.lazyhat.compukters.ide.project.document.ProjectDocumentStore
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.AdmittedProjectDelete
import ru.lazyhat.compukters.ide.project.tree.ProjectImport
import ru.lazyhat.compukters.ide.project.tree.ProjectMutationResult
import ru.lazyhat.compukters.ide.project.tree.ProjectTree
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeStore
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeClientControllerTest {
    @Test
    fun `failed background tooling leaves the editor open and reports its state`() {
        val tooling = CompletableFuture<IdeClientTooling>()
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"), tooling = tooling)

        fixture.startAndTick()
        assertEquals(IdeToolingState.Preparing, fixture.controller.viewState().tooling)
        assertIs<IdeEditorView.Text>(fixture.workspaceView().editor)

        tooling.completeExceptionally(IllegalStateException("worker payload is broken"))
        fixture.controller.tick()

        assertEquals(
            IdeToolingState.Unavailable("Kotlin tooling unavailable: worker payload is broken"),
            fixture.controller.viewState().tooling,
        )
        assertEquals(IdeProblemSeverity.Warning, requireNotNull(fixture.workspaceView().status).severity)
        assertIs<IdeEditorView.Text>(fixture.workspaceView().editor)
    }

    @Test
    fun `start restores remembered project and file`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))

        fixture.startAndTick()

        val workspace = assertIs<IdePageState.Workspace>(fixture.controller.viewState().page).value
        assertEquals("demo", workspace.project.directoryName)
        assertEquals(ProjectPath.file("src/main.kt"), workspace.activeFile)
        assertIs<IdeEditorView.Text>(workspace.editor)
    }

    @Test
    fun `start falls back to project catalog when remembered project is gone`() {
        val fixture = ControllerFixture(preferences = preferences("missing", "src/main.kt"))

        fixture.startAndTick()

        val start = assertIs<IdePageState.Start>(fixture.controller.viewState().page)
        assertEquals(listOf("demo"), start.projects.map { it.directoryName })
    }

    @Test
    fun `create project opens its generated main file`() {
        val fixture = ControllerFixture()
        fixture.startAndTick()

        fixture.controller.dispatch(IdeCommand.CreateProject("created"))
        fixture.controller.tick()
        fixture.controller.tick()

        val workspace = fixture.workspaceView()
        assertEquals("created", workspace.project.directoryName)
        assertEquals(ProjectPath.file("src/main.kt"), workspace.activeFile)
        assertIs<IdeEditorView.Text>(workspace.editor)
    }

    @Test
    fun `binary files are shown honestly and cannot be edited`() {
        val fixture = ControllerFixture()
        val binary = ProjectPath.file("asset.bin")
        fixture.workspace.openResults[binary] = ProjectFileOpenResult.Binary(binary, 17)
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.OpenProject("demo"))
        fixture.controller.tick()

        fixture.controller.dispatch(IdeCommand.OpenFile(binary))
        fixture.controller.tick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("ignored")))

        val editor = fixture.workspaceView().editor
        assertEquals(IdeEditorView.Binary(binary, 17), editor)
        assertTrue(fixture.workspace.saveRequests.isEmpty())
    }

    @Test
    fun `editor commands update bounded immutable view and preserve undo`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        val initial = fixture.textEditor().visibleLines.joinToString("\n")

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("one")))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type(" two")))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Undo))

        val editor = fixture.textEditor()
        assertEquals("one", editor.visibleLines.joinToString("\n"))
        assertTrue(editor.dirty)
        assertTrue(initial.isNotEmpty())
    }

    @Test
    fun `editor fundamentals flow through controller state as atomic edits and navigation`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        val source = "one two\nthree four\nfive six"

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type(source)))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectToken(4)))
        assertEquals("two", fixture.controller.selectedText())

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Cut))
        assertEquals("one \nthree four\nfive six", fixture.textEditor().visibleLines.joinToString("\n"))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Undo))

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Tab))
        assertEquals(source.prependIndent("    "), fixture.textEditor().visibleLines.joinToString("\n"))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Outdent))
        assertEquals(source, fixture.textEditor().visibleLines.joinToString("\n"))

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SetCaret(0, false)))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Page(IdeVerticalDirection.Down, 2, false)))
        assertEquals(19, fixture.textEditor().caretUtf16)
        assertEquals(1, fixture.textEditor().firstVisibleLine)
    }

    @Test
    fun `Kotlin smart typing flows through writable editor while plain text stays literal`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("fun main() ")))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("{")))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Enter))

        assertEquals("fun main() {\n    \n}", fixture.textEditor().visibleLines.joinToString("\n"))
        assertEquals("fun main() {\n    ".length, fixture.textEditor().caretUtf16)

        val plain = ControllerFixture(preferences = preferences("demo", "notes.txt"))
        plain.startAndTick()
        plain.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SetCaret("notes".length, false)))
        plain.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("(")))

        assertEquals("notes(", plain.textEditor().visibleLines.joinToString("\n"))
    }

    @Test
    fun `save completion marks only submitted editor revision`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("a")))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("b")))
        fixture.controller.dispatch(IdeCommand.Save)
        val submittedRevision = fixture.textEditor().contentRevision
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("c")))

        fixture.workspace.completeSave()
        fixture.controller.tick()

        val editor = fixture.textEditor()
        assertEquals("abc", editor.visibleLines.joinToString("\n"))
        assertTrue(editor.dirty)
        assertEquals(submittedRevision, editor.persistedContentRevision)
        assertTrue(editor.contentRevision > editor.persistedContentRevision)
    }

    @Test
    fun `controller rejects calls from another thread`() {
        val fixture = ControllerFixture()
        var failure: Throwable? = null
        Thread {
            failure = runCatching { fixture.controller.viewState() }.exceptionOrNull()
        }.apply {
            start()
            join()
        }

        assertIs<IllegalStateException>(failure)
    }

    @Test
    fun `clean external change reloads while dirty change enters conflict`() {
        val clean = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        clean.startAndTick()
        clean.workspace.replaceMainExternally("changed")
        clean.controller.dispatch(IdeCommand.Poll)
        clean.controller.tick()
        clean.controller.tick()
        assertEquals("changed", clean.textEditor().visibleLines.single())

        val dirty = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        dirty.startAndTick()
        dirty.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("local")))
        dirty.workspace.replaceMainExternally("external")
        dirty.controller.dispatch(IdeCommand.Poll)
        dirty.controller.tick()

        assertTrue(dirty.textEditor().conflict)
        assertIs<IdeDialogState.FileConflict>(dirty.controller.viewState().dialog)
    }

    @Test
    fun `dirty file switch waits for save then opens target`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("local")))

        fixture.controller.dispatch(IdeCommand.OpenFile(ProjectPath.file("notes.txt")))
        assertEquals(ProjectPath.file("src/main.kt"), fixture.workspaceView().activeFile)
        fixture.workspace.completeSave()
        fixture.controller.tick()
        fixture.controller.tick()

        assertEquals(ProjectPath.file("notes.txt"), fixture.workspaceView().activeFile)
        assertEquals("notes", fixture.textEditor().visibleLines.single())
    }

    @Test
    fun `close conflict offers cancel or explicit discard`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("local")))
        fixture.workspace.replaceMainExternally("external")
        fixture.controller.dispatch(IdeCommand.Poll)
        fixture.controller.tick()
        fixture.controller.dispatch(IdeCommand.CloseRequested)
        assertFalse(fixture.controller.isCloseReady())

        fixture.controller.dispatch(IdeCommand.ResolveConflict(IdeConflictAction.Cancel))
        assertFalse(fixture.controller.isCloseReady())
        fixture.controller.dispatch(IdeCommand.CloseRequested)
        fixture.controller.dispatch(IdeCommand.ResolveConflict(IdeConflictAction.DiscardAndClose))
        assertTrue(fixture.controller.isCloseReady())
    }

    @Test
    fun `rename updates active path only after admitted mutation succeeds`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        val renamed = ProjectPath.file("src/renamed.kt")

        fixture.controller.dispatch(IdeCommand.Rename(ProjectPath.file("src/main.kt"), renamed))
        assertEquals(ProjectPath.file("src/main.kt"), fixture.workspaceView().activeFile)
        fixture.controller.tick()

        assertEquals(renamed, fixture.workspaceView().activeFile)
        assertEquals(renamed, fixture.textEditor().path)
    }

    @Test
    fun `delete requires matching confirmation and clears clean active file`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()

        fixture.controller.dispatch(IdeCommand.RequestDelete(ProjectPath.file("src/main.kt")))
        fixture.controller.tick()
        val dialog = assertIs<IdeDialogState.Confirmation>(fixture.controller.viewState().dialog)
        fixture.controller.dispatch(IdeCommand.ConfirmDialog(dialog.actionId + 1))
        assertIs<IdeEditorView.Text>(fixture.workspaceView().editor)
        fixture.controller.dispatch(IdeCommand.ConfirmDialog(dialog.actionId))
        fixture.controller.tick()

        assertEquals(null, fixture.workspaceView().activeFile)
        assertEquals(IdeEditorView.Empty, fixture.workspaceView().editor)
    }

    @Test
    fun `invalidated project root returns to start page`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        fixture.workspace.invalidateProjectRoot()

        fixture.controller.dispatch(IdeCommand.Poll)
        fixture.controller.tick()

        val page = assertIs<IdePageState.Start>(fixture.controller.viewState().page)
        assertTrue(requireNotNull(page.error).message.contains("root"))
    }

    @Test
    fun `applied Kotlin edit starts latency before analysis rebuild and tick observes fresh state`() {
        val latency = ControllerRecordingVisibleLatencyTrace()
        val requests = ControllerRecordingAnalysisRequests()
        val fixture =
            ControllerFixture(
                preferences = preferences("demo", "src/main.kt"),
                visibleLatency = latency,
                analysisCoordinatorFactory = { workspace -> latencyCoordinator(workspace, requests, latency) },
            )
        fixture.startAndTick()
        latency.observed.clear()

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("x")))

        assertEquals(listOf(1L), latency.edits)
        latency.observed.clear()
        requests.publishFreshPresentation()
        assertTrue(latency.observed.isEmpty())
        fixture.controller.tick()
        assertEquals(listOf(1L), latency.observed)
    }

    @Test
    fun `analysis failure exposes detail once`() {
        val requests = ControllerRecordingAnalysisRequests()
        val failures = mutableListOf<IdeAnalysisFailure>()
        val fixture =
            ControllerFixture(
                preferences = preferences("demo", "src/main.kt"),
                analysisCoordinatorFactory = { workspace ->
                    latencyCoordinator(workspace, requests, IdeVisibleLatencyTrace.None)
                },
                analysisFailureReporter = IdeAnalysisFailureReporter(failures::add),
            )
        fixture.startAndTick()

        requests.publishFailure("completion exploded")
        fixture.controller.tick()

        assertEquals("Analysis unavailable: completion exploded", fixture.workspaceView().status?.message)
        assertEquals(
            listOf(IdeAnalysisFailure(VirtualSourcePath.kotlin("src/main.kt"), 0, "completion exploded")),
            failures,
        )

        fixture.controller.tick()
        assertEquals(1, failures.size)
    }

    @Test
    fun `file switch and controller close drop unfinished latency`() {
        val latency = ControllerRecordingVisibleLatencyTrace()
        val requests = ControllerRecordingAnalysisRequests()
        val fixture =
            ControllerFixture(
                preferences = preferences("demo", "src/main.kt"),
                visibleLatency = latency,
                analysisCoordinatorFactory = { workspace -> latencyCoordinator(workspace, requests, latency) },
            )
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("x")))

        fixture.controller.dispatch(IdeCommand.OpenFile(ProjectPath.file("notes.txt")))
        fixture.workspace.completeSave()
        fixture.controller.tick()
        fixture.controller.tick()

        assertEquals(1, latency.drops)
        fixture.controller.close()
        assertEquals(2, latency.drops)
    }

    @Test
    fun `declaration opens a project target and back restores the exact editor position`() {
        val requests = ControllerRecordingAnalysisRequests()
        val fixture = navigationFixture(requests)
        activateNavigation(fixture, requests)
        val source = fixture.textEditor().visibleLines.joinToString("\n")
        val token = source.indexOf("main").takeIf { it >= 0 } ?: source.indexOfFirst(Char::isJavaIdentifierPart)
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SetCaret(token + 1, extendSelection = false)))
        fixture.controller.dispatch(IdeCommand.ScrollEditor(lines = 1, columns = 3))
        val origin = fixture.textEditor()

        fixture.controller.dispatch(IdeCommand.GoToDeclaration())
        requests.completeNavigation(
            listOf(
                DeclarationLocation.Source(
                    DeclarationOrigin.Project,
                    VirtualSourcePath.kotlin("src/other.kt"),
                    EditorRange(1, 4),
                ),
            ),
            additionalLengths = mapOf(VirtualSourcePath.kotlin("src/other.kt") to 9),
        )
        fixture.controller.tick()
        fixture.controller.tick()

        assertEquals(ProjectPath.file("src/other.kt"), fixture.workspaceView().activeFile)
        assertEquals(1, fixture.textEditor().caretUtf16)

        fixture.controller.dispatch(IdeCommand.NavigateBack)
        fixture.controller.tick()

        val restored = fixture.textEditor()
        assertEquals(ProjectPath.file("src/main.kt"), fixture.workspaceView().activeFile)
        assertEquals(origin.caretUtf16, restored.caretUtf16)
        assertEquals(origin.firstVisibleLine, restored.firstVisibleLine)
        assertEquals(origin.firstVisibleColumn, restored.firstVisibleColumn)
    }

    @Test
    fun `cross-file declaration waits for dirty source save`() {
        val requests = ControllerRecordingAnalysisRequests()
        val fixture = navigationFixture(requests)
        activateNavigation(fixture, requests)
        val source = fixture.textEditor().visibleLines.joinToString("\n")
        val token = source.indexOf("main").takeIf { it >= 0 } ?: source.indexOfFirst(Char::isJavaIdentifierPart)
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SetCaret(source.length, extendSelection = false)))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type(" ")))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SetCaret(token + 1, extendSelection = false)))

        fixture.controller.dispatch(IdeCommand.GoToDeclaration())
        requests.completeNavigation(
            listOf(
                DeclarationLocation.Source(
                    DeclarationOrigin.Project,
                    VirtualSourcePath.kotlin("src/other.kt"),
                    EditorRange(1, 4),
                ),
            ),
            additionalLengths = mapOf(VirtualSourcePath.kotlin("src/other.kt") to 9),
        )
        fixture.controller.tick()

        assertEquals(ProjectPath.file("src/main.kt"), fixture.workspaceView().activeFile)
        assertEquals(1, fixture.workspace.saveRequests.size)
        fixture.workspace.completeSave()
        fixture.controller.tick()
        fixture.controller.tick()

        assertEquals(ProjectPath.file("src/other.kt"), fixture.workspaceView().activeFile)
        assertEquals(1, fixture.textEditor().caretUtf16)
    }

    @Test
    fun `attached declaration opens a read-only bundle-qualified preview`() {
        val bundle = AnalysisModuleIdentity("std.core", Hash256.of(ByteArray(32) { 9 }))
        val path = VirtualSourcePath.kotlin("compukter/api/Sample.kt")
        val text = "class Sample"
        val catalog = IdeAttachedSourceCatalog.of(mapOf(bundle to mapOf(path to text)), 1, 1, 64, 64)
        val requests = ControllerRecordingAnalysisRequests()
        val fixture = navigationFixture(requests, catalog)
        activateNavigation(fixture, requests)
        val source = fixture.textEditor().visibleLines.joinToString("\n")
        val token = source.indexOf("main").takeIf { it >= 0 } ?: source.indexOfFirst(Char::isJavaIdentifierPart)
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SetCaret(token + 1, extendSelection = false)))

        fixture.controller.dispatch(IdeCommand.GoToDeclaration())
        requests.completeNavigation(
            listOf(DeclarationLocation.Source(DeclarationOrigin.Platform(bundle), path, EditorRange(6, 12))),
            bundleLengths = mapOf(bundle to mapOf(path to text.length)),
        )
        fixture.controller.tick()

        val preview = fixture.textEditor()
        assertEquals(IdeEditorSource.AttachedApi(bundle, path), preview.source)
        assertTrue(preview.readOnly)
        assertEquals(6, preview.caretUtf16)
        assertTrue(preview.title.contains("std.core"))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("ignored")))
        assertEquals(text, fixture.textEditor().visibleLines.single())
        assertTrue(requireNotNull(fixture.workspaceView().status).message.contains("read-only"))
    }

    @Test
    fun `unavailable declaration reports status while multiple targets stay in chooser`() {
        val bundle = AnalysisModuleIdentity("missing.api", Hash256.of(ByteArray(32) { 7 }))
        val requests = ControllerRecordingAnalysisRequests()
        val fixture = navigationFixture(requests)
        activateNavigation(fixture, requests)
        val source = fixture.textEditor().visibleLines.joinToString("\n")
        val token = source.indexOf("main").takeIf { it >= 0 } ?: source.indexOfFirst(Char::isJavaIdentifierPart)
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SetCaret(token + 1, extendSelection = false)))

        fixture.controller.dispatch(IdeCommand.GoToDeclaration())
        requests.completeNavigation(listOf(DeclarationLocation.SourceUnavailable(DeclarationOrigin.Platform(bundle))))
        fixture.controller.tick()
        assertTrue(requireNotNull(fixture.workspaceView().status).message.contains("missing.api"))

        fixture.controller.dispatch(IdeCommand.GoToDeclaration())
        requests.completeNavigation(
            listOf(
                DeclarationLocation.Source(DeclarationOrigin.Project, VirtualSourcePath.kotlin("src/main.kt"), EditorRange(0, 3)),
                DeclarationLocation.Source(DeclarationOrigin.Project, VirtualSourcePath.kotlin("src/main.kt"), EditorRange(4, 8)),
            ),
        )
        fixture.controller.tick()

        assertIs<IdeSemanticInteraction.Chooser>(
            fixture
                .textEditor()
                .analysis
                .let {
                    assertIs<ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState.Active>(it)
                }.interaction,
        )
        fixture.controller.dispatch(IdeCommand.MoveDeclarationChoice(1))
        fixture.controller.dispatch(IdeCommand.AcceptDeclarationChoice)
        assertEquals(4, fixture.textEditor().caretUtf16)
    }

    @Test
    fun `navigation history exposed by the controller evicts its 129th oldest position`() {
        val requests = ControllerRecordingAnalysisRequests()
        val fixture = navigationFixture(requests)
        activateNavigation(fixture, requests)
        val source = fixture.textEditor().visibleLines.joinToString("\n")
        val token = source.indexOf("main").takeIf { it >= 0 } ?: source.indexOfFirst(Char::isJavaIdentifierPart)

        repeat(129) { index ->
            val target = if (index % 2 == 0) 0 else 4
            fixture.controller.dispatch(IdeCommand.GoToDeclaration(token + 1))
            requests.completeNavigation(
                listOf(
                    DeclarationLocation.Source(
                        DeclarationOrigin.Project,
                        VirtualSourcePath.kotlin("src/main.kt"),
                        EditorRange(target, target + 1),
                    ),
                ),
            )
            fixture.controller.tick()
        }

        repeat(128) { fixture.controller.dispatch(IdeCommand.NavigateBack) }
        val oldestRetained = fixture.textEditor().caretUtf16
        fixture.controller.dispatch(IdeCommand.NavigateBack)

        assertEquals(0, oldestRetained)
        assertEquals(oldestRetained, fixture.textEditor().caretUtf16)
    }

    @Test
    fun `declaration event from an old project generation is ignored`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        val oldGeneration = fixture.controller.viewState().generation

        fixture.controller.dispatch(IdeCommand.CreateProject("fresh"))
        fixture.eventQueue.offer(
            IdeEvent.DeclarationResolved(
                oldGeneration,
                operationId = 0,
                IdeDeclarationOutcome.Failed("stale declaration must stay hidden"),
            ),
        )
        fixture.controller.tick()

        assertEquals("fresh", fixture.workspaceView().project.directoryName)
        assertEquals(null, fixture.workspaceView().status)
    }

    private fun latencyCoordinator(
        workspace: ControlledWorkspace,
        requests: ControllerRecordingAnalysisRequests,
        latency: IdeVisibleLatencyTrace,
    ) = IdeAnalysisCoordinator(
        IdeAnalysisInputLoader(workspace::buildInput),
        IdeAnalysisSnapshotFactory { input, path, text -> latencyAnalysisSnapshot(input.sources, path, text) },
        IdeAnalysisRequestFactory { sink -> requests.apply { this.sink = sink } },
        latency,
        platformCatalog = TEST_PLATFORM_CATALOG,
    )

    private fun navigationFixture(
        requests: ControllerRecordingAnalysisRequests,
        attachedSources: IdeAttachedSourceCatalog = IdeAttachedSourceCatalog.empty(),
    ): ControllerFixture =
        ControllerFixture(
            preferences = preferences("demo", "src/main.kt"),
            analysisCoordinatorFactory = { workspace ->
                IdeAnalysisCoordinator(
                    IdeAnalysisInputLoader(workspace::buildInput),
                    IdeAnalysisSnapshotFactory { input, path, text -> latencyAnalysisSnapshot(input.sources, path, text) },
                    IdeAnalysisRequestFactory { sink -> requests.apply { this.sink = sink } },
                    attachedSources = attachedSources,
                    platformCatalog = TEST_PLATFORM_CATALOG,
                )
            },
        )

    private fun activateNavigation(
        fixture: ControllerFixture,
        requests: ControllerRecordingAnalysisRequests,
    ) {
        fixture.startAndTick()
        requests.publishFreshPresentation()
        fixture.controller.tick()
    }
}

internal class ControllerFixture(
    preferences: IdePreferences? = null,
    analysisCoordinatorFactory: ((ControlledWorkspace) -> ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisCoordinator)? = null,
    targetCoordinatorFactory: ((MutableClock) -> ru.lazyhat.compukters.ide.client.target.IdeTargetCoordinator)? = null,
    tooling: CompletableFuture<IdeClientTooling>? = null,
    visibleLatency: IdeVisibleLatencyTrace = IdeVisibleLatencyTrace.None,
    analysisFailureReporter: IdeAnalysisFailureReporter = IdeAnalysisFailureReporter.None,
    buildCoordinatorFactory: ((ControlledWorkspace, MutableClock) -> IdeBuildCoordinator)? = null,
) {
    val clock = MutableClock()
    val preferences = MemoryPreferences(preferences)
    val workspace = ControlledWorkspace()
    val buildCoordinator = buildCoordinatorFactory?.invoke(workspace, clock)
    val analysisCoordinator = analysisCoordinatorFactory?.invoke(workspace)
    val targetCoordinator = targetCoordinatorFactory?.invoke(clock)
    val eventQueue = BoundedIdeEventQueue(64)
    val controller =
        IdeClientController(
            workspace,
            this.preferences,
            clock,
            eventQueue,
            buildCoordinator = buildCoordinator,
            analysisCoordinator = analysisCoordinator,
            targetCoordinator = targetCoordinator,
            tooling = tooling,
            visibleLatency = visibleLatency,
            analysisFailureReporter = analysisFailureReporter,
        )

    fun startAndTick() {
        controller.start()
        controller.tick()
        controller.tick()
        controller.tick()
    }

    fun workspaceView() = assertIs<IdePageState.Workspace>(controller.viewState().page).value

    fun textEditor() = assertIs<IdeEditorView.Text>(workspaceView().editor)
}

internal class MutableClock(
    var now: Long = 0,
) : IdeControllerClock {
    override fun nowMillis(): Long = now
}

internal class MemoryPreferences(
    private var value: IdePreferences?,
) : IdePreferencesStore {
    override fun load(): IdePreferences? = value

    override fun save(preferences: IdePreferences) {
        value = preferences
    }
}

internal class ControlledWorkspace : IdeWorkspace {
    private val root = createTempDirectory("compukters-controller-")
    val descriptor = ProjectCatalog.open(root).create("demo")
    private val main = ProjectPath.file("src/main.kt")
    private val notes = ProjectPath.file("notes.txt")
    private val other = ProjectPath.file("src/other.kt")
    val openResults = mutableMapOf<ProjectPath, ProjectFileOpenResult>()
    val saveRequests = mutableListOf<IdeSaveRequest>()
    var buildInputRequests = 0
    private val pendingSaves = ArrayDeque<CompletableFuture<IdeSaveResult>>()

    init {
        descriptor.handle.canonicalPath
            .resolve(notes.value)
            .toFile()
            .writeText("notes")
        descriptor.handle.canonicalPath
            .resolve(other.value)
            .toFile()
            .writeText("val other")
        val store = ProjectDocumentStore(descriptor.handle)
        openResults[main] = ProjectFileOpenResult.Text(store.open(main))
        openResults[notes] = ProjectFileOpenResult.Text(store.open(notes))
        openResults[other] = ProjectFileOpenResult.Text(store.open(other))
    }

    override fun projects(): CompletableFuture<List<ProjectDescriptor>> = completed(listOf(descriptor))

    override fun createProject(name: String): CompletableFuture<ProjectDescriptor> = completeCall { ProjectCatalog.open(root).create(name) }

    override fun tree(project: ProjectHandle): CompletableFuture<ProjectTree> = completeCall { ProjectTreeStore(project).scan() }

    override fun open(
        project: ProjectHandle,
        path: ProjectPath,
    ): CompletableFuture<ProjectFileOpenResult> =
        if (project == descriptor.handle) {
            completed(requireNotNull(openResults[path]) { "missing fake result for $path" })
        } else {
            completeCall { ProjectFileOpenResult.Text(ProjectDocumentStore(project).open(path)) }
        }

    override fun save(request: IdeSaveRequest): CompletableFuture<IdeSaveResult> {
        saveRequests += request
        return CompletableFuture<IdeSaveResult>().also(pendingSaves::addLast)
    }

    override fun admitDelete(
        project: ProjectHandle,
        path: ProjectPath,
    ): CompletableFuture<AdmittedProjectDelete> = completeCall { ProjectTreeStore(descriptor.handle).admitDelete(path) }

    fun completeSave() {
        val request = saveRequests.last()
        val snapshot = DocumentSnapshot(request.path, request.text, revision(saveRequests.size + 1))
        pendingSaves.removeFirst().complete(DocumentSaveResult.Saved(snapshot))
    }

    fun completeSaveConflict() {
        val request = saveRequests.last()
        pendingSaves.removeFirst().complete(DocumentSaveResult.Conflict(request.expected, FileRevision.Absent))
    }

    fun installLock(bytes: ByteArray) {
        descriptor.handle.canonicalPath
            .resolve("compukter.lock")
            .toFile()
            .writeBytes(bytes)
    }

    fun replaceMainExternally(text: String) {
        descriptor.handle.canonicalPath
            .resolve(main.value)
            .toFile()
            .writeText(text)
        openResults[main] = ProjectFileOpenResult.Text(ProjectDocumentStore(descriptor.handle).open(main))
    }

    fun invalidateProjectRoot() {
        val oldRoot = descriptor.handle.canonicalPath
        val displaced = root.resolve("demo-displaced")
        java.nio.file.Files
            .move(oldRoot, displaced)
        java.nio.file.Files
            .createDirectory(oldRoot)
    }

    override fun mutate(request: IdeMutationRequest): CompletableFuture<ProjectMutationResult> =
        completeCall {
            val store = ProjectTreeStore(descriptor.handle)
            when (request) {
                is IdeMutationRequest.CreateText -> store.createText(request.path)
                is IdeMutationRequest.CreateDirectory -> store.createDirectory(request.path)
                is IdeMutationRequest.Rename -> store.rename(request.source, request.target)
                is IdeMutationRequest.Delete -> store.delete(request.admitted)
            }
        }

    override fun importTree(
        project: ProjectHandle,
        import: ProjectImport,
    ): CompletableFuture<ProjectMutationResult> = completeCall { ProjectTreeStore(project).importTree(import) }

    override fun buildInput(project: ProjectHandle): CompletableFuture<IdeBuildInput> {
        buildInputRequests++
        val root = descriptor.handle.canonicalPath
        val source = saveRequests.lastOrNull()?.text ?: assertIs<ProjectFileOpenResult.Text>(openResults.getValue(main)).snapshot.text
        return completed(
            IdeBuildInput(
                descriptor.handle,
                root.resolve("compukter.toml").toFile().readBytes(),
                root
                    .resolve("compukter.lock")
                    .toFile()
                    .takeIf { it.exists() }
                    ?.readBytes(),
                ProjectSnapshot.of(
                    listOf(ProjectSource(VirtualSourcePath.kotlin(main.value), BinaryValue.of(source.encodeToByteArray()))),
                    WorkerLimits(sourceFiles = 8, sourceFileBytes = 4096, sourceBytes = 8192),
                ),
            ),
        )
    }

    override fun close() = Unit

    private fun <T> completed(value: T) = CompletableFuture.completedFuture(value)

    private fun <T> completeCall(action: () -> T): CompletableFuture<T> =
        runCatching(action).fold(::completed) { failure -> CompletableFuture<T>().also { it.completeExceptionally(failure) } }
}

internal fun preferences(
    project: String,
    file: String,
): IdePreferences = IdePreferences.admit(project, file, 0, 0, 0, 240, 160, true)

private fun revision(seed: Int) = FileRevision.Present(Hash256.of(ByteArray(32) { seed.toByte() }))

private class ControllerRecordingAnalysisRequests : AnalysisRequestCoordinator {
    lateinit var sink: AnalysisResultSink
    val snapshots = mutableListOf<AdmittedAnalysisSnapshot>()
    private val navigation = ArrayDeque<CompletableFuture<AnalysisClientResult>>()

    override fun sourceChanged(
        snapshot: AdmittedAnalysisSnapshot,
        activePath: VirtualSourcePath,
    ) {
        snapshots += snapshot
    }

    override fun automaticCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ) = Unit

    override fun manualCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> = CompletableFuture()

    override fun declaration(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> = CompletableFuture<AnalysisClientResult>().also(navigation::addLast)

    override fun close() = Unit

    fun publishFreshPresentation() {
        val snapshot = snapshots.last()
        val lengths =
            snapshot.sources.sources.associate {
                it.path to
                    it.content
                        .toByteArray()
                        .decodeToString()
                        .length
            }
        sink.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    snapshot.identity,
                    SnapshotPresentation.create(snapshot.identity, lengths),
                ),
            ),
        )
    }

    fun publishFailure(detail: String) {
        sink.publish(AnalysisClientResult.Failure(AnalysisFailureKind.InternalAnalysis, detail))
    }

    fun completeNavigation(
        locations: List<DeclarationLocation>,
        additionalLengths: Map<VirtualSourcePath, Int> = emptyMap(),
        bundleLengths: Map<AnalysisModuleIdentity, Map<VirtualSourcePath, Int>> = emptyMap(),
    ) {
        val snapshot = snapshots.last()
        val lengths =
            snapshot.sources.sources.associate {
                it.path to
                    it.content
                        .toByteArray()
                        .decodeToString()
                        .length
            } + additionalLengths
        navigation.removeFirst().complete(
            AnalysisClientResult.Success(
                AnalysisResult.Declaration.create(
                    snapshot.identity,
                    locations,
                    lengths,
                    platformSourceLengthsUtf16 = bundleLengths,
                ),
            ),
        )
    }
}

private class ControllerRecordingVisibleLatencyTrace : IdeVisibleLatencyTrace {
    val edits = mutableListOf<Long>()
    val observed = mutableListOf<Long>()
    var drops = 0

    override fun editApplied(documentRevision: Long) {
        edits += documentRevision
    }

    override fun automaticCompletionExpected(documentRevision: Long) = Unit

    override fun analysisPublished(
        kind: IdeVisibleLatencyKind,
        documentRevision: Long,
    ) = Unit

    override fun controllerObserved(documentRevision: Long) {
        observed += documentRevision
    }

    override fun frameExtracted(
        documentRevision: Long,
        presentationVisible: Boolean,
        completionVisible: Boolean,
    ) = Unit

    override fun resultUnavailable(
        kind: IdeVisibleLatencyKind,
        documentRevision: Long,
    ) = Unit

    override fun dropActive() {
        drops++
    }
}

private fun latencyAnalysisSnapshot(
    original: ProjectSnapshot,
    path: VirtualSourcePath,
    text: String,
): AdmittedAnalysisSnapshot {
    val limits = WorkerLimits(sourceFiles = 8, sourceFileBytes = 4096, sourceBytes = 8192)
    val sources =
        ProjectSnapshot.of(
            original.sources.map { source ->
                if (source.path == path) ProjectSource(path, BinaryValue.of(text.encodeToByteArray())) else source
            },
            limits,
        )
    val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 5 }))
    val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profile)
    return AdmittedAnalysisSnapshot(
        identity,
        sources,
        AdmittedAnalysisProfile(
            profile,
            ru.lazyhat.compukters.ide.analysis.protocol
                .AdmittedAnalysisPlatform(Hash256.zero(), emptyList()),
        ),
        AnalysisLimits(),
    )
}
