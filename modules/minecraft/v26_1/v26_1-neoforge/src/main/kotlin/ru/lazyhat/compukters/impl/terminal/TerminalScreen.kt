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

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.impl.config.CompuktersClientConfig
import ru.lazyhat.compukters.impl.ide.ChildScreenParent
import ru.lazyhat.compukters.impl.ide.IdeClientBootstrap
import ru.lazyhat.compukters.impl.ui.CompuktersUiViewport
import ru.lazyhat.compukters.impl.ui.map
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
        val bounds = geometry.fontButton
        fontButton =
            addRenderableWidget(
                Button
                    .builder(fontButtonLabel()) { cycleFont() }
                    .bounds(bounds.left, bounds.top, bounds.width, bounds.height)
                    .build(),
            )
    }

    override fun setInitialFocus() = Unit

    override fun mouseClicked(
        event: MouseButtonEvent,
        doubleClick: Boolean,
    ): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val handled = super.mouseClicked(viewport.map(event), doubleClick)
        if (handled) clearFocus()
        return handled
    }

    fun update(payload: TerminalFullPayload): Boolean {
        if (payload.position != position || payload.machineId <= 0) return false
        if (!replica.replace(payload.state)) return false
        resourceReplica.replaceMachine(payload.machineId)
        machineId = payload.machineId
        return true
    }

    fun update(payload: TerminalDeltaPayload): Boolean =
        payload.position == position && payload.machineId == machineId && replica.apply(payload.delta)

    fun update(payload: TerminalResourcePayload): Boolean {
        if (payload.position != position) return false
        return resourceReplica.update(payload.machineId, payload.gauges)
    }

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

    override fun keyPressed(event: KeyEvent): Boolean {
        if (childLifecycle.suspended) return true
        if (!viewport().supported) return if (event.key() == GLFW.GLFW_KEY_ESCAPE) super.keyPressed(event) else true
        if (event.isPaste) {
            val pasted = TerminalInput.boundedText(minecraft.keyboardHandler.clipboard)
            if (pasted.isNotEmpty()) {
                sendText(pasted)
            }
            return true
        }
        val key = TerminalInput.key(event.key(), event.modifiers()) ?: return super.keyPressed(event)
        val action = if (pressedKeys.add(event.key())) TerminalKeyAction.PRESS else TerminalKeyAction.REPEAT
        transport.send(
            TerminalKeyPayload(position, machineId, key, action, TerminalInput.modifiers(event.modifiers())),
        )
        return if (key == TerminalKey.ESCAPE) super.keyPressed(event) else true
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        if (childLifecycle.suspended) return true
        if (!viewport().supported) return true
        val mapped = TerminalInput.isMappedKeyCode(event.key())
        pressedKeys.remove(event.key())
        return mapped || super.keyReleased(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (childLifecycle.suspended) return true
        if (!viewport().supported) return true
        val text = event.codepointAsString()
        sendText(text)
        return true
    }

    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, TerminalScreenStyle.DIM_COLOR)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val viewport = viewport()
        viewport.withTransform(graphics.pose()) {
            if (!viewport.supported) {
                graphics.text(font, TerminalScreenStyle.UNSUPPORTED_MESSAGE, 4, 4, TerminalScreenStyle.TITLE_COLOR, false)
                return@withTransform
            }
            val geometry = TerminalRenderGeometry(viewport.width, viewport.height, fontProfile)
            graphics.fill(
                geometry.panel.left - 1,
                geometry.panel.top - 1,
                geometry.panel.right + 1,
                geometry.panel.bottom + 1,
                TerminalScreenStyle.PANEL_BORDER_COLOR,
            )
            graphics.fill(
                geometry.panel.left,
                geometry.panel.top,
                geometry.panel.right,
                geometry.panel.bottom,
                TerminalScreenStyle.PANEL_COLOR,
            )
            graphics.text(font, title, geometry.titleX, geometry.titleY, TerminalScreenStyle.TITLE_COLOR, false)
            TerminalGridRenderer.draw(
                graphics,
                font,
                replica.state,
                fontProfile,
                geometry.gridGeometry,
                TerminalScreenStyle.animationMillis(),
            )
            val resourceText =
                Component
                    .literal(TerminalResourceText.format(resourceGauges))
                    .withStyle { style -> style.withFont(fontProfile.fontDescription) }
            graphics.text(
                font,
                resourceText,
                geometry.footer.left,
                geometry.footer.top + fontProfile.glyphDrawOffsetY,
                TerminalScreenStyle.RESOURCE_COLOR,
                false,
            )
            super.extractRenderState(
                graphics,
                viewport.toVirtualX(mouseX.toDouble()).toInt(),
                viewport.toVirtualY(mouseY.toDouble()).toInt(),
                partialTick,
            )
        }
    }

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
        IdeClientBootstrap.open(minecraft)
    }

    private fun sendText(text: String) {
        transport.send(TerminalTextPayload(position, machineId, text))
    }

    private fun viewport(): CompuktersUiViewport {
        val window = minecraft.window
        return CompuktersUiViewport.admit(window.width, window.height, window.guiScale)
    }
}

internal interface TerminalScreenTransport {
    fun send(payload: CustomPacketPayload)

    fun connectionIdentity(): Any?

    fun connected(): Boolean
}

private object ProductionTerminalScreenTransport : TerminalScreenTransport {
    override fun send(payload: CustomPacketPayload) {
        ClientPacketDistributor.sendToServer(payload)
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
