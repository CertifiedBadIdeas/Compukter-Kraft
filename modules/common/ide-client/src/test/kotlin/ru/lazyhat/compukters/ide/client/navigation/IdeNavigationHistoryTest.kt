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

package ru.lazyhat.compukters.ide.client.navigation

import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IdeNavigationHistoryTest {
    @Test
    fun `new navigation after back truncates forward history`() {
        val history = IdeNavigationHistory(128)
        val a = position("src/a.kt")
        val b = position("src/b.kt")
        val c = position("src/c.kt")
        val d = position("src/d.kt")
        history.record(a, b)
        history.record(b, c)

        assertEquals(b, history.back(c))
        history.record(b, d)

        assertNull(history.forward(d))
    }

    @Test
    fun `history evicts the oldest back positions at its bound`() {
        val history = IdeNavigationHistory(2)
        val a = position("src/a.kt")
        val b = position("src/b.kt")
        val c = position("src/c.kt")
        val d = position("src/d.kt")
        history.record(a, b)
        history.record(b, c)
        history.record(c, d)

        assertEquals(c, history.back(d))
        assertEquals(b, history.back(c))
        assertNull(history.back(b))
    }

    @Test
    fun `history requires a positive bound and ignores identical positions`() {
        assertFailsWith<IllegalArgumentException> { IdeNavigationHistory(0) }
        val history = IdeNavigationHistory(1)
        val a = position("src/a.kt")

        history.record(a, a)

        assertNull(history.back(a))
    }

    private fun position(path: String) =
        IdeNavigationPosition(
            IdeNavigationSource.Project(ProjectPath.file(path)),
            caretUtf16 = 0,
            firstVisibleLine = 0,
            firstVisibleColumn = 0,
        )
}
