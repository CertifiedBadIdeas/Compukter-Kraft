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

class SourceIndexPerformanceReportTest {
    @Test
    fun `report renders deterministic source index phase fields`() {
        val report =
            SourceIndexPerformanceReport(
                files = 512,
                lines = 4_992,
                initialIndex = PhaseSamples(listOf(11)),
                indexReplace = PhaseSamples(listOf(21)),
                psiSynchronization = PhaseSamples(listOf(31)),
                sourceReindex = PhaseSamples(listOf(41)),
                k2Invalidation = PhaseSamples(listOf(51)),
                workspaceUpdate = PhaseSamples(listOf(61)),
                rebuildsPerUpdate = 1,
            )

        assertEquals(
            """
            compukters.analysis.sourceIndexPerformance.v1
            schemaVersion=1
            files=512
            lines=4992
            initialIndex.medianNanos=11
            initialIndex.p95Nanos=11
            indexReplace.medianNanos=21
            indexReplace.p95Nanos=21
            psiSynchronization.medianNanos=31
            psiSynchronization.p95Nanos=31
            sourceReindex.medianNanos=41
            sourceReindex.p95Nanos=41
            k2Invalidation.medianNanos=51
            k2Invalidation.p95Nanos=51
            workspaceUpdate.medianNanos=61
            workspaceUpdate.p95Nanos=61
            rebuildsPerUpdate=1

            """.trimIndent(),
            report.render(),
        )
    }

    @Test
    fun `report rejects invalid fixture and rebuild dimensions`() {
        val sample = PhaseSamples(listOf(1))

        assertFailsWith<IllegalArgumentException> {
            SourceIndexPerformanceReport(0, 1, sample, sample, sample, sample, sample, sample, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            SourceIndexPerformanceReport(1, 0, sample, sample, sample, sample, sample, sample, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            SourceIndexPerformanceReport(1, 1, sample, sample, sample, sample, sample, sample, 2)
        }
    }
}
