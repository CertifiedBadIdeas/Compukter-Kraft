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
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.EditorExpressionInfo
import ru.lazyhat.compukters.ide.analysis.ParameterInfoItem
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.analysis.SemanticToken
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisPresentation
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.analysis.IdeCompletionEntry
import ru.lazyhat.compukters.ide.client.analysis.IdeCompletionModuleRequirement
import ru.lazyhat.compukters.ide.client.analysis.IdeCompletionState
import ru.lazyhat.compukters.ide.client.analysis.IdeDeclarationTarget
import ru.lazyhat.compukters.ide.client.analysis.IdeParameterInfoState
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticAnchor
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticInteraction
import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.build.IdeBuiltArtifact
import ru.lazyhat.compukters.ide.client.files.IdeComputerChildren
import ru.lazyhat.compukters.ide.client.files.IdeComputerNode
import ru.lazyhat.compukters.ide.client.files.IdeComputerTreeState
import ru.lazyhat.compukters.ide.client.state.IdeBusyOperation
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeProblem
import ru.lazyhat.compukters.ide.client.state.IdeProblemSeverity
import ru.lazyhat.compukters.ide.client.state.IdeProjectSummary
import ru.lazyhat.compukters.ide.client.state.IdeToolingState
import ru.lazyhat.compukters.ide.client.state.IdeViewState
import ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileMetadata
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.client.target.IdeTargetState
import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.highlight.IncrementalKotlinHighlighter
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeStore
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdeRendererStateTest {
    @Test
    fun `start page exposes bounded project rows and actions`() {
        val state =
            IdeViewState.startPage(
                listOf(
                    IdeProjectSummary("alpha", "Alpha"),
                    IdeProjectSummary("beta", "Beta"),
                ),
            )

        val model = IdeRenderer.extract(state, geometry())

        assertEquals(listOf("Alpha", "Beta"), model.text.filter { it.kind == IdeTextKind.StartProject }.map { it.value })
        assertTrue(model.hitTargets.any { it.action == IdeHitAction.CreateProject && it.enabled })
        assertTrue(model.hitTargets.any { it.action == IdeHitAction.OpenProject && it.enabled })
        assertEquals(listOf("Create project", "Open project"), model.text.filter { it.kind == IdeTextKind.Toolbar }.map { it.value })
        assertTrue(
            model.text.filter { it.kind == IdeTextKind.Toolbar }.minOf { it.zIndex } >
                model.panels.filter { it.kind == IdePanelKind.Control }.maxOf { it.zIndex },
        )
        assertTrue(model.zOrdered())
    }

    @Test
    fun `start page exposes one stable rotated terminal tool action`() {
        val local = IdeViewState.startPage(emptyList())
        val capable = IdeViewState.startPage(emptyList()).copy(target = IdeTargetState.Attached(target(terminal = true)))
        val unsupported = IdeViewState.startPage(emptyList()).copy(target = IdeTargetState.Attached(target(terminal = false)))

        val localModel = IdeRenderer.extract(local, geometry())
        val localTerminal = localModel.hitTargets.single { it.action == IdeHitAction.Terminal }
        assertFalse(localTerminal.enabled)
        assertEquals("No target attached", localTerminal.tooltip)

        val capableModel = IdeRenderer.extract(capable, geometry(), terminalVisible = true)
        val terminal = capableModel.hitTargets.single { it.action == IdeHitAction.Terminal }
        assertTrue(terminal.enabled)
        assertTrue(terminal.selected)
        assertEquals(IdeRect(940, 46, 960, 118), terminal.bounds)
        assertEquals(geometry().toolStripe, capableModel.panels.single { it.kind == IdePanelKind.ToolStripe }.bounds)
        assertTrue(capableModel.text.none { it.kind == IdeTextKind.Toolbar && it.value == "Terminal" })
        val label = capableModel.text.single { it.kind == IdeTextKind.ToolStripe }
        assertEquals("Terminal", label.value)
        assertEquals(IdeTextRotation.Clockwise90, label.rotation)

        val unsupportedModel = IdeRenderer.extract(unsupported, geometry())
        val unsupportedTerminal = unsupportedModel.hitTargets.single { it.action == IdeHitAction.Terminal }
        assertFalse(unsupportedTerminal.enabled)
        assertEquals("Target terminal is unavailable", unsupportedTerminal.tooltip)
    }

    @Test
    fun `workspace project control opens bounded project menu`() {
        val projects =
            listOf(
                IdeProjectSummary("demo", "Demo"),
                IdeProjectSummary("second", "Second"),
            )
        val model =
            IdeRenderer.extract(
                workspaceState(IdeEditorView.Empty, IdeBuildState.Idle, projects = projects),
                geometry(),
                projectSwitcherOpen = true,
            )

        assertTrue(model.hitTargets.single { it.action == IdeHitAction.ProjectSwitcher }.selected)
        val choices = model.hitTargets.filter { it.action == IdeHitAction.ProjectChoice }
        assertEquals(listOf(0, 1), choices.map { it.choiceIndex })
        assertTrue(choices.single { it.choiceIndex == 0 }.selected)
        assertEquals(listOf("Demo", "Second"), model.text.filter { it.kind == IdeTextKind.ProjectChoice }.map { it.value })
        assertTrue(model.hitTargets.any { it.action == IdeHitAction.CreateProject && it.zIndex > choices.first().zIndex - 1 })
        assertTrue(
            model.panels
                .single { it.kind == IdePanelKind.ProjectSwitcher }
                .bounds.bottom <= geometry().status.top,
        )
    }

    @Test
    fun `workspace clips rows and gives semantic spans precedence over lexical spans`() {
        val source = "fun main()\r\nval value = 1\r\nprintln(value)"
        val secondStart = source.indexOf("val value")
        val valueStart = source.indexOf("value")
        val document = EditorDocument(source)
        document.setCaret(valueStart + "value".length)
        val lexical = IncrementalKotlinHighlighter(document).use { it.snapshot() }
        val identity = AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.zero()), AnalysisProfileIdentity(Hash256.zero()))
        val path = ProjectPath.file("src/main.kt")
        val virtualPath = VirtualSourcePath.kotlin(path.value)
        val presentation =
            IdeAnalysisPresentation.of(
                diagnostics =
                    listOf(
                        EditorDiagnostic(
                            EditorDiagnosticSeverity.Warning,
                            "Example warning",
                            virtualPath,
                            EditorRange(valueStart, valueStart + 5),
                        ),
                    ),
                semanticTokens =
                    listOf(SemanticToken(virtualPath, EditorRange(valueStart, valueStart + 5), SemanticCategory.LocalVariable)),
            )
        val editor =
            IdeEditorView.Text(
                path = path,
                visibleLines = listOf("val value = 1", "println(value)"),
                visibleLineStartsUtf16 = listOf(secondStart, source.indexOf("println")),
                firstVisibleLine = 1,
                firstVisibleColumn = 0,
                totalLines = 3,
                caretUtf16 = document.caretOffset,
                selectionStartUtf16 = valueStart,
                selectionEndUtf16 = valueStart + 5,
                contentRevision = 1,
                persistedContentRevision = 0,
                dirty = true,
                conflict = false,
                lexical = lexical,
                analysis = IdeAnalysisState.Active(identity, virtualPath, 1, presentation, null),
            )
        val geometry = geometry(TerminalFontProfile.COZETTE)
        val model = IdeRenderer.extract(workspaceState(editor, IdeBuildState.Idle), geometry)

        assertEquals(listOf("2", "3"), model.text.filter { it.kind == IdeTextKind.LineNumber }.map { it.value.trim() })
        val value = model.text.single { it.sourceRange == EditorRange(valueStart, valueStart + 5) }
        assertEquals(IdeTextStyle.Semantic(SemanticCategory.LocalVariable), value.style)
        val keyword = model.text.single { it.sourceRange == EditorRange(secondStart, secondStart + 3) }
        assertEquals(IdeTextStyle.Lexical(KotlinLexicalKind.Keyword), keyword.style)
        val selection = model.fills.single { it.kind == IdeFillKind.Selection }
        assertEquals(geometry.editor.top, selection.bounds.top)
        assertEquals(geometry.editor.top + TerminalFontProfile.COZETTE.cellHeight, selection.bounds.bottom)
        val caret = model.fills.single { it.kind == IdeFillKind.Caret }
        assertEquals(geometry.editor.top, caret.bounds.top)
        assertEquals(geometry.editor.top + TerminalFontProfile.COZETTE.cellHeight, caret.bounds.bottom)
        assertEquals(listOf("Example warning"), model.text.filter { it.kind == IdeTextKind.Diagnostic }.map { it.value })
        assertTrue(model.scissors.any { it.kind == IdeScissorKind.Editor })
        assertTrue(model.text.filter { it.kind == IdeTextKind.TreeRow }.any { "main.kt" in it.value })
    }

    @Test
    fun `expression metadata does not override lexical code colors`() {
        val source = "var values = intArrayOf(7, 11)"
        val variableStart = source.indexOf("values")
        val initializerStart = source.indexOf("intArrayOf")
        val initializerEnd = source.length
        val functionEnd = initializerStart + "intArrayOf".length
        val document = EditorDocument(source)
        val lexical = IncrementalKotlinHighlighter(document).use { it.snapshot() }
        val identity = AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.zero()), AnalysisProfileIdentity(Hash256.zero()))
        val path = ProjectPath.file("src/main.kt")
        val virtualPath = VirtualSourcePath.kotlin(path.value)
        val presentation =
            IdeAnalysisPresentation.of(
                diagnostics = emptyList(),
                semanticTokens =
                    listOf(
                        SemanticToken(
                            virtualPath,
                            EditorRange(variableStart, variableStart + "values".length),
                            SemanticCategory.LocalVariable,
                            isMutable = true,
                        ),
                        SemanticToken(virtualPath, EditorRange(initializerStart, initializerEnd), SemanticCategory.InferredExpression),
                        SemanticToken(virtualPath, EditorRange(initializerStart, functionEnd), SemanticCategory.Function),
                    ),
            )
        val editor =
            IdeEditorView.Text(
                path = path,
                visibleLines = listOf(source),
                visibleLineStartsUtf16 = listOf(0),
                firstVisibleLine = 0,
                firstVisibleColumn = 0,
                totalLines = 1,
                caretUtf16 = 0,
                selectionStartUtf16 = null,
                selectionEndUtf16 = null,
                contentRevision = 1,
                persistedContentRevision = 1,
                dirty = false,
                conflict = false,
                lexical = lexical,
                analysis = IdeAnalysisState.Active(identity, virtualPath, 1, presentation, null),
            )

        val model = IdeRenderer.extract(workspaceState(editor, IdeBuildState.Idle), geometry(TerminalFontProfile.COZETTE))

        assertEquals(IdeTextStyle.Semantic(SemanticCategory.Function), model.sourceStyle("intArrayOf"))
        assertEquals(IdeTextStyle.Lexical(KotlinLexicalKind.Operator), model.sourceStyle("("))
        assertEquals(IdeTextStyle.Lexical(KotlinLexicalKind.Number), model.sourceStyle("7"))
        assertEquals(IdeTextStyle.Lexical(KotlinLexicalKind.Operator), model.sourceStyle(")"))
        assertEquals(IdeColors.MUTABLE_UNDERLINE, model.fills.single { it.kind == IdeFillKind.MutableUnderline }.color)
    }

    @Test
    fun `source rendering uses Islands Dark syntax colors`() {
        val source = "val answer: Int = 7; println(\"value: \${answer}\\n\") // note"
        val editor = semanticEditor(source) { _, _ -> IdeSemanticInteraction.None }

        val model = IdeRenderer.extract(workspaceState(editor, IdeBuildState.Idle), geometry(TerminalFontProfile.COZETTE))

        assertEquals(0xFF191A1C.toInt(), model.panels.single { it.kind == IdePanelKind.Editor }.color)
        assertEquals(0xFF4B5059.toInt(), model.text.single { it.kind == IdeTextKind.LineNumber }.color)
        assertEquals(0xFFCF8E6D.toInt(), model.sourceDraw("val").color)
        assertEquals(0xFFBCBEC4.toInt(), model.sourceDraw("Int").color)
        assertEquals(0xFF2AACB8.toInt(), model.sourceDraw("7").color)
        assertEquals(0xFF6AAB73.toInt(), model.sourceDraw("\"value: ").color)
        assertEquals(0xFFCF8E6D.toInt(), model.sourceDraw("\${").color)
        assertEquals(0xFFCF8E6D.toInt(), model.sourceDraw("}").color)
        assertEquals(0xFFCF8E6D.toInt(), model.sourceDraw("\\n").color)
        assertEquals(0xFF7A7E85.toInt(), model.sourceDraw("// note").color)
    }

    @Test
    fun `confirmed declaration link has exact hyperlink draw and clipped underline`() {
        val source = "val answer = sample"
        val range = EditorRange(4, 10)
        val editor =
            semanticEditor(source) { identity, path ->
                IdeSemanticInteraction.Link(
                    IdeSemanticAnchor(identity, path, 0, 6, range),
                    listOf(DeclarationLocation.Source(DeclarationOrigin.Project, path, range)),
                )
            }

        val model = IdeRenderer.extract(workspaceState(editor, IdeBuildState.Idle), geometry())
        val linked = model.text.single { it.sourceRange == range }

        assertEquals(IdeColors.HYPERLINK, linked.color)
        assertTrue(
            model.fills.any {
                it.kind == IdeFillKind.HyperlinkUnderline &&
                    it.bounds.left == linked.x &&
                    it.bounds.right <= geometry().editor.right
            },
        )
    }

    @Test
    fun `hover popup contains signature type and bundle origin below modal dialogs`() {
        val source = "val answer = sample"
        val range = EditorRange(4, 10)
        val bundle = AnalysisModuleIdentity("std.core", Hash256.of(ByteArray(32) { 4 }))
        val editor =
            semanticEditor(source) { identity, path ->
                IdeSemanticInteraction.Hover(
                    IdeSemanticAnchor(identity, path, 0, 6, range),
                    EditorExpressionInfo(path, range, "kotlin.Int", "val answer: kotlin.Int", DeclarationOrigin.Platform(bundle)),
                )
            }
        val state =
            workspaceState(editor, IdeBuildState.Idle).copy(
                dialog = IdeDialogState.Confirmation("Delete", "Permanent", 7),
            )

        val model = IdeRenderer.extract(state, geometry())
        val hover = model.text.filter { it.kind == IdeTextKind.Hover }

        assertTrue(hover.any { it.value == "val answer: kotlin.Int" })
        assertTrue(hover.any { "kotlin.Int" in it.value })
        assertTrue(hover.any { "std.core" in it.value })
        assertTrue(hover.maxOf { it.zIndex } < model.text.filter { it.kind == IdeTextKind.Dialog }.minOf { it.zIndex })
        assertTrue(
            hover.all {
                requireNotNull(it.clip).let { clip ->
                    clip.left >= geometry().editor.left &&
                        clip.right <= geometry().editor.right
                }
            },
        )
    }

    @Test
    fun `declaration chooser renders bounded selected rows with mouse hit metadata`() {
        val source = "val answer = sample"
        val range = EditorRange(4, 10)
        val editor =
            semanticEditor(source) { identity, path ->
                IdeSemanticInteraction.Chooser(
                    IdeSemanticAnchor(identity, path, 0, 6, range),
                    (0 until 12).map { index ->
                        IdeDeclarationTarget.Project(ProjectPath.file("src/Target$index.kt"), EditorRange(0, 3))
                    },
                    selectedIndex = 10,
                    maximumTargets = 64,
                )
            }

        val model = IdeRenderer.extract(workspaceState(editor, IdeBuildState.Idle), geometry())
        val rows = model.text.filter { it.kind == IdeTextKind.DeclarationChoice }

        assertEquals(8, rows.size)
        assertEquals(IdeColors.ACCENT, rows.last().color)
        assertEquals((3..10).toList(), model.hitTargets.filter { it.action == IdeHitAction.DeclarationChoice }.map { it.choiceIndex })
        assertTrue(model.scissors.any { it.kind == IdeScissorKind.SemanticPopup })
    }

    @Test
    fun `editor rows are clipped to derived font geometry`() {
        val source = (1..40).joinToString("\n") { "line$it" }
        val document = EditorDocument(source)
        val lexical = IncrementalKotlinHighlighter(document).use { it.snapshot() }
        val path = ProjectPath.file("src/main.kt")
        val editor =
            IdeEditorView.Text(
                path = path,
                visibleLines = (0 until document.lineCount).map(document::materializeLine),
                visibleLineStartsUtf16 = (0 until document.lineCount).map(document::lineStartOffset),
                firstVisibleLine = 0,
                firstVisibleColumn = 0,
                totalLines = document.lineCount,
                caretUtf16 = 0,
                selectionStartUtf16 = null,
                selectionEndUtf16 = null,
                contentRevision = 0,
                persistedContentRevision = 0,
                dirty = false,
                conflict = false,
                lexical = lexical,
                analysis = IdeAnalysisState.Idle,
            )
        val geometry = geometry()

        val model = IdeRenderer.extract(workspaceState(editor, IdeBuildState.Idle), geometry)

        val lineNumbers = model.text.filter { it.kind == IdeTextKind.LineNumber }
        assertEquals(geometry.codeRows, lineNumbers.size)
        assertEquals(geometry.codeRows.toString(), lineNumbers.last().value.trim())
    }

    @Test
    fun `toolbar reports artifact and leaves target actions visibly disabled`() {
        val editor = IdeEditorView.Binary(ProjectPath.file("image.bin"), 4_096)
        val build = IdeBuildState.Succeeded(Hash256.zero(), IdeBuiltArtifact.of(Hash256.zero(), ByteArray(321)), "demo", true, 42)

        val model = IdeRenderer.extract(workspaceState(editor, build), geometry(TerminalFontProfile.COZETTE))

        assertTrue(model.text.any { it.kind == IdeTextKind.Binary && "4096" in it.value })
        assertTrue(model.text.any { it.kind == IdeTextKind.Status && "321 B" in it.value && "cache" in it.value })
        listOf(IdeHitAction.Verify, IdeHitAction.Deploy, IdeHitAction.Run).forEach { action ->
            val target = model.hitTargets.single { it.action == action }
            assertFalse(target.enabled)
            assertEquals("No target attached", target.tooltip)
        }
    }

    @Test
    fun `workspace toolbar uses fixed icons in bounded action groups`() {
        val model = IdeRenderer.extract(workspaceState(IdeEditorView.Empty, IdeBuildState.Idle), geometry())

        assertEquals(
            listOf(
                IdeIconKind.Format,
                IdeIconKind.Resolve,
                IdeIconKind.Build,
                IdeIconKind.Verify,
                IdeIconKind.Deploy,
                IdeIconKind.Run,
                IdeIconKind.NewFile,
                IdeIconKind.NewDirectory,
                IdeIconKind.Rename,
                IdeIconKind.Delete,
            ),
            model.icons.map { it.kind },
        )
        assertTrue(model.icons.all { icon -> geometry().toolbar.contains(icon.bounds) })
        assertTrue(model.text.none { it.kind == IdeTextKind.Toolbar })
        assertTrue(model.hitTargets.filter { it.action in WORKSPACE_ACTIONS }.all { it.tooltip != null })
        listOf(500 to 240, 300 to 200).forEach { (width, height) ->
            val constrained = IdeRenderGeometry.compute(width, height, 96, 64, true, true, TerminalFontProfile.DINA)
            val constrainedModel = IdeRenderer.extract(workspaceState(IdeEditorView.Empty, IdeBuildState.Idle), constrained)
            assertTrue(constrained.supported)
            assertTrue(constrainedModel.icons.all { icon -> constrained.toolbar.contains(icon.bounds) })
        }

        val compiling =
            IdeRenderer.extract(
                workspaceState(
                    IdeEditorView.Empty,
                    IdeBuildState.Compiling(7, Hash256.zero(), SourceSnapshotId(Hash256.zero())),
                ),
                geometry(),
            )
        assertTrue(compiling.icons.any { it.kind == IdeIconKind.Cancel })
        assertTrue(compiling.icons.none { it.kind == IdeIconKind.Build })
        assertEquals("Cancel build", compiling.hitTargets.single { it.action == IdeHitAction.Cancel }.tooltip)
    }

    @Test
    fun `hovered toolbar action renders bounded tooltip below modal precedence`() {
        val state = workspaceState(IdeEditorView.Empty, IdeBuildState.Idle)
        val initial = IdeRenderer.extract(state, geometry())
        val build = initial.hitTargets.single { it.action == IdeHitAction.Build }
        val hovered = IdeRenderer.extract(state, geometry(), pointerX = build.bounds.left + 1, pointerY = build.bounds.top + 1)

        val tooltip = assertNotNull(hovered.panels.singleOrNull { it.kind == IdePanelKind.Tooltip })
        assertTrue(geometry().panel.contains(tooltip.bounds))
        assertEquals("Build (Ctrl+F9)", hovered.text.single { it.kind == IdeTextKind.Tooltip }.value)

        val modal =
            IdeRenderer.extract(
                state.copy(dialog = IdeDialogState.LockUpdate("demo")),
                geometry(),
                pointerX = build.bounds.left + 1,
                pointerY = build.bounds.top + 1,
            )
        assertTrue(modal.text.none { it.kind == IdeTextKind.Tooltip })
    }

    @Test
    fun `preparing tooling keeps editing visible and disables tooling actions`() {
        val state = workspaceState(IdeEditorView.Empty, IdeBuildState.Idle, tooling = IdeToolingState.Preparing)

        val model = IdeRenderer.extract(state, geometry())

        listOf(
            IdeHitAction.Format,
            IdeHitAction.Resolve,
            IdeHitAction.Build,
            IdeHitAction.Verify,
            IdeHitAction.Deploy,
            IdeHitAction.Run,
        ).forEach { action ->
            assertFalse(model.hitTargets.single { it.action == action }.enabled)
        }
        assertTrue(
            model.text
                .single { it.kind == IdeTextKind.Status }
                .value
                .contains("Kotlin tooling is starting"),
        )
    }

    @Test
    fun `toolbar exposes format for writable Kotlin and reports active formatting`() {
        val editor = semanticEditor("fun main(){}") { _, _ -> IdeSemanticInteraction.None }
        val ready = IdeRenderer.extract(workspaceState(editor, IdeBuildState.Idle), geometry())

        val action = ready.hitTargets.single { it.action == IdeHitAction.Format }
        assertTrue(action.enabled)
        assertEquals("Reformat Code (Ctrl+Alt+L)", action.tooltip)

        val formatting =
            IdeRenderer.extract(
                workspaceState(editor, IdeBuildState.Idle, busy = setOf(IdeBusyOperation.Format)),
                geometry(),
            )
        val busyAction = formatting.hitTargets.single { it.action == IdeHitAction.Format }
        assertFalse(busyAction.enabled)
        assertTrue(busyAction.selected)
        assertTrue(
            formatting.text
                .single { it.kind == IdeTextKind.Status }
                .value
                .contains("Formatting…"),
        )
    }

    @Test
    fun `attached target enables actions and reports honest submission state`() {
        val target = target()
        val path = IdeDeploymentPath.fromProgramName("demo")
        val state =
            workspaceState(
                IdeEditorView.Empty,
                IdeBuildState.Idle,
                target = IdeTargetState.CommandSubmitted(target, path, IdeExecutableRevision.Present(3)),
            )

        val model = IdeRenderer.extract(state, geometry())

        assertTrue(model.text.any { it.kind == IdeTextKind.Header && "Computer" in it.value })
        listOf(IdeHitAction.Verify, IdeHitAction.Deploy, IdeHitAction.Run).forEach { action ->
            assertTrue(model.hitTargets.single { it.action == action }.enabled)
        }
        assertTrue(model.text.any { it.kind == IdeTextKind.Status && "Command submitted" in it.value })
    }

    @Test
    fun `terminal tool stripe action reflects overlay state`() {
        val capable = IdeTargetState.Attached(target(terminal = true))
        val opening =
            IdeRenderer.extract(
                workspaceState(IdeEditorView.Empty, IdeBuildState.Idle, target = capable),
                geometry(),
                terminalState = IdeTerminalStatus.Opening,
                terminalVisible = true,
            )

        val openingTarget = opening.hitTargets.single { it.action == IdeHitAction.Terminal }
        assertTrue(openingTarget.enabled)
        assertTrue(openingTarget.selected)
        assertEquals("Opening target terminal…", openingTarget.tooltip)

        val failed =
            IdeRenderer.extract(
                workspaceState(IdeEditorView.Empty, IdeBuildState.Idle, target = capable),
                geometry(),
                terminalState = IdeTerminalStatus.Failed("Target stopped", retryable = true),
                terminalVisible = false,
            )
        val failedTarget = failed.hitTargets.single { it.action == IdeHitAction.Terminal }
        assertFalse(failedTarget.selected)
        assertEquals("Target stopped", failedTarget.tooltip)

        val unsupported =
            IdeRenderer.extract(
                workspaceState(IdeEditorView.Empty, IdeBuildState.Idle, target = IdeTargetState.Attached(target())),
                geometry(),
            )
        val unsupportedTarget = unsupported.hitTargets.single { it.action == IdeHitAction.Terminal }
        assertFalse(unsupportedTarget.enabled)
        assertEquals("Target terminal is unavailable", unsupportedTarget.tooltip)
        assertTrue(unsupported.text.none { it.kind == IdeTextKind.Toolbar && it.value == "Terminal" })
    }

    @Test
    fun `status renders an unavailable analysis message only once`() {
        val source = "fun main() = Unit"
        val document = EditorDocument(source)
        val lexical = IncrementalKotlinHighlighter(document).use { it.snapshot() }
        val path = ProjectPath.file("src/main.kt")
        val message = "Analysis unavailable"
        val editor =
            IdeEditorView.Text(
                path = path,
                visibleLines = listOf(source),
                visibleLineStartsUtf16 = listOf(0),
                firstVisibleLine = 0,
                firstVisibleColumn = 0,
                totalLines = 1,
                caretUtf16 = 0,
                selectionStartUtf16 = null,
                selectionEndUtf16 = null,
                contentRevision = 0,
                persistedContentRevision = 0,
                dirty = false,
                conflict = false,
                lexical = lexical,
                analysis = IdeAnalysisState.Unavailable(VirtualSourcePath.kotlin(path.value), 0, message, "worker stopped"),
            )
        val state = workspaceState(editor, IdeBuildState.Idle, IdeProblem(message, IdeProblemSeverity.Warning))

        val status =
            IdeRenderer
                .extract(state, geometry())
                .text
                .single { it.kind == IdeTextKind.Status }
                .value

        assertEquals(1, Regex(Regex.escape(message)).findAll(status).count())
    }

    @Test
    fun `completion popup expands for visible overload signatures within editor bounds`() {
        val source = "fun main() { printl }"
        val document = EditorDocument(source)
        document.setCaret(source.indexOf("printl") + "printl".length)
        val lexical = IncrementalKotlinHighlighter(document).use { it.snapshot() }
        val identity = AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.zero()), AnalysisProfileIdentity(Hash256.zero()))
        val path = ProjectPath.file("src/main.kt")
        val virtualPath = VirtualSourcePath.kotlin(path.value)
        val label = "println(message: SomeVeryLongApplicationSpecificType)"
        val completion =
            IdeCompletionState.create(
                identity,
                virtualPath,
                0,
                0,
                EditorRange(source.indexOf("printl"), document.caretOffset),
                listOf(IdeCompletionEntry(CompletionItem(label, "println", CompletionKind.Function), null, null)),
            )
        val editor =
            IdeEditorView.Text(
                path = path,
                visibleLines = listOf(source),
                visibleLineStartsUtf16 = listOf(0),
                firstVisibleLine = 0,
                firstVisibleColumn = 0,
                totalLines = 1,
                caretUtf16 = document.caretOffset,
                selectionStartUtf16 = null,
                selectionEndUtf16 = null,
                contentRevision = 0,
                persistedContentRevision = 0,
                dirty = false,
                conflict = false,
                lexical = lexical,
                analysis = IdeAnalysisState.Active(identity, virtualPath, 0, IdeAnalysisPresentation.Empty, completion),
            )
        val geometry = geometry()

        val model = IdeRenderer.extract(workspaceState(editor, IdeBuildState.Idle), geometry)
        val popup = model.panels.single { it.kind == IdePanelKind.Dialog }.bounds

        assertTrue(model.text.any { it.kind == IdeTextKind.Completion && it.value == "$label · function" })
        assertTrue(popup.width > 220, popup.toString())
        assertTrue(popup.width <= geometry.editor.width, popup.toString())
    }

    @Test
    fun `parameter info renders at the caret and accents only the active parameter`() {
        val source = "fun main() { println(42) }"
        val document = EditorDocument(source)
        document.setCaret(source.indexOf("42") + 1)
        val lexical = IncrementalKotlinHighlighter(document).use { it.snapshot() }
        val identity = AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.zero()), AnalysisProfileIdentity(Hash256.zero()))
        val path = ProjectPath.file("src/main.kt")
        val virtualPath = VirtualSourcePath.kotlin(path.value)
        val signature = "println(value: Any?): Unit"
        val parameterInfo =
            IdeParameterInfoState(
                identity,
                virtualPath,
                0,
                document.caretOffset,
                EditorRange(source.indexOf("println"), source.lastIndexOf(')') + 1),
                listOf(ParameterInfoItem(signature, EditorRange(8, 19), true)),
                32,
            )
        val editor =
            IdeEditorView.Text(
                path = path,
                visibleLines = listOf(source),
                visibleLineStartsUtf16 = listOf(0),
                firstVisibleLine = 0,
                firstVisibleColumn = 0,
                totalLines = 1,
                caretUtf16 = document.caretOffset,
                selectionStartUtf16 = null,
                selectionEndUtf16 = null,
                contentRevision = 0,
                persistedContentRevision = 0,
                dirty = false,
                conflict = false,
                lexical = lexical,
                analysis =
                    IdeAnalysisState.Active(
                        identity,
                        virtualPath,
                        0,
                        IdeAnalysisPresentation.Empty,
                        completion = null,
                        parameterInfo = parameterInfo,
                    ),
            )

        val geometry = geometry()
        val model = IdeRenderer.extract(workspaceState(editor, IdeBuildState.Idle), geometry)
        val runs = model.text.filter { it.kind == IdeTextKind.ParameterInfo }
        val popup = model.panels.single { it.kind == IdePanelKind.Dialog }.bounds

        assertEquals(listOf("println(", "value: Any?", "): Unit"), runs.map { it.value })
        assertEquals(listOf(IdeColors.TEXT, IdeColors.ACCENT, IdeColors.TEXT), runs.map { it.color })
        assertTrue(geometry.editor.contains(popup))
        assertTrue(model.scissors.any { it.kind == IdeScissorKind.SemanticPopup && it.bounds == popup })
    }

    @Test
    fun `completion popup renders the viewport containing keyboard selection`() {
        val source = "fun main() { item }"
        val document = EditorDocument(source)
        document.setCaret(source.indexOf("item") + "item".length)
        val lexical = IncrementalKotlinHighlighter(document).use { it.snapshot() }
        val identity = AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.zero()), AnalysisProfileIdentity(Hash256.zero()))
        val path = ProjectPath.file("src/main.kt")
        val virtualPath = VirtualSourcePath.kotlin(path.value)
        val items = (0 until 12).map { IdeCompletionEntry(CompletionItem("item$it", "item$it", CompletionKind.Function), null, null) }
        val completion =
            IdeCompletionState
                .create(
                    identity,
                    virtualPath,
                    0,
                    0,
                    EditorRange(source.indexOf("item"), document.caretOffset),
                    items,
                ).move(8)
        val editor =
            IdeEditorView.Text(
                path = path,
                visibleLines = listOf(source),
                visibleLineStartsUtf16 = listOf(0),
                firstVisibleLine = 0,
                firstVisibleColumn = 0,
                totalLines = 1,
                caretUtf16 = document.caretOffset,
                selectionStartUtf16 = null,
                selectionEndUtf16 = null,
                contentRevision = 0,
                persistedContentRevision = 0,
                dirty = false,
                conflict = false,
                lexical = lexical,
                analysis = IdeAnalysisState.Active(identity, virtualPath, 0, IdeAnalysisPresentation.Empty, completion),
            )

        val completionText =
            IdeRenderer
                .extract(workspaceState(editor, IdeBuildState.Idle), geometry())
                .text
                .filter { it.kind == IdeTextKind.Completion }

        assertEquals((1..8).map { "item$it · function" }, completionText.map { it.value })
        assertEquals(IdeColors.ACCENT, completionText.last().color)
    }

    @Test
    fun `completion popup renders import and module actions`() {
        val source = "Re"
        val document = EditorDocument(source)
        document.setCaret(source.length)
        val identity = AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.zero()), AnalysisProfileIdentity(Hash256.zero()))
        val path = ProjectPath.file("src/main.kt")
        val virtualPath = VirtualSourcePath.kotlin(path.value)
        val action = "import compukter.redstone.Redstone · enable compukter:redstone"
        val completion =
            IdeCompletionState.create(
                identity,
                virtualPath,
                0,
                0,
                EditorRange(0, 2),
                listOf(
                    IdeCompletionEntry(
                        CompletionItem("Redstone", "Redstone", CompletionKind.Object),
                        action,
                        IdeCompletionModuleRequirement(
                            ModuleId.parse("compukter:redstone"),
                            ApiMajor(2),
                        ),
                    ),
                ),
            )
        val editor =
            IdeEditorView.Text(
                path = path,
                visibleLines = listOf(source),
                visibleLineStartsUtf16 = listOf(0),
                firstVisibleLine = 0,
                firstVisibleColumn = 0,
                totalLines = 1,
                caretUtf16 = document.caretOffset,
                selectionStartUtf16 = null,
                selectionEndUtf16 = null,
                contentRevision = 0,
                persistedContentRevision = 0,
                dirty = false,
                conflict = false,
                lexical = IncrementalKotlinHighlighter(document).use { it.snapshot() },
                analysis = IdeAnalysisState.Active(identity, virtualPath, 0, IdeAnalysisPresentation.Empty, completion),
            )

        val row =
            IdeRenderer
                .extract(workspaceState(editor, IdeBuildState.Idle), geometry())
                .text
                .single { it.kind == IdeTextKind.Completion }

        assertEquals("Redstone · object · $action", row.value)
        assertEquals(IdeColors.ACCENT, row.color)
    }

    @Test
    fun `local prompt replaces page actions with modal actions`() {
        val state = IdeViewState.startPage(emptyList())

        val model =
            IdeRenderer.extract(
                state,
                geometry(),
                prompt = IdePromptState(IdePromptKind.CreateProject, "demo", "Example error"),
            )

        assertTrue(model.text.any { it.kind == IdeTextKind.Dialog && it.value == "demo_" })
        assertTrue(model.text.any { it.kind == IdeTextKind.Dialog && it.value == "Example error" })
        assertTrue(model.hitTargets.any { it.action == IdeHitAction.Confirm && it.enabled })
        assertTrue(model.hitTargets.any { it.action == IdeHitAction.Dismiss && it.enabled })
        assertTrue(model.hitTargets.filter { it.focusGroup == IdeFocusGroup.Page }.all { !it.enabled })
    }

    @Test
    fun `workspace explorer renders project and lazy computer roots with refresh`() {
        val file =
            IdeComputerNode.File(
                IdeTargetVirtualPath.of("/home/readme.txt"),
                IdeTargetFileMetadata(IdeTargetFileKind.File, 3, 2, false),
            )
        val home =
            IdeComputerNode.Directory(
                IdeTargetVirtualPath.of("/home"),
                IdeTargetFileMetadata(IdeTargetFileKind.Directory, 0, 1, false),
                IdeComputerChildren.Loaded(listOf(file)),
            )
        val root =
            IdeComputerNode.Directory(
                IdeTargetVirtualPath.of("/"),
                IdeTargetFileMetadata(IdeTargetFileKind.Directory, 0, 1, false),
                IdeComputerChildren.Loaded(listOf(home)),
            )
        val state =
            workspaceState(
                IdeEditorView.Empty,
                IdeBuildState.Idle,
                computerTree = IdeComputerTreeState.Available(root, setOf(IdeTargetVirtualPath.of("/"), IdeTargetVirtualPath.of("/home"))),
            )

        val model = IdeRenderer.extract(state, geometry())
        val rows = model.text.filter { it.kind == IdeTextKind.TreeRow }

        assertTrue(rows.any { it.value == "Project · Demo" })
        assertTrue(rows.any { it.value == "Computer" })
        assertTrue(rows.any { it.value.contains("home") })
        assertTrue(rows.any { it.value.contains("readme.txt") })
        assertTrue(model.hitTargets.any { it.action == IdeHitAction.RefreshComputer && it.enabled })
    }

    private fun workspaceState(
        editor: IdeEditorView,
        build: IdeBuildState,
        status: IdeProblem? = null,
        target: IdeTargetState = IdeTargetState.LocalOnly,
        tooling: IdeToolingState = IdeToolingState.Ready,
        computerTree: IdeComputerTreeState = IdeComputerTreeState.NoTarget,
        busy: Set<IdeBusyOperation> = emptySet(),
        projects: List<IdeProjectSummary> = listOf(IdeProjectSummary("demo", "Demo")),
    ): IdeViewState {
        val root = createTempDirectory("compukters-renderer-tree-")
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
                            status = status,
                            build = build,
                            computerTree = computerTree,
                            projects = projects,
                        ),
                    ),
                dialog = null,
                busy = busy,
                target = target,
                tooling = tooling,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun target(terminal: Boolean = false) =
        IdeAttachedTarget(
            IdeTargetId("computer-1"),
            IdeTargetProfileId(Hash256.zero()),
            TargetCompileProfile(
                ToolchainLockIdentity("2.4.10", "2.4", 1u, 1u, 1u, Hash256.zero(), Hash256.zero()),
                emptyList(),
                WorkerLimits(),
            ),
            IdeTargetCapabilities(writableFileSystem = true, canonicalInput = true, terminal = terminal),
            "Computer",
        )

    private fun semanticEditor(
        source: String,
        interaction: (AnalysisSnapshotIdentity, VirtualSourcePath) -> IdeSemanticInteraction,
    ): IdeEditorView.Text {
        val document = EditorDocument(source)
        val lexical = IncrementalKotlinHighlighter(document).use { it.snapshot() }
        val identity = AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.zero()), AnalysisProfileIdentity(Hash256.zero()))
        val path = ProjectPath.file("src/main.kt")
        val virtualPath = VirtualSourcePath.kotlin(path.value)
        return IdeEditorView.Text(
            path = path,
            visibleLines = listOf(source),
            visibleLineStartsUtf16 = listOf(0),
            firstVisibleLine = 0,
            firstVisibleColumn = 0,
            totalLines = 1,
            caretUtf16 = 0,
            selectionStartUtf16 = null,
            selectionEndUtf16 = null,
            contentRevision = 0,
            persistedContentRevision = 0,
            dirty = false,
            conflict = false,
            lexical = lexical,
            analysis =
                IdeAnalysisState.Active(
                    identity,
                    virtualPath,
                    0,
                    IdeAnalysisPresentation.Empty,
                    completion = null,
                    interaction = interaction(identity, virtualPath),
                ),
        )
    }

    private fun geometry(font: TerminalFontProfile = TerminalFontProfile.DINA) =
        IdeRenderGeometry.compute(960, 540, 180, 120, true, true, font)
}

private fun IdeDrawModel.zOrdered(): Boolean {
    val values =
        panels.map { it.zIndex } +
            fills.map { it.zIndex } +
            text.map { it.zIndex } +
            scissors.map { it.zIndex } +
            hitTargets.map { it.zIndex } +
            icons.map { it.zIndex }
    return values.all { it >= 0 }
}

private fun IdeDrawModel.sourceDraw(value: String): IdeTextDraw = text.single { it.kind == IdeTextKind.Source && it.value == value }

private fun IdeDrawModel.sourceStyle(value: String): IdeTextStyle = sourceDraw(value).style

private fun IdeRect.contains(other: IdeRect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

private val WORKSPACE_ACTIONS =
    setOf(
        IdeHitAction.Format,
        IdeHitAction.Resolve,
        IdeHitAction.Build,
        IdeHitAction.Cancel,
        IdeHitAction.Verify,
        IdeHitAction.Deploy,
        IdeHitAction.Run,
        IdeHitAction.CreateText,
        IdeHitAction.CreateDirectory,
        IdeHitAction.Rename,
        IdeHitAction.Delete,
    )
