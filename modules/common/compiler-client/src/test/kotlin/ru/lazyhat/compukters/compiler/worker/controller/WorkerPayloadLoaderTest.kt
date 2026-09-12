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

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerPayloadLoaderTest {
    @Test
    fun `loads canonical payload and validates every published jar`() =
        withPayload { root, manifest ->
            val loaded = WorkerPayloadLoader.load(root)

            assertEquals(manifest, loaded.manifest)
            assertEquals(listOf(root.resolve("lib/worker.jar")), loaded.classpath)
        }

    @Test
    fun `rejects malformed or noncanonical manifests`() {
        val mutations =
            listOf<(String) -> String>(
                { it.replace("format=1", "format=2") },
                { it.replace("identity.compiler=", "unknown=x\nidentity.compiler=") },
                { it.replace("identity.compiler=", "identity.compiler=duplicate\nidentity.compiler=") },
                {
                    it.replace(
                        "identity.compiler=2.3.20\nidentity.language=2.4",
                        "identity.language=2.4\nidentity.compiler=2.3.20",
                    )
                },
                { it.replace("file=lib/worker.jar", "file=../worker.jar") },
                { it.replace("\t3\t", "\tbad\t") },
                { it.replace(Regex("file=.*"), "file=broken") },
            )
        mutations.forEach { mutate ->
            withPayload { root, _ ->
                val path = root.resolve("worker.payload")
                path.writeBytes(mutate(Files.readString(path)).encodeToByteArray())
                assertFailsWith<WorkerPayloadException> { WorkerPayloadLoader.load(root) }
            }
        }
        withPayload { root, _ ->
            root.resolve("worker.payload").writeBytes(byteArrayOf(0xc3.toByte()))
            assertFailsWith<WorkerPayloadException> { WorkerPayloadLoader.load(root) }
        }
        withPayload { root, _ ->
            assertFailsWith<WorkerPayloadException> {
                WorkerPayloadLoader.load(root, WorkerPayloadLoadLimits(manifestBytes = 1))
            }
        }
    }

    @Test
    fun `rejects missing changed and symbolic-link payload files`() {
        withPayload { root, _ ->
            Files.delete(root.resolve("lib/worker.jar"))
            assertFailsWith<WorkerPayloadException> { WorkerPayloadLoader.load(root) }
        }
        withPayload { root, _ ->
            root.resolve("lib/worker.jar").writeBytes(byteArrayOf(9, 8, 7))
            assertFailsWith<WorkerPayloadException> { WorkerPayloadLoader.load(root) }
        }
        withPayload { root, _ ->
            val jar = root.resolve("lib/worker.jar")
            val target = root.resolve("real.jar").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            Files.delete(jar)
            Files.createSymbolicLink(jar, target)
            assertFailsWith<WorkerPayloadException> { WorkerPayloadLoader.load(root) }
        }
    }

    @Test
    fun `enforces file count and checked total payload bytes`() =
        withPayload { root, _ ->
            assertFailsWith<WorkerPayloadException> {
                WorkerPayloadLoader.load(root, WorkerPayloadLoadLimits(files = 0))
            }
            assertFailsWith<WorkerPayloadException> {
                WorkerPayloadLoader.load(root, WorkerPayloadLoadLimits(payloadBytes = 2))
            }
        }

    private fun withPayload(block: (Path, WorkerPayloadManifest) -> Unit) {
        val temporary = createTempDirectory("compukters-payload-loader-")
        try {
            val bytes = byteArrayOf(1, 2, 3)
            val manifest =
                WorkerPayloadManifest.create(
                    WorkerIdentity("2.3.20", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero()),
                    "worker.MainKt",
                    mapOf("lib/worker.jar" to bytes),
                )
            val published =
                WorkerPayloadPublisher.publish(
                    manifest,
                    WorkerPayloadSource { ByteArrayInputStream(bytes) },
                    temporary.resolve("cache"),
                )
            block(published.root, manifest)
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }
}
