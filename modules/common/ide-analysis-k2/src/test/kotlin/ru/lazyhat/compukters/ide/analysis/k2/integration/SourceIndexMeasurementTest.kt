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

package ru.lazyhat.compukters.ide.analysis.k2.integration

import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.lazyhat.compukters.ide.analysis.k2.measurement.AnalysisMeasurementFixtures
import ru.lazyhat.compukters.ide.analysis.k2.measurement.PhaseSamples
import ru.lazyhat.compukters.ide.analysis.k2.measurement.SourceIndexPerformanceReport
import ru.lazyhat.compukters.ide.analysis.k2.query.K2QueryFixture
import ru.lazyhat.compukters.ide.analysis.k2.standalone.DocumentK2SourceUpdater
import ru.lazyhat.compukters.ide.analysis.k2.standalone.K2SourceUpdatePhase
import ru.lazyhat.compukters.ide.analysis.k2.standalone.K2SourceUpdatePhaseSample
import ru.lazyhat.compukters.ide.analysis.k2.standalone.MutableProjectSourceIndex
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceIndexMeasurementTest {
    @Test
    fun `measure source index and incremental workspace phases at file limit`() {
        assumeTrue(System.getProperty("compukters.analysis.performance") == "true")
        val measurementFixture = AnalysisMeasurementFixtures.maximumFiles()
        val phaseSamples = mutableListOf<K2SourceUpdatePhaseSample>()
        val updater = DocumentK2SourceUpdater.measured(System::nanoTime, phaseSamples::add)
        val sources = measurementFixture.sources.map { it.path.value to it.text }.toTypedArray()

        K2QueryFixture.sourceWithUpdater(updater, AnalysisLimits(sourceFiles = 512), *sources).use { fixture ->
            val files = fixture.snapshot.files.values
            val activePath =
                measurementFixture.sources
                    .last()
                    .path.value
            val initialIndex = mutableListOf<Long>()
            val indexReplace = mutableListOf<Long>()
            val workspaceUpdate = mutableListOf<Long>()

            repeat(WARM_UP_CYCLES) {
                MutableProjectSourceIndex(files)
                fixture.update(activePath to activeSource(it))
            }
            phaseSamples.clear()

            repeat(SAMPLE_COUNT) {
                initialIndex += measureNanos { MutableProjectSourceIndex(files) }
            }
            val directIndex = MutableProjectSourceIndex(files)
            val activeFile = fixture.snapshot.files.getValue(measurementFixture.sources.last().path)
            repeat(SAMPLE_COUNT) {
                val rebuildsBefore = directIndex.rebuildCount
                indexReplace += measureNanos { directIndex.replace(activeFile) }
                assertEquals(rebuildsBefore + 1, directIndex.rebuildCount)
            }

            val rebuildsBefore = fixture.snapshot.environment.sourceIndexRebuildCount
            repeat(SAMPLE_COUNT) { sample ->
                workspaceUpdate +=
                    measureNanos {
                        fixture.update(activePath to activeSource(sample + WARM_UP_CYCLES))
                    }
            }
            val rebuilds = fixture.snapshot.environment.sourceIndexRebuildCount - rebuildsBefore
            assertEquals(SAMPLE_COUNT, rebuilds)

            val phases = phaseSamples.groupBy(K2SourceUpdatePhaseSample::phase)
            val report =
                SourceIndexPerformanceReport(
                    files = measurementFixture.sources.size,
                    lines = measurementFixture.totalLines,
                    initialIndex = PhaseSamples(initialIndex),
                    indexReplace = PhaseSamples(indexReplace),
                    psiSynchronization = PhaseSamples(phases.samples(K2SourceUpdatePhase.PsiSynchronization)),
                    sourceReindex = PhaseSamples(phases.samples(K2SourceUpdatePhase.SourceReindex)),
                    k2Invalidation = PhaseSamples(phases.samples(K2SourceUpdatePhase.K2Invalidation)),
                    workspaceUpdate = PhaseSamples(workspaceUpdate),
                    rebuildsPerUpdate = rebuilds / SAMPLE_COUNT,
                )
            println(report.render())
        }
    }

    private fun activeSource(revision: Int): String =
        """
        package benchmark
        class ActiveType
        object ActiveObject${if (revision % 2 == 0) "A" else "B"}
        fun activeUse() = file0Seed()
        val activeValue get() = file510Seed()
        """.trimIndent()

    private fun Map<K2SourceUpdatePhase, List<K2SourceUpdatePhaseSample>>.samples(phase: K2SourceUpdatePhase): List<Long> =
        getValue(phase).map(K2SourceUpdatePhaseSample::durationNanos)

    private inline fun measureNanos(operation: () -> Unit): Long {
        val startedNanos = System.nanoTime()
        operation()
        return System.nanoTime() - startedNanos
    }

    private companion object {
        const val WARM_UP_CYCLES = 5
        const val SAMPLE_COUNT = 20
    }
}
