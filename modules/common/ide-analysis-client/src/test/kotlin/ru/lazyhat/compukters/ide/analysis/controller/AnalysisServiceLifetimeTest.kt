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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AnalysisServiceLifetimeTest {
    @Test
    fun `sessions share a lazily created client and final close starts one idle timeout`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val created = mutableListOf<RecordingAnalysisClient>()
        val service = AnalysisServiceLifetime(50, scheduler) { RecordingAnalysisClient().also(created::add) }

        val first = service.openSession()
        val second = service.openSession()
        assertEquals(0, created.size)
        assertSame(first.client, second.client)

        val snapshot = testSnapshot()
        first.client.query(snapshot, testQuery(snapshot))
        assertEquals(1, created.size)
        first.close()
        assertEquals(0, scheduler.pendingCount)
        second.close()
        second.close()
        assertEquals(1, scheduler.pendingCount)

        scheduler.advanceBy(49)
        assertEquals(0, created.single().closeCount)
        scheduler.advanceBy(1)
        assertEquals(1, created.single().closeCount)
    }

    @Test
    fun `reopening before idle expiry cancels shutdown and later creates a fresh client`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val created = mutableListOf<RecordingAnalysisClient>()
        val service = AnalysisServiceLifetime(50, scheduler) { RecordingAnalysisClient().also(created::add) }
        val first = service.openSession()
        val snapshot = testSnapshot()
        first.client.query(snapshot, testQuery(snapshot))
        first.close()

        val reopened = service.openSession()
        scheduler.advanceBy(50)
        assertEquals(0, created.single().closeCount)
        reopened.close()
        scheduler.advanceBy(50)
        assertEquals(1, created.single().closeCount)

        service.openSession().use { it.client.query(snapshot, testQuery(snapshot)) }
        assertEquals(2, created.size)
        service.close()
        assertEquals(1, created.last().closeCount)
    }
}
