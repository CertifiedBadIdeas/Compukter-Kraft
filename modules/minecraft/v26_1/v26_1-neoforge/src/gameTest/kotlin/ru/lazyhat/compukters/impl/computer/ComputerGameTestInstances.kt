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

import com.mojang.serialization.MapCodec
import net.minecraft.core.Holder
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInstance
import net.minecraft.gametest.framework.TestData
import net.minecraft.gametest.framework.TestEnvironmentDefinition
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

internal class ComputerRedstoneGameTest(
    testData: TestData<Holder<TestEnvironmentDefinition<*>>>,
) : GameTestInstance(testData) {
    override fun run(helper: GameTestHelper) = ComputerRedstoneGameTestScenario.run(helper)

    override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

    override fun typeDescription(): MutableComponent = Component.literal("Compukters redstone GPIO")
}

internal class ComputerSoundGameTest(
    testData: TestData<Holder<TestEnvironmentDefinition<*>>>,
) : GameTestInstance(testData) {
    override fun run(helper: GameTestHelper) = ComputerSoundGameTestScenario.run(helper)

    override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

    override fun typeDescription(): MutableComponent = Component.literal("Compukters one-shot sound")
}

internal class VmActorServiceGameTest(
    testData: TestData<Holder<TestEnvironmentDefinition<*>>>,
) : GameTestInstance(testData) {
    override fun run(helper: GameTestHelper) = VmActorServiceGameTestScenario.run(helper)

    override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

    override fun typeDescription(): MutableComponent = Component.literal("Compukters server VM actor service")
}
