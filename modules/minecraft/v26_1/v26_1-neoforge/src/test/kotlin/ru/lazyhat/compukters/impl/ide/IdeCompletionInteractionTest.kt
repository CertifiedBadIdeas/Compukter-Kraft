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

import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeMoveDirection
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import kotlin.test.Test
import kotlin.test.assertEquals

class IdeCompletionInteractionTest {
    @Test
    fun `completion owns navigation acceptance and escape before editor`() {
        val commands = mutableListOf<IdeCommand>()
        val adapter = IdeInputAdapter(commands::add, IdeClipboard { "" }, IdeClientLimits())
        val focus = IdeFocusState.Editor.copy(completionVisible = true)

        adapter.keyPressed(KeyEvent(GLFW.GLFW_KEY_DOWN, 0, 0), focus)
        adapter.keyPressed(KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0), focus)
        adapter.keyPressed(KeyEvent(GLFW.GLFW_KEY_TAB, 0, 0), focus)
        adapter.keyPressed(KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0), focus)

        assertEquals(
            listOf<IdeCommand>(
                IdeCommand.Edit(IdeEditorInput.Move(IdeMoveDirection.Down, false)),
                IdeCommand.Edit(IdeEditorInput.Enter),
                IdeCommand.Edit(IdeEditorInput.Tab),
                IdeCommand.DismissCompletion,
            ),
            commands,
        )
    }

    @Test
    fun `dialog consumes escape and confirmation before completion`() {
        val commands = mutableListOf<IdeCommand>()
        val adapter = IdeInputAdapter(commands::add, IdeClipboard { "" }, IdeClientLimits())
        val focus =
            IdeFocusState.Editor.copy(
                completionVisible = true,
                dialog = IdeDialogState.Confirmation("Delete", "Permanent", 17),
            )

        adapter.keyPressed(KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0), focus)
        adapter.keyPressed(KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0), focus)

        assertEquals(listOf(IdeCommand.ConfirmDialog(17), IdeCommand.CancelDialog), commands)
    }

    @Test
    fun `tab remains indentation when completion is absent`() {
        val commands = mutableListOf<IdeCommand>()
        val adapter = IdeInputAdapter(commands::add, IdeClipboard { "" }, IdeClientLimits())

        adapter.keyPressed(KeyEvent(GLFW.GLFW_KEY_TAB, 0, 0), IdeFocusState.Editor)

        assertEquals(listOf<IdeCommand>(IdeCommand.Edit(IdeEditorInput.Tab)), commands)
    }

    @Test
    fun `conflict confirmation reloads an open editor or discards while closing`() {
        val commands = mutableListOf<IdeCommand>()
        val adapter = IdeInputAdapter(commands::add, IdeClipboard { "" }, IdeClientLimits())
        val path = ProjectPath.file("src/main.kt")

        adapter.keyPressed(
            KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0),
            IdeFocusState.Editor.copy(dialog = IdeDialogState.FileConflict(path, closing = false)),
        )
        adapter.keyPressed(
            KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0),
            IdeFocusState.Editor.copy(dialog = IdeDialogState.FileConflict(path, closing = true)),
        )

        assertEquals<List<IdeCommand>>(
            listOf(
                IdeCommand.ResolveConflict(ru.lazyhat.compukters.ide.client.state.IdeConflictAction.ReloadFromDisk),
                IdeCommand.ResolveConflict(ru.lazyhat.compukters.ide.client.state.IdeConflictAction.DiscardAndClose),
            ),
            commands,
        )
    }
}
