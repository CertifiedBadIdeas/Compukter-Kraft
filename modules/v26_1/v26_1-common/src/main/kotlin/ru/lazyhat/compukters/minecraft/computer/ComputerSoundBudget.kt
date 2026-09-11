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

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import java.util.WeakHashMap

internal object ComputerSoundBudget {
    private const val MAXIMUM_SOUNDS_PER_TICK = 64
    private val limiters = WeakHashMap<MinecraftServer, SoundTickLimiter>()

    fun tryAcquire(
        level: ServerLevel,
        tick: Long,
    ): Boolean {
        check(level.server.isSameThread) { "sound budget must be consumed on the server thread" }
        return limiters.getOrPut(level.server) { SoundTickLimiter(MAXIMUM_SOUNDS_PER_TICK) }.tryAcquire(tick)
    }
}

internal class SoundTickLimiter(
    private val maximumPerTick: Int,
) {
    private var tick = Long.MIN_VALUE
    private var admitted = 0

    init {
        require(maximumPerTick > 0) { "sound limit must be positive" }
    }

    fun tryAcquire(currentTick: Long): Boolean {
        require(currentTick >= 0) { "sound tick must not be negative" }
        if (tick != currentTick) {
            tick = currentTick
            admitted = 0
        }
        if (admitted >= maximumPerTick) return false
        admitted++
        return true
    }
}

internal class SoundCooldown(
    private val minimumTicks: Int,
) {
    private var lastEmissionTick = Long.MIN_VALUE

    init {
        require(minimumTicks > 0) { "sound cooldown must be positive" }
    }

    fun isReady(currentTick: Long): Boolean {
        require(currentTick >= 0) { "sound tick must not be negative" }
        return lastEmissionTick == Long.MIN_VALUE || currentTick - lastEmissionTick >= minimumTicks
    }

    fun markEmitted(currentTick: Long) {
        require(currentTick >= 0) { "sound tick must not be negative" }
        lastEmissionTick = currentTick
    }
}
