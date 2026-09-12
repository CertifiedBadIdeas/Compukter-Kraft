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

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import java.util.concurrent.CompletableFuture

internal class ManualAnalysisTaskScheduler : AnalysisTaskScheduler {
    private data class Task(
        val deadline: Long,
        val sequence: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    private val tasks = mutableListOf<Task>()
    private var now = 0L
    private var sequence = 0L
    val pendingCount: Int get() = tasks.count { !it.cancelled }

    override fun schedule(
        delayNanos: Long,
        action: () -> Unit,
    ): AnalysisScheduledTask {
        val task = Task(now + delayNanos, sequence++, action)
        tasks += task
        return AnalysisScheduledTask { task.cancelled = true }
    }

    fun advanceBy(nanos: Long) {
        now += nanos
        while (true) {
            val task =
                tasks.filter { !it.cancelled && it.deadline <= now }.minWithOrNull(compareBy(Task::deadline, Task::sequence)) ?: return
            task.cancelled = true
            task.action()
        }
    }

    override fun close() = Unit
}

internal class RecordingAnalysisClient : AnalysisClient {
    val opens = mutableListOf<AdmittedAnalysisSnapshot>()
    val querySnapshots = mutableListOf<AdmittedAnalysisSnapshot>()
    val queries = mutableListOf<AnalysisQuery>()
    val queryFutures = mutableListOf<CompletableFuture<AnalysisClientResult>>()
    val cancelled = mutableListOf<CompletableFuture<AnalysisClientResult>>()
    var closeCount = 0

    override fun open(snapshot: AdmittedAnalysisSnapshot): CompletableFuture<SnapshotOpenResult> =
        CompletableFuture.completedFuture<SnapshotOpenResult>(SnapshotOpenResult.Opened(snapshot.identity)).also { opens += snapshot }

    override fun query(
        snapshot: AdmittedAnalysisSnapshot,
        query: AnalysisQuery,
    ) = CompletableFuture<AnalysisClientResult>().also {
        querySnapshots += snapshot
        queries += query
        queryFutures += it
    }

    override fun cancel(future: CompletableFuture<AnalysisClientResult>): Boolean = cancelled.add(future)

    override fun closeSnapshot(identity: AnalysisSnapshotIdentity) = CompletableFuture.completedFuture(Unit)

    override fun close() {
        closeCount++
    }
}

internal fun admittedSnapshot(text: String): AdmittedAnalysisSnapshot {
    val source = ProjectSnapshot.of(listOf(ProjectSource(testPath(), BinaryValue.of(text.encodeToByteArray()))), WorkerLimits())
    val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(source), AnalysisProfileIdentity(testHash(text.length)))
    return AdmittedAnalysisSnapshot(
        identity,
        source,
        AdmittedAnalysisProfile(
            identity.profile,
            ru.lazyhat.compukters.ide.analysis.protocol
                .AdmittedAnalysisPlatform(Hash256.zero(), emptyList()),
        ),
        AnalysisLimits(),
    )
}

internal fun testSnapshot(): AdmittedAnalysisSnapshot = admittedSnapshot("val test = true")

internal fun testQuery(snapshot: AdmittedAnalysisSnapshot = testSnapshot()): AnalysisQuery =
    AnalysisQuery.Presentation(snapshot.identity, testPath())

internal fun testPath(): VirtualSourcePath = VirtualSourcePath.kotlin("main.kt")

private fun testHash(value: Int): Hash256 = Hash256.of(ByteArray(32) { value.toByte() })
