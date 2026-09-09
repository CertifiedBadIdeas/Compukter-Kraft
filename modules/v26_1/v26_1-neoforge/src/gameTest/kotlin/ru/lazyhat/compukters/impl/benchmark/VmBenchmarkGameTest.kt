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

package ru.lazyhat.compukters.impl.benchmark

import com.mojang.serialization.MapCodec
import net.minecraft.core.Holder
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInstance
import net.minecraft.gametest.framework.TestData
import net.minecraft.gametest.framework.TestEnvironmentDefinition
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

internal class VmBenchmarkGameTest(
    testData: TestData<Holder<TestEnvironmentDefinition<*>>>,
) : GameTestInstance(testData) {
    override fun run(helper: GameTestHelper) {
        val server = helper.level.server
        helper
            .startSequence()
            .thenExecute {
                server.commands.performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "compukters vmbench start 2 2",
                )
            }.thenWaitUntil {
                val snapshot = VmBenchmarkCommands.snapshot(server)
                helper.assertTrue(snapshot != null, "headless benchmark command did not create a fleet")
                helper.assertTrue(
                    snapshot!!.status == HeadlessVmBenchmarkStatus.COMPLETED,
                    "headless benchmark is still ${snapshot.status}: " +
                        "active=${snapshot.activeActors}, completed=${snapshot.completedActors}, failed=${snapshot.failedActors}",
                )
            }.thenExecute {
                val snapshot = requireNotNull(VmBenchmarkCommands.snapshot(server))
                helper.assertTrue(snapshot.admittedActors == 2, "headless benchmark did not admit both actors")
                helper.assertTrue(snapshot.completedActors == 2, "headless benchmark actors did not halt")
                helper.assertTrue(snapshot.failedActors == 0, "headless benchmark actor failed")
            }.thenSucceed()
    }

    override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

    override fun typeDescription(): MutableComponent = Component.literal("Compukters headless VM benchmark")
}
