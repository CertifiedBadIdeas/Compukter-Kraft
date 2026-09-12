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

import net.minecraft.gametest.framework.TestData
import net.minecraft.gametest.framework.TestEnvironmentDefinition
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Rotation
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.impl.benchmark.VmBenchmarkGameTest

@EventBusSubscriber(modid = MOD_ID)
object ComputerBlockGameTest {
    @JvmStatic
    @SubscribeEvent
    fun registerTests(event: RegisterGameTestsEvent) {
        val environment =
            event.registerEnvironment(
                Identifier.fromNamespaceAndPath(MOD_ID, "empty"),
                TestEnvironmentDefinition.AllOf(),
            )
        val testData =
            TestData(
                environment,
                Identifier.withDefaultNamespace("bastion/mobs/empty"),
                // GameTestServer runs ticks without pacing; allow real filesystem/compiler workers to finish.
                10000,
                0,
                true,
                Rotation.NONE,
                false,
                1,
                1,
                false,
                0,
            )
        event.registerTest(
            Identifier.fromNamespaceAndPath(MOD_ID, "computer_lifecycle"),
            ComputerLifecycleGameTest(testData),
        )
        event.registerTest(
            Identifier.fromNamespaceAndPath(MOD_ID, "computer_redstone"),
            ComputerRedstoneGameTest(testData),
        )
        event.registerTest(
            Identifier.fromNamespaceAndPath(MOD_ID, "computer_sound"),
            ComputerSoundGameTest(testData),
        )
        event.registerTest(
            Identifier.fromNamespaceAndPath(MOD_ID, "vm_actor_service"),
            VmActorServiceGameTest(testData),
        )
        event.registerTest(
            Identifier.fromNamespaceAndPath(MOD_ID, "vm_benchmark"),
            VmBenchmarkGameTest(testData),
        )
    }
}
