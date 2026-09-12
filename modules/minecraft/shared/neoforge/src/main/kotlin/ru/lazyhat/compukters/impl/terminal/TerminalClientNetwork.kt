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

import net.minecraft.client.Minecraft
import net.neoforged.neoforge.network.handling.IPayloadContext

object TerminalClientNetwork {
    private fun currentTerminal(): TerminalScreen? = Minecraft.getInstance().screen as? TerminalScreen

    internal fun handleFull(
        payload: TerminalFullPayload,
        @Suppress("UNUSED_PARAMETER") context: IPayloadContext,
    ) {
        val minecraft = Minecraft.getInstance()
        val current = currentTerminal()
        if (current is TerminalScreen && current.position == payload.position) {
            current.update(payload)
        } else if (shouldOpenStandaloneTerminal(minecraft.screen != null, payload.openScreen)) {
            minecraft.setScreen(TerminalScreen(payload))
        }
    }

    internal fun handleDelta(
        payload: TerminalDeltaPayload,
        @Suppress("UNUSED_PARAMETER") context: IPayloadContext,
    ) {
        val current = currentTerminal() ?: return
        if (current.position != payload.position || !current.update(payload)) {
            current.requestResync()
        }
    }

    internal fun handleResource(
        payload: TerminalResourcePayload,
        @Suppress("UNUSED_PARAMETER") context: IPayloadContext,
    ) {
        val current = currentTerminal() ?: return
        current.update(payload)
    }
}

internal fun shouldOpenStandaloneTerminal(
    hasOpenScreen: Boolean,
    requestedOpen: Boolean,
): Boolean = requestedOpen && !hasOpenScreen
