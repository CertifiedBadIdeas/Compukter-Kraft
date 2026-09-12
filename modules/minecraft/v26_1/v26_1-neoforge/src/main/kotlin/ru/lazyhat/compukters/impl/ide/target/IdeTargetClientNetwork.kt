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

    fun openPort(): NeoForgeIdeTargetPort {
        disconnect()
        val broker = IdeTargetRequestBroker(ClientPacketDistributor::sendToServer)
        current = broker
        return NeoForgeIdeTargetPort(OwnedChannel(broker))
    }

    fun openTerminal(): IdeTargetTerminalClient {
        currentTerminal?.close()
        return IdeTargetTerminalClient(ClientPacketDistributor::sendToServer).also { currentTerminal = it }
    }

    @JvmStatic
    @SubscribeEvent
    fun register(event: RegisterClientPayloadHandlersEvent) {
        event.register(IdeTargetReplyPayload.TYPE) { payload, _ -> current?.receive(payload) }
        event.register(IdeTerminalOpenedPayload.TYPE) { payload, _ -> currentTerminal?.accept(payload) }
        event.register(IdeTerminalFullPayload.TYPE) { payload, _ -> currentTerminal?.accept(payload) }
        event.register(IdeTerminalDeltaPayload.TYPE) { payload, _ -> currentTerminal?.accept(payload) }
        event.register(IdeTerminalFailedPayload.TYPE) { payload, _ -> currentTerminal?.accept(payload) }
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
