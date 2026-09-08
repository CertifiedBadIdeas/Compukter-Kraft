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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZ
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

class ArtifactSizeReportTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `classifies every outer entry and accounts for the exact archive size`() {
        val tooling = zip(temporary.resolve("tooling.zip"), TOOLING_ENTRIES)
        val archive =
            zip(
                temporary.resolve("mod.jar"),
                linkedMapOf(
                    "tooling/workers/k2-tooling-workers.zip.xz" to xz(tooling.toFile().readBytes()),
                    "META-INF/jars/kotlin-stdlib-2.4.10.jar" to ByteArray(31) { 1 },
                    "META-INF/natives/linux/x86_64/libcompukter_ffi.so" to ByteArray(47) { 2 },
                    "ru/lazyhat/compukters/Main.class" to ByteArray(59) { 3 },
                ),
            )

        val report = ArtifactSizeReport.classify(archive, baselineBytes = 1_000)

        assertEquals(4, report.classifiedOuterEntries)
        assertEquals(4, report.totalOuterEntries)
        assertEquals(archive.toFile().length(), report.totalBytes)
        assertEquals(report.totalBytes, report.categories.sumOf(ArtifactSizeCategory::bytes))
        assertEquals(report.totalBytes - 1_000, report.baselineDeltaBytes)
        assertEquals(
            setOf(
                "tooling common",
                "tooling compiler-private",
                "tooling analysis-private",
                "outer JVM dependencies",
                "native runtimes by OS/arch",
                "mod classes/resources",
                "ZIP structure",
            ),
            report.categories.mapTo(linkedSetOf(), ArtifactSizeCategory::name),
        )
        assertTrue(report.render().contains("delta from 1,000 bytes"))
    }

    @Test
    fun `rejects legacy tooling archives and unknown shared bundle entries`() {
        val legacy = zip(temporary.resolve("legacy.jar"), mapOf("compiler/worker/compiler-k2-worker.zip" to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { ArtifactSizeReport.classify(legacy, 1_000) }

        val tooling = zip(temporary.resolve("invalid-tooling.zip"), TOOLING_ENTRIES + ("other/lib/leak.jar" to byteArrayOf(9)))
        val invalid =
            zip(
                temporary.resolve("invalid.jar"),
                mapOf("tooling/workers/k2-tooling-workers.zip.xz" to xz(tooling.toFile().readBytes())),
            )
        assertThrows(IllegalArgumentException::class.java) { ArtifactSizeReport.classify(invalid, 1_000) }
    }

    private fun zip(path: Path, entries: Map<String, ByteArray>): Path {
        ZipOutputStream(path.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name).apply { time = 0L })
                output.write(bytes)
                output.closeEntry()
            }
        }
        return path
    }

    private fun xz(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        XZOutputStream(output, LZMA2Options(0), XZ.CHECK_CRC32).use { it.write(bytes) }
        return output.toByteArray()
    }

    private companion object {
        val TOOLING_ENTRIES =
            linkedMapOf(
                "tooling.bundle" to "format=1\n".toByteArray(),
                "common/lib/compiler.jar" to ByteArray(101) { 4 },
                "compiler/lib/compiler-private.jar" to ByteArray(23) { 5 },
                "analysis/lib/analysis-private.jar" to ByteArray(67) { 6 },
                "META-INF/NOTICE.txt" to "notice".toByteArray(),
            )
    }
}
