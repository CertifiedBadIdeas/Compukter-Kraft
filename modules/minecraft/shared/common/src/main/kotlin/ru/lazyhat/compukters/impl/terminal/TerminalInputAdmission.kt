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

package ru.lazyhat.compukters.impl.terminal

import java.util.UUID
import java.util.WeakHashMap

object TerminalInputAdmission {
    private val limiter = TerminalInputRateLimiter(MAXIMUM_INPUT_EVENTS_PER_TICK)

    fun accept(
        player: UUID,
        tick: Long,
    ): Boolean = limiter.accept(player, tick)

    private const val MAXIMUM_INPUT_EVENTS_PER_TICK = 64
}

class TerminalInputRateLimiter(
    private val maximumEventsPerTick: Int,
) {
    init {
        require(maximumEventsPerTick > 0) { "maximum input events per tick must be positive" }
    }

    private val windows = WeakHashMap<UUID, Window>()

    fun accept(
        player: UUID,
        tick: Long,
    ): Boolean {
        val previous = windows[player]
        if (previous == null || previous.tick != tick) {
            windows[player] = Window(tick, 1)
            return true
        }
        if (previous.events >= maximumEventsPerTick) return false
        windows[player] = previous.copy(events = previous.events + 1)
        return true
    }

    private data class Window(
        val tick: Long,
        val events: Int,
    )
}
