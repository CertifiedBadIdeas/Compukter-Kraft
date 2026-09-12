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

package ru.lazyhat.compukters.ide.analysis.k2.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalysisPerformanceReportTest {
    @Test
    fun `phase samples use nearest-rank median and p95`() {
        val samples = PhaseSamples(listOf(9, 1, 5, 3, 7).map(Int::toLong))

        assertEquals(5, samples.medianNanos)
        assertEquals(9, samples.p95Nanos)
    }

    @Test
    fun `phase samples reject missing or negative measurements`() {
        assertFailsWith<IllegalArgumentException> { PhaseSamples(emptyList()) }
        assertFailsWith<IllegalArgumentException> { PhaseSamples(listOf(1, -1)) }
    }

    @Test
    fun `report renders a deterministic versioned field set`() {
        val report =
            AnalysisPerformanceReport(
                initialAdmission = PhaseSamples(listOf(10)),
                snapshotApply = PhaseSamples(listOf(11)),
                presentation = PhaseSamples(listOf(21, 20)),
                completion = PhaseSamples(listOf(31)),
                endToEndPresentation = PhaseSamples(listOf(41)),
                endToEndCompletion = PhaseSamples(listOf(51)),
                cancellation = PhaseSamples(listOf(61)),
                workerStarts = 1,
                fullRebuilds = 2,
                incrementalUpdates = 3,
                heapBytes = 4,
                metaspaceBytes = 5,
                rssBytes = 6,
            )

        assertEquals(
            """
            compukters.analysis.performance.v3
            schemaVersion=3
            initialAdmission.medianNanos=10
            initialAdmission.p95Nanos=10
            snapshotApply.medianNanos=11
            snapshotApply.p95Nanos=11
            presentation.medianNanos=20
            presentation.p95Nanos=21
            completion.medianNanos=31
            completion.p95Nanos=31
            endToEndPresentation.medianNanos=41
            endToEndPresentation.p95Nanos=41
            endToEndCompletion.medianNanos=51
            endToEndCompletion.p95Nanos=51
            cancellation.medianNanos=61
            cancellation.p95Nanos=61
            workerStarts=1
            fullRebuilds=2
            incrementalUpdates=3
            heapBytes=4
            metaspaceBytes=5
            rssBytes=6

            """.trimIndent(),
            report.render(),
        )
    }
}
