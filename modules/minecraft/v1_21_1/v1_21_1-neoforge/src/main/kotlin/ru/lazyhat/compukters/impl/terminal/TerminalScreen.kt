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
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.impl.config.CompuktersClientConfig
import ru.lazyhat.compukters.impl.ide.ChildScreenParent
import ru.lazyhat.compukters.impl.ide.IdeClientBootstrap
import ru.lazyhat.compukters.impl.ui.CompuktersUiViewport
import ru.lazyhat.compukters.impl.ui.withTransform
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction

internal class TerminalScreen(
    initial: TerminalFullPayload,
    private val transport: TerminalScreenTransport = ProductionTerminalScreenTransport,
) : Screen(Component.literal("Compukters terminal")),
    ChildScreenParent {
    val position = initial.position
    internal var machineId: Long = initial.machineId
        private set

    private val replica = TerminalReplica(initial.state)
    private val resourceReplica = TerminalResourceReplica(initial.machineId)
    internal val resourceGauges: TerminalResourceGauges
        get() = resourceReplica.gauges
    private val pressedKeys = mutableSetOf<Int>()
    private var fontProfile = CompuktersClientConfig.selectedFont()
    private lateinit var ideButton: Button
    private lateinit var fontButton: Button
    private val childLifecycle =
        TerminalChildLifecycle(
            transport::connectionIdentity,
            transport::connected,
            { transport.send(TerminalClosePayload(position, machineId)) },
            ::requestResync,
        )

    override fun init() {
        super.init()
        val viewport = viewport()
        if (!viewport.supported) return
        val geometry = TerminalRenderGeometry(viewport.width, viewport.height, fontProfile)
        val ideBounds = geometry.ideButton
        ideButton =
            addRenderableWidget(
                Button
                    .builder(Component.literal("IDE  Ctrl+I")) { openIde() }
                    .bounds(ideBounds.left, ideBounds.top, ideBounds.width, ideBounds.height)
                    .build(),
            )
        val fontBounds = geometry.fontButton
        fontButton =
            addRenderableWidget(
                Button
                    .builder(fontButtonLabel()) { cycleFont() }
                    .bounds(fontBounds.left, fontBounds.top, fontBounds.width, fontBounds.height)
                    .build(),
            )
    }

    override fun setInitialFocus() = Unit

    override fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val handled = super.mouseClicked(viewport.toVirtualX(mouseX), viewport.toVirtualY(mouseY), button)
        if (handled) clearFocus()
        return handled
    }

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
        transport.send(TerminalResyncPayload(position, machineId, replica.state.revision))
    }

    override fun suspendForChild(): Screen {
        childLifecycle.suspend()
        pressedKeys.clear()
        return this
    }

    override fun resumeFromChild(): Boolean = childLifecycle.resume()

    override fun abandonChild() {
        childLifecycle.abandon()
        pressedKeys.clear()
    }

    override fun removed() {
        if (!childLifecycle.suspended) transport.send(TerminalClosePayload(position, machineId))
        pressedKeys.clear()
        super.removed()
    }

    override fun keyPressed(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        if (childLifecycle.suspended) return true
        if (!viewport().supported) return if (keyCode == GLFW.GLFW_KEY_ESCAPE) super.keyPressed(keyCode, scanCode, modifiers) else true
        if (Screen.isPaste(keyCode)) {
            val pasted = TerminalInput.boundedText(minecraft?.keyboardHandler?.clipboard.orEmpty())
            if (pasted.isNotEmpty()) sendText(pasted)
            return true
        }
        val key = TerminalInput.key(keyCode, modifiers) ?: return super.keyPressed(keyCode, scanCode, modifiers)
        val action = if (pressedKeys.add(keyCode)) TerminalKeyAction.PRESS else TerminalKeyAction.REPEAT
        transport.send(TerminalKeyPayload(position, machineId, key, action, TerminalInput.modifiers(modifiers)))
        return if (key == TerminalKey.ESCAPE) super.keyPressed(keyCode, scanCode, modifiers) else true
    }

    override fun keyReleased(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        if (childLifecycle.suspended) return true
        if (!viewport().supported) return true
        val mapped = TerminalInput.isMappedKeyCode(keyCode)
        pressedKeys.remove(keyCode)
        return mapped || super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun charTyped(
        codePoint: Char,
        modifiers: Int,
    ): Boolean {
        if (childLifecycle.suspended) return true
        if (!viewport().supported) return true
        if (codePoint.code >= GLFW.GLFW_KEY_SPACE) {
            sendText(codePoint.toString())
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
        val viewport = viewport()
        viewport.withTransform(graphics.pose()) {
            if (!viewport.supported) {
                graphics.drawString(font, UNSUPPORTED_MESSAGE, 4, 4, 0xFFE8E8E8.toInt(), false)
                return@withTransform
            }
            val geometry = TerminalRenderGeometry(viewport.width, viewport.height, fontProfile)
            graphics.fill(
                geometry.panel.left - 1,
                geometry.panel.top - 1,
                geometry.panel.right + 1,
                geometry.panel.bottom + 1,
                0xFF5A5A5A.toInt(),
            )
            graphics.fill(geometry.panel.left, geometry.panel.top, geometry.panel.right, geometry.panel.bottom, 0xFF161616.toInt())
            graphics.drawString(font, title, geometry.titleX, geometry.titleY, 0xFFE8E8E8.toInt(), false)
            TerminalGridRenderer.draw(
                graphics,
                font,
                replica.state,
                fontProfile,
                geometry.gridGeometry,
                viewport,
                System.currentTimeMillis(),
            )
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
            super.render(
                graphics,
                viewport.toVirtualX(mouseX.toDouble()).toInt(),
                viewport.toVirtualY(mouseY.toDouble()).toInt(),
                partialTick,
            )
        }
    }

    override fun renderBackground(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) = Unit

    override fun isPauseScreen(): Boolean = false

    private fun cycleFont() {
        fontProfile = fontProfile.next()
        CompuktersClientConfig.selectFont(fontProfile)
        fontButton.message = fontButtonLabel()
        positionToolbarButtons()
    }

    private fun positionToolbarButtons() {
        if (!::ideButton.isInitialized || !::fontButton.isInitialized) return
        val viewport = viewport()
        if (!viewport.supported) return
        val geometry = TerminalRenderGeometry(viewport.width, viewport.height, fontProfile)
        ideButton.x = geometry.ideButton.left
        ideButton.y = geometry.ideButton.top
        fontButton.x = geometry.fontButton.left
        fontButton.y = geometry.fontButton.top
    }

    private fun fontButtonLabel(): Component = Component.literal("Font: ${fontProfile.displayName}")

    private fun openIde() {
        IdeClientBootstrap.open(requireNotNull(minecraft) { "terminal screen is not attached to a Minecraft client" })
    }

    private fun sendText(text: String) {
        transport.send(TerminalTextPayload(position, machineId, text))
    }

    private fun viewport(): CompuktersUiViewport {
        val window = requireNotNull(minecraft).window
        return CompuktersUiViewport.admit(window.width, window.height, window.guiScale.toInt())
    }

    private companion object {
        val UNSUPPORTED_MESSAGE = Component.literal("Compukters UI requires at least 640x360 pixels")
    }
}

internal interface TerminalScreenTransport {
    fun send(payload: CustomPacketPayload)

    fun connectionIdentity(): Any?

    fun connected(): Boolean
}

private object ProductionTerminalScreenTransport : TerminalScreenTransport {
    override fun send(payload: CustomPacketPayload) {
        PacketDistributor.sendToServer(payload)
    }

    override fun connectionIdentity(): Any? =
        net.minecraft.client.Minecraft
            .getInstance()
            .connection

    override fun connected(): Boolean =
        net.minecraft.client.Minecraft
            .getInstance()
            .connection != null && net.minecraft.client.Minecraft
            .getInstance()
            .level != null
}
