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

package ru.lazyhat.compukters.ide.project

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object TomlSupport {
    val utf8Comparator = Comparator<String> { left, right -> compareUtf8(left, right) }

    fun strictUtf8(value: String): ByteArray {
        val encoded =
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
        return ByteArray(encoded.remaining()).also(encoded::get)
    }

    fun decodeStrictUtf8(value: ByteArray): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString()

    fun quoted(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> {
                        append("\\\"")
                    }

                    '\\' -> {
                        append("\\\\")
                    }

                    '\b' -> {
                        append("\\b")
                    }

                    '\t' -> {
                        append("\\t")
                    }

                    '\n' -> {
                        append("\\n")
                    }

                    '\u000c' -> {
                        append("\\f")
                    }

                    '\r' -> {
                        append("\\r")
                    }

                    else -> {
                        if (character.code < 0x20 || character.code == 0x7f) {
                            append("\\u%04x".format(character.code))
                        } else {
                            append(character)
                        }
                    }
                }
            }
            append('"')
        }

    private fun compareUtf8(
        left: String,
        right: String,
    ): Int {
        val leftBytes = strictUtf8(left)
        val rightBytes = strictUtf8(right)
        repeat(minOf(leftBytes.size, rightBytes.size)) { index ->
            val compared = (leftBytes[index].toInt() and 0xff).compareTo(rightBytes[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return leftBytes.size.compareTo(rightBytes.size)
    }
}
