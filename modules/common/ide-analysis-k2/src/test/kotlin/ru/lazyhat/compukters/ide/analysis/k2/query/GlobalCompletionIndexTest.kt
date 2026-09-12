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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalCompletionIndexTest {
    @Test
    fun `project index contains public importable top level declarations and preserves overloads`() {
        val declarations =
            """
            package library
            class Visible { class Nested }
            fun choose(value: Int) = value
            fun choose(value: String) = value
            fun String.extension() = Unit
            private object Hidden
            internal val internalOnly = 1
            """.trimIndent()
        K2QueryFixture.source("library.kt" to declarations, "main.kt" to "fun main() = Unit").use { fixture ->
            val index = fixture.snapshot.projectCompletionIndex

            assertEquals(listOf("Visible"), index.lookup("Vi", 8).map { it.shortName })
            assertEquals(2, index.lookup("cho", 8).count { it.shortName == "choose" })
            assertTrue(index.lookup("Nested", 8).isEmpty())
            assertTrue(index.lookup("extension", 8).isEmpty())
            assertTrue(index.lookup("Hidden", 8).isEmpty())
            assertTrue(index.lookup("internal", 8).isEmpty())
        }
    }

    @Test
    fun `project index replaces changed file declarations without retaining stale names`() {
        K2QueryFixture.source("api.kt" to "class OldName", "main.kt" to "fun main() = Unit").use { fixture ->
            assertEquals(
                listOf("OldName"),
                fixture.snapshot.projectCompletionIndex
                    .lookup("Old", 8)
                    .map { it.shortName },
            )

            fixture.update("api.kt" to "class NewName")

            assertTrue(
                fixture.snapshot.projectCompletionIndex
                    .lookup("Old", 8)
                    .isEmpty(),
            )
            assertEquals(
                listOf("NewName"),
                fixture.snapshot.projectCompletionIndex
                    .lookup("New", 8)
                    .map { it.shortName },
            )
        }
    }

    @Test
    fun `platform index includes declarations from modules outside the admitted K2 profile`() {
        K2QueryFixture.source("main.kt" to "fun main() = Unit").use { fixture ->
            val redstone = fixture.snapshot.platformCompletionIndex.lookup("Redstone", 8)

            assertTrue(redstone.any { it.fqName == "compukter.redstone.Redstone" }, redstone.toString())
        }
    }
}
