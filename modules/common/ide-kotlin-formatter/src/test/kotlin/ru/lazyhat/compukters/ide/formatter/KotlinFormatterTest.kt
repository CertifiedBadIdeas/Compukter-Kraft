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

package ru.lazyhat.compukters.ide.formatter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class KotlinFormatterTest {
    @Test
    fun `formats Kotlin with the repository standard rules`() {
        val source = "fun main(){val values=intArrayOf(7,11);println(\"\${values.size}: \${values[1]}\")}"
        val expected =
            """
            fun main() {
                val values = intArrayOf(7, 11)
                println("${'$'}{values.size}: ${'$'}{values[1]}")
            }

            """.trimIndent()

        val formatted = KotlinFormatter.format("main.kt", source)

        assertEquals(expected, formatted)
        assertEquals(formatted, KotlinFormatter.format("main.kt", formatted))
    }

    @Test
    fun `rejects malformed Kotlin and non-Kotlin paths`() {
        assertFails { KotlinFormatter.format("main.kt", "fun main( {") }
        assertFails { KotlinFormatter.format("notes.txt", "plain text") }
    }
}
