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

package ru.lazyhat.compukters.ide.analysis.k2.standalone

import ru.lazyhat.compukters.ide.analysis.k2.query.K2QueryFixture
import kotlin.test.Test
import kotlin.test.assertEquals

class K2SourceUpdateMeasurementTest {
    @Test
    fun `measured source update reports ordered phases and one index rebuild`() {
        val samples = mutableListOf<K2SourceUpdatePhaseSample>()
        val times = ArrayDeque(listOf(10L, 15L, 20L, 27L, 30L, 39L))
        val updater =
            DocumentK2SourceUpdater.measured(
                clock = { times.removeFirst() },
                observer = samples::add,
            )

        K2QueryFixture.sourceWithUpdater(updater, "main.kt" to "object Before").use { fixture ->
            val rebuildsBefore = fixture.snapshot.environment.sourceIndexRebuildCount

            fixture.update("main.kt" to "object After")

            assertEquals(rebuildsBefore + 1, fixture.snapshot.environment.sourceIndexRebuildCount)
        }
        assertEquals(
            listOf(
                K2SourceUpdatePhaseSample(K2SourceUpdatePhase.PsiSynchronization, 5),
                K2SourceUpdatePhaseSample(K2SourceUpdatePhase.SourceReindex, 7),
                K2SourceUpdatePhaseSample(K2SourceUpdatePhase.K2Invalidation, 9),
            ),
            samples,
        )
        assertEquals(0, times.size)
    }
}
