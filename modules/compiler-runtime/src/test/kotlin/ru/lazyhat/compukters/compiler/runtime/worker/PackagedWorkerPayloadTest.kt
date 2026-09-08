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

package ru.lazyhat.compukters.compiler.runtime.worker

import io.airlift.compress.v3.zstd.ZstdInputStream
import io.airlift.compress.v3.zstd.ZstdOutputStream
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadManifest
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.worker.payload.ToolingBundleManifest
import ru.lazyhat.compukters.worker.payload.ToolingProfileDefinition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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
    fun `validated package is published once and reused by content hash`() =
        withPackage { manifest, archive, root, expected ->
            val first = publish(manifest, archive, root)
            val second = publish(manifest, archive, root)

            assertEquals(first.root, second.root)
            assertEquals(expected.identity, first.manifest.identity)
            assertContentEquals(WORKER_BYTES, first.classpath.single().readBytes())
        }

    @Test
    fun `package accepts bounded license metadata without adding it to the worker classpath`() =
        withPackage { manifest, archive, root, expected ->
            val licensed =
                appendZipEntries(
                    archive,
                    "META-INF/licenses/Compukters-Apache-2.0.txt" to "Apache License 2.0".toByteArray(),
                    "META-INF/licenses/kotlin/v2.4.10/NOTICE.txt" to "Kotlin Compiler".toByteArray(),
                    "META-INF/NOTICE.txt" to "Compukters".toByteArray(),
                    "META-INF/THIRD-PARTY-NOTICES.md" to "Third-party notices".toByteArray(),
                )

            val published = publish(manifest, licensed, root)

            assertEquals(expected.identity, published.manifest.identity)
            assertContentEquals(WORKER_BYTES, published.classpath.single().readBytes())
        }

    @Test
    fun `package rejects unsafe duplicate and over-budget entries`() =
        withPackage { manifest, _, root, _ ->
            listOf(
                "../escape.jar",
                "/absolute.jar",
                "lib/../escape.jar",
                "lib\\escape.jar",
                "META-INF/arbitrary.txt",
                "META-INF/licenses/../escape.txt",
                "licenses/Compukters.txt",
            ).forEachIndexed { index, entry ->
                assertFailsWith<PackagedWorkerPayloadException> {
                    publish(manifest, zstd(zip(entry to byteArrayOf(1))), root.resolve("unsafe-$index"))
                }
            }
            assertFailsWith<PackagedWorkerPayloadException> {
                publish(manifest, duplicateZip(), root.resolve("duplicate"))
            }
            assertFailsWith<PackagedWorkerPayloadException> {
                publish(
                    manifest,
                    zstd(zip("worker.payload" to ByteArray(5))),
                    root.resolve("over-budget"),
                    PackagedWorkerPayloadLimits(entries = 1, bytes = 4),
                )
            }
        }

    @Test
    fun `package repairs a corrupt reused publication`() =
        withPackage { manifest, archive, root, _ ->
            val published = publish(manifest, archive, root)
            published.classpath.single().writeBytes(byteArrayOf(9, 9, 9))

            val repaired = publish(manifest, archive, root)
            assertContentEquals(WORKER_BYTES, repaired.classpath.single().readBytes())
        }

    private fun withPackage(block: (ByteArray, ByteArray, Path, WorkerPayloadManifest) -> Unit) {
        val root = createTempDirectory("compukters-packaged-worker-").toAbsolutePath().normalize()
        try {
            val files =
                linkedMapOf(
                    "compiler/lib/worker.jar" to WORKER_BYTES,
                    "analysis/lib/analysis.jar" to byteArrayOf(5, 6),
                )
            val bundle =
                ToolingBundleManifest.create(
                    files,
                    mapOf(
                        "compiler" to
                            ToolingProfileDefinition(
                                mapOf(
                                    "artifactWriter" to "1",
                                    "codegenAbi" to "1",
                                    "compiler" to "2.4.10",
                                    "language" to "2.4",
                                    "platformAbi" to Hash256.zero().hex(),
                                ),
                                MAIN_CLASS,
                                listOf("compiler/lib/worker.jar"),
                            ),
                        "analysis" to
                            ToolingProfileDefinition(
                                mapOf("compiler" to "2.4.10", "language" to "2.4"),
                                "compukter.AnalysisWorker",
                                listOf("analysis/lib/analysis.jar"),
                            ),
                    ),
                )
            val expected = WorkerPayloadManifest.fromToolingProfile(bundle.profiles.getValue("compiler"), bundle.files)
            block(
                bundle.canonicalBundleText().encodeToByteArray(),
                zstd(zip(files + bundle.encodedFiles())),
                root.resolve("published"),
                expected,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
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

    private fun zip(entries: Map<String, ByteArray>): ByteArray = zip(*entries.map { it.key to it.value }.toTypedArray())

    private fun appendZipEntries(
        archive: ByteArray,
        vararg entries: Pair<String, ByteArray>,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { target ->
            ZipInputStream(ZstdInputStream(ByteArrayInputStream(archive))).use { source ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    target.putNextEntry(ZipEntry(entry.name))
                    source.copyTo(target)
                    target.closeEntry()
                    source.closeEntry()
                }
            }
            entries.forEach { (name, bytes) ->
                target.putNextEntry(ZipEntry(name))
                target.write(bytes)
                target.closeEntry()
            }
        }
        return zstd(output.toByteArray())
    }

    private fun zstd(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZstdOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun publish(
        manifest: ByteArray,
        archive: ByteArray,
        root: Path,
        limits: PackagedWorkerPayloadLimits = PackagedWorkerPayloadLimits(),
    ) = PackagedWorkerPayload.publish(ByteArrayInputStream(manifest), ByteArrayInputStream(archive), root, limits)

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
        return zstd(bytes)
    }

    private companion object {
        const val MAIN_CLASS = "compukter.Worker"
        val WORKER_BYTES = byteArrayOf(1, 2, 3, 4)
    }
}
