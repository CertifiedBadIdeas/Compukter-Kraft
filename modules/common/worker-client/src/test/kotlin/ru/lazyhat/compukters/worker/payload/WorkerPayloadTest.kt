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

package ru.lazyhat.compukters.worker.payload

import ru.lazyhat.compukters.worker.value.Sha256
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerPayloadTest {
    @Test
    fun `validated generic payload publishes once and reloads canonically`() =
        withTempDirectory { root ->
            val files = linkedMapOf("lib/worker.jar" to byteArrayOf(1, 2, 3))
            val manifest =
                WorkerPayloadManifest.create(
                    kind = "compiler",
                    identityProperties = mapOf("compiler" to "2.4.10", "language" to "2.4"),
                    mainClass = "example.WorkerMain",
                    files = files,
                )
            val source = WorkerPayloadSource { path -> ByteArrayInputStream(files.getValue(path)) }

            val first = WorkerPayloadPublisher.publish(manifest, source, root.resolve("cache"))
            val second = WorkerPayloadPublisher.publish(manifest, source, root.resolve("cache"))
            val loaded = WorkerPayloadLoader.load(first.root)

            assertEquals(first.root, second.root)
            assertEquals(manifest, loaded.manifest)
            assertEquals(listOf(first.root.resolve("lib/worker.jar")), loaded.classpath)
            assertContentEquals(files.getValue("lib/worker.jar"), first.root.resolve("lib/worker.jar").readBytes())
        }

    @Test
    fun `generic payload rejects traversal and changed content`() =
        withTempDirectory { root ->
            assertFailsWith<IllegalArgumentException> {
                WorkerPayloadManifest.create("compiler", emptyMap(), "example.Main", mapOf("../escape.jar" to byteArrayOf(1)))
            }

            val files = mapOf("lib/worker.jar" to byteArrayOf(1))
            val manifest = WorkerPayloadManifest.create("compiler", emptyMap(), "example.Main", files)
            assertFailsWith<WorkerPayloadException> {
                WorkerPayloadPublisher.publish(
                    manifest,
                    WorkerPayloadSource { ByteArrayInputStream(byteArrayOf(2)) },
                    root.resolve("cache"),
                )
            }
            assertEquals(false, Files.exists(root.resolve("cache").resolve(manifest.payloadHash.hex())))
        }

    @Test
    fun `generic payload loader rejects noncanonical identity properties`() =
        withTempDirectory { root ->
            val files = mapOf("lib/worker.jar" to byteArrayOf(1))
            val manifest = WorkerPayloadManifest.create("compiler", mapOf("a" to "1", "b" to "2"), "example.Main", files)
            val published =
                WorkerPayloadPublisher.publish(
                    manifest,
                    WorkerPayloadSource { path -> ByteArrayInputStream(files.getValue(path)) },
                    root.resolve("cache"),
                )
            val manifestPath = published.root.resolve("worker.payload")
            val text = Files.readString(manifestPath)
            Files.writeString(manifestPath, text.replace("identity.a=1\nidentity.b=2", "identity.b=2\nidentity.a=1"))

            assertFailsWith<WorkerPayloadException> { WorkerPayloadLoader.load(published.root) }
        }

    @Test
    fun `generic payload publisher rejects a forged manifest`() =
        withTempDirectory { root ->
            val zero = Sha256.of(ByteArray(32))
            val file = WorkerPayloadFile("lib/worker.jar", 1, zero)
            val forged = WorkerPayloadManifest(1u, "compiler", emptyMap(), "example.Main", listOf(file, file), zero)

            assertFailsWith<WorkerPayloadException> {
                WorkerPayloadPublisher.publish(forged, WorkerPayloadSource { ByteArrayInputStream(byteArrayOf(1)) }, root.resolve("cache"))
            }
        }

    private inline fun withTempDirectory(block: (java.nio.file.Path) -> Unit) {
        val directory = createTempDirectory("compukters-worker-payload-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
