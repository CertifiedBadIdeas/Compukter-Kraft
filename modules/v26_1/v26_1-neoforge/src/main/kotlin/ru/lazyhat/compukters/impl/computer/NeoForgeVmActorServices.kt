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

package ru.lazyhat.compukters.impl.computer

import net.minecraft.server.MinecraftServer
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorService
import java.util.IdentityHashMap

/** Server-thread-owned lifecycle; a started server allocates workers only on first use. */
internal class VmActorServiceRegistry<S : Any>(
    private val checkOwner: (S) -> Unit,
    private val opener: () -> ProgramRuntimeActorService = ::ProgramRuntimeActorService,
    private val maximumEventsPerTick: Int = 256,
) {
    private val servers = IdentityHashMap<S, Entry>()

    init {
        require(maximumEventsPerTick > 0) { "VM result budget must be positive" }
    }

    fun start(server: S) {
        checkOwner(server)
        check(server !in servers) { "VM service lifecycle already started" }
        servers[server] = Entry()
    }

    fun service(server: S): ProgramRuntimeActorService {
        checkOwner(server)
        val entry = checkNotNull(servers[server]) { "VM service requires a running server" }
        return entry.service ?: opener().also { entry.service = it }
    }

    fun tick(server: S): Int {
        checkOwner(server)
        return servers[server]?.service?.pump(maximumEventsPerTick) ?: 0
    }

    fun stop(server: S) {
        checkOwner(server)
        servers.remove(server)?.service?.close()
    }

    private class Entry {
        var service: ProgramRuntimeActorService? = null
    }
}

internal object NeoForgeVmActorServices {
    private val registry =
        VmActorServiceRegistry<MinecraftServer>(
            checkOwner = { server ->
                check(server.isSameThread) { "VM service lifecycle must run on the server thread" }
            },
        )

    fun service(server: MinecraftServer): ProgramRuntimeActorService = registry.service(server)

    fun onServerStarting(event: ServerStartingEvent) = registry.start(event.server)

    fun afterServerTick(event: ServerTickEvent.Post) {
        registry.tick(event.server)
    }

    fun onServerStopping(event: ServerStoppingEvent) = registry.stop(event.server)
}
