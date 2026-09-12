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

package ru.lazyhat.compukters.ide.analysis.controller

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisSchedulerTest {
    private val first = identity(1)
    private val second = identity(2)

    @Test
    fun `scheduler retains only active latest interactive and coalesced presentation`() {
        val scheduler = AnalysisScheduler<String>()

        assertEquals("active", scheduler.offer(work("active", first, AnalysisWorkKind.ManualInteractive)).started?.value)
        assertNull(scheduler.offer(work("auto-1", first, AnalysisWorkKind.AutomaticCompletion)).started)
        assertEquals(
            listOf("auto-1"),
            scheduler.offer(work("auto-2", first, AnalysisWorkKind.AutomaticCompletion)).displaced.map { it.value },
        )
        assertEquals(
            emptyList(),
            scheduler.offer(work("presentation-1", first, AnalysisWorkKind.BackgroundPresentation)).displaced,
        )
        assertEquals(
            listOf("presentation-1"),
            scheduler.offer(work("presentation-2", first, AnalysisWorkKind.BackgroundPresentation)).displaced.map { it.value },
        )

        assertEquals(3, scheduler.size)
        assertEquals("auto-2", scheduler.completeActive().started?.value)
        assertEquals("presentation-2", scheduler.completeActive().started?.value)
        assertNull(scheduler.completeActive().started)
    }

    @Test
    fun `manual work outranks presentation and cannot be displaced by automatic completion`() {
        val scheduler = AnalysisScheduler<String>()
        scheduler.offer(work("active", first, AnalysisWorkKind.BackgroundPresentation))
        scheduler.offer(work("manual", first, AnalysisWorkKind.ManualInteractive))

        val rejected = scheduler.offer(work("automatic", first, AnalysisWorkKind.AutomaticCompletion))

        assertFalse(rejected.accepted)
        assertEquals(listOf("automatic"), rejected.displaced.map { it.value })
        assertEquals("manual", scheduler.completeActive().started?.value)
    }

    @Test
    fun `new snapshot removes every queued stale item and active identity remains observable`() {
        val scheduler = AnalysisScheduler<String>()
        scheduler.offer(work("active", first, AnalysisWorkKind.ManualInteractive))
        scheduler.offer(work("interactive", first, AnalysisWorkKind.ManualInteractive))
        scheduler.offer(work("background", first, AnalysisWorkKind.BackgroundPresentation))

        val stale = scheduler.dropQueuedExcept(second)

        assertEquals(listOf("interactive", "background"), stale.map { it.value })
        assertEquals(first, scheduler.active?.identity)
        assertTrue(scheduler.removeActive("active"))
        assertEquals(0, scheduler.size)
    }

    private fun work(
        value: String,
        identity: AnalysisSnapshotIdentity,
        kind: AnalysisWorkKind,
    ) = ScheduledAnalysisWork(identity, kind, value)

    private fun identity(value: Int) =
        AnalysisSnapshotIdentity(
            SourceSnapshotId(Hash256.of(ByteArray(32) { value.toByte() })),
            AnalysisProfileIdentity(Hash256.of(ByteArray(32) { (value + 1).toByte() })),
        )
}
