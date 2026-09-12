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

package ru.lazyhat.compukters.worker.process

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class JdkWorkerProcessFactory(
    private val inheritedEnvironment: Map<String, String> = System.getenv(),
    private val allowedEnvironmentKeys: Set<String> = DEFAULT_ALLOWED_ENVIRONMENT_KEYS,
) : WorkerProcessFactory {
    override fun start(launch: WorkerLaunch): WorkerProcess {
        Files.createDirectories(launch.temporaryDirectory)
        val builder = ProcessBuilder(command(launch))
        builder.environment().clear()
        builder.environment().putAll(admittedEnvironment(inheritedEnvironment, allowedEnvironmentKeys))
        return JdkWorkerProcess(builder.start(), launch.maximumFrameBytes, launch.maximumStderrBytes)
    }

    companion object {
        internal val DEFAULT_ALLOWED_ENVIRONMENT_KEYS = setOf("SystemRoot", "WINDIR")

        internal fun command(launch: WorkerLaunch): List<String> =
            listOf(
                launch.javaExecutable.toString(),
                "-Xms16m",
                "-Xmx${launch.maximumHeapMiB}m",
                "-XX:MaxMetaspaceSize=${launch.maximumMetaspaceMiB}m",
                "-Djava.io.tmpdir=${launch.temporaryDirectory}",
                "-cp",
                launch.classpath.joinToString(File.pathSeparator),
                launch.mainClass,
            )

        internal fun admittedEnvironment(
            inherited: Map<String, String>,
            allowedKeys: Set<String>,
        ): Map<String, String> = allowedKeys.mapNotNull { key -> inherited[key]?.let { value -> key to value } }.toMap()
    }
}

private class JdkWorkerProcess(
    private val process: Process,
    private val maximumFrameBytes: Int,
    maximumStderrBytes: Int,
) : WorkerProcess {
    private val input: InputStream = process.inputStream
    private val output: OutputStream = process.outputStream
    private val stderr = BoundedByteRing(maximumStderrBytes)
    private val terminated = AtomicBoolean()
    private val readLock = Any()
    private val writeLock = Any()
    private val reader =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "compukter-worker-stdout").apply { isDaemon = true }
        }
    private val stderrThread =
        Thread(
            {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                try {
                    while (true) {
                        val count = process.errorStream.read(buffer)
                        if (count < 0) return@Thread
                        if (count > 0) stderr.append(buffer.copyOf(count))
                    }
                } catch (_: Exception) {
                    // Termination closes the stream; the bounded snapshot remains available.
                }
            },
            "compukter-worker-stderr",
        ).apply {
            isDaemon = true
            start()
        }

    override val isAlive: Boolean
        get() = process.isAlive

    override val exitCode: Int?
        get() = runCatching(process::exitValue).getOrNull()

    override val processId: Long
        get() = process.pid()

    override fun writeFrame(frame: ByteArray) {
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
        }
    }

    override fun readFrame(deadlineNanos: Long): ByteArray? =
        synchronized(readLock) {
            val future = reader.submit<ByteArray?> { readWireFrame() }
            val remaining = if (deadlineNanos == Long.MAX_VALUE) Long.MAX_VALUE else deadlineNanos - System.nanoTime()
            if (remaining <= 0) {
                future.cancel(true)
                throw WorkerDeadlineExceededException()
            }
            try {
                future.get(remaining, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                throw WorkerDeadlineExceededException()
            } catch (exception: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw IllegalStateException("worker read interrupted", exception)
            } catch (exception: ExecutionException) {
                throw IllegalStateException("worker read failed", exception.cause)
            }
        }

    override fun stderrSnapshot(): ByteArray {
        if (!process.isAlive) stderrThread.join(STDERR_DRAIN_MILLIS)
        return stderr.snapshot()
    }

    override fun terminate(graceMillis: Long) {
        require(graceMillis >= 0) { "termination grace must not be negative" }
        if (!terminated.compareAndSet(false, true)) return
        runCatching(output::close)
        process.destroy()
        if (process.isAlive && !process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        runCatching(input::close)
        reader.shutdownNow()
    }

    override fun close() {
        terminate(0)
    }

    private fun readWireFrame(): ByteArray? {
        val header = readUpTo(FRAME_HEADER_BYTES) ?: return null
        if (header.size < FRAME_HEADER_BYTES) return header
        val payloadBytes =
            (0 until 4).fold(0L) { value, index ->
                value or ((header[8 + index].toLong() and 0xff) shl (index * 8))
            }
        if (payloadBytes > maximumFrameBytes) return header
        val payload = readUpTo(payloadBytes.toInt()) ?: ByteArray(0)
        return header + payload
    }

    private fun readUpTo(count: Int): ByteArray? {
        if (count == 0) return ByteArray(0)
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) return if (offset == 0) null else result.copyOf(offset)
            if (read > 0) offset += read
        }
        return result
    }

    private companion object {
        const val FRAME_HEADER_BYTES = 12
        const val STDERR_DRAIN_MILLIS = 100L
    }
}
