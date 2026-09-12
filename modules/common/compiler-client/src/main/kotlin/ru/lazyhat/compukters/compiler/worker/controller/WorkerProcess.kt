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

import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import java.nio.file.Path

typealias WorkerProcess = ru.lazyhat.compukters.worker.process.WorkerProcess
typealias WorkerDeadlineExceededException = ru.lazyhat.compukters.worker.process.WorkerDeadlineExceededException
typealias WorkerProcessFactory = ru.lazyhat.compukters.worker.process.WorkerProcessFactory
typealias JdkWorkerProcessFactory = ru.lazyhat.compukters.worker.process.JdkWorkerProcessFactory

data class WorkerLaunch(
    val javaExecutable: Path,
    val maximumHeapMiB: Int,
    val maximumMetaspaceMiB: Int,
    val temporaryDirectory: Path,
    val expectedIdentity: WorkerIdentity,
    val maximumFrameBytes: Int = 20 * 1024 * 1024,
    val maximumStderrBytes: Int = 64 * 1024,
) {
    init {
        require(maximumHeapMiB > 0) { "maximum heap must be positive" }
        require(maximumMetaspaceMiB > 0) { "maximum metaspace must be positive" }
        require(maximumFrameBytes >= 0) { "maximum frame bytes must not be negative" }
        require(maximumStderrBytes >= 0) { "maximum stderr bytes must not be negative" }
    }

    fun processLaunch(payload: PublishedWorkerPayload): ru.lazyhat.compukters.worker.process.WorkerLaunch =
        ru.lazyhat.compukters.worker.process.WorkerLaunch(
            javaExecutable = javaExecutable,
            classpath = payload.classpath,
            mainClass = payload.manifest.mainClass,
            maximumHeapMiB = maximumHeapMiB,
            maximumMetaspaceMiB = maximumMetaspaceMiB,
            temporaryDirectory = temporaryDirectory,
            maximumFrameBytes = maximumFrameBytes,
            maximumStderrBytes = maximumStderrBytes,
        )
}
