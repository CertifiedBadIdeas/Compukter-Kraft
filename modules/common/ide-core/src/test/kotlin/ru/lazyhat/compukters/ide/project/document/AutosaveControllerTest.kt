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

import kotlin.test.Test
import kotlin.test.assertEquals

class AutosaveControllerTest {
    @Test
    fun `latest edit schedules one save after 500 milliseconds`() {
        val clock = FakeClock()
        val autosave = AutosaveController(clock::now)

        autosave.edited()
        clock.advanceMillis(400)
        assertEquals(AutosaveAction.NoAction, autosave.poll())
        autosave.edited()
        clock.advanceMillis(499)
        assertEquals(AutosaveAction.NoAction, autosave.poll())
        clock.advanceMillis(1)
        assertEquals(AutosaveAction.SaveRequested, autosave.poll())
        assertEquals(AutosaveAction.NoAction, autosave.poll())
        autosave.saveSucceeded()
        clock.advanceMillis(500)
        assertEquals(AutosaveAction.NoAction, autosave.poll())
    }

    @Test
    fun `interaction boundaries request immediate save only while dirty`() {
        listOf<(AutosaveController) -> AutosaveAction>(
            AutosaveController::mouseActivity,
            AutosaveController::focusLost,
            AutosaveController::activeFileChanging,
            AutosaveController::buildRequested,
            AutosaveController::closeRequested,
        ).forEach { boundary ->
            val autosave = AutosaveController(clockNanos = { 0L })
            assertEquals(AutosaveAction.NoAction, boundary(autosave))
            autosave.edited()
            assertEquals(AutosaveAction.SaveRequested, boundary(autosave))
            assertEquals(AutosaveAction.NoAction, boundary(autosave))
        }
    }

    @Test
    fun `failure and conflict do not retry until a later edit`() {
        val clock = FakeClock()
        val autosave = AutosaveController(clock::now)
        autosave.edited()
        assertEquals(AutosaveAction.SaveRequested, autosave.mouseActivity())
        autosave.saveFailed()
        clock.advanceMillis(5_000)
        assertEquals(AutosaveAction.NoAction, autosave.poll())

        autosave.edited()
        assertEquals(AutosaveAction.SaveRequested, autosave.mouseActivity())
        autosave.conflicted()
        clock.advanceMillis(5_000)
        assertEquals(AutosaveAction.NoAction, autosave.poll())
    }

    private class FakeClock {
        private var nanos = 0L

        fun now(): Long = nanos

        fun advanceMillis(milliseconds: Long) {
            nanos += milliseconds * 1_000_000L
        }
    }
}
