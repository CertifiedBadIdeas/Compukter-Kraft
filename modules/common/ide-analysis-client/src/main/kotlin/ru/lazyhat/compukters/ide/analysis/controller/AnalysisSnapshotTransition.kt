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

package ru.lazyhat.compukters.ide.analysis.controller

import ru.lazyhat.compukters.compiler.project.ProjectSource
import java.util.Collections

internal sealed interface AnalysisSnapshotTransition {
    data object AlreadyCurrent : AnalysisSnapshotTransition

    class Incremental(
        changedSources: List<ProjectSource>,
    ) : AnalysisSnapshotTransition {
        val changedSources: List<ProjectSource> = Collections.unmodifiableList(changedSources.toList())
    }

    data object FullOpen : AnalysisSnapshotTransition
}

internal fun transition(
    confirmed: AdmittedAnalysisSnapshot,
    target: AdmittedAnalysisSnapshot,
): AnalysisSnapshotTransition {
    if (confirmed.identity == target.identity) return AnalysisSnapshotTransition.AlreadyCurrent
    if (confirmed.identity.profile != target.identity.profile) return AnalysisSnapshotTransition.FullOpen

    val confirmedSources = confirmed.sources.sources
    val targetSources = target.sources.sources
    if (confirmedSources.size != targetSources.size) return AnalysisSnapshotTransition.FullOpen

    val changed = ArrayList<ProjectSource>()
    confirmedSources.indices.forEach { index ->
        val previous = confirmedSources[index]
        val replacement = targetSources[index]
        if (previous.path != replacement.path) return AnalysisSnapshotTransition.FullOpen
        if (previous.content != replacement.content) changed += replacement
    }
    check(changed.isNotEmpty()) { "different source identities must contain changed source bytes" }
    return AnalysisSnapshotTransition.Incremental(changed)
}
