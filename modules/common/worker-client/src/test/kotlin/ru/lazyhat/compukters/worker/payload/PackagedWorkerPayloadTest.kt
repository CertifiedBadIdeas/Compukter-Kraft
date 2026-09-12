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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackagedWorkerPayloadTest {
    @Test
    fun `publishes once by content hash and verifies kind and identity`() =
        withPackage { archive, root, manifest ->
            val expectation = WorkerPayloadExpectation("analysis", manifest.identityProperties)
            val first = PackagedWorkerPayload.publish(ByteArrayInputStream(archive), root, expectation)
            val second = PackagedWorkerPayload.publish(ByteArrayInputStream(archive), root, expectation)

            assertEquals(first.root, second.root)
            assertEquals(manifest, first.manifest)
            assertContentEquals(WORKER_BYTES, first.classpath.single().readBytes())
            assertFailsWith<PackagedWorkerPayloadException> {
                PackagedWorkerPayload.publish(
                    ByteArrayInputStream(archive),
                    root.resolveSibling("wrong-kind"),
                    expectation.copy(kind = "compiler"),
                )
            }
            assertFailsWith<PackagedWorkerPayloadException> {
                PackagedWorkerPayload.publish(
                    ByteArrayInputStream(archive),
                    root.resolveSibling("wrong-identity"),
                    expectation.copy(identityProperties = mapOf("compiler" to "different", "language" to "2.4")),
                )
            }
        }

    @Test
    fun `rejects unsafe duplicate unexpected and over-budget entries`() {
        val root = createTempDirectory("compukters-packaged-worker-reject-").toAbsolutePath().normalize()
        try {
            listOf(
                "../escape.jar",
                "/absolute.jar",
                "lib/../escape.jar",
                "lib\\escape.jar",
                "META-INF/arbitrary.txt",
                "META-INF/licenses/../escape.txt",
                "licenses/Compukters.txt",
            ).forEach { entry ->
                assertFailsWith<PackagedWorkerPayloadException> {
                    PackagedWorkerPayload.publish(ByteArrayInputStream(zip(entry to byteArrayOf(1))), root)
                }
            }
            assertFailsWith<PackagedWorkerPayloadException> {
                PackagedWorkerPayload.publish(ByteArrayInputStream(duplicateZip()), root)
            }
            assertFailsWith<PackagedWorkerPayloadException> {
                PackagedWorkerPayload.publish(
                    ByteArrayInputStream(zip("worker.payload" to ByteArray(5))),
                    root,
                    limits = PackagedWorkerPayloadLimits(entries = 1, bytes = 4),
                )
            }
            assertFailsWith<PackagedWorkerPayloadException> {
                PackagedWorkerPayload.publish(
                    ByteArrayInputStream(zip("worker.payload" to byteArrayOf(1), "lib/a.jar" to byteArrayOf(2))),
                    root,
                    limits = PackagedWorkerPayloadLimits(entries = 1, bytes = 8),
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects symbolic cache root and corrupt reused publication`() =
        withPackage { archive, root, _ ->
            val published = PackagedWorkerPayload.publish(ByteArrayInputStream(archive), root)
            published.classpath.single().writeBytes(byteArrayOf(9, 9, 9))
            assertFailsWith<WorkerPayloadException> {
                PackagedWorkerPayload.publish(ByteArrayInputStream(archive), root)
            }

            val link = root.resolveSibling("published-link")
            runCatching { Files.createSymbolicLink(link, root) }.getOrElse { return@withPackage }
            try {
                assertFailsWith<PackagedWorkerPayloadException> {
                    PackagedWorkerPayload.publish(ByteArrayInputStream(archive), link)
                }
            } finally {
                Files.deleteIfExists(link)
            }
        }

    private fun withPackage(block: (ByteArray, Path, WorkerPayloadManifest) -> Unit) {
        val temporary = createTempDirectory("compukters-packaged-worker-").toAbsolutePath().normalize()
        try {
            val manifest =
                WorkerPayloadManifest.create(
                    kind = "analysis",
                    identityProperties = mapOf("compiler" to "2.4.10", "language" to "2.4"),
                    mainClass = MAIN_CLASS,
                    files = mapOf("lib/worker.jar" to WORKER_BYTES),
                )
            val published =
                WorkerPayloadPublisher.publish(
                    manifest,
                    { path -> ByteArrayInputStream(if (path == "lib/worker.jar") WORKER_BYTES else error(path)) },
                    temporary.resolve("source"),
                )
            block(zipDirectory(published.root), temporary.resolve("published"), manifest)
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    private fun zipDirectory(root: Path): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { path ->
                    zip.putNextEntry(ZipEntry(root.relativize(path).joinToString("/")))
                    zip.write(path.readBytes())
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun duplicateZip(): ByteArray {
        val bytes = zip("lib/a.jar" to byteArrayOf(1), "lib/b.jar" to byteArrayOf(2))
        val original = "lib/b.jar".encodeToByteArray()
        val duplicate = "lib/a.jar".encodeToByteArray()
        var index = 0
        while (index <= bytes.size - original.size) {
            if (bytes.copyOfRange(index, index + original.size).contentEquals(original)) {
                duplicate.copyInto(bytes, index)
                index += original.size
            } else {
                index++
            }
        }
        return bytes
    }

    private companion object {
        const val MAIN_CLASS = "compukter.Worker"
        val WORKER_BYTES = byteArrayOf(1, 2, 3, 4)
    }
}
