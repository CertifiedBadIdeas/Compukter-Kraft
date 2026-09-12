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

internal class IdeVisiblePhaseSamples(
    samples: List<IdeVisibleLatencySample>,
) {
    val kind: IdeVisibleLatencyKind
    val count: Int = samples.size

    private val analysis: List<Long>
    private val tickObservation: List<Long>
    private val renderWait: List<Long>
    private val total: List<Long>

    val analysisMedianNanos: Long
        get() = percentile(analysis, 50)
    val analysisP95Nanos: Long
        get() = percentile(analysis, 95)
    val tickObservationMedianNanos: Long
        get() = percentile(tickObservation, 50)
    val tickObservationP95Nanos: Long
        get() = percentile(tickObservation, 95)
    val renderWaitMedianNanos: Long
        get() = percentile(renderWait, 50)
    val renderWaitP95Nanos: Long
        get() = percentile(renderWait, 95)
    val totalMedianNanos: Long
        get() = percentile(total, 50)
    val totalP95Nanos: Long
        get() = percentile(total, 95)

    init {
        require(samples.isNotEmpty()) { "visible latency samples must not be empty" }
        kind = samples.first().kind
        require(samples.all { it.kind == kind }) { "visible latency samples must have one interaction kind" }
        require(
            samples.all {
                it.analysisNanos >= 0 &&
                    it.tickObservationNanos >= 0 &&
                    it.renderWaitNanos >= 0 &&
                    it.totalVisibleNanos >= 0
            },
        ) { "visible latency phases must not be negative" }
        analysis = samples.map(IdeVisibleLatencySample::analysisNanos).sorted()
        tickObservation = samples.map(IdeVisibleLatencySample::tickObservationNanos).sorted()
        renderWait = samples.map(IdeVisibleLatencySample::renderWaitNanos).sorted()
        total = samples.map(IdeVisibleLatencySample::totalVisibleNanos).sorted()
    }

    private fun percentile(
        values: List<Long>,
        percent: Int,
    ): Long {
        val index = ((values.size * percent + 99) / 100 - 1).coerceIn(values.indices)
        return values[index]
    }
}

internal data class IdeVisibleLatencyReport(
    val presentation: IdeVisiblePhaseSamples,
    val completion: IdeVisiblePhaseSamples,
    val droppedTraces: Long,
    val workerStarts: Long,
    val fullRebuilds: Long,
    val incrementalUpdates: Long,
    val heapBytes: Long,
    val metaspaceBytes: Long,
    val rssBytes: Long,
) {
    init {
        require(presentation.kind == IdeVisibleLatencyKind.Presentation) { "presentation samples have the wrong kind" }
        require(completion.kind == IdeVisibleLatencyKind.AutomaticCompletion) { "completion samples have the wrong kind" }
        require(droppedTraces >= 0) { "dropped traces must not be negative" }
        require(workerStarts >= 0) { "worker starts must not be negative" }
        require(fullRebuilds >= 0) { "full rebuilds must not be negative" }
        require(incrementalUpdates >= 0) { "incremental updates must not be negative" }
        require(heapBytes >= 0) { "heap bytes must not be negative" }
        require(metaspaceBytes >= 0) { "metaspace bytes must not be negative" }
        require(rssBytes >= 0) { "RSS bytes must not be negative" }
    }

    fun render(): String =
        buildString {
            appendLine("compukters.ide.visible-latency.v1")
            appendLine("schemaVersion=1")
            appendPhase("presentation", presentation)
            appendPhase("completion", completion)
            appendLine("droppedTraces=$droppedTraces")
            appendLine("workerStarts=$workerStarts")
            appendLine("fullRebuilds=$fullRebuilds")
            appendLine("incrementalUpdates=$incrementalUpdates")
            appendLine("heapBytes=$heapBytes")
            appendLine("metaspaceBytes=$metaspaceBytes")
            appendLine("rssBytes=$rssBytes")
        }

    private fun StringBuilder.appendPhase(
        name: String,
        samples: IdeVisiblePhaseSamples,
    ) {
        appendLine("$name.analysis.medianNanos=${samples.analysisMedianNanos}")
        appendLine("$name.analysis.p95Nanos=${samples.analysisP95Nanos}")
        appendLine("$name.tickObservation.medianNanos=${samples.tickObservationMedianNanos}")
        appendLine("$name.tickObservation.p95Nanos=${samples.tickObservationP95Nanos}")
        appendLine("$name.renderWait.medianNanos=${samples.renderWaitMedianNanos}")
        appendLine("$name.renderWait.p95Nanos=${samples.renderWaitP95Nanos}")
        appendLine("$name.total.medianNanos=${samples.totalMedianNanos}")
        appendLine("$name.total.p95Nanos=${samples.totalP95Nanos}")
    }
}
