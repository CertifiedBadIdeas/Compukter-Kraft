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

import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity

internal enum class AnalysisWorkKind {
    ManualInteractive,
    AutomaticCompletion,
    BackgroundPresentation,
}

internal data class ScheduledAnalysisWork<T>(
    val identity: AnalysisSnapshotIdentity,
    val kind: AnalysisWorkKind,
    val value: T,
)

internal data class AnalysisScheduleUpdate<T>(
    val accepted: Boolean,
    val started: ScheduledAnalysisWork<T>?,
    val displaced: List<ScheduledAnalysisWork<T>>,
)

internal class AnalysisScheduler<T> {
    var active: ScheduledAnalysisWork<T>? = null
        private set
    private var interactive: ScheduledAnalysisWork<T>? = null
    private var background: ScheduledAnalysisWork<T>? = null

    val size: Int
        get() = listOfNotNull(active, interactive, background).size

    fun values(): List<ScheduledAnalysisWork<T>> = listOfNotNull(active, interactive, background)

    fun offer(work: ScheduledAnalysisWork<T>): AnalysisScheduleUpdate<T> {
        if (active == null) {
            active = work
            return AnalysisScheduleUpdate(true, work, emptyList())
        }
        return when (work.kind) {
            AnalysisWorkKind.BackgroundPresentation -> replaceBackground(work)
            AnalysisWorkKind.ManualInteractive -> replaceInteractive(work)
            AnalysisWorkKind.AutomaticCompletion -> offerAutomatic(work)
        }
    }

    fun completeActive(): AnalysisScheduleUpdate<T> {
        require(active != null) { "analysis scheduler has no active work" }
        val next = interactive ?: background
        if (interactive != null) {
            interactive = null
        } else {
            background = null
        }
        active = next
        return AnalysisScheduleUpdate(true, next, emptyList())
    }

    fun dropQueuedExcept(identity: AnalysisSnapshotIdentity): List<ScheduledAnalysisWork<T>> {
        val dropped = mutableListOf<ScheduledAnalysisWork<T>>()
        interactive?.takeIf { it.identity != identity }?.let { work ->
            dropped += work
            interactive = null
        }
        background?.takeIf { it.identity != identity }?.let { work ->
            dropped += work
            background = null
        }
        return dropped
    }

    fun removeActive(value: T): Boolean {
        if (active?.value != value) return false
        active = null
        return true
    }

    fun remove(value: T): ScheduledAnalysisWork<T>? {
        if (active?.value == value) return active.also { active = null }
        if (interactive?.value == value) return interactive.also { interactive = null }
        if (background?.value == value) return background.also { background = null }
        return null
    }

    fun clear(): List<ScheduledAnalysisWork<T>> {
        val values = listOfNotNull(active, interactive, background)
        active = null
        interactive = null
        background = null
        return values
    }

    private fun replaceBackground(work: ScheduledAnalysisWork<T>): AnalysisScheduleUpdate<T> {
        val displaced = listOfNotNull(background)
        background = work
        return AnalysisScheduleUpdate(true, null, displaced)
    }

    private fun replaceInteractive(work: ScheduledAnalysisWork<T>): AnalysisScheduleUpdate<T> {
        val displaced = listOfNotNull(interactive)
        interactive = work
        return AnalysisScheduleUpdate(true, null, displaced)
    }

    private fun offerAutomatic(work: ScheduledAnalysisWork<T>): AnalysisScheduleUpdate<T> {
        val queued = interactive
        if (queued?.kind == AnalysisWorkKind.ManualInteractive) {
            return AnalysisScheduleUpdate(false, null, listOf(work))
        }
        interactive = work
        return AnalysisScheduleUpdate(true, null, listOfNotNull(queued))
    }
}
