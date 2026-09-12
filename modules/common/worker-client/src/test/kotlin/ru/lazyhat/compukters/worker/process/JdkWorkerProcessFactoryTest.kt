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
import kotlin.test.Test
import kotlin.test.assertEquals

class JdkWorkerProcessFactoryTest {
    @Test
    fun `command applies bounded child jvm policy`() {
        val launch =
            WorkerLaunch(
                javaExecutable = Path.of("jdk", "bin", "java"),
                classpath = listOf(Path.of("payload", "worker.jar")),
                mainClass = "example.WorkerKt",
                maximumHeapMiB = 384,
                maximumMetaspaceMiB = 192,
                temporaryDirectory = Path.of("tmp", "worker"),
            )

        assertEquals(
            listOf(
                Path.of("jdk", "bin", "java").toString(),
                "-Xms16m",
                "-Xmx384m",
                "-XX:MaxMetaspaceSize=192m",
                "-Djava.io.tmpdir=${Path.of("tmp", "worker")}",
                "-cp",
                Path.of("payload", "worker.jar").toString(),
                "example.WorkerKt",
            ),
            JdkWorkerProcessFactory.command(launch),
        )
    }

    @Test
    fun `environment admits only explicitly allowed inherited keys`() {
        assertEquals(
            mapOf("SystemRoot" to "C:\\Windows"),
            JdkWorkerProcessFactory.admittedEnvironment(
                inherited = mapOf("TOKEN" to "secret", "SystemRoot" to "C:\\Windows"),
                allowedKeys = setOf("SystemRoot", "WINDIR"),
            ),
        )
    }
}
