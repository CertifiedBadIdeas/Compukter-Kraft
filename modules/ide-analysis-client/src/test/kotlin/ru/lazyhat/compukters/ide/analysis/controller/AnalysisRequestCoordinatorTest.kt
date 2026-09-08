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

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnalysisRequestCoordinatorTest {
    @Test
    fun `parameter info is immediate and a newer request cancels the prior query`() {
        val client = RecordingAnalysisClient()
        val coordinator = DefaultAnalysisRequestCoordinator(client, ManualAnalysisTaskScheduler(), 0, 0)
        val snapshot = admittedSnapshot("fun main() { println(1) }")
        coordinator.sourceChanged(snapshot, testPath())

        val first = coordinator.parameterInfo(testPath(), 21)
        val firstClientFuture = client.queryFutures.single()
        val second = coordinator.parameterInfo(testPath(), 22)

        assertEquals(listOf(21, 22), client.queries.map { assertIs<AnalysisQuery.ParameterInfo>(it).offsetUtf16 })
        assertSame(firstClientFuture, first)
        assertSame(client.queryFutures.last(), second)
        assertEquals(listOf(firstClientFuture), client.cancelled)
    }

    @Test
    fun `format query carries exact source independently of the admitted snapshot text`() {
        val client = RecordingAnalysisClient()
        val coordinator = DefaultAnalysisRequestCoordinator(client, ManualAnalysisTaskScheduler(), 0, 0)
        val snapshot = admittedSnapshot("fun main() = Unit")
        coordinator.sourceChanged(snapshot, testPath())

        val submitted = "fun main(){println(1)}"
        val future = coordinator.format(testPath(), submitted, submitted.indexOf("println"))

        val query = assertIs<AnalysisQuery.Format>(client.queries.single())
        assertSame(snapshot, client.querySnapshots.single())
        assertEquals(submitted, query.source)
        assertEquals(submitted.indexOf("println"), query.caretOffsetUtf16)
        assertSame(client.queryFutures.single(), future)
    }

    @Test
    fun `hover waits for debounce and coalesces in the pointer lane`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val coordinator =
            DefaultAnalysisRequestCoordinator(
                client,
                scheduler,
                presentationDebounceNanos = 1_000,
                automaticCompletionDebounceNanos = 0,
                hoverDebounceNanos = 400,
            )
        val snapshot = admittedSnapshot("val answer = 42")
        coordinator.sourceChanged(snapshot, testPath())

        val first = coordinator.hoverInfo(testPath(), 3)
        val second = coordinator.hoverInfo(testPath(), 8)
        scheduler.advanceBy(399)

        assertTrue(client.queries.isEmpty())
        scheduler.advanceBy(1)
        assertEquals(8, assertIs<AnalysisQuery.ExpressionInfo>(client.queries.single()).offsetUtf16)
        assertTrue(first.isCancelled)
        assertFalse(second.isDone)
    }

    @Test
    fun `explicit declaration preempts pointer work and later hover cannot cancel it`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val coordinator =
            DefaultAnalysisRequestCoordinator(
                client,
                scheduler,
                presentationDebounceNanos = 0,
                automaticCompletionDebounceNanos = 0,
                hoverDebounceNanos = 400,
            )
        val snapshot = admittedSnapshot("val answer = 42")
        coordinator.sourceChanged(snapshot, testPath())

        val probe = coordinator.declarationProbe(testPath(), 4)
        val explicit = coordinator.declaration(testPath(), 4)
        coordinator.hoverInfo(testPath(), 8)

        assertTrue(probe.isCancelled)
        assertIs<AnalysisQuery.Declaration>(client.queries.single())
        assertTrue(client.cancelled.isEmpty())
        client.queryFutures.single().complete(AnalysisClientResult.Stale)
        assertEquals(AnalysisClientResult.Stale, explicit.join())
    }

    @Test
    fun `source changes cancel interactive lanes and suppress late completion`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val coordinator = DefaultAnalysisRequestCoordinator(client, scheduler, 1_000, 0, hoverDebounceNanos = 0)
        val firstSnapshot = admittedSnapshot("val first = 1")
        val secondSnapshot = admittedSnapshot("val second = 2")
        coordinator.sourceChanged(firstSnapshot, testPath())

        val hover = coordinator.hoverInfo(testPath(), 2)
        scheduler.advanceBy(0)
        val hoverClientFuture = client.queryFutures.single()
        val declaration = coordinator.declaration(testPath(), 2)
        val declarationClientFuture = client.queryFutures.last()
        coordinator.sourceChanged(secondSnapshot, testPath())

        assertTrue(hover.isCancelled)
        assertTrue(declaration.isCancelled)
        assertEquals(listOf(hoverClientFuture, declarationClientFuture), client.cancelled)
        hoverClientFuture.complete(AnalysisClientResult.Stale)
        declarationClientFuture.complete(AnalysisClientResult.Stale)
        assertTrue(hover.isCancelled)
        assertTrue(declaration.isCancelled)
    }

    @Test
    fun `close cancels scheduled pointer and active declaration`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val coordinator = DefaultAnalysisRequestCoordinator(client, scheduler, 0, 0, hoverDebounceNanos = 400)
        coordinator.sourceChanged(admittedSnapshot("val answer = 42"), testPath())

        val declaration = coordinator.declaration(testPath(), 2)
        val activeClientFuture = client.queryFutures.single()
        val hover = coordinator.hoverInfo(testPath(), 2)
        coordinator.close()

        assertTrue(hover.isCancelled)
        assertTrue(declaration.isCancelled)
        assertEquals(listOf(activeClientFuture), client.cancelled)
    }

    @Test
    fun `source changes coalesce presentation for the latest immutable snapshot`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val coordinator = DefaultAnalysisRequestCoordinator(client, scheduler, 30, 10)
        val first = admittedSnapshot("val first = 1")
        val second = admittedSnapshot("val second = 2")

        coordinator.sourceChanged(first, VirtualSourcePath.kotlin("src/first.kt"))
        coordinator.sourceChanged(second, VirtualSourcePath.kotlin("src/second.kt"))
        assertEquals(emptyList(), client.opens)
        assertEquals(emptyList(), client.queries)
        scheduler.advanceBy(30)

        val presentation = assertIs<AnalysisQuery.Presentation>(client.queries.single())
        assertEquals(second.identity, presentation.identity)
        assertEquals(VirtualSourcePath.kotlin("src/second.kt"), presentation.path)
        assertSame(second, client.querySnapshots.single())
    }

    @Test
    fun `automatic completion waits for its debounce and uses the latest snapshot`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val coordinator = DefaultAnalysisRequestCoordinator(client, scheduler, 150, 75)
        val first = admittedSnapshot("val answer = 4")
        val latest = admittedSnapshot("val answer = 42")

        coordinator.sourceChanged(first, testPath())
        coordinator.automaticCompletion(testPath(), 3)
        coordinator.sourceChanged(latest, testPath())
        coordinator.automaticCompletion(testPath(), 7)
        scheduler.advanceBy(74)
        assertEquals(emptyList(), client.queries)
        scheduler.advanceBy(1)

        val automatic = assertIs<AnalysisQuery.Completion>(client.queries.single())
        assertEquals(7, automatic.offsetUtf16)
        assertEquals(CompletionTrigger.Automatic, automatic.trigger)
        assertSame(latest, client.querySnapshots.single())
    }

    @Test
    fun `manual completion dispatches immediately and preempts active lower-priority work`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val coordinator = DefaultAnalysisRequestCoordinator(client, scheduler, 0, 10)
        val snapshot = admittedSnapshot("val answer = 42")
        coordinator.sourceChanged(snapshot, testPath())
        scheduler.advanceBy(0)
        val presentation = client.queryFutures.single()

        val firstManual = coordinator.manualCompletion(testPath(), 3)
        assertEquals(listOf(presentation), client.cancelled)
        assertEquals(CompletionTrigger.Manual, assertIs<AnalysisQuery.Completion>(client.queries.last()).trigger)

        coordinator.automaticCompletion(testPath(), 8)
        scheduler.advanceBy(10)
        assertEquals(2, client.queries.size)
        assertEquals(listOf(presentation), client.cancelled)

        val secondManual = coordinator.manualCompletion(testPath(), 9)
        assertEquals(listOf(presentation, firstManual), client.cancelled)
        assertSame(secondManual, client.queryFutures.last())
    }

    @Test
    fun `automatic completion preempts presentation while presentation never preempts completion`() {
        val snapshot = admittedSnapshot("val answer = 42")
        val firstScheduler = ManualAnalysisTaskScheduler()
        val firstClient = RecordingAnalysisClient()
        val firstCoordinator = DefaultAnalysisRequestCoordinator(firstClient, firstScheduler, 30, 10)

        firstCoordinator.sourceChanged(snapshot, testPath())
        firstScheduler.advanceBy(30)
        val presentation = firstClient.queryFutures.single()
        firstCoordinator.automaticCompletion(testPath(), 3)
        assertEquals(listOf(presentation), firstClient.cancelled)

        val secondScheduler = ManualAnalysisTaskScheduler()
        val secondClient = RecordingAnalysisClient()
        val secondCoordinator = DefaultAnalysisRequestCoordinator(secondClient, secondScheduler, 30, 10)

        secondCoordinator.sourceChanged(snapshot, testPath())
        secondCoordinator.automaticCompletion(testPath(), 4)
        secondScheduler.advanceBy(10)
        val completion = secondClient.queryFutures.single()
        secondScheduler.advanceBy(20)
        assertEquals(2, secondClient.queries.size)
        assertTrue(completion !in secondClient.cancelled)
    }

    @Test
    fun `presentation and automatic completion publish only current results`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val published = mutableListOf<AnalysisClientResult>()
        val coordinator =
            DefaultAnalysisRequestCoordinator(client, scheduler, 0, 0, resultSink = AnalysisResultSink(published::add))
        val first = admittedSnapshot("val first = 1")
        val second = admittedSnapshot("val second = 2")

        coordinator.sourceChanged(first, testPath())
        scheduler.advanceBy(0)
        val stalePresentation = client.queryFutures.single()
        coordinator.sourceChanged(second, testPath())
        stalePresentation.complete(AnalysisClientResult.Stale)
        assertEquals(emptyList(), published)

        scheduler.advanceBy(0)
        val presentation: AnalysisClientResult =
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    second.identity,
                    SnapshotPresentation.create(second.identity, mapOf(testPath() to "val second = 2".length)),
                ),
            )
        client.queryFutures.last().complete(presentation)
        assertEquals(listOf(presentation), published)

        coordinator.automaticCompletion(testPath(), 3)
        scheduler.advanceBy(0)
        client.queryFutures.last().complete(AnalysisClientResult.Stale)
        assertEquals(listOf(presentation, AnalysisClientResult.Stale), published)
    }

    @Test
    fun `late result is rejected when a different snapshot object reuses the identity`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val published = mutableListOf<AnalysisClientResult>()
        val coordinator =
            DefaultAnalysisRequestCoordinator(client, scheduler, 0, 0, resultSink = AnalysisResultSink(published::add))
        val first = admittedSnapshot("val answer = 42")
        val replacement = AdmittedAnalysisSnapshot(first.identity, first.sources, first.profile, first.limits)

        coordinator.sourceChanged(first, testPath())
        scheduler.advanceBy(0)
        val stale = client.queryFutures.single()
        coordinator.sourceChanged(replacement, testPath())
        stale.complete(
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    first.identity,
                    SnapshotPresentation.create(first.identity, mapOf(testPath() to "val answer = 42".length)),
                ),
            ),
        )

        assertEquals(emptyList(), published)
    }

    @Test
    fun `manual completion preempts automatic work and close suppresses late publication`() {
        val scheduler = ManualAnalysisTaskScheduler()
        val client = RecordingAnalysisClient()
        val published = mutableListOf<AnalysisClientResult>()
        val coordinator =
            DefaultAnalysisRequestCoordinator(client, scheduler, 30, 10, resultSink = AnalysisResultSink(published::add))
        coordinator.sourceChanged(admittedSnapshot("val answer = 42"), testPath())
        coordinator.automaticCompletion(testPath(), 3)
        scheduler.advanceBy(10)
        val automatic = client.queryFutures.single()

        val manual = coordinator.manualCompletion(testPath(), 4)
        assertEquals(listOf(automatic), client.cancelled)
        automatic.complete(AnalysisClientResult.Cancelled)
        assertEquals(emptyList(), published)

        coordinator.close()
        manual.complete(AnalysisClientResult.Stale)
        assertEquals(emptyList(), published)
    }
}
