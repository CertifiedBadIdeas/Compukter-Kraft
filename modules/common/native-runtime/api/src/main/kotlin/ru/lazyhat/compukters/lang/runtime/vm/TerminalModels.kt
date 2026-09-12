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

package ru.lazyhat.compukters.lang.runtime.vm

data class TerminalCell(
    val codePoint: Int,
    val foreground: Int,
    val background: Int,
)

data class TerminalPosition(
    val x: Int,
    val y: Int,
)

data class TerminalState(
    val revision: Long,
    val width: Int,
    val height: Int,
    val cells: List<TerminalCell>,
    val cursor: TerminalPosition,
    val cursorVisible: Boolean,
)

sealed interface TerminalChange {
    data class Patch(
        val start: Int,
        val cells: List<TerminalCell>,
    ) : TerminalChange

    data class Fill(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val cell: TerminalCell,
    ) : TerminalChange

    data class Scroll(
        val rows: Int,
        val fill: TerminalCell,
    ) : TerminalChange

    data class Cursor(
        val position: TerminalPosition,
        val visible: Boolean,
    ) : TerminalChange

    data object Reset : TerminalChange
}

sealed interface TerminalUpdate {
    data class Unchanged(
        val revision: Long,
    ) : TerminalUpdate

    data class Delta(
        val baseRevision: Long,
        val targetRevision: Long,
        val changes: List<TerminalChange>,
    ) : TerminalUpdate

    data class Full(
        val state: TerminalState,
    ) : TerminalUpdate
}

enum class TerminalKey(
    val wireCode: Int,
) {
    ESCAPE(1),
    BACKSPACE(8),
    TAB(9),
    ENTER(13),
    S(83),
    X(88),
    INSERT(256),
    DELETE(257),
    HOME(258),
    END(259),
    PAGE_UP(260),
    PAGE_DOWN(261),
    UP(262),
    LEFT(263),
    DOWN(264),
    RIGHT(265),
    F1(272),
    F2(273),
    F3(274),
    F4(275),
    F5(276),
    F6(277),
    F7(278),
    F8(279),
    F9(280),
    F10(281),
    F11(282),
    F12(283),
}

enum class TerminalKeyAction(
    val wireCode: Int,
) {
    PRESS(0),
    REPEAT(1),
}

enum class TerminalModifier(
    val mask: Int,
) {
    SHIFT(1),
    CONTROL(2),
    ALT(4),
    SUPER(8),
}
