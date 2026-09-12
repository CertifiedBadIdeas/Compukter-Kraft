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

@file:Suppress("ktlint:standard:filename")

package ru.lazyhat.compukters.impl

import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent
import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.impl.compiler.NeoForgeCompilerServices
import ru.lazyhat.compukters.impl.computer.NeoForgeVmActorServices
import ru.lazyhat.compukters.impl.config.CompuktersClientConfig
import ru.lazyhat.compukters.impl.config.CompuktersServerConfig
import ru.lazyhat.compukters.impl.fs.NeoForgeWorldFileSystemStores
import ru.lazyhat.compukters.impl.ide.target.IdeTargetNetwork
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.impl.terminal.TerminalNetwork
import ru.lazyhat.compukters.lang.runtime.vm.JniRuntimeBackend
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime

@Mod(MOD_ID)
class CompuktersMod(
    eventBus: IEventBus,
    modContainer: ModContainer,
) {
    init {
        val native = requireNativeRuntime()
        CompuktersRegistry.register(eventBus)
        eventBus.addListener(TerminalNetwork::register)
        eventBus.addListener(IdeTargetNetwork::register)
        NeoForge.EVENT_BUS.addListener(NeoForgeWorldFileSystemStores::onLevelSave)
        NeoForge.EVENT_BUS.addListener(NeoForgeVmActorServices::onServerStarting)
        NeoForge.EVENT_BUS.addListener(NeoForgeWorldFileSystemStores::onServerStarting)
        NeoForge.EVENT_BUS.addListener(
            EventPriority.HIGHEST,
            ServerTickEvent.Pre::class.java,
            NeoForgeVmActorServices::beforeServerTick,
        )
        NeoForge.EVENT_BUS.addListener(NeoForgeVmActorServices::onServerStopping)
        NeoForge.EVENT_BUS.addListener(NeoForgeWorldFileSystemStores::onServerStopping)
        NeoForge.EVENT_BUS.addListener(NeoForgeCompilerServices::onServerStopping)
        modContainer.registerConfig(ModConfig.Type.CLIENT, CompuktersClientConfig.SPEC)
        modContainer.registerConfig(ModConfig.Type.SERVER, CompuktersServerConfig.SPEC)
        LOGGER.debug { "$MOD_ID loaded Java 21 JNI VM from ${native.source}" }
    }

    companion object {
        internal fun requireNativeRuntime() =
            VmRuntime
                .also { it.install(JniRuntimeBackend) }
                .requireLoaded()
    }
}
