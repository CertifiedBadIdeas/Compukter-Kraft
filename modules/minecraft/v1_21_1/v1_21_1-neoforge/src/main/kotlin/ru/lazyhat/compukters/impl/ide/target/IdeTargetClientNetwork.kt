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

package ru.lazyhat.compukters.impl.ide.target

import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.handling.IPayloadContext
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.impl.ide.IdeClientTargetTransport
import java.util.concurrent.CompletableFuture

@EventBusSubscriber(modid = MOD_ID, value = [Dist.CLIENT])
internal object IdeTargetClientNetwork : IdeClientTargetTransport {
    private var current: IdeTargetRequestBroker? = null
    private var currentTerminal: IdeTargetTerminalClient? = null

    override fun openPort(): NetworkIdeTargetPort {
        disconnect()
        val broker =
            IdeTargetRequestBroker(send = { envelope ->
                PacketDistributor.sendToServer(IdeTargetRequestPayload(envelope.requestId, envelope.request))
            })
        current = broker
        return NetworkIdeTargetPort(OwnedChannel(broker))
    }

    override fun openTerminal(): IdeTargetTerminalClient {
        currentTerminal?.close()
        return IdeTargetTerminalClient(::sendTerminal).also { currentTerminal = it }
    }

    fun handleReply(
        payload: IdeTargetReplyPayload,
        @Suppress("UNUSED_PARAMETER") context: IPayloadContext,
    ) {
        current?.receive(IdeTargetReplyEnvelope(payload.requestId, payload.reply))
    }

    fun handleOpened(
        payload: IdeTerminalOpenedPayload,
        @Suppress("UNUSED_PARAMETER") context: IPayloadContext,
    ) {
        currentTerminal?.accept(IdeTerminalOpened(payload.generation, payload.token, payload.machineId, payload.state))
    }

    fun handleFull(
        payload: IdeTerminalFullPayload,
        @Suppress("UNUSED_PARAMETER") context: IPayloadContext,
    ) {
        currentTerminal?.accept(IdeTerminalFull(payload.token, payload.machineId, payload.state))
    }

    fun handleDelta(
        payload: IdeTerminalDeltaPayload,
        @Suppress("UNUSED_PARAMETER") context: IPayloadContext,
    ) {
        currentTerminal?.accept(IdeTerminalDelta(payload.token, payload.machineId, payload.delta))
    }

    fun handleFailed(
        payload: IdeTerminalFailedPayload,
        @Suppress("UNUSED_PARAMETER") context: IPayloadContext,
    ) {
        currentTerminal?.accept(IdeTerminalFailed(payload.generation, payload.token, payload.kind, payload.detail, payload.retryable))
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

    override fun release(terminal: IdeTargetTerminalClient) {
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
        PacketDistributor.sendToServer(payload)
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
