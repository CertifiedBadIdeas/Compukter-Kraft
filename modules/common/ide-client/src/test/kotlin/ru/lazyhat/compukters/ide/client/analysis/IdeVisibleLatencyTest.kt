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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdeVisibleLatencyTest {
    @Test
    fun `fresh presentation records every phase for one exact revision`() {
        val clock = MutableNanoClock()
        val trace = BoundedIdeVisibleLatencyCollector(clock, maximumSamples = 4)

        trace.editApplied(7)
        clock.advance(100)
        trace.analysisPublished(IdeVisibleLatencyKind.Presentation, 7)
        clock.advance(20)
        trace.controllerObserved(7)
        clock.advance(5)
        trace.frameExtracted(7, presentationVisible = true, completionVisible = false)

        assertEquals(
            IdeVisibleLatencySample(IdeVisibleLatencyKind.Presentation, 7, 100, 20, 5, 125),
            trace.samples().single(),
        )
    }

    @Test
    fun `new revision drops unfinished traces and stale frames cannot complete them`() {
        val clock = MutableNanoClock()
        val trace = BoundedIdeVisibleLatencyCollector(clock, maximumSamples = 4)
        trace.editApplied(1)
        trace.automaticCompletionExpected(1)
        trace.editApplied(2)
        trace.analysisPublished(IdeVisibleLatencyKind.Presentation, 1)
        trace.controllerObserved(1)
        trace.frameExtracted(1, presentationVisible = true, completionVisible = true)

        assertEquals(2, trace.droppedTraces)
        assertTrue(trace.samples().isEmpty())
    }

    @Test
    fun `automatic completion shares edit start and requires a visible popup`() {
        val clock = MutableNanoClock()
        val trace = BoundedIdeVisibleLatencyCollector(clock, maximumSamples = 4)
        trace.editApplied(3)
        clock.advance(10)
        trace.automaticCompletionExpected(3)
        clock.advance(30)
        trace.analysisPublished(IdeVisibleLatencyKind.AutomaticCompletion, 3)
        trace.controllerObserved(3)
        trace.frameExtracted(3, presentationVisible = false, completionVisible = false)
        assertTrue(trace.samples().isEmpty())
        clock.advance(2)
        trace.frameExtracted(3, presentationVisible = false, completionVisible = true)
        assertEquals(42, trace.samples().single().totalVisibleNanos)
    }

    @Test
    fun `duplicate notifications are idempotent and completed storage is bounded`() {
        val clock = MutableNanoClock()
        val trace = BoundedIdeVisibleLatencyCollector(clock, maximumSamples = 2)
        repeat(3) { revision ->
            trace.editApplied(revision.toLong())
            trace.analysisPublished(IdeVisibleLatencyKind.Presentation, revision.toLong())
            trace.analysisPublished(IdeVisibleLatencyKind.Presentation, revision.toLong())
            trace.controllerObserved(revision.toLong())
            trace.frameExtracted(revision.toLong(), presentationVisible = true, completionVisible = false)
            trace.frameExtracted(revision.toLong(), presentationVisible = true, completionVisible = false)
        }
        assertEquals(listOf(1L, 2L), trace.samples().map(IdeVisibleLatencySample::documentRevision))
    }

    @Test
    fun `lifecycle drop releases active traces and backward clock is rejected`() {
        val clock = MutableNanoClock(100)
        val trace = BoundedIdeVisibleLatencyCollector(clock, maximumSamples = 2)
        trace.editApplied(9)
        trace.dropActive()
        assertEquals(1, trace.droppedTraces)
        clock.now = 99
        assertFailsWith<IllegalStateException> {
            trace.editApplied(10)
        }
    }

    @Test
    fun `empty result drops only its matching interaction without a sample`() {
        val clock = MutableNanoClock()
        val trace = BoundedIdeVisibleLatencyCollector(clock, maximumSamples = 2)
        trace.editApplied(11)
        trace.automaticCompletionExpected(11)
        trace.resultUnavailable(IdeVisibleLatencyKind.AutomaticCompletion, 11)

        assertEquals(1, trace.droppedTraces)
        trace.analysisPublished(IdeVisibleLatencyKind.Presentation, 11)
        trace.controllerObserved(11)
        trace.frameExtracted(11, presentationVisible = true, completionVisible = false)
        assertEquals(IdeVisibleLatencyKind.Presentation, trace.samples().single().kind)
    }

    @Test
    fun `lifecycle drop allows a new document to restart its revisions`() {
        val clock = MutableNanoClock()
        val trace = BoundedIdeVisibleLatencyCollector(clock, maximumSamples = 2)
        trace.editApplied(9)
        trace.dropActive()

        trace.editApplied(0)
        trace.analysisPublished(IdeVisibleLatencyKind.Presentation, 0)
        trace.controllerObserved(0)
        trace.frameExtracted(0, presentationVisible = true, completionVisible = false)

        assertEquals(0, trace.samples().single().documentRevision)
    }
}

private class MutableNanoClock(
    var now: Long = 0,
) : IdeVisibleLatencyClock {
    override fun nowNanos(): Long = now

    fun advance(delta: Long) {
        now = Math.addExact(now, delta)
    }
}
