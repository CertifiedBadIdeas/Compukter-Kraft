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

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AnalysisSnapshotTransitionTest {
    @Test
    fun `equal snapshot identity is already current`() {
        val confirmed = snapshot(1, "a.kt" to "val value = 1")
        val equivalent = snapshot(1, "a.kt" to "val value = 1")

        assertEquals(AnalysisSnapshotTransition.AlreadyCurrent, transition(confirmed, equivalent))
    }

    @Test
    fun `same profile and paths transmit only changed full sources`() {
        val confirmed = snapshot(1, "a.kt" to "val a = 1", "b.kt" to "val b = 2", "c.kt" to "val c = 3")
        val target = snapshot(1, "a.kt" to "val a = 10", "b.kt" to "val b = 2", "c.kt" to "val c = 30")

        val incremental = assertIs<AnalysisSnapshotTransition.Incremental>(transition(confirmed, target))

        assertEquals(listOf("a.kt", "c.kt"), incremental.changedSources.map { it.path.value })
        assertEquals(listOf("val a = 10", "val c = 30"), incremental.changedSources.map(::text))
    }

    @Test
    fun `profile change requires full open`() {
        val confirmed = snapshot(1, "a.kt" to "val value = 1")
        val target = snapshot(2, "a.kt" to "val value = 2")

        assertEquals(AnalysisSnapshotTransition.FullOpen, transition(confirmed, target))
    }

    @Test
    fun `source addition removal and rename require full open`() {
        val confirmed = snapshot(1, "a.kt" to "val value = 1")
        val added = snapshot(1, "a.kt" to "val value = 1", "b.kt" to "val other = 2")
        val removed = snapshot(1, "b.kt" to "val other = 2")
        val renamed = snapshot(1, "renamed.kt" to "val value = 1")

        listOf(added, removed, renamed).forEach { target ->
            assertEquals(AnalysisSnapshotTransition.FullOpen, transition(confirmed, target))
        }
    }

    @Test
    fun `direct transition skips intermediate source contents`() {
        val revisions =
            listOf(
                snapshot(1, "a.kt" to "val value = 1"),
                snapshot(1, "a.kt" to "val value = 2"),
                snapshot(1, "a.kt" to "val value = 3"),
            )

        val incremental = assertIs<AnalysisSnapshotTransition.Incremental>(transition(revisions.first(), revisions.last()))

        assertEquals(listOf("val value = 3"), incremental.changedSources.map(::text))
    }

    private fun snapshot(
        profileByte: Int,
        vararg sources: Pair<String, String>,
    ): AdmittedAnalysisSnapshot {
        val project =
            ProjectSnapshot.of(
                sources.map { (path, source) ->
                    ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(source.encodeToByteArray()))
                },
                WorkerLimits(),
            )
        val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { profileByte.toByte() }))
        return AdmittedAnalysisSnapshot(
            AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(project), profile),
            project,
            AdmittedAnalysisProfile(
                profile,
                ru.lazyhat.compukters.ide.analysis.protocol
                    .AdmittedAnalysisPlatform(Hash256.zero(), emptyList()),
            ),
            AnalysisLimits(),
        )
    }

    private fun text(source: ProjectSource): String = source.content.toByteArray().decodeToString()
}
