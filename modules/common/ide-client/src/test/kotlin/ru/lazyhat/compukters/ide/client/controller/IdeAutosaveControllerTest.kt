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

package ru.lazyhat.compukters.ide.client.controller

import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import kotlin.test.Test
import kotlin.test.assertEquals

class IdeAutosaveControllerTest {
    @Test
    fun `autosave starts only after five hundred milliseconds without edits`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("a")))

        fixture.clock.now = 499
        fixture.controller.tick()
        assertEquals(0, fixture.workspace.saveRequests.size)

        fixture.clock.now = 500
        fixture.controller.tick()
        assertEquals(1, fixture.workspace.saveRequests.size)
    }

    @Test
    fun `new edit rearms autosave while previous save is completing`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("a")))
        fixture.clock.now = 500
        fixture.controller.tick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("b")))
        fixture.workspace.completeSave()
        fixture.controller.tick()

        fixture.clock.now = 999
        fixture.controller.tick()
        assertEquals(1, fixture.workspace.saveRequests.size)
        fixture.clock.now = 1_000
        fixture.controller.tick()
        assertEquals(2, fixture.workspace.saveRequests.size)
    }
}
