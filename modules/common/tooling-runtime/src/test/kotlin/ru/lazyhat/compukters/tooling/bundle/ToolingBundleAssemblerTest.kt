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

package ru.lazyhat.compukters.tooling.bundle

import ru.lazyhat.compukters.worker.payload.WorkerPayloadManifest
import ru.lazyhat.compukters.worker.payload.WorkerPayloadPublisher
import ru.lazyhat.compukters.worker.payload.WorkerPayloadSource
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolingBundleAssemblerTest {
    @Test
    fun `partitions exact compatible files and preserves both classpath orders reproducibly`() {
        val root = createTempDirectory("compukters-tooling-assembler-test-")
        try {
            val shared = "shared".encodeToByteArray()
            val compiler =
                payload(
                    root.resolve("compiler-source"),
                    "compiler",
                    linkedMapOf(
                        "compiler-worker.jar" to "compiler-worker".encodeToByteArray(),
                        "renamed-shared.jar" to shared,
                        "collision.jar" to "compiler-collision".encodeToByteArray(),
                        "kotlinx-coroutines-core-jvm-1.8.0.jar" to "ordinary-coroutines".encodeToByteArray(),
                    ),
                )
            val analysis =
                payload(
                    root.resolve("analysis-source"),
                    "analysis",
                    linkedMapOf(
                        "analysis-worker.jar" to "analysis-worker".encodeToByteArray(),
                        "shared.jar" to shared,
                        "collision.jar" to "analysis-collision".encodeToByteArray(),
                        "kotlinx-coroutines-core-jvm-1.8.0-intellij-13.jar" to "intellij-coroutines".encodeToByteArray(),
                    ),
                )

            val first = root.resolve("first")
            val second = root.resolve("second")
            val firstManifest = ToolingBundleAssembler.assemble(compiler, analysis, first)
            val secondManifest = ToolingBundleAssembler.assemble(compiler, analysis, second)

            assertEquals(firstManifest, secondManifest)
            assertEquals(
                firstManifest.files.map { it.path },
                secondManifest.files.map { it.path },
            )
            firstManifest.files.forEach { file ->
                assertContentEquals(first.resolve(file.path).readBytes(), second.resolve(file.path).readBytes())
                ZipInputStream(first.resolve(file.path).readBytes().inputStream()).use { jar ->
                    while (true) {
                        val entry = jar.nextEntry ?: break
                        assertEquals(ZipEntry.STORED, entry.method)
                    }
                }
            }
            val compilerClasspath = firstManifest.profiles.getValue("compiler").classpath
            val analysisClasspath = firstManifest.profiles.getValue("analysis").classpath
            assertEquals(4, compilerClasspath.size)
            assertEquals(4, analysisClasspath.size)
            assertEquals(1, firstManifest.files.count { it.path.startsWith("common/lib/") })
            assertEquals(2, firstManifest.files.count { it.path.substringAfterLast('/').startsWith("collision-") })
            assertTrue(compilerClasspath[2].contains("kotlinx-coroutines-core-jvm-1.8.0"))
            assertTrue(analysisClasspath[2].contains("kotlinx-coroutines-core-jvm-1.8.0-intellij-13"))
            assertTrue(compilerClasspath.last().startsWith("common/lib/"))
            assertEquals(compilerClasspath.last(), analysisClasspath.last())
            assertEquals(
                firstManifest.files.map { it.path }.toSet(),
                (compilerClasspath + analysisClasspath).toSet(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `keeps byte-identical ordinary and IntelliJ coroutine runtimes private`() {
        val root = createTempDirectory("compukters-tooling-coroutines-test-")
        try {
            val sameBytes = "same-coroutine-bytes".encodeToByteArray()
            val compiler =
                payload(
                    root.resolve("compiler-source"),
                    "compiler",
                    linkedMapOf(
                        "compiler-worker.jar" to "compiler-worker".encodeToByteArray(),
                        "kotlinx-coroutines-core-jvm-1.8.0.jar" to sameBytes,
                    ),
                )
            val analysis =
                payload(
                    root.resolve("analysis-source"),
                    "analysis",
                    linkedMapOf(
                        "analysis-worker.jar" to "analysis-worker".encodeToByteArray(),
                        "kotlinx-coroutines-core-jvm-1.8.0-intellij-13.jar" to sameBytes,
                    ),
                )

            val manifest = ToolingBundleAssembler.assemble(compiler, analysis, root.resolve("output"))

            assertTrue(manifest.files.none { it.path.startsWith("common/lib/") })
            assertTrue(
                manifest.profiles
                    .getValue("compiler")
                    .classpath
                    .any { it.startsWith("compiler/lib/kotlinx-coroutines") },
            )
            assertTrue(
                manifest.profiles
                    .getValue("analysis")
                    .classpath
                    .any { it.startsWith("analysis/lib/kotlinx-coroutines") },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects one payload mixing embeddable and ordinary compiler distributions`() {
        val root = createTempDirectory("compukters-tooling-mixed-compiler-test-")
        try {
            val compiler =
                payload(
                    root.resolve("compiler-source"),
                    "compiler",
                    linkedMapOf(
                        "compiler-worker.jar" to "compiler-worker".encodeToByteArray(),
                        "kotlin-compiler-2.4.10.jar" to "ordinary".encodeToByteArray(),
                        "kotlin-compiler-embeddable-2.4.10.jar" to "embeddable".encodeToByteArray(),
                    ),
                )
            val analysis =
                payload(
                    root.resolve("analysis-source"),
                    "analysis",
                    linkedMapOf("analysis-worker.jar" to "analysis-worker".encodeToByteArray()),
                )

            assertFailsWith<IllegalArgumentException> {
                ToolingBundleAssembler.assemble(compiler, analysis, root.resolve("output"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun payload(
        cacheRoot: Path,
        kind: String,
        files: LinkedHashMap<String, ByteArray>,
    ): Path {
        cacheRoot.createDirectories()
        val jars = files.mapValuesTo(linkedMapOf()) { (_, bytes) -> jar(bytes) }
        val manifest =
            WorkerPayloadManifest.create(
                kind = kind,
                identityProperties = mapOf("compiler" to "2.4.10"),
                mainClass = "$kind.MainKt",
                files = jars.mapKeys { (name, _) -> "lib/$name" },
            )
        return WorkerPayloadPublisher
            .publish(
                manifest,
                WorkerPayloadSource { path -> jars.getValue(path.removePrefix("lib/")).inputStream() },
                cacheRoot,
            ).root
    }

    private fun jar(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { jar ->
            jar.putNextEntry(ZipEntry("payload.bin"))
            jar.write(bytes)
            jar.closeEntry()
        }
        return output.toByteArray()
    }
}
