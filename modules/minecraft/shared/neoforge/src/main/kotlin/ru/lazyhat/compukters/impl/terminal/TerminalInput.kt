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
 */

package ru.lazyhat.compukters.impl.terminal

import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier

internal object TerminalInput {
    fun key(
        keyCode: Int,
        modifierBits: Int,
    ): TerminalKey? =
        KEY_MAP[keyCode]
            ?: CONTROL_KEY_MAP[keyCode]?.takeIf { modifierBits and GLFW.GLFW_MOD_CONTROL != 0 }

    fun isMappedKeyCode(keyCode: Int): Boolean = keyCode in KEY_MAP || keyCode in CONTROL_KEY_MAP

    fun modifiers(bits: Int): Set<TerminalModifier> =
        buildSet {
            if (bits and GLFW.GLFW_MOD_SHIFT != 0) add(TerminalModifier.SHIFT)
            if (bits and GLFW.GLFW_MOD_CONTROL != 0) add(TerminalModifier.CONTROL)
            if (bits and GLFW.GLFW_MOD_ALT != 0) add(TerminalModifier.ALT)
            if (bits and GLFW.GLFW_MOD_SUPER != 0) add(TerminalModifier.SUPER)
        }

    fun boundedText(
        value: String,
        maximumCodeUnits: Int = TerminalProtocol.MAXIMUM_TEXT_CODE_UNITS,
    ): String {
        require(maximumCodeUnits >= 0) { "maximum terminal text length must not be negative" }
        val result = StringBuilder(minOf(value.length, maximumCodeUnits))
        var offset = 0
        while (offset < value.length) {
            val first = value[offset]
            val validPair =
                Character.isHighSurrogate(first) &&
                    offset + 1 < value.length &&
                    Character.isLowSurrogate(value[offset + 1])
            val codePoint =
                when {
                    validPair -> Character.toCodePoint(first, value[offset + 1])
                    Character.isSurrogate(first) -> 0xFFFD
                    else -> first.code
                }
            val inputUnits = if (validPair) 2 else 1
            val outputUnits = Character.charCount(codePoint)
            if (result.length + outputUnits > maximumCodeUnits) break
            result.appendCodePoint(codePoint)
            offset += inputUnits
        }
        return result.toString()
    }

    private val KEY_MAP =
        mapOf(
            GLFW.GLFW_KEY_ESCAPE to TerminalKey.ESCAPE,
            GLFW.GLFW_KEY_BACKSPACE to TerminalKey.BACKSPACE,
            GLFW.GLFW_KEY_TAB to TerminalKey.TAB,
            GLFW.GLFW_KEY_ENTER to TerminalKey.ENTER,
            GLFW.GLFW_KEY_INSERT to TerminalKey.INSERT,
            GLFW.GLFW_KEY_DELETE to TerminalKey.DELETE,
            GLFW.GLFW_KEY_HOME to TerminalKey.HOME,
            GLFW.GLFW_KEY_END to TerminalKey.END,
            GLFW.GLFW_KEY_PAGE_UP to TerminalKey.PAGE_UP,
            GLFW.GLFW_KEY_PAGE_DOWN to TerminalKey.PAGE_DOWN,
            GLFW.GLFW_KEY_UP to TerminalKey.UP,
            GLFW.GLFW_KEY_LEFT to TerminalKey.LEFT,
            GLFW.GLFW_KEY_DOWN to TerminalKey.DOWN,
            GLFW.GLFW_KEY_RIGHT to TerminalKey.RIGHT,
            GLFW.GLFW_KEY_F1 to TerminalKey.F1,
            GLFW.GLFW_KEY_F2 to TerminalKey.F2,
            GLFW.GLFW_KEY_F3 to TerminalKey.F3,
            GLFW.GLFW_KEY_F4 to TerminalKey.F4,
            GLFW.GLFW_KEY_F5 to TerminalKey.F5,
            GLFW.GLFW_KEY_F6 to TerminalKey.F6,
            GLFW.GLFW_KEY_F7 to TerminalKey.F7,
            GLFW.GLFW_KEY_F8 to TerminalKey.F8,
            GLFW.GLFW_KEY_F9 to TerminalKey.F9,
            GLFW.GLFW_KEY_F10 to TerminalKey.F10,
            GLFW.GLFW_KEY_F11 to TerminalKey.F11,
            GLFW.GLFW_KEY_F12 to TerminalKey.F12,
        )
    private val CONTROL_KEY_MAP =
        mapOf(
            GLFW.GLFW_KEY_S to TerminalKey.S,
            GLFW.GLFW_KEY_X to TerminalKey.X,
        )
}
