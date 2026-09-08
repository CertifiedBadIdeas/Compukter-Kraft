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

import io.airlift.compress.v3.zstd.ZstdInputStream
import io.airlift.compress.v3.zstd.ZstdOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackagedToolingBundleTest {
    @Test
    fun `publishes once and selects two ordered profiles from one root`() =
        withPackage { archive, root, manifest ->
            val executor = Executors.newFixedThreadPool(2)
            val published =
                try {
                    executor
                        .invokeAll(
                            List(2) {
                                Callable { publish(ByteArrayInputStream(archive), root, manifest) }
                            },
                        ).map { it.get() }
                } finally {
                    executor.shutdownNow()
                }

            assertEquals(published[0].root, published[1].root)
            assertEquals(manifest, published[0].manifest)
            val compiler = published[0].profile("compiler")
            val analysis = published[0].profile("analysis")
            assertEquals(published[0].root, compiler.root)
            assertEquals(published[0].root, analysis.root)
            assertEquals(
                listOf(
                    published[0].root.resolve("common/lib/kotlin-compiler.jar"),
                    published[0].root.resolve("compiler/lib/compiler.jar"),
                ),
                compiler.classpath,
            )
            assertEquals(
                listOf(
                    published[0].root.resolve("common/lib/kotlin-compiler.jar"),
                    published[0].root.resolve("analysis/lib/analysis.jar"),
                ),
                analysis.classpath,
            )
            assertContentEquals(COMPILER_BYTES, compiler.classpath.last().readBytes())
        }

    @Test
    fun `rejects unsafe duplicate missing corrupt and over-budget archives`() =
        withPackage { archive, root, manifest ->
            listOf(
                "../escape.jar",
                "/absolute.jar",
                "common/lib/../escape.jar",
                "common\\lib\\escape.jar",
                "manifests/extra.payload",
                "META-INF/arbitrary.txt",
            ).forEachIndexed { index, entry ->
                assertFailsWith<PackagedToolingBundleException> {
                    publish(
                        ByteArrayInputStream(zstd(zip(mapOf(entry to byteArrayOf(1))))),
                        root.resolve("unsafe-$index"),
                        manifest,
                    )
                }
            }
            assertFailsWith<PackagedToolingBundleException> {
                publish(ByteArrayInputStream(duplicateZip()), root.resolve("duplicate"), manifest)
            }
            assertFailsWith<PackagedToolingBundleException> {
                publish(
                    ByteArrayInputStream(removeEntry(archive, "analysis/lib/analysis.jar")),
                    root.resolve("missing"),
                    manifest,
                )
            }
            assertFailsWith<PackagedToolingBundleException> {
                publish(
                    ByteArrayInputStream(replaceEntry(archive, "compiler/lib/compiler.jar", byteArrayOf(9))),
                    root.resolve("corrupt"),
                    manifest,
                )
            }
            assertFailsWith<PackagedToolingBundleException> {
                publish(
                    ByteArrayInputStream(archive),
                    root.resolve("entry-limit"),
                    manifest,
                    PackagedToolingBundleLimits(entries = 1, bytes = 1024),
                )
            }
            assertFailsWith<PackagedToolingBundleException> {
                publish(ByteArrayInputStream(byteArrayOf(1, 2, 3)), root.resolve("invalid-zstd"), manifest)
            }
            assertFailsWith<PackagedToolingBundleException> {
                publish(
                    ByteArrayInputStream(archive),
                    root.resolve("byte-limit"),
                    manifest,
                    PackagedToolingBundleLimits(entries = 32, bytes = 1),
                )
            }
            assertFailsWith<PackagedToolingBundleException> {
                publish(
                    ByteArrayInputStream(archive),
                    root.resolve("manifest-limit"),
                    manifest,
                    PackagedToolingBundleLimits(entries = 32, bytes = 1024, manifestBytes = 1),
                )
            }
        }

    @Test
    fun `reuses valid cache without reading carrier and repairs corrupt publications`() =
        withPackage { archive, root, manifest ->
            val published = publish(ByteArrayInputStream(archive), root, manifest)
            val reused = publish(FailOnReadInputStream(), root, manifest)
            assertEquals(published.root, reused.root)

            val compilerJar = published.root.resolve("common/lib/kotlin-compiler.jar")
            compilerJar.writeBytes(byteArrayOf(9))
            assertFailsWith<PackagedToolingBundleException> {
                publish(ByteArrayInputStream(byteArrayOf(1, 2, 3)), root, manifest)
            }
            assertContentEquals(byteArrayOf(9), compilerJar.readBytes())

            val repaired = publish(ByteArrayInputStream(archive), root, manifest)
            assertContentEquals(COMMON_BYTES, repaired.root.resolve("common/lib/kotlin-compiler.jar").readBytes())

            val link = root.resolveSibling("tooling-link")
            runCatching { Files.createSymbolicLink(link, root) }.getOrElse { return@withPackage }
            try {
                assertFailsWith<PackagedToolingBundleException> {
                    publish(ByteArrayInputStream(archive), link, manifest)
                }
            } finally {
                Files.deleteIfExists(link)
            }
        }

    private fun withPackage(block: (ByteArray, Path, ToolingBundleManifest) -> Unit) {
        val temporary = createTempDirectory("compukters-tooling-bundle-test-").toAbsolutePath().normalize()
        try {
            val manifest = ToolingBundleManifest.create(FILES, PROFILES)
            val entries =
                linkedMapOf<String, ByteArray>().apply {
                    putAll(FILES)
                    putAll(manifest.encodedFiles())
                    put("META-INF/NOTICE.txt", "notice\n".encodeToByteArray())
                    put("META-INF/licenses/Compukters-Apache-2.0.txt", "license\n".encodeToByteArray())
                }
            block(zstd(zip(entries)), temporary.resolve("published"), manifest)
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    private fun removeEntry(
        archive: ByteArray,
        removed: String,
    ): ByteArray = zstd(zip(unzip(archive).filterKeys { it != removed }))

    private fun replaceEntry(
        archive: ByteArray,
        name: String,
        bytes: ByteArray,
    ): ByteArray = zstd(zip(unzip(archive) + (name to bytes)))

    private fun unzip(archive: ByteArray): Map<String, ByteArray> =
        java.util.zip.ZipInputStream(ZstdInputStream(ByteArrayInputStream(archive))).use { input ->
            buildMap {
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (!entry.isDirectory) put(entry.name, input.readAllBytes())
                    input.closeEntry()
                }
            }
        }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
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

    private fun zstd(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZstdOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun publish(
        archive: InputStream,
        root: Path,
        manifest: ToolingBundleManifest,
        limits: PackagedToolingBundleLimits = PackagedToolingBundleLimits(),
    ): PublishedToolingBundle =
        PackagedToolingBundle.publish(
            manifest.canonicalBundleText().byteInputStream(),
            archive,
            root,
            limits,
        )

    private fun duplicateZip(): ByteArray {
        val bytes = zip(linkedMapOf("common/lib/a.jar" to byteArrayOf(1), "common/lib/b.jar" to byteArrayOf(2)))
        val original = "common/lib/b.jar".encodeToByteArray()
        val duplicate = "common/lib/a.jar".encodeToByteArray()
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
        class FailOnReadInputStream : InputStream() {
            override fun read(): Int = error("cached tooling publication must not read the carrier")
        }

        val COMMON_BYTES = byteArrayOf(1)
        val COMPILER_BYTES = byteArrayOf(2)
        val ANALYSIS_BYTES = byteArrayOf(3)
        val FILES =
            linkedMapOf(
                "common/lib/kotlin-compiler.jar" to COMMON_BYTES,
                "compiler/lib/compiler.jar" to COMPILER_BYTES,
                "analysis/lib/analysis.jar" to ANALYSIS_BYTES,
            )
        val PROFILES =
            mapOf(
                "compiler" to
                    ToolingProfileDefinition(
                        mapOf("compiler" to "2.4.10", "language" to "2.4"),
                        "example.CompilerMain",
                        listOf("common/lib/kotlin-compiler.jar", "compiler/lib/compiler.jar"),
                    ),
                "analysis" to
                    ToolingProfileDefinition(
                        mapOf("compiler" to "2.4.10", "language" to "2.4"),
                        "example.AnalysisMain",
                        listOf("common/lib/kotlin-compiler.jar", "analysis/lib/analysis.jar"),
                    ),
            )
    }
}
