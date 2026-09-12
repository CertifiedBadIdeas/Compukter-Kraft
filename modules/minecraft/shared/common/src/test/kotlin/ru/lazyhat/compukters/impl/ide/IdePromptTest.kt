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

import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IdePromptTest {
    @Test
    fun `project prompt rejects blank names and emits create command`() {
        val prompt = IdePromptController()
        prompt.open(IdePromptKind.CreateProject, "   ")

        assertNull(prompt.confirm())
        assertNotNull(prompt.state?.error)

        prompt.type("demo")
        assertEquals(IdeCommand.CreateProject("demo"), prompt.confirm())
        assertNull(prompt.state)
    }

    @Test
    fun `rename prompt keeps source and removes supplementary characters atomically`() {
        val prompt = IdePromptController()
        val source = ProjectPath.file("src/main.kt")
        prompt.open(IdePromptKind.Rename(source), "src/new😀")

        prompt.backspace()

        assertEquals("src/new", prompt.state?.value)
        assertEquals(IdeCommand.Rename(source, ProjectPath.file("src/new")), prompt.confirm())
    }

    @Test
    fun `prompt never truncates through a surrogate pair`() {
        val prompt = IdePromptController()
        prompt.open(IdePromptKind.CreateText, "a".repeat(255))

        prompt.type("😀")

        assertEquals("a".repeat(255), prompt.state?.value)
    }
}
