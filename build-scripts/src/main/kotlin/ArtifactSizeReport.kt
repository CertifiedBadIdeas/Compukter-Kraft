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

import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class ArtifactSizeCategory(
    val name: String,
    val bytes: Long,
)

data class ArtifactSizeReportModel(
    val categories: List<ArtifactSizeCategory>,
    val classifiedOuterEntries: Int,
    val totalOuterEntries: Int,
    val totalBytes: Long,
    val baselineBytes: Long,
) {
    val baselineDeltaBytes: Long = totalBytes - baselineBytes

    fun render(): String {
        val numbers = NumberFormat.getIntegerInstance(Locale.ROOT)
        return buildString {
            appendLine("category\tbytes\tMiB")
            categories.forEach { category ->
                append(category.name).append('\t').append(category.bytes).append('\t')
                appendLine(String.format(Locale.ROOT, "%.1f", category.bytes / MIB))
            }
            append("total\t").append(totalBytes).append('\t')
            appendLine(String.format(Locale.ROOT, "%.1f", totalBytes / MIB))
            append("delta from ").append(numbers.format(baselineBytes)).append(" bytes\t")
            append(baselineDeltaBytes).append('\t')
            appendLine(String.format(Locale.ROOT, "%+.1f", baselineDeltaBytes / MIB))
            append("classified outer entries\t").append(classifiedOuterEntries).append('/').appendLine(totalOuterEntries)
        }
    }

    private companion object {
        const val MIB = 1024.0 * 1024.0
    }
}

object ArtifactSizeReport {
    const val DEFAULT_BASELINE_BYTES = 86_077_515L
    const val TOOLING_RESOURCE = "tooling/workers/k2-tooling-workers.zip.xz"

    fun classify(
        archive: Path,
        baselineBytes: Long = DEFAULT_BASELINE_BYTES,
    ): ArtifactSizeReportModel {
        require(baselineBytes >= 0) { "artifact size baseline must not be negative" }
        require(Files.isRegularFile(archive)) { "artifact must be a regular file: $archive" }

        val categories = CATEGORY_NAMES.associateWithTo(linkedMapOf()) { 0L }
        var totalEntries = 0
        var classifiedEntries = 0
        var compressedEntryBytes = 0L
        var toolingEntries = 0
        ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                totalEntries++
                require(entry.compressedSize >= 0) { "ZIP entry has no compressed size: ${entry.name}" }
                compressedEntryBytes += entry.compressedSize
                when {
                    entry.name == TOOLING_RESOURCE -> {
                        toolingEntries++
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val weights = toolingWeights(bytes)
                        allocate(entry.compressedSize, weights).forEach { (name, amount) ->
                            categories[name] = categories.getValue(name) + amount
                        }
                    }
                    entry.name == "compiler/worker/compiler-k2-worker.zip" ||
                        entry.name == "analysis/worker/ide-analysis-k2-worker.zip" ->
                        throw IllegalArgumentException("legacy tooling worker archive is forbidden: ${entry.name}")
                    entry.name.startsWith("META-INF/jars/") && entry.name.endsWith(".jar") ->
                        categories.add(OUTER_JVM, entry.compressedSize)
                    entry.name.startsWith("META-INF/natives/") ->
                        categories.add(NATIVE, entry.compressedSize)
                    else -> categories.add(MOD, entry.compressedSize)
                }
                classifiedEntries++
            }
        }
        require(toolingEntries == 1) { "expected exactly one $TOOLING_RESOURCE, found $toolingEntries" }

        val totalBytes = Files.size(archive)
        val zipStructure = totalBytes - compressedEntryBytes
        require(zipStructure >= 0) { "ZIP entry sizes exceed the artifact size" }
        categories[ZIP_STRUCTURE] = zipStructure
        require(categories.values.sum() == totalBytes) { "artifact size report does not account for every archive byte" }
        require(classifiedEntries == totalEntries) { "artifact size report left an outer entry unclassified" }
        return ArtifactSizeReportModel(
            categories.map { (name, bytes) -> ArtifactSizeCategory(name, bytes) },
            classifiedEntries,
            totalEntries,
            totalBytes,
            baselineBytes,
        )
    }

    fun write(
        archive: Path,
        output: Path,
        baselineBytes: Long = DEFAULT_BASELINE_BYTES,
    ): ArtifactSizeReportModel {
        val report = classify(archive, baselineBytes)
        output.parent?.let(Files::createDirectories)
        Files.writeString(output, report.render())
        return report
    }

    private fun toolingWeights(bytes: ByteArray): Map<String, Long> {
        val weights = linkedMapOf(COMMON to 0L, COMPILER_PRIVATE to 0L, ANALYSIS_PRIVATE to 0L)
        var files = 0
        ZipInputStream(XZInputStream(ByteArrayInputStream(bytes))).use { nested ->
            while (true) {
                val entry = nested.nextEntry ?: break
                if (!entry.isDirectory) {
                    val category =
                        when {
                            entry.name.startsWith("common/") ||
                                entry.name.startsWith("META-INF/") ||
                                entry.name.startsWith("manifests/") ||
                                entry.name == "tooling.bundle" -> COMMON
                            entry.name.startsWith("compiler/") -> COMPILER_PRIVATE
                            entry.name.startsWith("analysis/") -> ANALYSIS_PRIVATE
                            else -> throw IllegalArgumentException("unknown shared tooling entry: ${entry.name}")
                        }
                    var size = 0L
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = nested.read(buffer)
                        if (read < 0) break
                        size += read
                    }
                    weights[category] = weights.getValue(category) + size
                    files++
                }
                nested.closeEntry()
            }
        }
        require(files > 0) { "shared tooling archive is empty" }
        return weights
    }

    private fun allocate(
        total: Long,
        weights: Map<String, Long>,
    ): Map<String, Long> {
        val weightTotal = weights.values.sum()
        if (weightTotal == 0L) return weights.mapValues { (name, _) -> if (name == COMMON) total else 0L }
        val allocated = weights.mapValuesTo(linkedMapOf()) { (_, weight) -> total * weight / weightTotal }
        var remaining = total - allocated.values.sum()
        val order =
            weights.entries.sortedWith(
                compareByDescending<Map.Entry<String, Long>> { (_, weight) -> (total * weight) % weightTotal }
                    .thenBy { it.key },
            )
        var index = 0
        while (remaining > 0) {
            val name = order[index % order.size].key
            allocated[name] = allocated.getValue(name) + 1
            index++
            remaining--
        }
        return allocated
    }

    private fun MutableMap<String, Long>.add(name: String, bytes: Long) {
        this[name] = getValue(name) + bytes
    }

    private const val COMMON = "tooling common"
    private const val COMPILER_PRIVATE = "tooling compiler-private"
    private const val ANALYSIS_PRIVATE = "tooling analysis-private"
    private const val OUTER_JVM = "outer JVM dependencies"
    private const val NATIVE = "native runtimes by OS/arch"
    private const val MOD = "mod classes/resources"
    private const val ZIP_STRUCTURE = "ZIP structure"
    private val CATEGORY_NAMES = listOf(COMMON, COMPILER_PRIVATE, ANALYSIS_PRIVATE, OUTER_JVM, NATIVE, MOD, ZIP_STRUCTURE)
}
