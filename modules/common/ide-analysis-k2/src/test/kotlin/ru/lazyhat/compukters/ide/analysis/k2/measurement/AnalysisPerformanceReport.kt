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

internal class PhaseSamples(
    valuesNanos: List<Long>,
) {
    private val sortedNanos = valuesNanos.toList().sorted()

    init {
        require(sortedNanos.isNotEmpty()) { "analysis phase must contain at least one measurement" }
        require(sortedNanos.all { it >= 0 }) { "analysis phase measurements must not be negative" }
    }

    val medianNanos: Long
        get() = percentile(50)

    val p95Nanos: Long
        get() = percentile(95)

    private fun percentile(percent: Int): Long {
        val index = ((sortedNanos.size * percent + 99) / 100 - 1).coerceIn(sortedNanos.indices)
        return sortedNanos[index]
    }
}

internal data class AnalysisPerformanceReport(
    val initialAdmission: PhaseSamples,
    val snapshotApply: PhaseSamples,
    val presentation: PhaseSamples,
    val completion: PhaseSamples,
    val endToEndPresentation: PhaseSamples,
    val endToEndCompletion: PhaseSamples,
    val cancellation: PhaseSamples,
    val workerStarts: Int,
    val fullRebuilds: Int,
    val incrementalUpdates: Int,
    val heapBytes: Long,
    val metaspaceBytes: Long,
    val rssBytes: Long,
) {
    init {
        require(workerStarts >= 0) { "worker start count must not be negative" }
        require(fullRebuilds >= 0) { "full rebuild count must not be negative" }
        require(incrementalUpdates >= 0) { "incremental update count must not be negative" }
        require(heapBytes >= 0) { "heap usage must not be negative" }
        require(metaspaceBytes >= 0) { "metaspace usage must not be negative" }
        require(rssBytes >= 0) { "resident usage must not be negative" }
    }

    fun render(): String =
        buildString {
            appendLine("compukters.analysis.performance.v$SCHEMA_VERSION")
            appendLine("schemaVersion=$SCHEMA_VERSION")
            appendPhase("initialAdmission", initialAdmission)
            appendPhase("snapshotApply", snapshotApply)
            appendPhase("presentation", presentation)
            appendPhase("completion", completion)
            appendPhase("endToEndPresentation", endToEndPresentation)
            appendPhase("endToEndCompletion", endToEndCompletion)
            appendPhase("cancellation", cancellation)
            appendLine("workerStarts=$workerStarts")
            appendLine("fullRebuilds=$fullRebuilds")
            appendLine("incrementalUpdates=$incrementalUpdates")
            appendLine("heapBytes=$heapBytes")
            appendLine("metaspaceBytes=$metaspaceBytes")
            appendLine("rssBytes=$rssBytes")
        }

    private fun StringBuilder.appendPhase(
        name: String,
        samples: PhaseSamples,
    ) {
        appendLine("$name.medianNanos=${samples.medianNanos}")
        appendLine("$name.p95Nanos=${samples.p95Nanos}")
    }

    private companion object {
        const val SCHEMA_VERSION = 3
    }
}
