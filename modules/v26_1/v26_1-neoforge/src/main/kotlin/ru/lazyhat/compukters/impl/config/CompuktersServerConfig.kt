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

package ru.lazyhat.compukters.impl.config

import net.neoforged.neoforge.common.ModConfigSpec
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig

object CompuktersServerConfig {
    private val builder = ModConfigSpec.Builder()
    private val defaultWorkers = VmActorSchedulerConfig.defaultWorkerCount()

    private val workerCount =
        builder
            .comment("Number of platform threads used to execute VM actors")
            .defineInRange("vm.workers", defaultWorkers, 1, MAXIMUM_WORKERS)
    private val maximumActors =
        builder
            .comment("Maximum number of resident VM actors per server")
            .defineInRange(
                "vm.maximum_actors",
                VmActorSchedulerConfig.DEFAULT_MAXIMUM_ACTORS,
                1,
                16_384,
            )
    private val mailboxCapacity =
        builder
            .comment("Maximum accepted commands waiting for one VM actor")
            .defineInRange("vm.mailbox_capacity", 64, 1, 1_024)
    private val messagesPerTurn =
        builder
            .comment("Maximum commands processed when a worker leases one VM actor")
            .defineInRange("vm.messages_per_turn", 4, 1, 64)
    private val resultCapacityPerWorker =
        builder
            .comment("Maximum undelivered actor replies retained by each VM worker")
            .defineInRange("vm.result_capacity_per_worker", 256, 1, 4_096)

    val SPEC: ModConfigSpec = builder.build()

    fun schedulerConfig(): VmActorSchedulerConfig =
        VmActorSchedulerConfig(
            workerCount = workerCount.get(),
            maximumActors = maximumActors.get(),
            mailboxCapacity = mailboxCapacity.get(),
            messagesPerTurn = messagesPerTurn.get(),
            resultCapacityPerWorker = resultCapacityPerWorker.get(),
        )

    private const val MAXIMUM_WORKERS = 64
}
