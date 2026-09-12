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
 */

package ru.lazyhat.compukters.impl.ide.target

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.impl.network.ServerOperationScope
import java.util.WeakHashMap

@EventBusSubscriber(modid = MOD_ID)
internal object IdeTargetNetwork {
    private val servers = WeakHashMap<MinecraftServer, ServerTransport>()

    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToServer(IdeTargetRequestPayload.TYPE, IdeTargetRequestPayload.STREAM_CODEC, ::handle)
        registrar.playToClient(IdeTargetReplyPayload.TYPE, IdeTargetReplyPayload.STREAM_CODEC)
        registrar.playToServer(IdeTerminalOpenPayload.TYPE, IdeTerminalOpenPayload.STREAM_CODEC, ::handleTerminalOpen)
        registrar.playToServer(IdeTerminalResyncPayload.TYPE, IdeTerminalResyncPayload.STREAM_CODEC, ::handleTerminalResync)
        registrar.playToServer(IdeTerminalKeyPayload.TYPE, IdeTerminalKeyPayload.STREAM_CODEC, ::handleTerminalKey)
        registrar.playToServer(IdeTerminalTextPayload.TYPE, IdeTerminalTextPayload.STREAM_CODEC, ::handleTerminalText)
        registrar.playToServer(IdeTerminalClosePayload.TYPE, IdeTerminalClosePayload.STREAM_CODEC, ::handleTerminalClose)
        registrar.playToClient(IdeTerminalOpenedPayload.TYPE, IdeTerminalOpenedPayload.STREAM_CODEC)
        registrar.playToClient(IdeTerminalFullPayload.TYPE, IdeTerminalFullPayload.STREAM_CODEC)
        registrar.playToClient(IdeTerminalDeltaPayload.TYPE, IdeTerminalDeltaPayload.STREAM_CODEC)
        registrar.playToClient(IdeTerminalFailedPayload.TYPE, IdeTerminalFailedPayload.STREAM_CODEC)
    }

    private fun handleTerminalOpen(
        payload: IdeTerminalOpenPayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val transport = transport(player)
        val pending =
            transport.operations.submit(player.uuid) {
                transport.terminals.open(
                    player.uuid,
                    payload.generation,
                    payload.target,
                    player
                        .level()
                        .server.tickCount
                        .toLong(),
                )
            }
        if (pending == null) {
            context.reply(
                IdeTerminalFailedPayload(
                    payload.generation,
                    null,
                    IdeTargetFailureKind.Other,
                    "Server request capacity is exhausted",
                    true,
                ),
            )
            return
        }
        pending.whenComplete { reply, _ ->
            if (!transport.operations.closed) {
                context.reply(
                    (
                        reply
                            ?: IdeTerminalFailed(
                                payload.generation,
                                null,
                                IdeTargetFailureKind.TargetLost,
                                "Target terminal is unavailable",
                                true,
                            )
                    ).toPayload(),
                )
            }
        }
    }

    private fun handleTerminalResync(
        payload: IdeTerminalResyncPayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val transport = transport(player)
        transport.operations
            .submit(player.uuid) {
                transport.terminals.resync(
                    player.uuid,
                    IdeTerminalResync(payload.token, payload.machineId, payload.revision),
                    player
                        .level()
                        .server.tickCount
                        .toLong(),
                )
            }?.thenAccept { reply ->
                if (!transport.operations.closed) reply?.toPayload()?.let(context::reply)
            }
    }

    private fun handleTerminalKey(
        payload: IdeTerminalKeyPayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val transport = transport(player)
        transport.operations.submit(player.uuid) {
            transport.terminals.key(
                player.uuid,
                IdeTerminalKeyInput(payload.token, payload.machineId, payload.key, payload.action, payload.modifiers),
                player
                    .level()
                    .server.tickCount
                    .toLong(),
            )
        }
    }

    private fun handleTerminalText(
        payload: IdeTerminalTextPayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val transport = transport(player)
        transport.operations.submit(player.uuid) {
            transport.terminals.text(
                player.uuid,
                IdeTerminalTextInput(payload.token, payload.machineId, payload.text),
                player
                    .level()
                    .server.tickCount
                    .toLong(),
            )
        }
    }

    private fun handleTerminalClose(
        payload: IdeTerminalClosePayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        transport(player).terminals.close(player.uuid, IdeTerminalClose(payload.token))
    }

    private fun transport(player: ServerPlayer): ServerTransport {
        val server = player.level().server
        return servers.getOrPut(server) { ServerTransport(server) }
    }

    private fun handle(
        payload: IdeTargetRequestPayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        val server = player.level().server
        val transport = servers.getOrPut(server) { ServerTransport(server) }
        val pending =
            transport.operations.submit(player.uuid) {
                transport.processor.handle(player.uuid, payload.request, server.tickCount.toLong())
            }
        if (pending == null) {
            context.reply(IdeTargetReplyPayload(payload.requestId, requestFailed("Server request capacity is exhausted", true)))
            return
        }
        pending.whenComplete { reply, failure ->
            if (!transport.operations.closed) {
                context.reply(IdeTargetReplyPayload(payload.requestId, reply ?: requestFailed("Target request failed", false)))
            }
        }
    }

    @JvmStatic
    @SubscribeEvent
    fun afterServerTick(event: ServerTickEvent.Post) {
        servers[event.server]?.expire(event.server.tickCount.toLong())
    }

    @JvmStatic
    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        servers.remove(event.server)?.close()
    }

    private class ServerTransport(
        server: MinecraftServer,
    ) : AutoCloseable {
        private val leases = IdeTargetLeaseService(NeoForgeIdeTargetResolver(server))
        private val deployments = IdeTargetDeploymentService(leases)
        val operations = ServerOperationScope()
        val terminals = IdeTargetTerminalSessionService(leases)
        val processor = IdeTargetRequestProcessor(leases, deployments)
        private val server = server

        fun expire(tick: Long) {
            leases.expire(tick)
            deployments.expire(tick)
            terminals.publish(tick).forEach { delivery ->
                server.playerList.getPlayer(delivery.player)?.let { player ->
                    PacketDistributor.sendToPlayer(player, delivery.event.toPayload())
                }
            }
        }

        override fun close() {
            operations.close()
            terminals.close()
            deployments.close()
            leases.close()
        }
    }

    private fun requestFailed(
        detail: String,
        retryable: Boolean,
    ) = IdeTargetReply.Failed(IdeTargetFailure(IdeTargetFailureKind.Other, detail), retryable)

    private fun IdeTerminalEvent.toPayload(): CustomPacketPayload =
        when (this) {
            is IdeTerminalOpened -> IdeTerminalOpenedPayload(generation, token, machineId, state)
            is IdeTerminalFull -> IdeTerminalFullPayload(token, machineId, state)
            is IdeTerminalDelta -> IdeTerminalDeltaPayload(token, machineId, delta)
            is IdeTerminalFailed -> IdeTerminalFailedPayload(generation, token, kind, detail, retryable)
        }
}
