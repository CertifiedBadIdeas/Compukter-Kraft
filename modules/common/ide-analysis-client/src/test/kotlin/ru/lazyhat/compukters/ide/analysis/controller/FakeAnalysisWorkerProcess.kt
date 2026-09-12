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

import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFrameCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessage
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageType
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisProtocolContext
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import ru.lazyhat.compukters.ide.analysis.protocol.ProtocolLimits
import ru.lazyhat.compukters.worker.process.WorkerDeadlineExceededException
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import ru.lazyhat.compukters.worker.process.WorkerProcess
import ru.lazyhat.compukters.worker.process.WorkerProcessFactory
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class FakeAnalysisWorkerProcess : WorkerProcess {
    private val reads = LinkedBlockingQueue<Read>()
    private val writes = LinkedBlockingQueue<AnalysisMessage>()
    private var context = AnalysisProtocolContext.unchecked()
    val operations = mutableListOf<String>()
    val terminationGraces = mutableListOf<Long>()
    var stderr = ByteArray(0)
    override var exitCode: Int? = null
    override var isAlive = true

    fun enqueue(message: AnalysisMessage) {
        val encoded = AnalysisFrameCodec.encode(AnalysisMessageCodec.encode(message, AnalysisProtocolContext.unchecked()))
        reads.put(Read.Frame(encoded))
    }

    fun enqueueMalformed(bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        reads.put(Read.Frame(bytes))
    }

    fun enqueueEof() {
        reads.put(Read.Eof)
    }

    fun enqueueTimeout() {
        reads.put(Read.Timeout)
    }

    fun awaitWrite(): AnalysisMessage = checkNotNull(writes.poll(5, TimeUnit.SECONDS)) { "analysis worker request was not written" }

    override fun writeFrame(frame: ByteArray) {
        val envelope = AnalysisFrameCodec.decode(frame, ProtocolLimits.MAX_FRAME_PAYLOAD_BYTES)
        val decoded =
            AnalysisMessageCodec.decode(
                envelope,
                if (envelope.type ==
                    AnalysisMessageType.Query
                ) {
                    context
                } else {
                    AnalysisProtocolContext.unchecked()
                },
            )
        if (decoded is OpenSnapshotRequest) context = AnalysisProtocolContext.of(decoded.sources, decoded.profile, decoded.limits)
        synchronized(operations) { operations += "write:${envelope.type}" }
        writes.put(decoded)
    }

    override fun readFrame(deadlineNanos: Long): ByteArray? =
        when (val read = reads.take()) {
            is Read.Frame -> read.bytes
            Read.Eof -> null
            Read.Timeout -> throw WorkerDeadlineExceededException()
        }

    override fun stderrSnapshot(): ByteArray = stderr.copyOf()

    override fun terminate(graceMillis: Long) {
        terminationGraces += graceMillis
        isAlive = false
        reads.offer(Read.Eof)
    }

    override fun close() {
        terminate(0)
    }

    private sealed interface Read {
        data class Frame(
            val bytes: ByteArray,
        ) : Read

        data object Eof : Read

        data object Timeout : Read
    }
}

internal class FakeAnalysisWorkerProcessFactory(
    processes: List<FakeAnalysisWorkerProcess>,
) : WorkerProcessFactory {
    private val available = ArrayDeque(processes)
    val starts = mutableListOf<WorkerLaunch>()

    override fun start(launch: WorkerLaunch): WorkerProcess {
        starts += launch
        return available.removeFirst()
    }
}
