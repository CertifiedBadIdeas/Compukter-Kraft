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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.analysis.SemanticToken
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import ru.lazyhat.compukters.ide.client.analysis.BoundedIdeVisibleLatencyCollector
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisPresentation
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.analysis.IdeCompletionEntry
import ru.lazyhat.compukters.ide.client.analysis.IdeCompletionState
import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencyClock
import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeProjectSummary
import ru.lazyhat.compukters.ide.client.state.IdeViewState
import ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.highlight.IncrementalKotlinHighlighter
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeStore
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeVisibleFrameEvidenceTest {
    @Test
    fun `semantic source draw acknowledges presentation for editor revision`() {
        val state = workspaceState(textEditor(revision = 12, presentation = semanticPresentation(ANSWER_RANGE)))
        val model = IdeRenderer.extract(state, geometry())

        assertEquals(
            IdeVisibleFrameEvidence(12, presentationVisible = true, completionVisible = false),
            IdeVisibleFrameEvidence.from(state, model),
        )
    }

    @Test
    fun `provisional semantic frame cannot complete a trace before fresh publication`() {
        val state = workspaceState(textEditor(revision = 12, presentation = semanticPresentation(ANSWER_RANGE)))
        val model = IdeRenderer.extract(state, geometry())
        val evidence = assertNotNull(IdeVisibleFrameEvidence.from(state, model))
        val trace = BoundedIdeVisibleLatencyCollector(IdeVisibleLatencyClock { 0L }, maximumSamples = 2)
        trace.editApplied(12)

        trace.frameExtracted(evidence.documentRevision, evidence.presentationVisible, evidence.completionVisible)

        assertTrue(trace.samples().isEmpty())
    }

    @Test
    fun `lexical source and offscreen semantic token do not acknowledge presentation`() {
        val lexicalState = workspaceState(textEditor(revision = 12))
        val lexical = assertNotNull(IdeVisibleFrameEvidence.from(lexicalState, IdeRenderer.extract(lexicalState, geometry())))
        assertFalse(lexical.presentationVisible)

        val offscreenState =
            workspaceState(
                textEditor(
                    revision = 13,
                    presentation = semanticPresentation(HIDDEN_RANGE),
                    visibleLine = 0,
                ),
            )
        val offscreen = assertNotNull(IdeVisibleFrameEvidence.from(offscreenState, IdeRenderer.extract(offscreenState, geometry())))
        assertFalse(offscreen.presentationVisible)
    }

    @Test
    fun `non-empty completion text acknowledges popup for editor revision`() {
        val completion =
            IdeCompletionState.create(
                IDENTITY,
                VIRTUAL_PATH,
                13,
                0,
                ANSWER_RANGE,
                listOf(IdeCompletionEntry(CompletionItem("answer", "answer", CompletionKind.Property), null, null)),
            )
        val state = workspaceState(textEditor(revision = 13, completion = completion, caretUtf16 = ANSWER_RANGE.endUtf16))
        val model = IdeRenderer.extract(state, geometry())

        assertEquals(
            IdeVisibleFrameEvidence(13, presentationVisible = false, completionVisible = true),
            IdeVisibleFrameEvidence.from(state, model),
        )
    }

    @Test
    fun `non-workspace and non-text states produce no frame evidence`() {
        val start = IdeViewState.startPage(emptyList())
        assertNull(IdeVisibleFrameEvidence.from(start, IdeRenderer.extract(start, geometry())))

        val binary = workspaceState(IdeEditorView.Binary(ProjectPath.file("asset.bin"), 4))
        assertNull(IdeVisibleFrameEvidence.from(binary, IdeRenderer.extract(binary, geometry())))
    }

    private fun textEditor(
        revision: Long,
        presentation: IdeAnalysisPresentation = IdeAnalysisPresentation.Empty,
        completion: IdeCompletionState? = null,
        visibleLine: Int = 0,
        caretUtf16: Int = 0,
    ): IdeEditorView.Text {
        val document = EditorDocument(SOURCE)
        val lexical = IncrementalKotlinHighlighter(document).use { it.snapshot() }
        val lines = SOURCE.lines()
        val starts = listOf(0, SOURCE.indexOf("val hidden"))
        return IdeEditorView.Text(
            path = PATH,
            visibleLines = listOf(lines[visibleLine]),
            visibleLineStartsUtf16 = listOf(starts[visibleLine]),
            firstVisibleLine = visibleLine,
            firstVisibleColumn = 0,
            totalLines = lines.size,
            caretUtf16 = caretUtf16,
            selectionStartUtf16 = null,
            selectionEndUtf16 = null,
            contentRevision = revision,
            persistedContentRevision = 0,
            dirty = true,
            conflict = false,
            lexical = lexical,
            analysis = IdeAnalysisState.Active(IDENTITY, VIRTUAL_PATH, revision, presentation, completion),
        )
    }

    private fun semanticPresentation(range: EditorRange) =
        IdeAnalysisPresentation.of(
            diagnostics = emptyList(),
            semanticTokens = listOf(SemanticToken(VIRTUAL_PATH, range, SemanticCategory.LocalVariable)),
        )

    private fun workspaceState(editor: IdeEditorView): IdeViewState {
        val root = createTempDirectory("compukters-visible-frame-")
        val descriptor = ProjectCatalog.open(root).create("demo")
        return try {
            IdeViewState(
                generation = 1,
                page =
                    IdePageState.Workspace(
                        IdeWorkspaceView(
                            project = IdeProjectSummary("demo", "Demo"),
                            tree = ProjectTreeStore(descriptor.handle).scan(),
                            activeFile =
                                when (editor) {
                                    is IdeEditorView.Text -> editor.path
                                    is IdeEditorView.Binary -> editor.path
                                    IdeEditorView.Empty -> null
                                },
                            editor = editor,
                            status = null,
                            build = IdeBuildState.Idle,
                        ),
                    ),
                dialog = null,
                busy = emptySet(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun geometry() = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)

    private companion object {
        const val SOURCE = "val answer = 42\nval hidden = 0"
        val PATH = ProjectPath.file("src/main.kt")
        val VIRTUAL_PATH = VirtualSourcePath.kotlin(PATH.value)
        val IDENTITY = AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.zero()), AnalysisProfileIdentity(Hash256.zero()))
        val ANSWER_RANGE = EditorRange(SOURCE.indexOf("answer"), SOURCE.indexOf("answer") + "answer".length)
        val HIDDEN_RANGE = EditorRange(SOURCE.indexOf("hidden"), SOURCE.indexOf("hidden") + "hidden".length)
    }
}
