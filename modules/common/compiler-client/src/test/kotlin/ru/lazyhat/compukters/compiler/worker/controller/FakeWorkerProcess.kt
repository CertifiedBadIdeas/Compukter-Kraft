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

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.worker.protocol.WorkerCodec
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessage
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessageCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class FakeWorkerProcess : WorkerProcess {
    private val reads = LinkedBlockingQueue<Read>()
    private val writes = LinkedBlockingQueue<WorkerMessage>()
    val operations = mutableListOf<String>()
    val readDeadlines = mutableListOf<Long>()
    var terminationCount = 0
    val terminationGraces = mutableListOf<Long>()
    var stderr = ByteArray(0)
    override var exitCode: Int? = null
    override var isAlive = true

    fun enqueue(message: WorkerMessage) {
        reads.put(Read.Frame(encode(message)))
    }

    fun enqueueEof() {
        reads.put(Read.Eof)
    }

    fun enqueueTimeout() {
        reads.put(Read.Timeout)
    }

    fun awaitWrite(): WorkerMessage = checkNotNull(writes.poll(5, TimeUnit.SECONDS)) { "worker request was not written" }

    override fun writeFrame(frame: ByteArray) {
        val message = decode(frame)
        synchronized(operations) { operations += "write:${message.type}" }
        writes.put(message)
    }

    override fun readFrame(deadlineNanos: Long): ByteArray? {
        readDeadlines += deadlineNanos
        return when (val read = reads.take()) {
            is Read.Frame -> {
                val message = decode(read.bytes)
                synchronized(operations) { operations += "read:${message.type}" }
                read.bytes
            }

            Read.Eof -> {
                null
            }

            Read.Timeout -> {
                throw WorkerDeadlineExceededException()
            }
        }
    }

    override fun stderrSnapshot(): ByteArray = stderr.copyOf()

    override fun terminate(graceMillis: Long) {
        terminationCount++
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

internal class FakeWorkerProcessFactory(
    processes: List<FakeWorkerProcess>,
) : WorkerProcessFactory {
    private val available = ArrayDeque(processes)
    val starts = mutableListOf<ru.lazyhat.compukters.worker.process.WorkerLaunch>()

    override fun start(launch: ru.lazyhat.compukters.worker.process.WorkerLaunch): WorkerProcess {
        starts += launch
        return available.removeFirst()
    }
}

private fun encode(message: WorkerMessage): ByteArray = WorkerCodec.encodeFrame(WorkerMessageCodec.encode(message))

private fun decode(frame: ByteArray): WorkerMessage = WorkerMessageCodec.decode(WorkerCodec.decodeFrame(frame, Int.MAX_VALUE))
