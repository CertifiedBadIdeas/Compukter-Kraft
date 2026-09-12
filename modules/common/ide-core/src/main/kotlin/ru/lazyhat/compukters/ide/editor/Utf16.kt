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

package ru.lazyhat.compukters.ide.editor

internal object Utf16 {
    fun strictUtf8Length(value: CharSequence): Int? {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val current = value[index]
            val encoded =
                when {
                    current.code <= 0x7f -> {
                        1
                    }

                    current.code <= 0x7ff -> {
                        2
                    }

                    Character.isHighSurrogate(current) -> {
                        if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return null
                        index++
                        4
                    }

                    Character.isLowSurrogate(current) -> {
                        return null
                    }

                    else -> {
                        3
                    }
                }
            bytes += encoded
            if (bytes > Int.MAX_VALUE) return null
            index++
        }
        return bytes.toInt()
    }

    fun strictUtf8Length(
        length: Int,
        charAt: (Int) -> Char,
    ): Int {
        var bytes = 0
        var index = 0
        while (index < length) {
            val current = charAt(index)
            bytes +=
                when {
                    current.code <= 0x7f -> {
                        1
                    }

                    current.code <= 0x7ff -> {
                        2
                    }

                    Character.isHighSurrogate(current) -> {
                        check(index + 1 < length && Character.isLowSurrogate(charAt(index + 1)))
                        index++
                        4
                    }

                    else -> {
                        3
                    }
                }
            index++
        }
        return bytes
    }
}
