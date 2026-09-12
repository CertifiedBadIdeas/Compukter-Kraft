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

package ru.lazyhat.compukters.ide.project.document

enum class AutosaveAction {
    NoAction,
    SaveRequested,
}

class AutosaveController(
    private val clockNanos: () -> Long,
    private val delayNanos: Long = 500_000_000L,
) {
    private var dirty = false
    private var requestOutstanding = false
    private var mouseFlushArmed = false
    private var deadline = 0L

    init {
        require(delayNanos >= 0) { "autosave delay must be non-negative" }
    }

    fun edited() {
        dirty = true
        requestOutstanding = false
        mouseFlushArmed = true
        deadline = Math.addExact(clockNanos(), delayNanos)
    }

    fun poll(): AutosaveAction = if (dirty && !requestOutstanding && clockNanos() >= deadline) requestSave() else AutosaveAction.NoAction

    fun mouseActivity(): AutosaveAction {
        if (!mouseFlushArmed) return AutosaveAction.NoAction
        mouseFlushArmed = false
        return immediateBoundary()
    }

    fun focusLost(): AutosaveAction = immediateBoundary()

    fun activeFileChanging(): AutosaveAction = immediateBoundary()

    fun buildRequested(): AutosaveAction = immediateBoundary()

    fun closeRequested(): AutosaveAction = immediateBoundary()

    fun saveSucceeded() {
        dirty = false
        requestOutstanding = false
        mouseFlushArmed = false
    }

    fun saveFailed() {
        requestOutstanding = true
        mouseFlushArmed = false
    }

    fun conflicted() = saveFailed()

    private fun immediateBoundary(): AutosaveAction = if (dirty && !requestOutstanding) requestSave() else AutosaveAction.NoAction

    private fun requestSave(): AutosaveAction {
        requestOutstanding = true
        return AutosaveAction.SaveRequested
    }
}
