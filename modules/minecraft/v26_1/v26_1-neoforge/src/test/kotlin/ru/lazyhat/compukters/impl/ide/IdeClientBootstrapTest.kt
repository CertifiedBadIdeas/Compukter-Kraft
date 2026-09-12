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
import net.neoforged.neoforge.client.settings.KeyModifier
import org.lwjgl.glfw.GLFW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeClientBootstrapTest {
    @Test
    fun `IDE key mapping defaults to configurable control I`() {
        val mapping = IdeClientBootstrap.openIde

        assertEquals(GLFW.GLFW_KEY_I, mapping.defaultKey.value)
        assertEquals(KeyModifier.CONTROL, mapping.defaultKeyModifier)
        assertEquals("key.compukters.open_ide", mapping.name)
    }

    @Test
    fun `screen shortcut requires the configured key and active modifier`() {
        val controlI = KeyEvent(GLFW.GLFW_KEY_I, 0, GLFW.GLFW_MOD_CONTROL)
        val controlK = KeyEvent(GLFW.GLFW_KEY_K, 0, GLFW.GLFW_MOD_CONTROL)

        assertTrue(IdeClientBootstrap.matchesScreenShortcut(controlI, modifierActive = true))
        assertFalse(IdeClientBootstrap.matchesScreenShortcut(controlI, modifierActive = false))
        assertFalse(IdeClientBootstrap.matchesScreenShortcut(controlK, modifierActive = true))
    }

    @Test
    fun `terminal target attaches before the parent observation is suspended`() {
        val events = mutableListOf<String>()

        IdeOpeningHandoff.open(
            createSession = {
                events += "open"
                "session"
            },
            attachTarget = { session ->
                assertEquals("session", session)
                events += "attach"
            },
            suspendParent = {
                events += "suspend"
                "parent"
            },
            installScreen = { session, parent ->
                assertEquals("session", session)
                assertEquals("parent", parent)
                events += "install"
            },
            closeSession = { events += "close" },
            resumeParent = { events += "resume" },
        )

        assertEquals(listOf("open", "attach", "suspend", "install"), events)
    }
}
