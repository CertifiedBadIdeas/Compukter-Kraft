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

import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent
import ru.lazyhat.compukters.core.MOD_ID
import java.util.concurrent.CompletableFuture

@EventBusSubscriber(modid = MOD_ID, value = [Dist.CLIENT])
internal object IdeTargetClientNetwork {
    private var current: IdeTargetRequestBroker? = null
    private var currentTerminal: IdeTargetTerminalClient? = null

    fun openPort(): NetworkIdeTargetPort {
        disconnect()
        val broker =
            IdeTargetRequestBroker(send = { envelope ->
                ClientPacketDistributor.sendToServer(IdeTargetRequestPayload(envelope.requestId, envelope.request))
            })
        current = broker
        return NetworkIdeTargetPort(OwnedChannel(broker))
    }

    fun openTerminal(): IdeTargetTerminalClient {
        currentTerminal?.close()
        return IdeTargetTerminalClient(::sendTerminal).also { currentTerminal = it }
    }

    @JvmStatic
    @SubscribeEvent
    fun register(event: RegisterClientPayloadHandlersEvent) {
        event.register(IdeTargetReplyPayload.TYPE) { payload, _ ->
            current?.receive(IdeTargetReplyEnvelope(payload.requestId, payload.reply))
        }
        event.register(IdeTerminalOpenedPayload.TYPE) { payload, _ ->
            currentTerminal?.accept(IdeTerminalOpened(payload.generation, payload.token, payload.machineId, payload.state))
        }
        event.register(IdeTerminalFullPayload.TYPE) { payload, _ ->
            currentTerminal?.accept(IdeTerminalFull(payload.token, payload.machineId, payload.state))
        }
        event.register(IdeTerminalDeltaPayload.TYPE) { payload, _ ->
            currentTerminal?.accept(IdeTerminalDelta(payload.token, payload.machineId, payload.delta))
        }
        event.register(IdeTerminalFailedPayload.TYPE) { payload, _ ->
            currentTerminal?.accept(IdeTerminalFailed(payload.generation, payload.token, payload.kind, payload.detail, payload.retryable))
        }
    }

    @JvmStatic
    @SubscribeEvent
    fun onLoggingOut(
        @Suppress("UNUSED_PARAMETER") event: ClientPlayerNetworkEvent.LoggingOut,
    ) {
        disconnect()
    }

    private fun disconnect() {
        current?.disconnect()
        current = null
        currentTerminal?.connectionLost()
        currentTerminal = null
    }

    fun release(terminal: IdeTargetTerminalClient) {
        terminal.close()
        if (currentTerminal === terminal) currentTerminal = null
    }

    private fun sendTerminal(command: IdeTerminalCommand) {
        val payload =
            when (command) {
                is IdeTerminalOpen -> {
                    IdeTerminalOpenPayload(command.generation, command.target)
                }

                is IdeTerminalResync -> {
                    IdeTerminalResyncPayload(command.token, command.machineId, command.revision)
                }

                is IdeTerminalKeyInput -> {
                    IdeTerminalKeyPayload(command.token, command.machineId, command.key, command.action, command.modifiers)
                }

                is IdeTerminalTextInput -> {
                    IdeTerminalTextPayload(command.token, command.machineId, command.text)
                }

                is IdeTerminalClose -> {
                    IdeTerminalClosePayload(command.token)
                }
            }
        ClientPacketDistributor.sendToServer(payload)
    }

    private class OwnedChannel(
        private val broker: IdeTargetRequestBroker,
    ) : IdeTargetRequestChannel {
        override fun request(request: IdeTargetRequest): CompletableFuture<IdeTargetReply> = broker.request(request)

        override fun disconnect() {
            broker.disconnect()
            if (current === broker) current = null
        }
    }
}
