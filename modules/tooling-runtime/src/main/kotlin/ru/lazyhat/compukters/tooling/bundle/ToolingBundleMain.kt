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

import ru.lazyhat.compukters.worker.payload.PackagedToolingBundle
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream

fun main(arguments: Array<String>) {
    when (arguments.firstOrNull()) {
        "assemble" -> {
            require(arguments.size == 4) { "usage: tooling-bundle assemble <compiler-payload> <analysis-payload> <output>" }
            ToolingBundleAssembler.assemble(Path.of(arguments[1]), Path.of(arguments[2]), Path.of(arguments[3]))
        }

        "verify" -> {
            require(arguments.size == 5) {
                "usage: tooling-bundle verify <compiler-payload> <analysis-payload> <archive> <scratch>"
            }
            verify(
                compilerRoot = Path.of(arguments[1]),
                analysisRoot = Path.of(arguments[2]),
                archive = Path.of(arguments[3]),
                scratch = Path.of(arguments[4]),
            )
        }

        "compress" -> {
            require(arguments.size == 3) { "usage: tooling-bundle compress <canonical-zip> <zstd-output>" }
            ToolingBundleCarrier.encode(Path.of(arguments[1]), Path.of(arguments[2]))
        }

        else -> {
            error("expected assemble, compress, or verify command")
        }
    }
}

private fun verify(
    compilerRoot: Path,
    analysisRoot: Path,
    archive: Path,
    scratch: Path,
) {
    deleteTree(scratch)
    scratch.createDirectories()
    try {
        val firstRoot = scratch.resolve("first")
        val secondRoot = scratch.resolve("second")
        val first = ToolingBundleAssembler.assemble(compilerRoot, analysisRoot, firstRoot)
        val second = ToolingBundleAssembler.assemble(compilerRoot, analysisRoot, secondRoot)
        check(first == second) { "tooling assembly manifests are not reproducible" }
        compareTrees(firstRoot, secondRoot)

        val published =
            archive.inputStream().use { input ->
                PackagedToolingBundle.publish(
                    input,
                    scratch.resolve("cache").toAbsolutePath(),
                )
            }
        check(published.manifest == first) { "packaged tooling archive differs from its clean assembly" }
        check(
            first.files.count { file ->
                file.path.startsWith("common/lib/kotlin-compiler-") &&
                    "embeddable" !in file.path
            } == 1,
        ) { "tooling bundle must contain exactly one shared ordinary Kotlin compiler" }
    } finally {
        deleteTree(scratch)
    }
}

private fun compareTrees(
    first: Path,
    second: Path,
) {
    val firstFiles = regularFiles(first)
    val secondFiles = regularFiles(second)
    check(firstFiles == secondFiles) { "tooling assembly file inventories are not reproducible" }
    firstFiles.forEach { relative ->
        check(Files.mismatch(first.resolve(relative), second.resolve(relative)) == -1L) {
            "tooling assembly file is not reproducible: $relative"
        }
    }
}

private fun regularFiles(root: Path): List<Path> =
    Files.walk(root).use { paths ->
        paths
            .filter(Files::isRegularFile)
            .map(root::relativize)
            .sorted()
            .toList()
    }

private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
