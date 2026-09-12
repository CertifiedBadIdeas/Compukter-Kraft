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

package ru.lazyhat.compukters.impl.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TerminalScreenFocusTest {
    @Test
    fun `terminal screen owns initial focus policy`() {
        val initialFocus =
            TerminalScreen::class.java.declaredMethods.singleOrNull {
                it.name == "setInitialFocus" && it.parameterCount == 0
            }

        assertNotNull(initialFocus, "TerminalScreen must override Screen's automatic initial widget focus")
    }

    @Test
    fun `terminal screen owns mouse interaction focus policy`() {
        val mouseClicked =
            TerminalScreen::class.java.declaredMethods.singleOrNull {
                it.name == "mouseClicked" && it.parameterCount == 2
            }

        assertNotNull(mouseClicked, "TerminalScreen must clear widget focus after mouse interaction")
    }

    @Test
    fun `terminal screen owns IDE opening action`() {
        val openIde =
            TerminalScreen::class.java.declaredMethods.singleOrNull {
                it.name == "openIde" && it.parameterCount == 0
            }

        assertNotNull(openIde, "TerminalScreen must expose the IDE button through its own action")
    }

    @Test
    fun `terminal screen repositions the complete toolbar after a font change`() {
        val buttonFields =
            TerminalScreen::class.java.declaredFields
                .filter { it.type.name == "net.minecraft.client.gui.components.Button" }
                .map { it.name }
                .toSet()
        val positionToolbarButtons =
            TerminalScreen::class.java.declaredMethods.singleOrNull {
                it.name == "positionToolbarButtons" && it.parameterCount == 0
            }

        assertEquals(setOf("ideButton", "fontButton"), buttonFields)
        assertNotNull(positionToolbarButtons, "TerminalScreen must reposition both title-row buttons together")
    }
}
