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
import ru.lazyhat.compukters.core.device.computer.ActorProgramComputer
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

internal class VmActorServiceGameTest(
    testData: TestData<Holder<TestEnvironmentDefinition<*>>>,
) : GameTestInstance(testData) {
    override fun run(helper: GameTestHelper) {
        val server = helper.level.server
        val service = NeoForgeVmActorServices.service(server)
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(603, 1), 1)
        val rom = ComputerBlockGameTest.processTestRom()
        val store = WorldFileSystemStore.open(Files.createTempDirectory("compukters-actor-gametest-"))
        val lease = requireNotNull(service.attachBootable(endpoint, store, rom))
        val computer = ActorProgramComputer(service, lease, { RedstoneCommitResult.Committed })
        val boot = computer.turnOn()
        var deliveredOnServer = false
        val observed = boot.thenAccept { deliveredOnServer = server.isSameThread }
        var terminal: CompletableFuture<ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply>? = null
        var closed: CompletableFuture<Long?>? = null
        helper
            .startSequence()
            .thenWaitUntil {
                helper.assertTrue(observed.isDone, "server tick did not deliver the actor reply")
            }.thenExecute {
                helper.assertTrue(deliveredOnServer, "actor callback ran outside the server thread")
            }.thenWaitUntil {
                computer.serverTick(server.tickCount.toLong())
                helper.assertTrue(computer.state == ProgramRuntimeState.WaitingForInput, "actor shell did not become ready")
            }.thenExecute {
                terminal = computer.request(ProgramRuntimeActorCommand::TerminalFullState)
            }.thenWaitUntil {
                helper.assertTrue(terminal!!.isDone, "terminal snapshot was not delivered")
            }.thenExecute {
                val snapshot = terminal!!.getNow(null).value as ProgramRuntimeActorValue.TerminalStateValue
                helper.assertTrue(
                    snapshot.state != null,
                    "actor boot did not publish a native terminal",
                )
            }.thenExecute {
                closed = computer.closeAsync()
            }.thenWaitUntil {
                helper.assertTrue(closed!!.isDone, "actor did not close on its worker")
            }.thenExecute {
                val generation = closed!!.getNow(null)
                helper.assertTrue(generation != null, "actor close did not report its final filesystem generation")
                store.flush(endpoint.computerId, requireNotNull(generation))
                store.close()
            }.thenSucceed()
    }

    override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

    override fun typeDescription(): MutableComponent = Component.literal("Compukters server VM actor service")
}
