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
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeProjectSummary
import ru.lazyhat.compukters.ide.client.state.IdeViewState
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.impl.ide.target.IdeTargetReference
import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalClient
import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalTransport
import ru.lazyhat.compukters.impl.ide.target.IdeTerminalKeyPayload
import ru.lazyhat.compukters.impl.ide.target.IdeTerminalOpenedPayload
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IdeScreenFocusTest {
    @Test
    fun `terminal focus captures control shortcuts while editor focus keeps IDE commands`() {
        val transport = RecordingTerminalTransport()
        val terminal = IdeTargetTerminalClient(transport)
        val overlay = IdeTerminalOverlayController(terminal)
        val commands = mutableListOf<IdeCommand>()
        val input = IdeInputAdapter(commands::add, IdeClipboard { "" }, IdeClientLimits())
        overlay.setTarget(IdeTargetReference(IdeTargetId("target"), IdeTargetProfileId(Hash256.zero())))
        overlay.show()
        terminal.accept(IdeTerminalOpenedPayload(1, TOKEN, 7, terminalState()))

        assertTrue(overlay.keyPressed(KeyEvent(GLFW.GLFW_KEY_S, 0, GLFW.GLFW_MOD_CONTROL), ""))
        assertEquals(TerminalKey.S, (transport.sent.last() as IdeTerminalKeyPayload).key)
        assertTrue(commands.isEmpty())

        overlay.focusLost()
        assertTrue(input.keyPressed(KeyEvent(GLFW.GLFW_KEY_S, 0, GLFW.GLFW_MOD_CONTROL), IdeFocusState.Editor))
        assertEquals(listOf<IdeCommand>(IdeCommand.Save), commands)
    }

    @Test
    fun `escape text and paste stay inside a focused visible terminal`() {
        val transport = RecordingTerminalTransport()
        val terminal = IdeTargetTerminalClient(transport)
        val overlay = IdeTerminalOverlayController(terminal)
        overlay.setTarget(IdeTargetReference(IdeTargetId("target"), IdeTargetProfileId(Hash256.zero())))
        overlay.show()
        terminal.accept(IdeTerminalOpenedPayload(1, TOKEN, 7, terminalState()))

        assertTrue(overlay.keyPressed(KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0), ""))
        assertTrue(overlay.charTyped(CharacterEvent('x'.code)))
        assertTrue(overlay.keyPressed(KeyEvent(GLFW.GLFW_KEY_V, 0, GLFW.GLFW_MOD_CONTROL), "paste"))
        assertTrue(overlay.visible)

        overlay.hide()
        assertTrue(!overlay.charTyped(CharacterEvent('y'.code)))
    }

    @Test
    fun `initial IDE focus routes character input to the editor`() {
        val commands = mutableListOf<IdeCommand>()
        val input = IdeInputAdapter(commands::add, IdeClipboard { "" }, IdeClientLimits())

        assertTrue(input.charTyped(CharacterEvent('x'.code), IdeFocusState.Initial))
        assertEquals(listOf<IdeCommand>(IdeCommand.Edit(IdeEditorInput.Type("x"))), commands)
    }

    @Test
    fun `editor keyboard focus is an input rejecting Minecraft text focus`() {
        val font =
            Font(
                Proxy.newProxyInstance(
                    Font.Provider::class.java.classLoader,
                    arrayOf(Font.Provider::class.java),
                ) { _, method, _ -> error("Unexpected test font call: ${method.name}") } as Font.Provider,
            )
        val proxy = IdeEditorKeyboardFocus(font)
        val fallback =
            object : GuiEventListener {
                private var focused = false

                override fun setFocused(focused: Boolean) {
                    this.focused = focused
                }

                override fun isFocused(): Boolean = focused
            }

        assertTrue(proxy.canConsumeInput())
        assertSame(proxy, proxy.resolve(IdeFocusArea.Editor, fallback))
        assertSame(fallback, proxy.resolve(IdeFocusArea.Tree, fallback))
        assertFalse(proxy.keyPressed(KeyEvent(GLFW.GLFW_KEY_B, 0, GLFW.GLFW_MOD_CONTROL)))
        assertFalse(proxy.charTyped(CharacterEvent('x'.code)))
        assertEquals("", proxy.value)
    }

    @Test
    fun `IDE screen owns mouse focus policy`() {
        assertNotNull(IdeScreen::class.java.declaredMethods.singleOrNull { it.name == "mouseClicked" && it.parameterCount == 2 })
        assertNotNull(IdeScreen::class.java.declaredMethods.singleOrNull { it.name == "mouseMoved" && it.parameterCount == 2 })
        assertNotNull(IdeScreen::class.java.declaredMethods.singleOrNull { it.name == "getFocused" && it.parameterCount == 0 })
        assertTrue(
            IdeScreen::class.java.declaredFields.any { it.type == IdeEditorKeyboardFocus::class.java },
        )
    }

    @Test
    fun `dialog is modal and drawn above ordinary actions`() {
        val base = IdeViewState.startPage(listOf(IdeProjectSummary("demo", "Demo")))
        val state = base.copy(dialog = IdeDialogState.Confirmation("Delete", "Permanent", 7))
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)

        val model = IdeRenderer.extract(state, geometry)
        val dialogTargets = model.hitTargets.filter { it.focusGroup == IdeFocusGroup.Dialog }
        val ordinaryTargets = model.hitTargets.filter { it.focusGroup == IdeFocusGroup.Page }

        assertTrue(dialogTargets.isNotEmpty())
        assertTrue(ordinaryTargets.all { !it.enabled })
        assertTrue(dialogTargets.minOf { it.zIndex } > ordinaryTargets.maxOf { it.zIndex })
        assertTrue(model.text.filter { it.kind == IdeTextKind.Dialog }.any { it.value == "Permanent" })
    }

    @Test
    fun `terminal render layer stays below modal scrim and dialog`() {
        val rendered = mutableListOf<String>()
        val state =
            IdeViewState
                .startPage(listOf(IdeProjectSummary("demo", "Demo")))
                .copy(dialog = IdeDialogState.Confirmation("Delete", "Permanent", 7))
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
        val model = IdeRenderer.extract(state, geometry)

        executeIdeRenderOperations(
            operations =
                mutableListOf(
                    IdeRenderOperation(model.panels.single { it.kind == IdePanelKind.Main }.zIndex) { rendered += "workspace" },
                    IdeRenderOperation(model.fills.single { it.kind == IdeFillKind.DialogScrim }.zIndex) { rendered += "scrim" },
                    IdeRenderOperation(model.panels.single { it.kind == IdePanelKind.Dialog }.zIndex) { rendered += "dialog" },
                ),
            terminalVisible = true,
            renderTerminal = { rendered += "terminal" },
        )

        assertEquals(listOf("workspace", "terminal", "scrim", "dialog"), rendered)
    }

    private class RecordingTerminalTransport : IdeTargetTerminalTransport {
        val sent = mutableListOf<CustomPacketPayload>()

        override fun send(payload: CustomPacketPayload) {
            sent += payload
        }
    }

    private companion object {
        val TOKEN: UUID = UUID.fromString("d3354610-5460-4546-8546-000000000001")

        fun terminalState() =
            TerminalState(
                1,
                51,
                19,
                List(51 * 19) { TerminalCell(' '.code, 15, 0) },
                TerminalPosition(0, 0),
                true,
            )
    }
}
