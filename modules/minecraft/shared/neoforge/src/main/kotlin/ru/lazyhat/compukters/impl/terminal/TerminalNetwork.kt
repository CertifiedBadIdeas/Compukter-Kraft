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
import ru.lazyhat.compukters.core.device.runtime.program.ProgramResourceSnapshot
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
        val registrar = event.registrar("3")
        registrar.playToClient(TerminalFullPayload.TYPE, TerminalFullPayload.STREAM_CODEC, TerminalClientNetwork::handleFull)
        registrar.playToClient(TerminalDeltaPayload.TYPE, TerminalDeltaPayload.STREAM_CODEC, TerminalClientNetwork::handleDelta)
        registrar.playToClient(TerminalResourcePayload.TYPE, TerminalResourcePayload.STREAM_CODEC, TerminalClientNetwork::handleResource)
        registrar.playToServer(TerminalResyncPayload.TYPE, TerminalResyncPayload.STREAM_CODEC, ::handleResync)
        registrar.playToServer(TerminalClosePayload.TYPE, TerminalClosePayload.STREAM_CODEC, ::handleClose)
        registrar.playToServer(TerminalKeyPayload.TYPE, TerminalKeyPayload.STREAM_CODEC, ::handleKey)
        registrar.playToServer(TerminalTextPayload.TYPE, TerminalTextPayload.STREAM_CODEC, ::handleText)
    }

    fun open(
        player: ServerPlayer,
        entity: ComputerBlockEntity,
    ) = openObservation(player, entity, openScreen = true)

    private fun openObservation(
        player: ServerPlayer,
        entity: ComputerBlockEntity,
        openScreen: Boolean,
    ) {
        val marker = Any()
        pendingOpens[player.uuid] = marker
        operations.submit(player.uuid) { entity.prepareTerminalAsync().awaitServerResult() }?.whenComplete { state, failure ->
            if (!pendingOpens.remove(player.uuid, marker) || failure != null || state == null) return@whenComplete
            val machineId = entity.terminalMachineId ?: return@whenComplete
            val level = player.level() as? ServerLevel ?: return@whenComplete
            if (!player.isValidViewer(level, entity.blockPos)) return@whenComplete
            viewers[player.uuid] = Viewer(level.dimension(), entity.blockPos, machineId, state.revision)
            PacketDistributor.sendToPlayer(player, TerminalFullPayload(entity.blockPos, machineId, state, openScreen))
        } ?: pendingOpens.remove(player.uuid, marker)
    }

    internal fun isViewing(
        player: ServerPlayer,
        position: BlockPos,
        machineId: Long,
    ): Boolean {
        val viewer = viewers[player.uuid] ?: return false
        val level = player.level() as? ServerLevel ?: return false
        return viewer.dimension == level.dimension() &&
            viewer.position == position &&
            viewer.machineId == machineId &&
            player.isValidViewer(level, position)
    }

    @JvmStatic
    @SubscribeEvent
    fun afterServerTick(event: ServerTickEvent.Post) {
        val worldTick = event.server.tickCount.toLong()
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
            val includeResources = viewer.resourceSchedule.isDue(worldTick)
            val pending =
                polls.submit(playerId) {
                    val update =
                        if (machineId == viewer.machineId) {
                            entity.terminalChangesSinceAsync(viewer.revision).awaitServerResult()
                        } else {
                            entity.terminalFullStateAsync().awaitServerResult()?.let(TerminalUpdate::Full)
                        }
                    val resources =
                        if (includeResources) {
                            try {
                                entity.resourceSnapshotAsync().awaitServerResult()
                            } catch (_: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                    PollResult(update, resources)
                } ?: return@forEach
            if (includeResources) viewer.resourceSchedule.submitted(worldTick)
            // Round-robin when the shared limit is full.
            if (viewers[playerId] === viewer) {
                viewers.remove(playerId)
                viewers[playerId] = viewer
            }
            pending.whenComplete { result, failure ->
                if (failure != null || viewers[playerId] !== viewer || !player.isValidViewer(level, viewer.position)) return@whenComplete
                val currentMachine = entity.terminalMachineId ?: return@whenComplete
                if (currentMachine != machineId) return@whenComplete
                publish(player, entity.blockPos, viewer, currentMachine, result.update)
                if (includeResources) {
                    PacketDistributor.sendToPlayer(
                        player,
                        TerminalResourcePayload(entity.blockPos, currentMachine, viewer.resourceWindow.accept(result.resources)),
                    )
                }
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
                if (viewer.machineId != machineId) viewer.resourceWindow.reset()
                viewer.machineId = machineId
                viewer.revision = update.state.revision
                PacketDistributor.sendToPlayer(player, TerminalFullPayload(position, machineId, update.state, false))
            }

            is TerminalUpdate.Unchanged, null -> {
                return
            }
        }
    }

    private fun handleResync(
        payload: TerminalResyncPayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val entity = player.computerAt(payload.position) ?: return
        val viewer = viewers[player.uuid]
        when (terminalObservationAction(viewer?.position, payload.position)) {
            TerminalObservationAction.REOPEN -> {
                openObservation(player, entity, openScreen = false)
                return
            }

            TerminalObservationAction.REJECT -> {
                return
            }

            TerminalObservationAction.RESYNC -> {
                Unit
            }
        }
        val activeViewer = checkNotNull(viewer)
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
                if (failure != null || result == null || viewers[player.uuid] !== activeViewer) return@whenComplete
                publish(player, entity.blockPos, activeViewer, result.first, result.second)
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
                (player.level() as? ServerLevel)?.server?.tickCount?.toLong() ?: return,
            )
        ) {
            return
        }
        val entity = player.computerAt(position) ?: return
        if (entity.terminalMachineId != machineId) return
        operations.submit(player.uuid) { input(entity) }
    }

    private fun ServerPlayer.computerAt(position: BlockPos): ComputerBlockEntity? {
        val level = level() as? ServerLevel ?: return null
        if (!isValidViewer(level, position)) return null
        return level.getBlockEntity(position) as? ComputerBlockEntity
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
    ) {
        val resourceSchedule = TerminalResourcePollSchedule()
        val resourceWindow = TerminalResourceGaugeWindow()
    }

    private data class PollResult(
        val update: TerminalUpdate?,
        val resources: ProgramResourceSnapshot?,
    )

    private const val MAXIMUM_DISTANCE_SQUARED = 64.0
}

internal enum class TerminalObservationAction {
    REOPEN,
    RESYNC,
    REJECT,
}

internal fun terminalObservationAction(
    viewerPosition: BlockPos?,
    requestedPosition: BlockPos,
): TerminalObservationAction =
    when (viewerPosition) {
        null -> TerminalObservationAction.REOPEN
        requestedPosition -> TerminalObservationAction.RESYNC
        else -> TerminalObservationAction.REJECT
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
