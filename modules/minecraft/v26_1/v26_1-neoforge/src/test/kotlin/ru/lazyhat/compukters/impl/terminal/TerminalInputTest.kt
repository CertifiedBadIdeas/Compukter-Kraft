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

package ru.lazyhat.compukters.impl.terminal

import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalInputTest {
    @Test
    fun `control keys require control while navigation keys do not`() {
        assertEquals(TerminalKey.S, TerminalInput.key(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL))
        assertEquals(TerminalKey.X, TerminalInput.key(GLFW.GLFW_KEY_X, GLFW.GLFW_MOD_CONTROL))
        assertNull(TerminalInput.key(GLFW.GLFW_KEY_S, 0))
        assertEquals(TerminalKey.LEFT, TerminalInput.key(GLFW.GLFW_KEY_LEFT, 0))
    }

    @Test
    fun `modifiers preserve every supported glfw bit`() {
        val bits =
            GLFW.GLFW_MOD_SHIFT or
                GLFW.GLFW_MOD_CONTROL or
                GLFW.GLFW_MOD_ALT or
                GLFW.GLFW_MOD_SUPER

        assertEquals(
            setOf(
                TerminalModifier.SHIFT,
                TerminalModifier.CONTROL,
                TerminalModifier.ALT,
                TerminalModifier.SUPER,
            ),
            TerminalInput.modifiers(bits),
        )
    }

    @Test
    fun `bounded text replaces isolated surrogates and never splits a valid pair`() {
        assertEquals("A\uFFFDB", TerminalInput.boundedText("A\uD800B", 3))
        assertEquals("😀", TerminalInput.boundedText("😀x", 2))
        assertEquals("", TerminalInput.boundedText("😀", 1))
    }
}
