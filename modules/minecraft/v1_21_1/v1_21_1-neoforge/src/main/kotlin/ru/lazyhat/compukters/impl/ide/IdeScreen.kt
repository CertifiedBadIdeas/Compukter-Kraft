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

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticInteraction
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeTargetState
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.impl.config.CompuktersClientConfig
import ru.lazyhat.compukters.impl.ide.target.IdeTargetReference
import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalState
import ru.lazyhat.compukters.impl.terminal.TerminalGridGeometry
import ru.lazyhat.compukters.impl.terminal.TerminalGridRenderer
import ru.lazyhat.compukters.impl.terminal.fontDescription
import ru.lazyhat.compukters.impl.ui.CompuktersUiViewport
import ru.lazyhat.compukters.impl.ui.withTransform
import kotlin.math.ceil
import kotlin.math.floor

internal class IdeRenderOperation(
    val zIndex: Int,
    val draw: () -> Unit,
)

internal fun executeIdeRenderOperations(
    operations: MutableList<IdeRenderOperation>,
    terminalVisible: Boolean,
    renderTerminal: () -> Unit,
) {
    if (terminalVisible) operations += IdeRenderOperation(IDE_TERMINAL_OVERLAY_Z, renderTerminal)
    operations.sortBy(IdeRenderOperation::zIndex)
    operations.forEach { it.draw() }
}

internal fun <T> withIdeTextTransform(
    pose: PoseStack,
    rotation: IdeTextRotation,
    x: Int,
    y: Int,
    draw: () -> T,
): T {
    if (rotation == IdeTextRotation.None) return draw()
    pose.pushPose()
    return try {
        pose.translate(x.toFloat(), y.toFloat(), 0f)
        pose.mulPose(Axis.ZP.rotationDegrees(CLOCKWISE_QUARTER_TURN_DEGREES))
        draw()
    } finally {
        pose.popPose()
    }
}

private const val IDE_TERMINAL_OVERLAY_Z = 80
private val IDE_TOOLBAR_ICON_TEXTURE = ResourceLocation.fromNamespaceAndPath("compukters", "textures/gui/ide_toolbar.png")

internal class IdeScreen(
    private val session: IdeClientSession<IdeClientApplication>,
    private val parent: Screen?,
) : Screen(Component.literal("Compukters IDE")) {
    private val application = session.application
    private lateinit var editorKeyboardFocus: IdeEditorKeyboardFocus
    private val prompt = IdePromptController()
    private val input =
        IdeInputAdapter(
            application.controller::dispatch,
            IdeClipboard { client().keyboardHandler.clipboard },
            IdeClientLimits(),
            IdeUiActionSink(::activateUiAction),
            IdeClipboardWriter { client().keyboardHandler.clipboard = it },
            IdeSelectionSource(application.controller::selectedText),
        )
    private val splitters = IdeSplitterInteraction(application.preferences.layout(), application.preferences::saveLayout)
    private val terminalOverlay = IdeTerminalOverlayController(application.targetTerminal)
    private var focusArea = IdeFocusState.Initial.area
    private var selectedTreePath: ProjectPath? = null
    private var returningToParent = false
    private var sessionClosed = false
    private var controlDown = false
    private var pointerX: Double? = null
    private var pointerY: Double? = null
    private var projectSwitcherOpen = false
    private var previousClick = PointerClick.None

    override fun init() {
        super.init()
        editorKeyboardFocus = IdeEditorKeyboardFocus(font)
        client().textureManager.getTexture(IDE_TOOLBAR_ICON_TEXTURE).setFilter(true, false)
    }

    override fun setInitialFocus() = Unit

    override fun getFocused(): GuiEventListener? = editorKeyboardFocus.resolve(focusArea, super.getFocused())

    override fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val uiX = viewport.toVirtualX(mouseX)
        val uiY = viewport.toVirtualY(mouseY)
        val modifiers = currentModifiers()
        val doubleClick = isDoubleClick(uiX, uiY, button)
        val geometry = geometry()
        val state = application.controller.viewState()
        if (prompt.state != null || state.dialog != null) {
            input.pointerClicked(uiX, uiY, modifiers, pointerContext(geometry), doubleClick)
            clearFocus()
            return true
        }
        if (projectSwitcherOpen) {
            val switcherContext = pointerContext(geometry)
            val switcherAction =
                switcherContext.hitTargets
                    .asReversed()
                    .firstOrNull { it.enabled && it.bounds.contains(uiX, uiY) }
                    ?.action
            val outsideSwitcher =
                switcherAction != IdeHitAction.ProjectSwitcher &&
                    switcherAction != IdeHitAction.ProjectChoice &&
                    switcherAction != IdeHitAction.CreateProject
            if (outsideSwitcher) {
                projectSwitcherOpen = false
                input.pointerActivity()
                return true
            }
            input.pointerClicked(uiX, uiY, modifiers, switcherContext, doubleClick)
            if (switcherAction == IdeHitAction.ProjectChoice) projectSwitcherOpen = false
            focusArea = IdeFocusArea.Panel
            clearFocus()
            terminalOverlay.focusLost()
            return true
        }
        val overlay = terminalOverlayGeometry(geometry)
        if (terminalOverlay.visible && overlay.panel.contains(uiX, uiY)) {
            focusArea = IdeFocusArea.Terminal
            terminalOverlay.focus()
            if (overlay.title.contains(uiX, uiY)) terminalOverlay.retry()
            clearFocus()
            input.pointerActivity()
            return true
        }
        if (splitters.press(uiX.toInt(), uiY.toInt(), geometry)) {
            input.pointerActivity()
            return true
        }
        val pointerContext = pointerContext(geometry)
        if (input.explorerPressed(uiX, uiY, modifiers, pointerContext)) {
            focusArea = IdeFocusArea.Tree
            selectedTreePath = null
            clearFocus()
            terminalOverlay.focusLost()
            return true
        }
        selectTreeRow(uiX, uiY, geometry)
        val hitAction =
            pointerContext.hitTargets
                .asReversed()
                .firstOrNull { it.enabled && it.bounds.contains(uiX, uiY) }
                ?.action
        if (input.pointerClicked(uiX, uiY, modifiers, pointerContext, doubleClick)) {
            if (hitAction == IdeHitAction.ProjectChoice) projectSwitcherOpen = false
            focusArea =
                when {
                    hitAction == IdeHitAction.Terminal && terminalOverlay.visible -> IdeFocusArea.Terminal
                    geometry.editor.contains(uiX, uiY) -> IdeFocusArea.Editor
                    geometry.tree?.contains(uiX, uiY) == true -> IdeFocusArea.Tree
                    else -> IdeFocusArea.Panel
                }
            clearFocus()
            if (focusArea == IdeFocusArea.Terminal) terminalOverlay.focus() else terminalOverlay.focusLost()
            return true
        }
        val handled = super.mouseClicked(uiX, uiY, button)
        if (handled) clearFocus()
        focusArea =
            when {
                geometry.editor.contains(uiX, uiY) -> IdeFocusArea.Editor
                geometry.tree?.contains(uiX, uiY) == true -> IdeFocusArea.Tree
                geometry.panel.contains(uiX, uiY) -> IdeFocusArea.Panel
                else -> IdeFocusArea.None
            }
        terminalOverlay.focusLost()
        input.pointerActivity()
        return handled || focusArea != IdeFocusArea.None
    }

    override fun mouseDragged(
        mouseX: Double,
        mouseY: Double,
        button: Int,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val uiX = viewport.toVirtualX(mouseX)
        val uiY = viewport.toVirtualY(mouseY)
        val geometry = geometry()
        if (terminalOverlay.visible && terminalOverlayGeometry(geometry).panel.contains(uiX, uiY)) return true
        if (splitters.drag(uiX.toInt(), uiY.toInt(), geometry)) return true
        if (input.explorerDragged(uiX, uiY, pointerContext(geometry))) return true
        if (focusArea == IdeFocusArea.Editor) {
            return input.pointerClicked(
                uiX,
                uiY,
                currentModifiers() or GLFW.GLFW_MOD_SHIFT,
                pointerContext(geometry),
            )
        }
        return super.mouseDragged(uiX, uiY, button, viewport.toVirtualDelta(dragX), viewport.toVirtualDelta(dragY))
    }

    override fun mouseReleased(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val uiX = viewport.toVirtualX(mouseX)
        val uiY = viewport.toVirtualY(mouseY)
        if (splitters.release()) return true
        if (input.explorerReleased(uiX, uiY, currentModifiers(), pointerContext(geometry()))) return true
        return super.mouseReleased(uiX, uiY, button)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val uiX = viewport.toVirtualX(mouseX)
        val uiY = viewport.toVirtualY(mouseY)
        val geometry = geometry()
        if (terminalOverlay.visible && terminalOverlayGeometry(geometry).panel.contains(uiX, uiY)) return true
        return input.scroll(uiX, uiY, scrollX, scrollY, pointerContext(geometry)) ||
            super.mouseScrolled(uiX, uiY, scrollX, scrollY)
    }

    override fun mouseMoved(
        mouseX: Double,
        mouseY: Double,
    ) {
        val viewport = viewport()
        if (viewport.supported) {
            val uiX = viewport.toVirtualX(mouseX)
            val uiY = viewport.toVirtualY(mouseY)
            pointerX = uiX
            pointerY = uiY
            val modifiers = if (controlDown) GLFW.GLFW_MOD_CONTROL else 0
            input.pointerMoved(
                uiX,
                uiY,
                modifiers,
                pointerContext(geometry()),
            )
        }
        super.mouseMoved(mouseX, mouseY)
    }

    override fun keyPressed(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        if (
            keyCode == GLFW.GLFW_KEY_LEFT_CONTROL || keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL ||
            modifiers and GLFW.GLFW_MOD_CONTROL != 0
        ) {
            controlDown = true
        }
        if (!viewport().supported) {
            return if (keyCode == GLFW.GLFW_KEY_ESCAPE) super.keyPressed(keyCode, scanCode, modifiers) else true
        }
        val inputEvent = IdeKeyInput(keyCode, modifiers, Screen.isPaste(keyCode))
        if (prompt.state != null) {
            return when {
                keyCode == GLFW.GLFW_KEY_ESCAPE -> prompt.cancel()
                keyCode == GLFW.GLFW_KEY_ENTER -> confirmPrompt()
                keyCode == GLFW.GLFW_KEY_BACKSPACE -> prompt.backspace()
                inputEvent.paste -> prompt.type(client().keyboardHandler.clipboard)
                else -> true
            }
        }
        if (application.controller.viewState().dialog != null) return input.keyPressed(inputEvent, focusState())
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && projectSwitcherOpen) {
            projectSwitcherOpen = false
            return true
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && input.cancelExplorerDrag()) return true
        if (splitters.captured) return true
        if (focusArea == IdeFocusArea.Terminal && terminalOverlay.keyPressed(inputEvent, client().keyboardHandler.clipboard)) {
            return true
        }
        if (
            focusArea == IdeFocusArea.Editor &&
            (keyCode == GLFW.GLFW_KEY_LEFT_CONTROL || keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL)
        ) {
            val x = pointerX
            val y = pointerY
            if (x != null && y != null) input.pointerMoved(x, y, GLFW.GLFW_MOD_CONTROL, pointerContext(geometry()))
        }
        return input.keyPressed(inputEvent, focusState()) || super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun keyReleased(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        if (keyCode == GLFW.GLFW_KEY_LEFT_CONTROL || keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL) controlDown = false
        if (!viewport().supported) return true
        val semanticHandled = input.keyReleased(IdeKeyInput(keyCode, modifiers))
        if (focusArea == IdeFocusArea.Terminal && terminalOverlay.keyReleased(keyCode)) return true
        return semanticHandled || super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun charTyped(
        codePoint: Char,
        modifiers: Int,
    ): Boolean {
        if (!viewport().supported) return true
        val inputEvent = IdeCharacterInput(codePoint.toString())
        if (prompt.state != null) return prompt.type(inputEvent.text)
        if (focusArea == IdeFocusArea.Terminal && terminalOverlay.charTyped(inputEvent)) return true
        return input.charTyped(inputEvent, focusState()) || super.charTyped(codePoint, modifiers)
    }

    override fun removed() {
        input.cancelExplorerDrag()
        splitters.focusLost()
        terminalOverlay.focusLost()
        if (!sessionClosed) application.controller.dispatch(IdeCommand.EditorFocusLost)
        if (!returningToParent) {
            (parent as? ChildScreenParent)?.abandonChild()
            closeSession()
        }
        super.removed()
    }

    override fun tick() {
        application.controller.tick()
        terminalOverlay.setTarget(
            application.controller
                .viewState()
                .target
                .terminalReference(),
        )
        if (application.controller.isCloseReady()) restoreParent()
        super.tick()
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, IdeColors.DIM)
        val viewport = viewport()
        viewport.withTransform(graphics.pose()) {
            if (!viewport.supported) {
                graphics.drawString(font, UNSUPPORTED_MESSAGE, 4, 4, TERMINAL_TEXT, false)
                return@withTransform
            }
            renderSupported(graphics, mouseX, mouseY, partialTick, viewport)
        }
    }

    private fun renderSupported(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
        viewport: CompuktersUiViewport,
    ) {
        val profile = CompuktersClientConfig.selectedFont()
        val geometry = geometry(profile, viewport)
        val treeFirstRow = admittedTreeFirstRow(geometry)
        val state = application.controller.viewState()
        val model =
            IdeRenderer.extract(
                state,
                geometry,
                prompt = prompt.state,
                treeFirstRow = treeFirstRow,
                selectedTreePath = selectedTreePath,
                terminalState = terminalOverlay.state().presentationStatus(),
                terminalVisible = terminalOverlay.visible,
                explorerDrag = input.explorerDragVisual,
                projectSwitcherOpen = projectSwitcherOpen && prompt.state == null && state.dialog == null,
                pointerX = viewport.toVirtualX(mouseX.toDouble()).toInt(),
                pointerY = viewport.toVirtualY(mouseY.toDouble()).toInt(),
            )
        IdeVisibleFrameEvidence.from(state, model)?.let { evidence ->
            application.visibleLatency.frameExtracted(
                evidence.documentRevision,
                evidence.presentationVisible,
                evidence.completionVisible,
            )
        }
        val operations = mutableListOf<IdeRenderOperation>()
        model.panels.forEach { draw -> operations += IdeRenderOperation(draw.zIndex) { graphics.fill(draw.bounds, draw.color) } }
        model.fills.forEach { draw -> operations += IdeRenderOperation(draw.zIndex) { graphics.fill(draw.bounds, draw.color) } }
        model.icons.forEach { draw ->
            operations +=
                IdeRenderOperation(draw.zIndex) {
                    val sprite = IdeToolbarIconAtlas.sprite(draw)
                    graphics.setColor(draw.color.red(), draw.color.green(), draw.color.blue(), draw.color.alpha())
                    graphics.blit(
                        IDE_TOOLBAR_ICON_TEXTURE,
                        sprite.bounds.left,
                        sprite.bounds.top,
                        sprite.bounds.width,
                        sprite.bounds.height,
                        sprite.sourceX.toFloat(),
                        0f,
                        IdeToolbarIconAtlas.CELL_SIZE,
                        IdeToolbarIconAtlas.CELL_SIZE,
                        IdeToolbarIconAtlas.WIDTH,
                        IdeToolbarIconAtlas.HEIGHT,
                    )
                    graphics.setColor(1f, 1f, 1f, 1f)
                }
        }
        model.text.forEach { draw ->
            operations +=
                IdeRenderOperation(draw.zIndex) {
                    draw.clip?.let { enableScissor(graphics, viewport, it.left, it.top, it.right, it.bottom) }
                    withIdeTextTransform(graphics.pose(), draw.rotation, draw.x, draw.y) {
                        val transformed = draw.rotation != IdeTextRotation.None
                        val textX = if (transformed) 0 else draw.x
                        val textY = if (transformed) 0 else draw.y
                        val codeFont = draw.codeFont
                        if (codeFont == null) {
                            graphics.drawString(font, Component.literal(draw.value), textX, textY, draw.color, false)
                        } else {
                            IdeCodeGlyphLayout.layout(draw.value, textX, codeFont).forEach { glyph ->
                                val value =
                                    Component
                                        .literal(glyph.value)
                                        .withStyle { style -> style.withFont(codeFont.fontDescription) }
                                graphics.drawString(font, value, glyph.x, textY, draw.color, false)
                            }
                        }
                    }
                    if (draw.clip != null) graphics.disableScissor()
                }
        }
        executeIdeRenderOperations(
            operations = operations,
            terminalVisible = terminalOverlay.visible,
            renderTerminal = { renderTerminalOverlay(graphics, terminalOverlayGeometry(geometry), profile, viewport) },
        )
        super.render(
            graphics,
            viewport.toVirtualX(mouseX.toDouble()).toInt(),
            viewport.toVirtualY(mouseY.toDouble()).toInt(),
            partialTick,
        )
    }

    override fun renderBackground(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) = Unit

    override fun isPauseScreen(): Boolean = false

    private fun geometry(
        profile: ru.lazyhat.compukters.impl.terminal.TerminalFontProfile = CompuktersClientConfig.selectedFont(),
        viewport: CompuktersUiViewport = viewport(),
    ): IdeRenderGeometry {
        val layout = splitters.layout
        return IdeRenderGeometry.compute(
            viewport.width,
            viewport.height,
            layout.treeWidth,
            layout.diagnosticsHeight,
            layout.diagnosticsExpanded,
            treeVisible = true,
            profile,
        )
    }

    private fun viewport(): CompuktersUiViewport {
        val window = client().window
        return CompuktersUiViewport.admit(window.width, window.height, window.guiScale.toInt())
    }

    private fun focusState(): IdeFocusState {
        val state = application.controller.viewState()
        val editor = ((state.page as? IdePageState.Workspace)?.value?.editor as? IdeEditorView.Text)
        val analysis = editor?.analysis as? IdeAnalysisState.Active
        val completion = analysis?.completion != null
        val chooser =
            (analysis?.interaction as? IdeSemanticInteraction.Chooser) != null
        return IdeFocusState(
            focusArea,
            completion,
            chooser,
            state.dialog,
            geometry().codeRows.coerceAtLeast(1),
            analysis?.parameterInfo != null,
        )
    }

    private fun pointerContext(geometry: IdeRenderGeometry): IdePointerContext {
        val state = application.controller.viewState()
        val treeFirstRow = admittedTreeFirstRow(geometry)
        val model =
            IdeRenderer.extract(
                state,
                geometry,
                prompt = prompt.state,
                treeFirstRow = treeFirstRow,
                selectedTreePath = selectedTreePath,
                terminalState = terminalOverlay.state().presentationStatus(),
                terminalVisible = terminalOverlay.visible,
                explorerDrag = input.explorerDragVisual,
                projectSwitcherOpen = projectSwitcherOpen && prompt.state == null && state.dialog == null,
            )
        return when (val page = state.page) {
            is IdePageState.Start -> {
                IdePointerContext(
                    geometry,
                    projects = page.projects,
                    hitTargets = model.hitTargets,
                    dialog = state.dialog,
                )
            }

            is IdePageState.Workspace -> {
                IdePointerContext(
                    geometry,
                    editor = page.value.editor as? IdeEditorView.Text,
                    projects = page.value.projects,
                    tree = page.value.tree.flatten(),
                    explorer = page.value.explorerRows(),
                    treeFirstRow = treeFirstRow,
                    hitTargets = model.hitTargets,
                    dialog = state.dialog,
                )
            }
        }
    }

    private fun activateUiAction(action: IdeHitAction): Boolean {
        when (action) {
            IdeHitAction.CreateProject -> {
                projectSwitcherOpen = false
                prompt.open(IdePromptKind.CreateProject)
            }

            IdeHitAction.OpenProject -> {
                focusArea = IdeFocusArea.Editor
            }

            IdeHitAction.ProjectSwitcher -> {
                projectSwitcherOpen = !projectSwitcherOpen
                if (projectSwitcherOpen) terminalOverlay.hide()
            }

            IdeHitAction.CreateText -> {
                prompt.open(IdePromptKind.CreateText, "src/")
            }

            IdeHitAction.CreateDirectory -> {
                prompt.open(IdePromptKind.CreateDirectory, "src/")
            }

            IdeHitAction.Rename -> {
                val path = selectedTreePath ?: activeFile() ?: return false
                prompt.open(IdePromptKind.Rename(path), path.value)
            }

            IdeHitAction.Delete -> {
                val path = selectedTreePath ?: activeFile() ?: return false
                application.controller.dispatch(IdeCommand.RequestDelete(path))
            }

            IdeHitAction.Terminal -> {
                terminalOverlay.toggle()
                focusArea = if (terminalOverlay.visible) IdeFocusArea.Terminal else IdeFocusArea.Editor
            }

            IdeHitAction.Confirm -> {
                return confirmPrompt()
            }

            IdeHitAction.Dismiss -> {
                return prompt.cancel()
            }

            else -> {
                return false
            }
        }
        clearFocus()
        return true
    }

    private fun terminalOverlayGeometry(geometry: IdeRenderGeometry): IdeTerminalOverlayGeometry =
        IdeTerminalOverlayGeometry.compute(geometry.content, geometry.font)

    private fun renderTerminalOverlay(
        graphics: GuiGraphics,
        overlay: IdeTerminalOverlayGeometry,
        profile: ru.lazyhat.compukters.impl.terminal.TerminalFontProfile,
        viewport: CompuktersUiViewport,
    ) {
        graphics.fill(overlay.shadow, TERMINAL_SHADOW)
        graphics.fill(overlay.panel, TERMINAL_BORDER)
        val inner = IdeRect(overlay.panel.left + 1, overlay.panel.top + 1, overlay.panel.right - 1, overlay.panel.bottom - 1)
        graphics.fill(inner, TERMINAL_PANEL)
        if (!overlay.supported) {
            enableScissor(
                graphics,
                viewport,
                overlay.messageBounds.left,
                overlay.messageBounds.top,
                overlay.messageBounds.right,
                overlay.messageBounds.bottom,
            )
            graphics.drawString(
                font,
                Component.literal(overlay.unsupportedMessage),
                overlay.messageBounds.left,
                overlay.messageBounds.top,
                TERMINAL_ERROR,
                false,
            )
            graphics.disableScissor()
            return
        }
        enableScissor(graphics, viewport, overlay.title.left, overlay.title.top, overlay.title.right, overlay.title.bottom)
        graphics.drawString(
            font,
            Component.literal(terminalOverlayTitle(terminalOverlay.state())),
            overlay.title.left + 5,
            overlay.title.top + 5,
            if (terminalOverlay.focused) TERMINAL_ACCENT else TERMINAL_TEXT,
            false,
        )
        graphics.disableScissor()
        val session =
            when (val state = terminalOverlay.state()) {
                is IdeTargetTerminalState.Active -> state.replica
                is IdeTargetTerminalState.Resyncing -> state.replica
                else -> null
            }
        overlay.grid?.let { grid ->
            graphics.fill(grid, TERMINAL_GRID)
            session?.let { replica ->
                TerminalGridRenderer.draw(
                    graphics,
                    font,
                    replica.state,
                    profile,
                    TerminalGridGeometry(grid.left, grid.top, profile),
                    viewport,
                    System.nanoTime() / 1_000_000L,
                )
            }
        }
    }

    private fun IdeTargetState.terminalReference(): IdeTargetReference? =
        attachedTarget()?.takeIf { it.capabilities.terminal }?.let { IdeTargetReference(it.id, it.profile) }

    private fun IdeTargetState.attachedTarget(): IdeAttachedTarget? =
        when (this) {
            IdeTargetState.LocalOnly,
            is IdeTargetState.Attaching,
            is IdeTargetState.Detached,
            -> null

            is IdeTargetState.Attached -> target

            is IdeTargetState.Uploading -> target

            is IdeTargetState.Verified -> target

            is IdeTargetState.Observing -> target

            is IdeTargetState.ConfirmationRequired -> target

            is IdeTargetState.Deploying -> target

            is IdeTargetState.Deployed -> target

            is IdeTargetState.Submitting -> target

            is IdeTargetState.CommandSubmitted -> target

            is IdeTargetState.Failed -> target
        }

    private fun confirmPrompt(): Boolean {
        val command = prompt.confirm() ?: return true
        application.controller.dispatch(command)
        return true
    }

    private fun restoreParent() {
        returningToParent = true
        val target =
            when (val lifecycle = parent as? ChildScreenParent) {
                null -> parent
                else -> parent.takeIf { lifecycle.resumeFromChild() }
            }
        closeSession()
        client().setScreen(target)
    }

    private fun closeSession() {
        if (sessionClosed) return
        sessionClosed = true
        session.close()
    }

    private fun client() = requireNotNull(minecraft) { "IDE screen is not attached to a Minecraft client" }

    private fun activeFile(): ProjectPath? = ((application.controller.viewState().page as? IdePageState.Workspace)?.value?.activeFile)

    private fun admittedTreeFirstRow(geometry: IdeRenderGeometry): Int {
        val entries = (application.controller.viewState().page as? IdePageState.Workspace)?.value?.explorerRows()?.size ?: 0
        return input.clampTree(entries, geometry.tree?.height ?: 0)
    }

    private fun selectTreeRow(
        x: Double,
        y: Double,
        geometry: IdeRenderGeometry,
    ) {
        val treeBounds = geometry.tree ?: return
        if (!treeBounds.contains(x, y)) return
        val workspace = (application.controller.viewState().page as? IdePageState.Workspace)?.value ?: return
        val row = input.treeFirstRow + ((y - treeBounds.top - TREE_ROWS_TOP).toInt() / UI_LINE_HEIGHT)
        selectedTreePath = (workspace.explorerRows().getOrNull(row) as? IdeExplorerRow.ProjectEntry)?.entry?.path
    }

    private fun IdeRect.contains(
        x: Double,
        y: Double,
    ): Boolean = x >= left && x < right && y >= top && y < bottom

    private fun currentModifiers(): Int {
        var modifiers = 0
        if (hasShiftDown()) modifiers = modifiers or GLFW.GLFW_MOD_SHIFT
        if (hasControlDown()) modifiers = modifiers or GLFW.GLFW_MOD_CONTROL
        if (hasAltDown()) modifiers = modifiers or GLFW.GLFW_MOD_ALT
        return modifiers
    }

    private fun isDoubleClick(
        x: Double,
        y: Double,
        button: Int,
    ): Boolean {
        val now = System.nanoTime()
        val previous = previousClick
        previousClick = PointerClick(now, x, y, button)
        return previous.button == button &&
            now - previous.timeNanos <= DOUBLE_CLICK_NANOS &&
            kotlin.math.abs(previous.x - x) <= DOUBLE_CLICK_DISTANCE &&
            kotlin.math.abs(previous.y - y) <= DOUBLE_CLICK_DISTANCE
    }

    private fun GuiGraphics.fill(
        bounds: IdeRect,
        color: Int,
    ) {
        fill(bounds.left, bounds.top, bounds.right, bounds.bottom, color)
    }

    private fun enableScissor(
        graphics: GuiGraphics,
        viewport: CompuktersUiViewport,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        graphics.enableScissor(
            floor(viewport.toMinecraftX(left.toDouble())).toInt(),
            floor(viewport.toMinecraftY(top.toDouble())).toInt(),
            ceil(viewport.toMinecraftX(right.toDouble())).toInt(),
            ceil(viewport.toMinecraftY(bottom.toDouble())).toInt(),
        )
    }

    private companion object {
        const val UI_LINE_HEIGHT = 12
        const val TREE_ROWS_TOP = 4
        const val DOUBLE_CLICK_NANOS = 250_000_000L
        const val DOUBLE_CLICK_DISTANCE = 4.0
        val TERMINAL_SHADOW = 0x66000000
        val TERMINAL_PANEL = 0xFF101418.toInt()
        val TERMINAL_BORDER = 0xFF27323A.toInt()
        val TERMINAL_GRID = 0xFF000000.toInt()
        val TERMINAL_TEXT = 0xFFF2F4F8.toInt()
        val TERMINAL_ACCENT = 0xFF38D6B4.toInt()
        val TERMINAL_ERROR = 0xFFFF6B6B.toInt()
        val UNSUPPORTED_MESSAGE = Component.literal("Compukters UI requires at least 640x360 pixels")
    }
}

private const val CLOCKWISE_QUARTER_TURN_DEGREES = 90f

private data class PointerClick(
    val timeNanos: Long,
    val x: Double,
    val y: Double,
    val button: Int,
) {
    companion object {
        val None = PointerClick(Long.MIN_VALUE, Double.NaN, Double.NaN, Int.MIN_VALUE)
    }
}

private fun Int.red(): Float = (this ushr 16 and 0xFF) / 255f

private fun Int.green(): Float = (this ushr 8 and 0xFF) / 255f

private fun Int.blue(): Float = (this and 0xFF) / 255f

private fun Int.alpha(): Float = (this ushr 24 and 0xFF) / 255f

private fun IdeTargetTerminalState.presentationStatus(): IdeTerminalStatus =
    when (this) {
        IdeTargetTerminalState.Closed -> IdeTerminalStatus.Closed
        is IdeTargetTerminalState.Opening -> IdeTerminalStatus.Opening
        is IdeTargetTerminalState.Active -> IdeTerminalStatus.Active
        is IdeTargetTerminalState.Resyncing -> IdeTerminalStatus.Resyncing
        is IdeTargetTerminalState.Failed -> IdeTerminalStatus.Failed(detail, retryable)
    }
