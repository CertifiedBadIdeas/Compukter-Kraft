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
import ru.lazyhat.compukters.ide.analysis.k2.formatter.KotlinSourceFormatter
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FormatQueryTest {
    @Test
    fun `format query uses the exact submitted source and maps its caret`() {
        K2QueryFixture.source("main.kt" to "fun main() = Unit").use { fixture ->
            val submitted = "fun main(){println(1)}"
            val formatted = "fun main() {\n    println(1)\n}\n"
            var receivedSource: String? = null
            val result =
                FormatQuery.execute(
                    AnalysisQuery.Format(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        submitted,
                        submitted.indexOf("println"),
                    ),
                    fixture.snapshot,
                    AnalysisLimits(),
                    KotlinSourceFormatter { _, source ->
                        receivedSource = source
                        formatted
                    },
                )

            assertEquals(submitted, receivedSource)
            assertEquals(formatted, result.source)
            assertEquals(formatted.indexOf("println"), result.caretOffsetUtf16)
        }
    }

    @Test
    fun `format query rejects a path outside the active snapshot`() {
        K2QueryFixture.source("main.kt" to "fun main() = Unit").use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                FormatQuery.execute(
                    AnalysisQuery.Format(
                        fixture.identity,
                        VirtualSourcePath.kotlin("other.kt"),
                        "fun other(){}",
                        0,
                    ),
                    fixture.snapshot,
                    AnalysisLimits(),
                    KotlinSourceFormatter { _, source -> source },
                )
            }
        }
    }

    @Test
    fun `caret mapping never splits a surrogate pair`() {
        val source = "val face=\"😀\""
        val formatted = "val face = \"😀\"\n"

        val mapped = FormatQuery.mapCaret(source, formatted, source.indexOf("😀") + 2)

        assertEquals(formatted.indexOf("😀") + 2, mapped)
    }
}
