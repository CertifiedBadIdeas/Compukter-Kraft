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

import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile

data class IdeRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    init {
        require(right >= left && bottom >= top) { "IDE rectangle must be half-open and non-negative" }
    }
}

sealed interface IdeGeometryFallback {
    data object Configured : IdeGeometryFallback

    data object DiagnosticsCollapsed : IdeGeometryFallback

    data object TreeHidden : IdeGeometryFallback

    data object Unsupported : IdeGeometryFallback
}

enum class AnchoredPopupPlacement { Above, Below }

data class AnchoredPopupGeometry(
    val bounds: IdeRect,
    val placement: AnchoredPopupPlacement,
)

typealias CompletionPopupPlacement = AnchoredPopupPlacement
typealias CompletionPopupGeometry = AnchoredPopupGeometry

class IdeRenderGeometry private constructor(
    val viewport: IdeRect,
    val panel: IdeRect,
    val toolStripe: IdeRect,
    val header: IdeRect,
    val toolbar: IdeRect,
    val content: IdeRect,
    val status: IdeRect,
    val tree: IdeRect?,
    val treeSplitter: IdeRect?,
    val editor: IdeRect,
    val diagnosticsSplitter: IdeRect?,
    val diagnostics: IdeRect?,
    val treeVisible: Boolean,
    val diagnosticsExpanded: Boolean,
    val font: TerminalFontProfile,
    val fallback: IdeGeometryFallback,
    val unsupportedMessage: String,
) {
    val supported: Boolean = fallback != IdeGeometryFallback.Unsupported
    val codeColumns: Int = editor.width / font.cellWidth
    val codeRows: Int = editor.height / font.cellHeight

    fun treeWidthAt(pointerX: Int): Int {
        check(supported && treeVisible) { "project tree is not visible" }
        val maximum = content.width - SPLITTER_SIZE - MINIMUM_EDITOR_WIDTH
        return (pointerX - content.left).coerceIn(MINIMUM_TREE_WIDTH, maximum)
    }

    fun diagnosticsHeightAt(pointerY: Int): Int {
        check(supported && diagnosticsExpanded) { "diagnostics panel is not expanded" }
        val maximum = content.height - SPLITTER_SIZE - MINIMUM_EDITOR_HEIGHT
        return (content.bottom - pointerY).coerceIn(MINIMUM_DIAGNOSTICS_HEIGHT, maximum)
    }

    fun completionPopup(
        caret: IdeRect,
        requestedWidth: Int,
        requestedHeight: Int,
    ): CompletionPopupGeometry = anchoredPopup(caret, requestedWidth, requestedHeight)

    fun anchoredPopup(
        anchor: IdeRect,
        requestedWidth: Int,
        requestedHeight: Int,
    ): AnchoredPopupGeometry {
        check(supported) { "anchored popup is unavailable for unsupported geometry" }
        require(requestedWidth > 0 && requestedHeight > 0) { "anchored popup size must be positive" }
        val width = minOf(requestedWidth, editor.width)
        val spaceBelow = (editor.bottom - anchor.bottom).coerceAtLeast(0)
        val spaceAbove = (anchor.top - editor.top).coerceAtLeast(0)
        val placement =
            if (requestedHeight <= spaceBelow || spaceBelow >= spaceAbove) {
                AnchoredPopupPlacement.Below
            } else {
                AnchoredPopupPlacement.Above
            }
        val height = minOf(requestedHeight, if (placement == AnchoredPopupPlacement.Below) spaceBelow else spaceAbove)
        val left = anchor.left.coerceIn(editor.left, editor.right - width)
        val top =
            if (placement == AnchoredPopupPlacement.Below) {
                anchor.bottom.coerceIn(editor.top, editor.bottom - height)
            } else {
                (anchor.top - height).coerceIn(editor.top, editor.bottom - height)
            }
        return AnchoredPopupGeometry(IdeRect(left, top, left + width, top + height), placement)
    }

    companion object {
        const val MINIMUM_EDITOR_WIDTH = 240
        const val MINIMUM_EDITOR_HEIGHT = 120
        const val MINIMUM_TREE_WIDTH = 96
        const val MINIMUM_DIAGNOSTICS_HEIGHT = 64
        const val SPLITTER_SIZE = 1
        const val HEADER_HEIGHT = 24
        const val TOOLBAR_HEIGHT = 22
        const val STATUS_HEIGHT = 18
        const val TOOL_STRIPE_WIDTH = 20
        private const val UNSUPPORTED_MESSAGE = "Viewport is too small for the Compukters IDE"

        fun compute(
            viewportWidth: Int,
            viewportHeight: Int,
            treeWidth: Int,
            diagnosticsHeight: Int,
            diagnosticsExpanded: Boolean,
            treeVisible: Boolean,
            font: TerminalFontProfile,
        ): IdeRenderGeometry {
            require(viewportWidth >= 0 && viewportHeight >= 0) { "IDE viewport must not be negative" }
            val candidates = mutableListOf<Candidate>()

            fun candidate(
                appliedDiagnostics: Boolean,
                appliedTree: Boolean,
                fallback: IdeGeometryFallback,
            ) {
                val value = Candidate(appliedDiagnostics, appliedTree, fallback)
                if (value !in candidates) candidates += value
            }

            candidate(diagnosticsExpanded, treeVisible, IdeGeometryFallback.Configured)
            if (diagnosticsExpanded) candidate(false, treeVisible, IdeGeometryFallback.DiagnosticsCollapsed)
            if (treeVisible) candidate(false, false, IdeGeometryFallback.TreeHidden)
            val viewport = IdeRect(0, 0, viewportWidth, viewportHeight)
            candidates.firstOrNull { it.fits(viewportWidth, viewportHeight) }?.let { selected ->
                return build(viewport, selected, treeWidth, diagnosticsHeight, font)
            }
            return unsupported(viewport, font)
        }

        private fun Candidate.fits(
            viewportWidth: Int,
            viewportHeight: Int,
        ): Boolean {
            val panelWidth = viewportWidth - TOOL_STRIPE_WIDTH.toLong()
            val panelHeight = viewportHeight.toLong()
            val contentHeight = panelHeight - HEADER_HEIGHT - TOOLBAR_HEIGHT - STATUS_HEIGHT
            val requiredWidth = MINIMUM_EDITOR_WIDTH + if (tree) MINIMUM_TREE_WIDTH + SPLITTER_SIZE else 0
            val requiredHeight = MINIMUM_EDITOR_HEIGHT + if (diagnostics) MINIMUM_DIAGNOSTICS_HEIGHT + SPLITTER_SIZE else 0
            return panelWidth >= requiredWidth && contentHeight >= requiredHeight
        }

        private fun build(
            viewport: IdeRect,
            candidate: Candidate,
            requestedTreeWidth: Int,
            requestedDiagnosticsHeight: Int,
            font: TerminalFontProfile,
        ): IdeRenderGeometry {
            val panel = viewport
            val toolStripe = IdeRect(panel.right - TOOL_STRIPE_WIDTH, panel.top, panel.right, panel.bottom)
            val header = IdeRect(panel.left, panel.top, toolStripe.left, panel.top + HEADER_HEIGHT)
            val toolbar = IdeRect(panel.left, header.bottom, toolStripe.left, header.bottom + TOOLBAR_HEIGHT)
            val status = IdeRect(panel.left, panel.bottom - STATUS_HEIGHT, toolStripe.left, panel.bottom)
            val content = IdeRect(panel.left, toolbar.bottom, toolStripe.left, status.top)
            val treeWidth =
                if (candidate.tree) {
                    requestedTreeWidth.coerceIn(
                        MINIMUM_TREE_WIDTH,
                        content.width - SPLITTER_SIZE - MINIMUM_EDITOR_WIDTH,
                    )
                } else {
                    0
                }
            val tree = if (candidate.tree) IdeRect(content.left, content.top, content.left + treeWidth, content.bottom) else null
            val treeSplitter = tree?.let { IdeRect(it.right, content.top, it.right + SPLITTER_SIZE, content.bottom) }
            val editorLeft = treeSplitter?.right ?: content.left
            val editorArea = IdeRect(editorLeft, content.top, content.right, content.bottom)
            val diagnosticsHeight =
                if (candidate.diagnostics) {
                    requestedDiagnosticsHeight.coerceIn(
                        MINIMUM_DIAGNOSTICS_HEIGHT,
                        editorArea.height - SPLITTER_SIZE - MINIMUM_EDITOR_HEIGHT,
                    )
                } else {
                    0
                }
            val diagnostics =
                if (candidate.diagnostics) {
                    IdeRect(editorArea.left, editorArea.bottom - diagnosticsHeight, editorArea.right, editorArea.bottom)
                } else {
                    null
                }
            val diagnosticsSplitter =
                diagnostics?.let { IdeRect(editorArea.left, it.top - SPLITTER_SIZE, editorArea.right, it.top) }
            val editorBottom = diagnosticsSplitter?.top ?: editorArea.bottom
            val editor = IdeRect(editorArea.left, editorArea.top, editorArea.right, editorBottom)
            return IdeRenderGeometry(
                viewport,
                panel,
                toolStripe,
                header,
                toolbar,
                content,
                status,
                tree,
                treeSplitter,
                editor,
                diagnosticsSplitter,
                diagnostics,
                candidate.tree,
                candidate.diagnostics,
                font,
                candidate.fallback,
                "",
            )
        }

        private fun unsupported(
            viewport: IdeRect,
            font: TerminalFontProfile,
        ): IdeRenderGeometry {
            val empty = IdeRect(0, 0, 0, 0)
            return IdeRenderGeometry(
                viewport,
                viewport,
                empty,
                empty,
                empty,
                empty,
                empty,
                null,
                null,
                empty,
                null,
                null,
                false,
                false,
                font,
                IdeGeometryFallback.Unsupported,
                UNSUPPORTED_MESSAGE,
            )
        }
    }
}

private data class Candidate(
    val diagnostics: Boolean,
    val tree: Boolean,
    val fallback: IdeGeometryFallback,
)
