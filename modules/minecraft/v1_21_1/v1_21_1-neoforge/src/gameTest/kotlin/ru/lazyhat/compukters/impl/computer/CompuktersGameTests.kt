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

package ru.lazyhat.compukters.impl.computer

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.impl.benchmark.VmBenchmarkGameTestScenario

@PrefixGameTestTemplate(false)
object CompuktersGameTests {
    @JvmStatic
    @GameTest(
        batch = "lifecycle",
        template = EMPTY_TEMPLATE,
        templateNamespace = "minecraft",
        timeoutTicks = TIMEOUT_TICKS,
    )
    fun computerLifecycle(helper: GameTestHelper) = ComputerLifecycleGameTestScenario.run(helper)

    @JvmStatic
    @GameTest(
        batch = "redstone",
        template = EMPTY_TEMPLATE,
        templateNamespace = "minecraft",
        timeoutTicks = TIMEOUT_TICKS,
    )
    fun computerRedstone(helper: GameTestHelper) = ComputerRedstoneGameTestScenario.run(helper)

    @JvmStatic
    @GameTest(
        batch = "sound",
        template = EMPTY_TEMPLATE,
        templateNamespace = "minecraft",
        timeoutTicks = TIMEOUT_TICKS,
    )
    fun computerSound(helper: GameTestHelper) = ComputerSoundGameTestScenario.run(helper)

    @JvmStatic
    @GameTest(
        batch = "actor_service",
        template = EMPTY_TEMPLATE,
        templateNamespace = "minecraft",
        timeoutTicks = TIMEOUT_TICKS,
    )
    fun vmActorService(helper: GameTestHelper) = VmActorServiceGameTestScenario.run(helper)

    @JvmStatic
    @GameTest(
        batch = "benchmark",
        template = EMPTY_TEMPLATE,
        templateNamespace = "minecraft",
        timeoutTicks = TIMEOUT_TICKS,
    )
    fun vmBenchmark(helper: GameTestHelper) = VmBenchmarkGameTestScenario.run(helper)

    private const val EMPTY_TEMPLATE = "bastion/mobs/empty"
    private const val TIMEOUT_TICKS = 100_000
}

@EventBusSubscriber(modid = MOD_ID)
object CompuktersGameTestRegistration {
    @JvmStatic
    @SubscribeEvent
    fun registerTests(event: RegisterGameTestsEvent) {
        event.register(CompuktersGameTests::class.java)
    }
}
