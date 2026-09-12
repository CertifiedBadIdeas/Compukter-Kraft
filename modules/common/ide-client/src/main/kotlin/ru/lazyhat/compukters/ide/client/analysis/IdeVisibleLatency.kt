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

package ru.lazyhat.compukters.ide.client.analysis

import java.util.ArrayDeque
import java.util.EnumMap

enum class IdeVisibleLatencyKind {
    Presentation,
    AutomaticCompletion,
}

fun interface IdeVisibleLatencyClock {
    fun nowNanos(): Long

    data object System : IdeVisibleLatencyClock {
        override fun nowNanos(): Long = java.lang.System.nanoTime()
    }
}

data class IdeVisibleLatencySample(
    val kind: IdeVisibleLatencyKind,
    val documentRevision: Long,
    val analysisNanos: Long,
    val tickObservationNanos: Long,
    val renderWaitNanos: Long,
    val totalVisibleNanos: Long,
)

interface IdeVisibleLatencyTrace {
    fun editApplied(documentRevision: Long)

    fun automaticCompletionExpected(documentRevision: Long)

    fun analysisPublished(
        kind: IdeVisibleLatencyKind,
        documentRevision: Long,
    )

    fun controllerObserved(documentRevision: Long)

    fun frameExtracted(
        documentRevision: Long,
        presentationVisible: Boolean,
        completionVisible: Boolean,
    )

    fun resultUnavailable(
        kind: IdeVisibleLatencyKind,
        documentRevision: Long,
    )

    fun dropActive()

    data object None : IdeVisibleLatencyTrace {
        override fun editApplied(documentRevision: Long) = Unit

        override fun automaticCompletionExpected(documentRevision: Long) = Unit

        override fun analysisPublished(
            kind: IdeVisibleLatencyKind,
            documentRevision: Long,
        ) = Unit

        override fun controllerObserved(documentRevision: Long) = Unit

        override fun frameExtracted(
            documentRevision: Long,
            presentationVisible: Boolean,
            completionVisible: Boolean,
        ) = Unit

        override fun resultUnavailable(
            kind: IdeVisibleLatencyKind,
            documentRevision: Long,
        ) = Unit

        override fun dropActive() = Unit
    }
}

class BoundedIdeVisibleLatencyCollector(
    private val clock: IdeVisibleLatencyClock,
    private val maximumSamples: Int,
) : IdeVisibleLatencyTrace {
    private val active = EnumMap<IdeVisibleLatencyKind, ActiveTrace>(IdeVisibleLatencyKind::class.java)
    private val retiredRevisions = EnumMap<IdeVisibleLatencyKind, Long>(IdeVisibleLatencyKind::class.java)
    private val completed = ArrayDeque<IdeVisibleLatencySample>()
    private var latestEdit: EditStart? = null
    private var lastTimestamp: Long? = null

    @get:Synchronized
    var droppedTraces: Long = 0
        private set

    init {
        require(maximumSamples > 0) { "maximum samples must be positive" }
    }

    @Synchronized
    override fun editApplied(documentRevision: Long) {
        validateRevision(documentRevision)
        val priorEdit = latestEdit
        if (priorEdit != null && documentRevision <= priorEdit.documentRevision) return
        val timestamp = now()
        active.values
            .filter { it.documentRevision < documentRevision }
            .toList()
            .forEach(::retire)
        latestEdit = EditStart(documentRevision, timestamp)
        active[IdeVisibleLatencyKind.Presentation] =
            ActiveTrace(IdeVisibleLatencyKind.Presentation, documentRevision, timestamp)
    }

    @Synchronized
    override fun automaticCompletionExpected(documentRevision: Long) {
        validateRevision(documentRevision)
        val edit = latestEdit?.takeIf { it.documentRevision == documentRevision } ?: return
        val kind = IdeVisibleLatencyKind.AutomaticCompletion
        if (retiredRevisions[kind]?.let { documentRevision <= it } == true) return
        val current = active[kind]
        if (current?.documentRevision == documentRevision) return
        active[kind] = ActiveTrace(kind, documentRevision, edit.timestamp)
    }

    @Synchronized
    override fun analysisPublished(
        kind: IdeVisibleLatencyKind,
        documentRevision: Long,
    ) {
        validateRevision(documentRevision)
        val trace = active[kind]?.takeIf { it.documentRevision == documentRevision && it.analysisPublished == null } ?: return
        trace.analysisPublished = now()
    }

    @Synchronized
    override fun controllerObserved(documentRevision: Long) {
        validateRevision(documentRevision)
        val matching =
            active.values.filter { trace ->
                trace.documentRevision == documentRevision && trace.analysisPublished != null && trace.controllerObserved == null
            }
        if (matching.isEmpty()) return
        val timestamp = now()
        matching.forEach { it.controllerObserved = timestamp }
    }

    @Synchronized
    override fun frameExtracted(
        documentRevision: Long,
        presentationVisible: Boolean,
        completionVisible: Boolean,
    ) {
        validateRevision(documentRevision)
        val matching =
            active.values.filter { trace ->
                trace.documentRevision == documentRevision &&
                    trace.controllerObserved != null &&
                    when (trace.kind) {
                        IdeVisibleLatencyKind.Presentation -> presentationVisible
                        IdeVisibleLatencyKind.AutomaticCompletion -> completionVisible
                    }
            }
        if (matching.isEmpty()) return
        val timestamp = now()
        matching.forEach { trace -> complete(trace, timestamp) }
    }

    @Synchronized
    override fun resultUnavailable(
        kind: IdeVisibleLatencyKind,
        documentRevision: Long,
    ) {
        validateRevision(documentRevision)
        active[kind]?.takeIf { it.documentRevision == documentRevision }?.let(::retire)
    }

    @Synchronized
    override fun dropActive() {
        active.values.toList().forEach(::retire)
        latestEdit = null
        retiredRevisions.clear()
    }

    @Synchronized
    fun samples(): List<IdeVisibleLatencySample> = completed.toList()

    private fun complete(
        trace: ActiveTrace,
        frameExtracted: Long,
    ) {
        val analysisPublished = requireNotNull(trace.analysisPublished)
        val controllerObserved = requireNotNull(trace.controllerObserved)
        val sample =
            IdeVisibleLatencySample(
                kind = trace.kind,
                documentRevision = trace.documentRevision,
                analysisNanos = duration(analysisPublished, trace.editApplied),
                tickObservationNanos = duration(controllerObserved, analysisPublished),
                renderWaitNanos = duration(frameExtracted, controllerObserved),
                totalVisibleNanos = duration(frameExtracted, trace.editApplied),
            )
        active.remove(trace.kind)
        retireRevision(trace.kind, trace.documentRevision)
        completed.addLast(sample)
        while (completed.size > maximumSamples) completed.removeFirst()
    }

    private fun retire(trace: ActiveTrace) {
        if (!active.remove(trace.kind, trace)) return
        retireRevision(trace.kind, trace.documentRevision)
        incrementDropped()
    }

    private fun retireRevision(
        kind: IdeVisibleLatencyKind,
        documentRevision: Long,
    ) {
        val prior = retiredRevisions[kind]
        if (prior == null || documentRevision > prior) retiredRevisions[kind] = documentRevision
    }

    private fun incrementDropped() {
        if (droppedTraces < Long.MAX_VALUE) droppedTraces++
    }

    private fun now(): Long {
        val timestamp = clock.nowNanos()
        val prior = lastTimestamp
        check(prior == null || timestamp >= prior) { "visible latency clock moved backwards" }
        lastTimestamp = timestamp
        return timestamp
    }

    private fun duration(
        end: Long,
        start: Long,
    ): Long =
        Math.subtractExact(end, start).also { value ->
            check(value >= 0) { "visible latency duration must not be negative" }
        }

    private fun validateRevision(documentRevision: Long) {
        require(documentRevision >= 0) { "document revision must not be negative" }
    }

    private data class EditStart(
        val documentRevision: Long,
        val timestamp: Long,
    )

    private class ActiveTrace(
        val kind: IdeVisibleLatencyKind,
        val documentRevision: Long,
        val editApplied: Long,
        var analysisPublished: Long? = null,
        var controllerObserved: Long? = null,
    )
}
