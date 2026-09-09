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

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.impl.network.ServerOperationScope
import ru.lazyhat.compukters.impl.network.awaitServerResult
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import java.util.UUID
import java.util.WeakHashMap

@EventBusSubscriber(modid = MOD_ID)
object TerminalNetwork {
    private val viewers = linkedMapOf<UUID, Viewer>()
    private val pendingOpens = mutableMapOf<UUID, Any>()
    private val operations = ServerOperationScope(maximumPending = 256, maximumPendingPerPlayer = 4)
    private val polls = ServerOperationScope(maximumPending = 256, maximumPendingPerPlayer = 1)

    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("2")
        registrar.playToClient(TerminalFullPayload.TYPE, TerminalFullPayload.STREAM_CODEC)
        registrar.playToClient(TerminalDeltaPayload.TYPE, TerminalDeltaPayload.STREAM_CODEC)
        registrar.playToServer(TerminalResyncPayload.TYPE, TerminalResyncPayload.STREAM_CODEC, ::handleResync)
        registrar.playToServer(TerminalClosePayload.TYPE, TerminalClosePayload.STREAM_CODEC, ::handleClose)
        registrar.playToServer(TerminalKeyPayload.TYPE, TerminalKeyPayload.STREAM_CODEC, ::handleKey)
        registrar.playToServer(TerminalTextPayload.TYPE, TerminalTextPayload.STREAM_CODEC, ::handleText)
    }

    fun open(
        player: ServerPlayer,
        entity: ComputerBlockEntity,
    ) {
        val marker = Any()
        pendingOpens[player.uuid] = marker
        operations.submit(player.uuid) { entity.prepareTerminalAsync().awaitServerResult() }?.whenComplete { state, failure ->
            if (failure != null || state == null || pendingOpens.remove(player.uuid, marker).not()) return@whenComplete
            val machineId = entity.terminalMachineId ?: return@whenComplete
            if (!player.isValidViewer(player.level(), entity.blockPos)) return@whenComplete
            viewers[player.uuid] = Viewer(player.level().dimension(), entity.blockPos, machineId, state.revision)
            PacketDistributor.sendToPlayer(player, TerminalFullPayload(entity.blockPos, machineId, state, openScreen = true))
        } ?: pendingOpens.remove(player.uuid, marker)
    }

    internal fun isViewing(
        player: ServerPlayer,
        position: BlockPos,
        machineId: Long,
    ): Boolean {
        val viewer = viewers[player.uuid] ?: return false
        return viewer.dimension == player.level().dimension() &&
            viewer.position == position &&
            viewer.machineId == machineId &&
            player.isValidViewer(player.level(), position)
    }

    @JvmStatic
    @SubscribeEvent
    fun afterServerTick(event: ServerTickEvent.Post) {
        viewers.keys.toList().forEach { playerId ->
            val viewer = viewers[playerId] ?: return@forEach
            val player = event.server.playerList.getPlayer(playerId)
            val level = event.server.getLevel(viewer.dimension)
            if (player == null || level == null || !player.isValidViewer(level, viewer.position)) {
                viewers.remove(playerId)
                return@forEach
            }
            val entity = level.getBlockEntity(viewer.position) as? ComputerBlockEntity ?: return@forEach
            val machineId = entity.terminalMachineId ?: return@forEach
            val pending =
                polls.submit(playerId) {
                    if (machineId == viewer.machineId) {
                        entity.terminalChangesSinceAsync(viewer.revision).awaitServerResult()
                    } else {
                        entity.terminalFullStateAsync().awaitServerResult()?.let(TerminalUpdate::Full)
                    }
                } ?: return@forEach
            // Round-robin when the shared limit is full.
            if (viewers[playerId] === viewer) {
                viewers.remove(playerId)
                viewers[playerId] = viewer
            }
            pending.whenComplete { update, failure ->
                if (failure != null || viewers[playerId] !== viewer || !player.isValidViewer(level, viewer.position)) return@whenComplete
                val currentMachine = entity.terminalMachineId ?: return@whenComplete
                if (currentMachine != machineId) return@whenComplete
                publish(player, entity.blockPos, viewer, currentMachine, update)
            }
        }
    }

    @JvmStatic
    @SubscribeEvent
    fun onServerStopping(
        @Suppress("UNUSED_PARAMETER") event: ServerStoppingEvent,
    ) {
        viewers.clear()
        pendingOpens.clear()
    }

    private fun publish(
        player: ServerPlayer,
        position: BlockPos,
        viewer: Viewer,
        machineId: Long,
        update: TerminalUpdate?,
    ) {
        when (update) {
            is TerminalUpdate.Delta -> {
                if (update.baseRevision != viewer.revision || update.targetRevision <= viewer.revision) return
                viewer.revision = update.targetRevision
                PacketDistributor.sendToPlayer(player, TerminalDeltaPayload(position, machineId, update))
            }

            is TerminalUpdate.Full -> {
                if (update.state.revision < viewer.revision && machineId == viewer.machineId) return
                viewer.machineId = machineId
                viewer.revision = update.state.revision
                PacketDistributor.sendToPlayer(player, TerminalFullPayload(position, machineId, update.state, false))
            }

            is TerminalUpdate.Unchanged, null -> {
                Unit
            }
        }
    }

    private fun handleResync(
        payload: TerminalResyncPayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val entity = player.computerAt(payload.position) ?: return
        val viewer = viewers[player.uuid] ?: return
        if (viewer.position != payload.position) return
        operations
            .submit(player.uuid) {
                val machineId = entity.terminalMachineId ?: return@submit null
                val update =
                    if (machineId == payload.machineId) {
                        entity.terminalChangesSinceAsync(payload.revision).awaitServerResult()
                    } else {
                        null
                    }
                machineId to (update ?: entity.terminalFullStateAsync().awaitServerResult()?.let(TerminalUpdate::Full))
            }?.whenComplete { result, failure ->
                if (failure != null || result == null || viewers[player.uuid] !== viewer) return@whenComplete
                publish(player, entity.blockPos, viewer, result.first, result.second)
            }
    }

    private fun handleClose(
        payload: TerminalClosePayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        pendingOpens.remove(player.uuid)
        val viewer = viewers[player.uuid] ?: return
        if (viewer.position == payload.position && viewer.machineId == payload.machineId) viewers.remove(player.uuid)
    }

    private fun handleKey(
        payload: TerminalKeyPayload,
        context: IPayloadContext,
    ) {
        withInputTarget(payload.position, payload.machineId, context) {
            it.submitTerminalKeyAsync(payload.key, payload.action, payload.modifiers).awaitServerResult()
        }
    }

    private fun handleText(
        payload: TerminalTextPayload,
        context: IPayloadContext,
    ) {
        withInputTarget(payload.position, payload.machineId, context) {
            it.submitTerminalTextAsync(payload.text).awaitServerResult()
        }
    }

    private fun withInputTarget(
        position: BlockPos,
        machineId: Long,
        context: IPayloadContext,
        input: suspend (ComputerBlockEntity) -> Unit,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val viewer = viewers[player.uuid] ?: return
        if (viewer.position != position || viewer.machineId != machineId) return
        if (!TerminalInputAdmission.accept(
                player.uuid,
                player
                    .level()
                    .server.tickCount
                    .toLong(),
            )
        ) {
            return
        }
        val entity = player.computerAt(position) ?: return
        if (entity.terminalMachineId != machineId) return
        operations.submit(player.uuid) { input(entity) }
    }

    private fun ServerPlayer.computerAt(position: BlockPos): ComputerBlockEntity? {
        if (!isValidViewer(level(), position)) return null
        return level().getBlockEntity(position) as? ComputerBlockEntity
    }

    private fun ServerPlayer.isValidViewer(
        level: ServerLevel,
        position: BlockPos,
    ): Boolean =
        this.level() === level &&
            distanceToSqr(position.x + 0.5, position.y + 0.5, position.z + 0.5) <= MAXIMUM_DISTANCE_SQUARED &&
            level.getBlockEntity(position) is ComputerBlockEntity

    private class Viewer(
        val dimension: ResourceKey<Level>,
        val position: BlockPos,
        var machineId: Long,
        var revision: Long,
    )

    private const val MAXIMUM_DISTANCE_SQUARED = 64.0
}

internal object TerminalInputAdmission {
    private val limiter = TerminalInputRateLimiter(MAXIMUM_INPUT_EVENTS_PER_TICK)

    fun accept(
        player: UUID,
        tick: Long,
    ): Boolean = limiter.accept(player, tick)

    private const val MAXIMUM_INPUT_EVENTS_PER_TICK = 64
}

internal class TerminalInputRateLimiter(
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
