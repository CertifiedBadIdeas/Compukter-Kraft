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

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component

internal class IdeEditorKeyboardFocus(
    font: Font,
) : EditBox(font, 0, 0, 1, 1, Component.empty()) {
    init {
        setBordered(false)
        setEditable(false)
        setFocused(true)
    }

    fun resolve(
        area: IdeFocusArea,
        fallback: GuiEventListener?,
    ): GuiEventListener? = if (area == IdeFocusArea.Editor) this else fallback

    override fun canConsumeInput(): Boolean = true

    override fun keyPressed(event: KeyEvent): Boolean = false

    override fun charTyped(event: CharacterEvent): Boolean = false
}
