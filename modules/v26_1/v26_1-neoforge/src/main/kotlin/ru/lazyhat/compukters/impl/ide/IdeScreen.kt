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

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2fStack
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
import ru.lazyhat.compukters.impl.ui.CompuktersUiViewport

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
    pose: Matrix3x2fStack,
    rotation: IdeTextRotation,
    x: Int,
    y: Int,
    draw: () -> T,
): T {
    if (rotation == IdeTextRotation.None) return draw()
    pose.pushMatrix()
    return try {
        pose.translate(x.toFloat(), y.toFloat())
        pose.rotate(CLOCKWISE_QUARTER_TURN)
        draw()
    } finally {
        pose.popMatrix()
    }
}

private const val IDE_TERMINAL_OVERLAY_Z = 80
private val IDE_TOOLBAR_ICON_TEXTURE = Identifier.fromNamespaceAndPath("compukters", "textures/gui/ide_toolbar.png")

internal class IdeScreen(
    private val session: IdeClientSession<IdeClientApplication>,
    private val parent: Screen?,
) : Screen(Component.literal("Compukters IDE")) {
    private val application = session.application
    private val editorKeyboardFocus = IdeEditorKeyboardFocus(font)
    private val prompt = IdePromptController()
    private val input =
        IdeInputAdapter(
            application.controller::dispatch,
            IdeClipboard { minecraft.keyboardHandler.clipboard },
            IdeClientLimits(),
            IdeUiActionSink(::activateUiAction),
            IdeClipboardWriter { minecraft.keyboardHandler.clipboard = it },
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

    override fun setInitialFocus() = Unit

    override fun getFocused(): GuiEventListener? = editorKeyboardFocus.resolve(focusArea, super.getFocused())

    override fun mouseClicked(
        event: MouseButtonEvent,
        doubleClick: Boolean,
    ): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val uiEvent = viewport.map(event)
        val geometry = geometry()
        val state = application.controller.viewState()
        if (prompt.state != null || state.dialog != null) {
            input.pointerClicked(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), pointerContext(geometry), doubleClick)
            clearFocus()
            return true
        }
        if (projectSwitcherOpen) {
            val switcherContext = pointerContext(geometry)
            val switcherAction =
                switcherContext.hitTargets
                    .asReversed()
                    .firstOrNull { it.enabled && it.bounds.contains(uiEvent.x(), uiEvent.y()) }
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
            input.pointerClicked(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), switcherContext, doubleClick)
            if (switcherAction == IdeHitAction.ProjectChoice) projectSwitcherOpen = false
            focusArea = IdeFocusArea.Panel
            clearFocus()
            terminalOverlay.focusLost()
            return true
        }
        val overlay = terminalOverlayGeometry(geometry)
        if (terminalOverlay.visible && overlay.panel.contains(uiEvent.x(), uiEvent.y())) {
            focusArea = IdeFocusArea.Terminal
            terminalOverlay.focus()
            if (overlay.title.contains(uiEvent.x(), uiEvent.y())) terminalOverlay.retry()
            clearFocus()
            input.pointerActivity()
            return true
        }
        if (splitters.press(uiEvent.x().toInt(), uiEvent.y().toInt(), geometry)) {
            input.pointerActivity()
            return true
        }
        val pointerContext = pointerContext(geometry)
        if (input.explorerPressed(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), pointerContext)) {
            focusArea = IdeFocusArea.Tree
            selectedTreePath = null
            clearFocus()
            terminalOverlay.focusLost()
            return true
        }
        selectTreeRow(uiEvent.x(), uiEvent.y(), geometry)
        val hitAction =
            pointerContext.hitTargets
                .asReversed()
                .firstOrNull { it.enabled && it.bounds.contains(uiEvent.x(), uiEvent.y()) }
                ?.action
        if (input.pointerClicked(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), pointerContext, doubleClick)) {
            if (hitAction == IdeHitAction.ProjectChoice) projectSwitcherOpen = false
            focusArea =
                when {
                    hitAction == IdeHitAction.Terminal && terminalOverlay.visible -> IdeFocusArea.Terminal
                    geometry.editor.contains(uiEvent.x(), uiEvent.y()) -> IdeFocusArea.Editor
                    geometry.tree?.contains(uiEvent.x(), uiEvent.y()) == true -> IdeFocusArea.Tree
                    else -> IdeFocusArea.Panel
                }
            clearFocus()
            if (focusArea == IdeFocusArea.Terminal) terminalOverlay.focus() else terminalOverlay.focusLost()
            return true
        }
        val handled = super.mouseClicked(uiEvent, doubleClick)
        if (handled) clearFocus()
        focusArea =
            when {
                geometry.editor.contains(uiEvent.x(), uiEvent.y()) -> IdeFocusArea.Editor
                geometry.tree?.contains(uiEvent.x(), uiEvent.y()) == true -> IdeFocusArea.Tree
                geometry.panel.contains(uiEvent.x(), uiEvent.y()) -> IdeFocusArea.Panel
                else -> IdeFocusArea.None
            }
        terminalOverlay.focusLost()
        input.pointerActivity()
        return handled || focusArea != IdeFocusArea.None
    }

    override fun mouseDragged(
        event: MouseButtonEvent,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val uiEvent = viewport.map(event)
        val geometry = geometry()
        if (terminalOverlay.visible && terminalOverlayGeometry(geometry).panel.contains(uiEvent.x(), uiEvent.y())) return true
        if (splitters.drag(uiEvent.x().toInt(), uiEvent.y().toInt(), geometry)) return true
        if (input.explorerDragged(uiEvent.x(), uiEvent.y(), pointerContext(geometry))) return true
        if (focusArea == IdeFocusArea.Editor) {
            return input.pointerClicked(
                uiEvent.x(),
                uiEvent.y(),
                uiEvent.modifiers() or GLFW.GLFW_MOD_SHIFT,
                pointerContext(geometry),
            )
        }
        return super.mouseDragged(uiEvent, viewport.toVirtualDelta(dragX), viewport.toVirtualDelta(dragY))
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val uiEvent = viewport.map(event)
        if (splitters.release()) return true
        if (input.explorerReleased(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), pointerContext(geometry()))) return true
        return super.mouseReleased(uiEvent)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val uiMouseX = viewport.toVirtualX(mouseX)
        val uiMouseY = viewport.toVirtualY(mouseY)
        val geometry = geometry()
        if (terminalOverlay.visible && terminalOverlayGeometry(geometry).panel.contains(uiMouseX, uiMouseY)) return true
        return input.scroll(uiMouseX, uiMouseY, scrollX, scrollY, pointerContext(geometry)) ||
            super.mouseScrolled(uiMouseX, uiMouseY, scrollX, scrollY)
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

    override fun keyPressed(event: KeyEvent): Boolean {
        if (
            event.key() == GLFW.GLFW_KEY_LEFT_CONTROL || event.key() == GLFW.GLFW_KEY_RIGHT_CONTROL ||
            event.modifiers() and GLFW.GLFW_MOD_CONTROL != 0
        ) {
            controlDown = true
        }
        if (!viewport().supported) return if (event.key() == GLFW.GLFW_KEY_ESCAPE) super.keyPressed(event) else true
        if (prompt.state != null) {
            return when {
                event.key() == GLFW.GLFW_KEY_ESCAPE -> prompt.cancel()
                event.key() == GLFW.GLFW_KEY_ENTER -> confirmPrompt()
                event.key() == GLFW.GLFW_KEY_BACKSPACE -> prompt.backspace()
                event.isPaste -> prompt.type(minecraft.keyboardHandler.clipboard)
                else -> true
            }
        }
        if (application.controller.viewState().dialog != null) return input.keyPressed(event, focusState())
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && projectSwitcherOpen) {
            projectSwitcherOpen = false
            return true
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && input.cancelExplorerDrag()) return true
        if (splitters.captured) return true
        if (focusArea == IdeFocusArea.Terminal && terminalOverlay.keyPressed(event, minecraft.keyboardHandler.clipboard)) return true
        if (
            focusArea == IdeFocusArea.Editor &&
            (event.key() == GLFW.GLFW_KEY_LEFT_CONTROL || event.key() == GLFW.GLFW_KEY_RIGHT_CONTROL)
        ) {
            val x = pointerX
            val y = pointerY
            if (x != null && y != null) input.pointerMoved(x, y, GLFW.GLFW_MOD_CONTROL, pointerContext(geometry()))
        }
        return input.keyPressed(event, focusState()) || super.keyPressed(event)
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_LEFT_CONTROL || event.key() == GLFW.GLFW_KEY_RIGHT_CONTROL) controlDown = false
        if (!viewport().supported) return true
        val semanticHandled = input.keyReleased(event)
        if (focusArea == IdeFocusArea.Terminal && terminalOverlay.keyReleased(event.key())) return true
        return semanticHandled || super.keyReleased(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (!viewport().supported) return true
        if (prompt.state != null) return prompt.type(event.codepointAsString())
        if (focusArea == IdeFocusArea.Terminal && terminalOverlay.charTyped(event)) return true
        return input.charTyped(event, focusState()) || super.charTyped(event)
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

    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, IdeColors.DIM)
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
                graphics.text(font, UNSUPPORTED_MESSAGE, 4, 4, TERMINAL_TEXT, false)
                return@withTransform
            }
            extractSupportedRenderState(graphics, mouseX, mouseY, partialTick, viewport)
        }
    }

    private fun extractSupportedRenderState(
        graphics: GuiGraphicsExtractor,
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
                terminalState = terminalOverlay.state(),
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
                    graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        IDE_TOOLBAR_ICON_TEXTURE,
                        sprite.bounds.left,
                        sprite.bounds.top,
                        sprite.sourceX.toFloat(),
                        0f,
                        sprite.bounds.width,
                        sprite.bounds.height,
                        IdeToolbarIconAtlas.CELL_SIZE,
                        IdeToolbarIconAtlas.CELL_SIZE,
                        IdeToolbarIconAtlas.WIDTH,
                        IdeToolbarIconAtlas.HEIGHT,
                        draw.color,
                    )
                }
        }
        model.text.forEach { draw ->
            operations +=
                IdeRenderOperation(draw.zIndex) {
                    draw.clip?.let { graphics.enableScissor(it.left, it.top, it.right, it.bottom) }
                    withIdeTextTransform(graphics.pose(), draw.rotation, draw.x, draw.y) {
                        val transformed = draw.rotation != IdeTextRotation.None
                        val textX = if (transformed) 0 else draw.x
                        val textY = if (transformed) 0 else draw.y
                        val codeFont = draw.codeFont
                        if (codeFont == null) {
                            graphics.text(font, Component.literal(draw.value), textX, textY, draw.color, false)
                        } else {
                            IdeCodeGlyphLayout.layout(draw.value, textX, codeFont).forEach { glyph ->
                                val value =
                                    Component
                                        .literal(glyph.value)
                                        .withStyle { style -> style.withFont(codeFont.fontDescription) }
                                graphics.text(font, value, glyph.x, textY, draw.color, false)
                            }
                        }
                    }
                    if (draw.clip != null) graphics.disableScissor()
                }
        }
        executeIdeRenderOperations(
            operations = operations,
            terminalVisible = terminalOverlay.visible,
            renderTerminal = { renderTerminalOverlay(graphics, terminalOverlayGeometry(geometry), profile) },
        )
        super.extractRenderState(
            graphics,
            viewport.toVirtualX(mouseX.toDouble()).toInt(),
            viewport.toVirtualY(mouseY.toDouble()).toInt(),
            partialTick,
        )
    }

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
        val window = minecraft.window
        return CompuktersUiViewport.admit(window.width, window.height, window.guiScale)
    }

    private fun focusState(): IdeFocusState {
        val state = application.controller.viewState()
        val editor = ((state.page as? IdePageState.Workspace)?.value?.editor as? IdeEditorView.Text)
        val completion = (editor?.analysis as? IdeAnalysisState.Active)?.completion != null
        val chooser =
            ((editor?.analysis as? IdeAnalysisState.Active)?.interaction as? IdeSemanticInteraction.Chooser) != null
        return IdeFocusState(focusArea, completion, chooser, state.dialog, geometry().codeRows.coerceAtLeast(1))
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
                terminalState = terminalOverlay.state(),
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
        graphics: GuiGraphicsExtractor,
        overlay: IdeTerminalOverlayGeometry,
        profile: ru.lazyhat.compukters.impl.terminal.TerminalFontProfile,
    ) {
        graphics.fill(overlay.shadow, TERMINAL_SHADOW)
        graphics.fill(overlay.panel, TERMINAL_BORDER)
        val inner = IdeRect(overlay.panel.left + 1, overlay.panel.top + 1, overlay.panel.right - 1, overlay.panel.bottom - 1)
        graphics.fill(inner, TERMINAL_PANEL)
        if (!overlay.supported) {
            graphics.enableScissor(
                overlay.messageBounds.left,
                overlay.messageBounds.top,
                overlay.messageBounds.right,
                overlay.messageBounds.bottom,
            )
            graphics.text(
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
        graphics.enableScissor(overlay.title.left, overlay.title.top, overlay.title.right, overlay.title.bottom)
        graphics.text(
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
        minecraft.setScreen(target)
    }

    private fun closeSession() {
        if (sessionClosed) return
        sessionClosed = true
        session.close()
    }

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

    private fun GuiGraphicsExtractor.fill(
        bounds: IdeRect,
        color: Int,
    ) {
        fill(bounds.left, bounds.top, bounds.right, bounds.bottom, color)
    }

    private companion object {
        const val UI_LINE_HEIGHT = 12
        const val TREE_ROWS_TOP = 4
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

private val CLOCKWISE_QUARTER_TURN = (Math.PI / 2.0).toFloat()
