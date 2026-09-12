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

internal data class SourceIndexPerformanceReport(
    val files: Int,
    val lines: Int,
    val initialIndex: PhaseSamples,
    val indexReplace: PhaseSamples,
    val psiSynchronization: PhaseSamples,
    val sourceReindex: PhaseSamples,
    val k2Invalidation: PhaseSamples,
    val workspaceUpdate: PhaseSamples,
    val rebuildsPerUpdate: Int,
) {
    init {
        require(files > 0) { "source index fixture must contain files" }
        require(lines > 0) { "source index fixture must contain lines" }
        require(rebuildsPerUpdate == 1) { "one-file update must rebuild exactly one source index entry" }
    }

    fun render(): String =
        buildString {
            appendLine("compukters.analysis.sourceIndexPerformance.v$SCHEMA_VERSION")
            appendLine("schemaVersion=$SCHEMA_VERSION")
            appendLine("files=$files")
            appendLine("lines=$lines")
            appendPhase("initialIndex", initialIndex)
            appendPhase("indexReplace", indexReplace)
            appendPhase("psiSynchronization", psiSynchronization)
            appendPhase("sourceReindex", sourceReindex)
            appendPhase("k2Invalidation", k2Invalidation)
            appendPhase("workspaceUpdate", workspaceUpdate)
            appendLine("rebuildsPerUpdate=$rebuildsPerUpdate")
        }

    private fun StringBuilder.appendPhase(
        name: String,
        samples: PhaseSamples,
    ) {
        appendLine("$name.medianNanos=${samples.medianNanos}")
        appendLine("$name.p95Nanos=${samples.p95Nanos}")
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
