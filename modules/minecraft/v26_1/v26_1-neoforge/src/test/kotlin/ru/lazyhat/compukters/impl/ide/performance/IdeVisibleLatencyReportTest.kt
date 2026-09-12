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

package ru.lazyhat.compukters.impl.ide.performance

import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencyKind
import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencySample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdeVisibleLatencyReportTest {
    @Test
    fun `report renders deterministic schema three phase and total percentiles`() {
        val report =
            IdeVisibleLatencyReport(
                presentation = phase(IdeVisibleLatencyKind.Presentation, listOf(100L, 200L, 300L)),
                completion = phase(IdeVisibleLatencyKind.AutomaticCompletion, listOf(10L, 20L, 30L)),
                droppedTraces = 2,
                workerStarts = 1,
                fullRebuilds = 1,
                incrementalUpdates = 40,
                heapBytes = 128L * 1024 * 1024,
                metaspaceBytes = 64L * 1024 * 1024,
                rssBytes = 512L * 1024 * 1024,
            )

        assertEquals(
            """
            compukters.ide.visible-latency.v1
            schemaVersion=1
            presentation.analysis.medianNanos=20
            presentation.analysis.p95Nanos=30
            presentation.tickObservation.medianNanos=2
            presentation.tickObservation.p95Nanos=3
            presentation.renderWait.medianNanos=4
            presentation.renderWait.p95Nanos=6
            presentation.total.medianNanos=200
            presentation.total.p95Nanos=300
            completion.analysis.medianNanos=2
            completion.analysis.p95Nanos=3
            completion.tickObservation.medianNanos=0
            completion.tickObservation.p95Nanos=0
            completion.renderWait.medianNanos=0
            completion.renderWait.p95Nanos=0
            completion.total.medianNanos=20
            completion.total.p95Nanos=30
            droppedTraces=2
            workerStarts=1
            fullRebuilds=1
            incrementalUpdates=40
            heapBytes=134217728
            metaspaceBytes=67108864
            rssBytes=536870912
            """.trimIndent() + "\n",
            report.render(),
        )
    }

    @Test
    fun `phase samples use nearest rank and defensively copy input`() {
        val source = samples(IdeVisibleLatencyKind.Presentation, listOf(5L, 1L, 3L, 2L)).toMutableList()
        val phase = IdeVisiblePhaseSamples(source)
        source.clear()

        assertEquals(4, phase.count)
        assertEquals(2, phase.totalMedianNanos)
        assertEquals(5, phase.totalP95Nanos)
    }

    @Test
    fun `report rejects missing kinds negative phases and invalid counters`() {
        assertFailsWith<IllegalArgumentException> { IdeVisiblePhaseSamples(emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            IdeVisiblePhaseSamples(listOf(sample(IdeVisibleLatencyKind.Presentation, total = -1)))
        }
        assertFailsWith<IllegalArgumentException> {
            validReport().copy(presentation = phase(IdeVisibleLatencyKind.AutomaticCompletion, listOf(1)))
        }
        assertFailsWith<IllegalArgumentException> { validReport().copy(workerStarts = -1) }
        assertFailsWith<IllegalArgumentException> { validReport().copy(rssBytes = -1) }
    }

    private fun validReport() =
        IdeVisibleLatencyReport(
            presentation = phase(IdeVisibleLatencyKind.Presentation, listOf(1)),
            completion = phase(IdeVisibleLatencyKind.AutomaticCompletion, listOf(1)),
            droppedTraces = 0,
            workerStarts = 1,
            fullRebuilds = 1,
            incrementalUpdates = 1,
            heapBytes = 1,
            metaspaceBytes = 1,
            rssBytes = 1,
        )

    private fun phase(
        kind: IdeVisibleLatencyKind,
        totals: List<Long>,
    ) = IdeVisiblePhaseSamples(samples(kind, totals))

    private fun samples(
        kind: IdeVisibleLatencyKind,
        totals: List<Long>,
    ) = totals.mapIndexed { index, total -> sample(kind, index.toLong() + 1, total) }

    private fun sample(
        kind: IdeVisibleLatencyKind,
        revision: Long = 1,
        total: Long,
    ) = IdeVisibleLatencySample(
        kind = kind,
        documentRevision = revision,
        analysisNanos = total.coerceAtLeast(0) / 10,
        tickObservationNanos = total.coerceAtLeast(0) / 100,
        renderWaitNanos = total.coerceAtLeast(0) / 50,
        totalVisibleNanos = total,
    )
}
