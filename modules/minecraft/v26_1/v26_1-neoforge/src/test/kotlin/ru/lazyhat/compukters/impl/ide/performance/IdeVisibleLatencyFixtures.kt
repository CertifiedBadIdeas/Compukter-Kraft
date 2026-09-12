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

package ru.lazyhat.compukters.impl.ide.performance

import ru.lazyhat.compukters.ide.project.fs.ProjectPath

internal data class IdeVisibleLatencySource(
    val path: ProjectPath,
    val text: String,
)

internal class IdeVisibleLatencyFixture(
    val name: String,
    sources: List<IdeVisibleLatencySource>,
    val activePath: ProjectPath,
) {
    val sources: List<IdeVisibleLatencySource> = sources.toList()
    val totalLines: Int = this.sources.sumOf { it.text.lines().size }

    init {
        require(name.isNotBlank()) { "fixture name must not be blank" }
        require(this.sources.isNotEmpty()) { "fixture sources must not be empty" }
        require(
            this.sources
                .map(IdeVisibleLatencySource::path)
                .distinct()
                .size == this.sources.size,
        ) {
            "fixture source paths must be unique"
        }
        require(this.sources.any { it.path == activePath }) { "active source is missing" }
        require(totalLines <= MAXIMUM_LINES) { "fixture exceeds $MAXIMUM_LINES lines" }
        require(activeSource().text.count { it == MEASUREMENT_MARKER } == 1) { "active source measurement marker is invalid" }
        require(COMPLETION_PROBE in activeSource().text) { "active source completion probe is missing" }
    }

    fun activeText(measurementRevision: Int): String {
        require(measurementRevision >= 0) { "measurement revision must not be negative" }
        return activeSource().text.replace(MEASUREMENT_MARKER.toString(), measurementRevision.toString())
    }

    fun completionCaretUtf16(measurementRevision: Int): Int {
        val text = activeText(measurementRevision)
        val probe = text.indexOf(COMPLETION_PROBE)
        check(probe >= 0)
        return Math.addExact(probe, COMPLETION_PROBE.indexOf(COMPLETION_PREFIX) + COMPLETION_PREFIX.length)
    }

    private fun activeSource(): IdeVisibleLatencySource = sources.single { it.path == activePath }

    private companion object {
        const val MAXIMUM_LINES = 5_000
        const val MEASUREMENT_MARKER = '#'
        const val COMPLETION_PROBE = "fun completionProbe() { can }"
        const val COMPLETION_PREFIX = "can"
    }
}

internal object IdeVisibleLatencyFixtures {
    fun singleFile(): IdeVisibleLatencyFixture {
        val active = ProjectPath.file("src/main.kt")
        return IdeVisibleLatencyFixture(
            name = "single-file",
            sources =
                listOf(
                    IdeVisibleLatencySource(
                        active,
                        sourceText(
                            header =
                                listOf(
                                    "package benchmark",
                                    "fun candidate() = Unit",
                                    "fun completionProbe() { can }",
                                    "val measurementRevision = #",
                                ),
                            generatedLines = 497,
                            prefix = "singleValue",
                        ),
                    ),
                ),
            activePath = active,
        )
    }

    fun fiveFiles(): IdeVisibleLatencyFixture {
        val sources =
            List(5) { fileIndex ->
                val path = if (fileIndex == 0) ProjectPath.file("src/main.kt") else ProjectPath.file("src/File$fileIndex.kt")
                val header =
                    buildList {
                        add("package benchmark")
                        add("fun file${fileIndex}Seed() = $fileIndex")
                        add(
                            if (fileIndex ==
                                0
                            ) {
                                "fun file0Use() = file0Seed()"
                            } else {
                                "fun file${fileIndex}Use() = file${fileIndex - 1}Seed()"
                            },
                        )
                        if (fileIndex == 4) {
                            add("fun candidate() = file0Seed()")
                            add("fun completionProbe() { can }")
                            add("val measurementRevision = #")
                        }
                    }
                IdeVisibleLatencySource(path, sourceText(header, 897, "file${fileIndex}Value"))
            }
        return IdeVisibleLatencyFixture("five-file", sources, sources.last().path)
    }

    private fun sourceText(
        header: List<String>,
        generatedLines: Int,
        prefix: String,
    ): String =
        buildList {
            addAll(header)
            repeat(generatedLines) { index -> add("val $prefix$index get() = $index") }
        }.joinToString(separator = "\n", postfix = "\n")
}
