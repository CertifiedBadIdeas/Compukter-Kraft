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

package ru.lazyhat.compukters.ide.analysis.k2.measurement

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath

internal data class AnalysisMeasurementSource(
    val path: VirtualSourcePath,
    val text: String,
)

internal class AnalysisMeasurementFixture(
    sources: List<AnalysisMeasurementSource>,
    val completionPrefix: String,
) {
    val sources: List<AnalysisMeasurementSource> = sources.toList()
    val totalLines: Int = this.sources.sumOf { it.text.lines().size }
}

internal object AnalysisMeasurementFixtures {
    fun singleFile(): AnalysisMeasurementFixture =
        AnalysisMeasurementFixture(
            listOf(
                AnalysisMeasurementSource(
                    VirtualSourcePath.kotlin("benchmark/Main.kt"),
                    sourceText(
                        header =
                            listOf(
                                "package benchmark",
                                "fun candidate() = Unit",
                                "fun completionProbe() { can }",
                            ),
                        generatedLines = 497,
                        prefix = "singleValue",
                    ),
                ),
            ),
            completionPrefix = "can",
        )

    fun fiveFiles(): AnalysisMeasurementFixture =
        AnalysisMeasurementFixture(
            List(5) { fileIndex ->
                val header =
                    buildList {
                        add("package benchmark")
                        add("fun file${fileIndex}Seed() = $fileIndex")
                        add(
                            if (fileIndex == 0) {
                                "fun file${fileIndex}Use() = file${fileIndex}Seed()"
                            } else {
                                "fun file${fileIndex}Use() = file${fileIndex - 1}Seed()"
                            },
                        )
                    }
                AnalysisMeasurementSource(
                    VirtualSourcePath.kotlin("benchmark/File$fileIndex.kt"),
                    sourceText(header, 897, "file${fileIndex}Value"),
                )
            },
            completionPrefix = "file0S",
        )

    fun maximumFiles(): AnalysisMeasurementFixture {
        val supportFiles =
            List(511) { fileIndex ->
                val previousIndex = (fileIndex - 1).coerceAtLeast(0)
                val header =
                    listOf(
                        "package benchmark",
                        "class File${fileIndex}Type",
                        "object File${fileIndex}Object",
                        "fun file${fileIndex}Seed() = $fileIndex",
                        "val file${fileIndex}Value get() = file${previousIndex}Seed()",
                    )
                AnalysisMeasurementSource(
                    VirtualSourcePath.kotlin("benchmark/File${fileIndex.toString().padStart(3, '0')}.kt"),
                    sourceText(
                        header = header,
                        generatedLines = if (fileIndex < 388) 5 else 4,
                        prefix = "file${fileIndex}Generated",
                    ),
                )
            }
        val activeFile =
            AnalysisMeasurementSource(
                VirtualSourcePath.kotlin("benchmark/File511.kt"),
                listOf(
                    "package benchmark",
                    "class ActiveType",
                    "object ActiveObject",
                    "fun activeUse() = file0Seed()",
                    "val activeValue get() = file510Seed()",
                ).joinToString("\n"),
            )
        return AnalysisMeasurementFixture(
            supportFiles + activeFile,
            completionPrefix = "file0S",
        )
    }

    private fun sourceText(
        header: List<String>,
        generatedLines: Int,
        prefix: String,
    ): String =
        buildList {
            addAll(header)
            repeat(generatedLines) { index -> add("val $prefix$index get() = $index") }
        }.joinToString("\n")
}
