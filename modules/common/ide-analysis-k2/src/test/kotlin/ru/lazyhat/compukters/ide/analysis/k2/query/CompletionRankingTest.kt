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

class CompletionRankingTest {
    @Test
    fun `duplicate keys retain the best candidate without consuming capacity`() {
        val best = BoundedUniqueBest<String, Candidate>(2, compareBy(Candidate::rank), Candidate::key)

        best.offer(Candidate("println", 3))
        best.offer(Candidate("println", 1))
        best.offer(Candidate("print", 2))

        assertEquals(listOf(Candidate("println", 1), Candidate("print", 2)), best.sorted())
    }

    private data class Candidate(
        val key: String,
        val rank: Int,
    )
}
