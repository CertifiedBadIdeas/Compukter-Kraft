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

package ru.lazyhat.compukters.ide.analysis.k2.query

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParameterInfoQueryTest {
    @Test
    fun `parameter info preserves overloads and marks the current positional parameter`() {
        val source =
            """
            fun greet(name: String, times: Int) = Unit
            fun greet(name: String, excited: Boolean) = Unit
            fun main() { greet("Ada", 2) }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val info = assertNotNull(fixture.parameterInfo(source.indexOf("2)")))

            assertEquals(2, info.items.size)
            assertTrue(info.items.all { item -> item.activeText() in setOf("times: Int", "excited: Boolean") })
            assertTrue(info.items.first().bestCandidate)
        }
    }

    @Test
    fun `parameter info selects named and vararg parameters`() {
        val source =
            """
            fun configure(name: String, count: Int) = Unit
            fun emit(prefix: String, vararg values: Int) = Unit
            fun main() { configure(count = 2, name = "ok"); emit("n", 1, 2) }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val named = assertNotNull(fixture.parameterInfo(source.indexOf("\"ok\"") + 1)).items.single()
            val vararg = assertNotNull(fixture.parameterInfo(source.lastIndexOf("2)"))).items.single()

            assertEquals("name: String", named.activeText())
            assertEquals("vararg values: Int", vararg.activeText())
        }
    }

    @Test
    fun `parameter info chooses the innermost call and returns no value outside arguments`() {
        val source =
            """
            fun inner(left: Int, right: Int) = left + right
            fun outer(value: Int) = Unit
            fun main() { outer(inner(1, 2)) }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val nested = assertNotNull(fixture.parameterInfo(source.indexOf("2)")))

            assertTrue(
                nested.items
                    .single()
                    .signature
                    .startsWith("inner("),
            )
            assertEquals("right: Int", nested.items.single().activeText())
            assertNull(fixture.parameterInfo(source.indexOf("fun main")))
        }
    }

    @Test
    fun `parameter info exposes a platform Int default`() {
        val source =
            """
            import compukter.sound.Sound

            fun main() { Sound.beep(12, ) }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val info = assertNotNull(fixture.parameterInfo(source.indexOf(",") + 2))

            assertEquals(listOf("beep(note: Int, volume: Int = …): Boolean"), info.items.map { it.signature })
            assertEquals("volume: Int = …", info.items.single().activeText())
        }
    }

    private fun K2QueryFixture.parameterInfo(offset: Int) =
        (execute(AnalysisQuery.ParameterInfo(identity, VirtualSourcePath.kotlin("main.kt"), offset)) as AnalysisResult.ParameterInfo).value

    private fun ru.lazyhat.compukters.ide.analysis.ParameterInfoItem.activeText(): String? =
        activeParameter?.let { range -> signature.substring(range.startUtf16, range.endUtf16) }
}
