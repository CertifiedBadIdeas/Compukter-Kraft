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

import net.minecraft.gametest.framework.GameTestHelper
import ru.lazyhat.compukters.core.device.computer.ActorProgramComputer
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.impl.fs.WorldFileSystemStoreRegistry
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

internal object VmActorServiceGameTestScenario {
    fun run(helper: GameTestHelper) {
        val server = helper.level.server
        val service = NeoForgeVmActorServices.service(server)
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(603, 1), 1)
        val rom = ComputerGameTestFixtures.processTestRom()
        val root = Files.createTempDirectory("compukters-actor-gametest-")
        val stores =
            WorldFileSystemStoreRegistry(
                opener = WorldFileSystemStore::open,
                flusher = WorldFileSystemStore::flush,
                tombstoner = WorldFileSystemStore::tombstone,
                recoverer = WorldFileSystemStore::recover,
                closer = WorldFileSystemStore::close,
            )
        val store = stores.store(root)
        val lease = requireNotNull(service.attachBootable(endpoint, store, rom))
        val computer = ActorProgramComputer(service, lease, { RedstoneCommitResult.Committed })
        stores.lifecycle(root).attach(endpoint.computerId, { computer.fileSystemGeneration }, computer::closeAsync)
        val boot = computer.turnOn()
        var deliveredOnServer = false
        val observed = boot.thenAccept { deliveredOnServer = server.isSameThread }
        var terminal: CompletableFuture<ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply>? = null
        var closed: CompletableFuture<Void>? = null
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
                closed = stores.stop(root)
            }.thenWaitUntil {
                helper.assertTrue(closed!!.isDone, "actor did not close on its worker")
            }.thenExecute {
                closed!!.getNow(null)
                // Reopening proves the real native store was closed after the worker released its machine.
                val reopened = stores.store(root)
                helper.assertTrue(
                    reopened.health() == ru.lazyhat.compukters.lang.runtime.fs.FileSystemStoreHealth.ACTIVE,
                    "filesystem store did not reopen after the actor close barrier",
                )
                closed = stores.stop(root)
            }.thenWaitUntil {
                helper.assertTrue(closed!!.isDone, "reopened store did not close on the persistence worker")
            }.thenExecute {
                closed!!.getNow(null)
            }.thenSucceed()
    }

}
