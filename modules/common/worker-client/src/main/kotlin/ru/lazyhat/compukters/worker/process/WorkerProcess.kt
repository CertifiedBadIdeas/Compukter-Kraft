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

import java.nio.file.Path

interface WorkerProcess : AutoCloseable {
    val isAlive: Boolean
    val exitCode: Int?
    val processId: Long?
        get() = null

    fun writeFrame(frame: ByteArray)

    fun readFrame(deadlineNanos: Long): ByteArray?

    fun stderrSnapshot(): ByteArray

    fun terminate(graceMillis: Long)
}

class WorkerDeadlineExceededException : IllegalStateException("worker read deadline exceeded")

fun interface WorkerProcessFactory {
    fun start(launch: WorkerLaunch): WorkerProcess
}

data class WorkerLaunch(
    val javaExecutable: Path,
    val classpath: List<Path>,
    val mainClass: String,
    val maximumHeapMiB: Int,
    val maximumMetaspaceMiB: Int,
    val temporaryDirectory: Path,
    val maximumFrameBytes: Int = 20 * 1024 * 1024,
    val maximumStderrBytes: Int = 64 * 1024,
) {
    init {
        require(javaExecutable.normalize() == javaExecutable) { "java executable must be normalized" }
        require(classpath.isNotEmpty()) { "worker classpath must not be empty" }
        require(classpath.all { it.normalize() == it }) { "worker classpath entries must be normalized" }
        require(mainClass.isNotBlank() && mainClass.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "worker main class is invalid"
        }
        require(maximumHeapMiB > 0) { "maximum heap must be positive" }
        require(maximumMetaspaceMiB > 0) { "maximum metaspace must be positive" }
        require(temporaryDirectory.normalize() == temporaryDirectory) { "temporary directory must be normalized" }
        require(maximumFrameBytes >= 0) { "maximum frame bytes must not be negative" }
        require(maximumStderrBytes >= 0) { "maximum stderr bytes must not be negative" }
    }
}
