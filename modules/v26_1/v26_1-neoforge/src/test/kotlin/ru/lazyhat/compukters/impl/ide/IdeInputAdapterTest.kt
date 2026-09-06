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
 */

package ru.lazyhat.compukters.impl.ide

import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisPresentation
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.analysis.IdeDeclarationTarget
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticAnchor
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticInteraction
import ru.lazyhat.compukters.ide.client.files.IdeComputerNode
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdeHorizontalDirection
import ru.lazyhat.compukters.ide.client.state.IdeMoveDirection
import ru.lazyhat.compukters.ide.client.state.IdeProjectSummary
import ru.lazyhat.compukters.ide.client.state.IdeVerticalDirection
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileMetadata
import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalSnapshot
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectFileKind
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeEntry
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeInputAdapterTest {
    @Test
    fun `character input preserves supplementary code points only for editor focus`() {
        val fixture = fixture()

        assertTrue(fixture.adapter.charTyped(CharacterEvent(0x1f600), IdeFocusState.Editor))
        assertEquals(listOf<IdeCommand>(IdeCommand.Edit(IdeEditorInput.Type("😀"))), fixture.commands)
        assertFalse(fixture.adapter.charTyped(CharacterEvent('x'.code), IdeFocusState.Tree))
    }

    @Test
    fun `editor shortcuts and modified navigation translate once`() {
        val fixture = fixture()

        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_B, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_F9, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_MOD_SHIFT), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_MOD_ALT), IdeFocusState.Editor)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_MOD_ALT), IdeFocusState.Editor)

        assertEquals(
            listOf<IdeCommand>(
                IdeCommand.Save,
                IdeCommand.GoToDeclaration(),
                IdeCommand.Build,
                IdeCommand.ManualCompletion,
                IdeCommand.Edit(IdeEditorInput.Undo),
                IdeCommand.Edit(IdeEditorInput.Redo),
                IdeCommand.Edit(IdeEditorInput.Move(IdeMoveDirection.Left, true)),
                IdeCommand.NavigateBack,
                IdeCommand.NavigateForward,
            ),
            fixture.commands,
        )
    }

    @Test
    fun `editor fundamentals map word deletion indentation and page navigation`() {
        val fixture = fixture()
        val focus = IdeFocusState(IdeFocusArea.Editor, editorPageRows = 17)

        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT), focus)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_MOD_CONTROL), focus)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_MOD_CONTROL), focus)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_DELETE, GLFW.GLFW_MOD_CONTROL), focus)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_TAB), focus)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_SHIFT), focus)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_MOD_SHIFT), focus)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_PAGE_DOWN), focus)

        assertEquals(
            listOf<IdeCommand>(
                IdeCommand.Edit(IdeEditorInput.MoveWord(IdeHorizontalDirection.Left, true)),
                IdeCommand.Edit(IdeEditorInput.MoveWord(IdeHorizontalDirection.Right, false)),
                IdeCommand.Edit(IdeEditorInput.DeleteWordBackward),
                IdeCommand.Edit(IdeEditorInput.DeleteWordForward),
                IdeCommand.Edit(IdeEditorInput.Tab),
                IdeCommand.Edit(IdeEditorInput.Outdent),
                IdeCommand.Edit(IdeEditorInput.Page(IdeVerticalDirection.Up, 17, true)),
                IdeCommand.Edit(IdeEditorInput.Page(IdeVerticalDirection.Down, 17, false)),
            ),
            fixture.commands,
        )
    }

    @Test
    fun `copy and cut use the selected text at the UI clipboard edge`() {
        val commands = mutableListOf<IdeCommand>()
        val writes = mutableListOf<String>()
        val adapter =
            IdeInputAdapter(
                commands::add,
                IdeClipboard { "" },
                IdeClientLimits(),
                clipboardWriter = IdeClipboardWriter(writes::add),
                selectionSource = IdeSelectionSource { "выбор😀" },
            )

        assertTrue(adapter.keyPressed(key(GLFW.GLFW_KEY_C, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor))
        assertTrue(adapter.keyPressed(key(GLFW.GLFW_KEY_X, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor))

        assertEquals(listOf("выбор😀", "выбор😀"), writes)
        assertEquals(listOf<IdeCommand>(IdeCommand.Edit(IdeEditorInput.Cut)), commands)
    }

    @Test
    fun `Ctrl click navigates without first moving the caret`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
        val editor = textEditor("answer")
        val codeLeft = geometry.editor.left + 4 * geometry.font.cellWidth

        fixture.adapter.pointerClicked(
            codeLeft + geometry.font.cellWidth.toDouble(),
            geometry.editor.top + 1.0,
            GLFW.GLFW_MOD_CONTROL,
            IdePointerContext(geometry, editor),
        )

        assertEquals(IdeCommand.GoToDeclaration(1), fixture.commands.first())
        assertFalse(fixture.commands.any { it is IdeCommand.Edit })
    }

    @Test
    fun `double click selects the token under the pointer`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
        val editor = textEditor("answer")
        val codeLeft = geometry.editor.left + 4 * geometry.font.cellWidth

        fixture.adapter.pointerClicked(
            codeLeft + 2.0 * geometry.font.cellWidth,
            geometry.editor.top + 1.0,
            0,
            IdePointerContext(geometry, editor),
            doubleClick = true,
        )

        assertEquals(IdeCommand.Edit(IdeEditorInput.SelectToken(2)), fixture.commands.first())
    }

    @Test
    fun `pointer mapping preserves tabs and surrogate pairs and clears outside source glyphs`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
        val editor = textEditor("a😀\tb")
        val codeLeft = geometry.editor.left + 4 * geometry.font.cellWidth
        val context = IdePointerContext(geometry, editor)

        fixture.adapter.pointerMoved(
            codeLeft + 4.0 * geometry.font.cellWidth,
            geometry.editor.top + 1.0,
            GLFW.GLFW_MOD_CONTROL,
            context,
        )
        fixture.adapter.pointerMoved(
            codeLeft + 6.0 * geometry.font.cellWidth,
            geometry.editor.top + 1.0,
            0,
            context,
        )
        fixture.adapter.pointerMoved(geometry.editor.right + 1.0, geometry.editor.top + 1.0, 0, context)

        assertEquals(
            listOf<IdeCommand>(
                IdeCommand.SourcePointer(4, true),
                IdeCommand.SourcePointer(null, false),
                IdeCommand.SourcePointer(null, false),
            ),
            fixture.commands,
        )
    }

    @Test
    fun `chooser consumes navigation keys before completion and ordinary editor input`() {
        val fixture = fixture()
        val focus = IdeFocusState(IdeFocusArea.Editor, completionVisible = true, declarationChooserVisible = true)

        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_DOWN), focus)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_UP), focus)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_ENTER), focus)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_ESCAPE), focus)

        assertEquals(
            listOf(
                IdeCommand.MoveDeclarationChoice(1),
                IdeCommand.MoveDeclarationChoice(-1),
                IdeCommand.AcceptDeclarationChoice,
                IdeCommand.DismissSemanticInteraction,
            ),
            fixture.commands,
        )
    }

    @Test
    fun `clicking a declaration chooser row selects and accepts its exact index`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
        val editor = chooserEditor()
        val bounds = IdeRect(300, 100, 500, 112)
        val target =
            IdeHitTarget(
                IdeHitAction.DeclarationChoice,
                bounds,
                enabled = true,
                tooltip = null,
                focusGroup = IdeFocusGroup.Page,
                zIndex = 55,
                choiceIndex = 1,
            )

        fixture.adapter.pointerClicked(301.0, 101.0, 0, IdePointerContext(geometry, editor, hitTargets = listOf(target)))

        assertEquals(
            listOf<IdeCommand>(
                IdeCommand.MoveDeclarationChoice(1),
                IdeCommand.AcceptDeclarationChoice,
                IdeCommand.PointerActivity,
            ),
            fixture.commands,
        )
    }

    @Test
    fun `completion consumes its navigation keys when no declaration chooser is open`() {
        val fixture = fixture()
        val focus = IdeFocusState(IdeFocusArea.Editor, completionVisible = true)

        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_DOWN), focus)
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_ESCAPE), focus)

        assertEquals(
            listOf<IdeCommand>(
                IdeCommand.Edit(IdeEditorInput.Move(IdeMoveDirection.Down, false)),
                IdeCommand.DismissCompletion,
            ),
            fixture.commands,
        )
    }

    @Test
    fun `dialog remains modal over declaration chooser`() {
        val fixture = fixture()
        val dialog = IdeDialogState.Confirmation("Delete", "Permanent", 7)
        val focus = IdeFocusState(IdeFocusArea.Editor, declarationChooserVisible = true, dialog = dialog)

        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_ESCAPE), focus)

        assertEquals(listOf<IdeCommand>(IdeCommand.CancelDialog), fixture.commands)
    }

    @Test
    fun `releasing either control key clears semantic link state`() {
        val fixture = fixture()

        assertTrue(fixture.adapter.keyReleased(key(GLFW.GLFW_KEY_LEFT_CONTROL)))
        assertTrue(fixture.adapter.keyReleased(key(GLFW.GLFW_KEY_RIGHT_CONTROL)))

        assertEquals(listOf<IdeCommand>(IdeCommand.ControlReleased, IdeCommand.ControlReleased), fixture.commands)
    }

    @Test
    fun `paste is bounded on UTF-16 and UTF-8 boundaries`() {
        val fixture = fixture(clipboard = "😀абвextra", limits = IdeClientLimits(clipboardCodeUnits = 5, clipboardUtf8Bytes = 8))

        assertTrue(fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_V, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor))

        assertEquals(listOf<IdeCommand>(IdeCommand.Edit(IdeEditorInput.Type("😀аб"))), fixture.commands)
    }

    @Test
    fun `unhandled keys fall through and pointer activity requests eager autosave`() {
        val fixture = fixture()

        assertFalse(fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_F8), IdeFocusState.Editor))
        fixture.adapter.pointerActivity()

        assertEquals(listOf<IdeCommand>(IdeCommand.PointerActivity), fixture.commands)
    }

    @Test
    fun `mouse maps code cells tree rows and start rows to commands`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
        val path = ProjectPath.file("src/main.kt")
        val editor =
            IdeEditorView.Text(
                path,
                listOf("a😀\tb"),
                listOf(0),
                0,
                0,
                1,
                0,
                null,
                null,
                0,
                0,
                false,
                false,
                KotlinLexicalSnapshot(0, emptyList()),
                IdeAnalysisState.Idle,
            )
        val codeLeft = geometry.editor.left + 4 * geometry.font.cellWidth

        fixture.adapter.pointerClicked(
            codeLeft + 2.0 * geometry.font.cellWidth,
            geometry.editor.top + 1.0,
            GLFW.GLFW_MOD_SHIFT,
            IdePointerContext(geometry, editor = editor),
        )
        fixture.adapter.pointerClicked(
            geometry.tree!!.left + 2.0,
            geometry.tree.top + 5.0,
            0,
            IdePointerContext(
                geometry,
                tree = listOf(ProjectTreeEntry(path, ProjectFileKind.Text(1), null)),
            ),
        )
        fixture.adapter.pointerClicked(
            geometry.editor.left + 2.0,
            geometry.editor.top + 7.0,
            0,
            IdePointerContext(geometry, projects = listOf(IdeProjectSummary("demo", "Demo"))),
        )

        assertTrue(fixture.commands.contains(IdeCommand.Edit(IdeEditorInput.SetCaret(3, true))))
        assertTrue(fixture.commands.contains(IdeCommand.OpenFile(path)))
        assertTrue(fixture.commands.contains(IdeCommand.OpenProject("demo")))
    }

    @Test
    fun `wheel routes editor scroll through controller command`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
        val editor =
            IdeEditorView.Text(
                ProjectPath.file("main.kt"),
                listOf("x"),
                listOf(0),
                0,
                0,
                1,
                0,
                null,
                null,
                0,
                0,
                false,
                false,
                KotlinLexicalSnapshot(0, emptyList()),
                IdeAnalysisState.Idle,
            )

        fixture.adapter.scroll(geometry.editor.left + 1.0, geometry.editor.top + 1.0, 1.0, -2.0, IdePointerContext(geometry, editor))

        assertTrue(fixture.commands.contains(IdeCommand.ScrollEditor(6, 4)))
    }

    @Test
    fun `wheel scrolls project tree without sending an editor command`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
        val entries =
            (0 until 80).map { index ->
                ProjectTreeEntry(ProjectPath.file("src/file$index.kt"), ProjectFileKind.Text(1), null)
            }

        assertTrue(
            fixture.adapter.scroll(
                geometry.tree!!.left + 1.0,
                geometry.tree.top + 1.0,
                0.0,
                -2.0,
                IdePointerContext(geometry, tree = entries),
            ),
        )

        assertEquals(6, fixture.adapter.treeFirstRow)
        assertFalse(fixture.commands.any { it is IdeCommand.ScrollEditor })
    }

    @Test
    fun `wheel scroll includes computer explorer rows`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
        val metadata = IdeTargetFileMetadata(IdeTargetFileKind.File, 1, 1, false)
        val rows =
            (0 until 80).map { index ->
                IdeExplorerRow.ComputerEntry(IdeComputerNode.File(IdeTargetVirtualPath.of("/file$index"), metadata), 1)
            }

        fixture.adapter.scroll(
            geometry.tree!!.left + 1.0,
            geometry.tree.top + 1.0,
            0.0,
            -2.0,
            IdePointerContext(geometry, explorer = rows),
        )

        assertEquals(6, fixture.adapter.treeFirstRow)
    }

    @Test
    fun `target toolbar actions dispatch controller commands`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
        val actions = listOf(IdeHitAction.Verify, IdeHitAction.Deploy, IdeHitAction.Run)
        actions.forEachIndexed { index, action ->
            val bounds = IdeRect(index * 20, 0, index * 20 + 18, 18)
            fixture.adapter.pointerClicked(
                bounds.left + 1.0,
                bounds.top + 1.0,
                0,
                IdePointerContext(
                    geometry,
                    hitTargets = listOf(IdeHitTarget(action, bounds, true, null, IdeFocusGroup.Page, 1)),
                ),
            )
        }

        assertTrue(fixture.commands.containsAll(listOf(IdeCommand.Verify, IdeCommand.Deploy, IdeCommand.Run)))
    }

    @Test
    fun `overwrite dialog maps enter and escape to exact target decisions`() {
        val fixture = fixture()
        val dialog = IdeDialogState.TargetOverwrite(IdeDeploymentPath.fromProgramName("demo"), IdeExecutableRevision.Present(2))

        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_ENTER), IdeFocusState(IdeFocusArea.Panel, dialog = dialog))
        fixture.adapter.keyPressed(key(GLFW.GLFW_KEY_ESCAPE), IdeFocusState(IdeFocusArea.Panel, dialog = dialog))

        assertEquals(listOf(IdeCommand.ConfirmTargetDeployment, IdeCommand.CancelTargetDeployment), fixture.commands)
    }

    @Test
    fun `computer drag waits four pixels and drops only on project directories`() {
        val fixture = fixture()
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
        val directory = ProjectTreeEntry(ProjectPath.file("src"), ProjectFileKind.Directory, null)
        val source =
            IdeComputerNode.File(
                IdeTargetVirtualPath.of("/home/a.kt"),
                IdeTargetFileMetadata(IdeTargetFileKind.File, 1, 1, false),
            )
        val rows = listOf(IdeExplorerRow.ProjectEntry(directory), IdeExplorerRow.ComputerEntry(source, 1))
        val context = IdePointerContext(geometry, explorer = rows)
        val tree = geometry.tree!!
        val sourceX = tree.left + 8.0
        val sourceY = tree.top + 4 + 12 + 2.0
        val destinationY = tree.top + 4 + 2.0

        assertTrue(fixture.adapter.explorerPressed(sourceX, sourceY, 0, context))
        assertTrue(fixture.adapter.explorerDragged(sourceX + 3, sourceY, context))
        assertFalse(fixture.adapter.explorerDragActive)
        assertTrue(fixture.adapter.explorerReleased(sourceX + 3, sourceY, 0, context))
        assertTrue(fixture.commands.contains(IdeCommand.OpenComputerFile(source.path)))

        fixture.commands.clear()
        fixture.adapter.explorerPressed(sourceX, sourceY, 0, context)
        fixture.adapter.explorerDragged(sourceX, destinationY, context)
        assertTrue(fixture.adapter.explorerDragActive)
        fixture.adapter.explorerReleased(sourceX, destinationY, 0, context)
        assertEquals(listOf(IdeCommand.DropComputerEntry(source.path, directory.path), IdeCommand.PointerActivity), fixture.commands)

        val projectFile = ProjectTreeEntry(ProjectPath.file("main.kt"), ProjectFileKind.Text(0), null)
        val fileContext =
            IdePointerContext(
                geometry,
                explorer = listOf(IdeExplorerRow.ProjectEntry(projectFile), IdeExplorerRow.ComputerEntry(source, 1)),
            )
        fixture.commands.clear()
        fixture.adapter.explorerPressed(sourceX, sourceY, 0, fileContext)
        fixture.adapter.explorerDragged(sourceX, destinationY, fileContext)
        fixture.adapter.explorerReleased(sourceX, destinationY, 0, fileContext)
        assertTrue(fixture.commands.isEmpty())
    }

    private fun fixture(
        clipboard: String = "",
        limits: IdeClientLimits = IdeClientLimits(),
    ): Fixture {
        val commands = mutableListOf<IdeCommand>()
        return Fixture(commands, IdeInputAdapter(commands::add, IdeClipboard { clipboard }, limits))
    }

    private fun key(
        key: Int,
        modifiers: Int = 0,
    ) = KeyEvent(key, 0, modifiers)

    private fun textEditor(text: String) =
        IdeEditorView.Text(
            ProjectPath.file("src/main.kt"),
            listOf(text),
            listOf(0),
            0,
            0,
            1,
            0,
            null,
            null,
            0,
            0,
            false,
            false,
            KotlinLexicalSnapshot(0, emptyList()),
            IdeAnalysisState.Idle,
        )

    private fun chooserEditor(): IdeEditorView.Text {
        val source = "val answer = sample"
        val identity = AnalysisSnapshotIdentity(SourceSnapshotId(Hash256.zero()), AnalysisProfileIdentity(Hash256.zero()))
        val path = VirtualSourcePath.kotlin("src/main.kt")
        val anchor = IdeSemanticAnchor(identity, path, 0, 6, EditorRange(4, 10))
        val chooser =
            IdeSemanticInteraction.Chooser(
                anchor,
                listOf(
                    IdeDeclarationTarget.Project(ProjectPath.file("src/One.kt"), EditorRange(0, 3)),
                    IdeDeclarationTarget.Project(ProjectPath.file("src/Two.kt"), EditorRange(0, 3)),
                ),
                selectedIndex = 0,
                maximumTargets = 64,
            )
        return IdeEditorView.Text(
            ProjectPath.file(path.value),
            listOf(source),
            listOf(0),
            0,
            0,
            1,
            0,
            null,
            null,
            0,
            0,
            false,
            false,
            KotlinLexicalSnapshot(0, emptyList()),
            IdeAnalysisState.Active(identity, path, 0, IdeAnalysisPresentation.Empty, null, chooser),
        )
    }

    private data class Fixture(
        val commands: MutableList<IdeCommand>,
        val adapter: IdeInputAdapter,
    )
}
