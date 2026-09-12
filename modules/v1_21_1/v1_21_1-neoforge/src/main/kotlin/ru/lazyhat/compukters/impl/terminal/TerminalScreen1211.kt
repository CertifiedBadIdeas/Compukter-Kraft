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

@file:Suppress("ktlint:standard:filename")

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.impl.config.CompuktersClientConfig
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction

internal class TerminalScreen(
    initial: TerminalFullPayload,
) : Screen(Component.literal("Compukters terminal")) {
    val position = initial.position
    internal var machineId: Long = initial.machineId
        private set

    private val replica = TerminalReplica(initial.state)
    private val resourceReplica = TerminalResourceReplica(initial.machineId)
    private val pressedKeys = mutableSetOf<Int>()
    private var fontProfile = CompuktersClientConfig.selectedFont()

    fun update(payload: TerminalFullPayload): Boolean {
        if (payload.position != position || payload.machineId <= 0 || !replica.replace(payload.state)) return false
        resourceReplica.replaceMachine(payload.machineId)
        machineId = payload.machineId
        return true
    }

    fun update(payload: TerminalDeltaPayload): Boolean =
        payload.position == position && payload.machineId == machineId && replica.apply(payload.delta)

    fun update(payload: TerminalResourcePayload): Boolean =
        payload.position == position && resourceReplica.update(payload.machineId, payload.gauges)

    fun requestResync() {
        PacketDistributor.sendToServer(TerminalResyncPayload(position, machineId, replica.state.revision))
    }

    override fun removed() {
        PacketDistributor.sendToServer(TerminalClosePayload(position, machineId))
        pressedKeys.clear()
        super.removed()
    }

    override fun keyPressed(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        if (Screen.isPaste(keyCode)) {
            val pasted = TerminalInput.boundedText(minecraft?.keyboardHandler?.clipboard.orEmpty())
            if (pasted.isNotEmpty()) PacketDistributor.sendToServer(TerminalTextPayload(position, machineId, pasted))
            return true
        }
        val key = TerminalInput.key(keyCode, modifiers) ?: return super.keyPressed(keyCode, scanCode, modifiers)
        val action = if (pressedKeys.add(keyCode)) TerminalKeyAction.PRESS else TerminalKeyAction.REPEAT
        PacketDistributor.sendToServer(TerminalKeyPayload(position, machineId, key, action, TerminalInput.modifiers(modifiers)))
        return if (key == TerminalKey.ESCAPE) super.keyPressed(keyCode, scanCode, modifiers) else true
    }

    override fun keyReleased(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        val mapped = TerminalInput.isMappedKeyCode(keyCode)
        pressedKeys.remove(keyCode)
        return mapped || super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun charTyped(
        codePoint: Char,
        modifiers: Int,
    ): Boolean {
        if (codePoint.code >= GLFW.GLFW_KEY_SPACE) {
            PacketDistributor.sendToServer(TerminalTextPayload(position, machineId, codePoint.toString()))
        }
        return true
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, 0xE0101010.toInt())
        val geometry = TerminalRenderGeometry(width, height, fontProfile)
        graphics.fill(
            geometry.panel.left - 1,
            geometry.panel.top - 1,
            geometry.panel.right + 1,
            geometry.panel.bottom + 1,
            0xFF5A5A5A.toInt(),
        )
        graphics.fill(geometry.panel.left, geometry.panel.top, geometry.panel.right, geometry.panel.bottom, 0xFF161616.toInt())
        graphics.drawString(font, title, geometry.titleX, geometry.titleY, 0xFFE8E8E8.toInt(), false)
        TerminalGridRenderer.draw(graphics, font, replica.state, fontProfile, geometry.gridGeometry, System.currentTimeMillis())
        val resources =
            Component
                .literal(TerminalResourceText.format(resourceReplica.gauges))
                .withStyle { it.withFont(fontProfile.fontDescription) }
        graphics.drawString(
            font,
            resources,
            geometry.footer.left,
            geometry.footer.top + fontProfile.glyphDrawOffsetY,
            0xFFB8B8B8.toInt(),
            false,
        )
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun isPauseScreen(): Boolean = false
}
