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
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent
import ru.lazyhat.compukters.core.MOD_ID

@EventBusSubscriber(modid = MOD_ID, value = [Dist.CLIENT])
object TerminalClientNetwork {
    private fun currentTerminal(): TerminalScreen? = Minecraft.getInstance().screen as? TerminalScreen

    @JvmStatic
    @SubscribeEvent
    fun register(event: RegisterClientPayloadHandlersEvent) {
        event.register(TerminalFullPayload.TYPE) { payload, _ ->
            val minecraft = Minecraft.getInstance()
            val current = currentTerminal()
            if (current is TerminalScreen && current.position == payload.position) {
                current.update(payload)
            } else if (shouldOpenStandaloneTerminal(minecraft.screen != null, payload.openScreen)) {
                minecraft.setScreen(TerminalScreen(payload))
            }
        }
        event.register(TerminalDeltaPayload.TYPE) deltaHandler@{ payload, _ ->
            val current = currentTerminal() ?: return@deltaHandler
            if (current.position != payload.position || !current.update(payload)) {
                current.requestResync()
            }
        }
        event.register(TerminalResourcePayload.TYPE) resourceHandler@{ payload, _ ->
            val current = currentTerminal() ?: return@resourceHandler
            current.update(payload)
        }
    }
}

internal fun shouldOpenStandaloneTerminal(
    hasOpenScreen: Boolean,
    requestedOpen: Boolean,
): Boolean = requestedOpen && !hasOpenScreen
