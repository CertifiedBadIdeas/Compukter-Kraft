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
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId

internal class VmActorServiceGameTest(
    testData: TestData<Holder<TestEnvironmentDefinition<*>>>,
) : GameTestInstance(testData) {
    override fun run(helper: GameTestHelper) {
        val server = helper.level.server
        val service = NeoForgeVmActorServices.service(server)
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(603, 1), 1)
        helper.assertTrue(service.registerStandalone(endpoint), "actor endpoint was not registered")
        val reply = service.request(endpoint, ProgramRuntimeActorCommand::TerminalFullState)
        var deliveredOnServer = false
        val observed = reply.thenAccept { deliveredOnServer = server.isSameThread }
        helper
            .startSequence()
            .thenWaitUntil {
                helper.assertTrue(observed.isDone, "server tick did not deliver the actor reply")
            }.thenExecute {
                helper.assertTrue(deliveredOnServer, "actor callback ran outside the server thread")
                helper.assertTrue(
                    reply.getNow(null).value is ProgramRuntimeActorValue.TerminalStateValue,
                    "actor returned an unexpected reply",
                )
            }.thenExecute {
                service.unregister(endpoint)
            }.thenWaitUntil {
                helper.assertTrue(service.metrics().registeredActors == 0, "actor did not close on its worker")
            }.thenSucceed()
    }

    override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

    override fun typeDescription(): MutableComponent = Component.literal("Compukters server VM actor service")
}
