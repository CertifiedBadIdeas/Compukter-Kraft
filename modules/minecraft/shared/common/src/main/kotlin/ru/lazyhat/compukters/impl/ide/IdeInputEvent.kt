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

data class IdeKeyInput(
    val key: Int,
    val modifiers: Int = 0,
    val paste: Boolean = false,
)

@JvmInline
value class IdeCharacterInput(
    val text: String,
)

object IdeKeyCode {
    const val SPACE = 32
    const val A = 65
    const val B = 66
    const val C = 67
    const val L = 76
    const val P = 80
    const val S = 83
    const val V = 86
    const val X = 88
    const val Y = 89
    const val Z = 90
    const val ESCAPE = 256
    const val ENTER = 257
    const val TAB = 258
    const val BACKSPACE = 259
    const val DELETE = 261
    const val RIGHT = 262
    const val LEFT = 263
    const val DOWN = 264
    const val UP = 265
    const val PAGE_UP = 266
    const val PAGE_DOWN = 267
    const val HOME = 268
    const val END = 269
    const val F8 = 297
    const val F9 = 298
    const val LEFT_CONTROL = 341
    const val RIGHT_CONTROL = 345
}

object IdeModifier {
    const val SHIFT = 0x0001
    const val CONTROL = 0x0002
    const val ALT = 0x0004
}
