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

package ru.lazyhat.compukters.ide.analysis.k2.server

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import ru.lazyhat.compukters.ide.analysis.k2.standalone.K2ProjectEnvironment
import ru.lazyhat.compukters.ide.analysis.k2.testPlatform
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class K2ProgressCancellationTest {
    @Test
    fun `analysis token cancels the IntelliJ progress boundary`() {
        val root = createTempDirectory("compukters-progress-cancel-")
        val source = root.resolve("source")
        Files.createDirectories(source)
        Files.writeString(source.resolve("main.kt"), "val answer = 42")
        val environment =
            K2ProjectEnvironment.create(
                source,
                testPlatform(),
                emptySet(),
            )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val cancellation = AnalysisCancellation()
            val started = CountDownLatch(1)
            val future =
                executor.submit<Unit> {
                    K2ProgressCancellation.run(cancellation) {
                        started.countDown()
                        while (true) ProgressManager.checkCanceled()
                    }
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            cancellation.cancel()

            val failure = assertFailsWith<ExecutionException> { future.get(5, TimeUnit.SECONDS) }
            assertIs<ProcessCanceledException>(failure.cause)
        } finally {
            executor.shutdownNow()
            environment.close()
            root.toFile().deleteRecursively()
        }
    }
}
