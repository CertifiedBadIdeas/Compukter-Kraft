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

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

internal object IdeTargetNetwork {
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        IdeTargetServerNetwork.register(registrar)
        registrar.playToClient(IdeTargetReplyPayload.TYPE, IdeTargetReplyPayload.STREAM_CODEC, IdeTargetClientNetwork::handleReply)
        registrar.playToClient(IdeTerminalOpenedPayload.TYPE, IdeTerminalOpenedPayload.STREAM_CODEC, IdeTargetClientNetwork::handleOpened)
        registrar.playToClient(IdeTerminalFullPayload.TYPE, IdeTerminalFullPayload.STREAM_CODEC, IdeTargetClientNetwork::handleFull)
        registrar.playToClient(IdeTerminalDeltaPayload.TYPE, IdeTerminalDeltaPayload.STREAM_CODEC, IdeTargetClientNetwork::handleDelta)
        registrar.playToClient(IdeTerminalFailedPayload.TYPE, IdeTerminalFailedPayload.STREAM_CODEC, IdeTargetClientNetwork::handleFailed)
    }
}
