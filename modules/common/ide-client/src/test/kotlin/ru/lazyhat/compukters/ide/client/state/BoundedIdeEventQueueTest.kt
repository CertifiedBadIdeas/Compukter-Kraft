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

package ru.lazyhat.compukters.ide.client.state

import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.tree.ProjectTree
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoundedIdeEventQueueTest {
    @Test
    fun `replaceable events coalesce and terminal events reject overflow`() {
        val queue = BoundedIdeEventQueue(capacity = 2)
        val firstTree = tree()
        val secondTree = tree()
        val build = IdeBuildSummary("ab", 2, cacheHit = false)
        assertTrue(queue.offer(IdeEvent.PollCompleted(1, firstTree)))
        assertTrue(queue.offer(IdeEvent.PollCompleted(1, secondTree)))
        assertTrue(queue.offer(IdeEvent.BuildCompleted(1, build)))

        assertFalse(queue.offer(IdeEvent.BuildCompleted(1, build)))
        assertEquals(
            listOf(IdeEvent.PollCompleted(1, secondTree), IdeEvent.BuildCompleted(1, build)),
            queue.drain(),
        )
    }

    @Test
    fun `queue copies collection payloads before publication`() {
        val projects = mutableListOf(IdeProjectSummary("one", "One"))
        val queue = BoundedIdeEventQueue(1)

        assertTrue(queue.offer(IdeEvent.CatalogLoaded(1, projects)))
        projects.clear()

        val event = assertIs<IdeEvent.CatalogLoaded>(queue.drain().single())
        assertEquals(listOf(IdeProjectSummary("one", "One")), event.projects)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (event.projects as MutableList<IdeProjectSummary>).clear()
        }
    }

    @Test
    fun `view state never retains mutable collections`() {
        val input = mutableListOf(IdeProjectSummary("one", "One"))
        val state = IdeViewState.startPage(input)
        input.clear()

        val start = assertIs<IdePageState.Start>(state.page)
        assertEquals(1, start.projects.size)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (start.projects as MutableList<IdeProjectSummary>).clear()
        }
    }

    @Test
    fun `invalid queue capacity is rejected`() {
        assertFailsWith<IllegalArgumentException> { BoundedIdeEventQueue(0) }
    }

    private fun tree(): ProjectTree {
        val root = createTempDirectory("compukters-ide-event-tree-")
        val project = ProjectCatalog.open(root).create("hello")
        return ProjectTreeStore(project.handle, ProjectLimits()).scan()
    }
}
