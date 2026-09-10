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

package ru.lazyhat.compukters.impl.benchmark

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig
import ru.lazyhat.compukters.impl.computer.NeoForgeVmActorServices
import ru.lazyhat.compukters.minecraft.computer.HeadlessVmBenchmarkArtifact
import java.util.IdentityHashMap
import java.util.Locale

internal object VmBenchmarkCommands {
    private val fleets = IdentityHashMap<MinecraftServer, HeadlessVmBenchmarkFleet>()
    private val automaticReports = IdentityHashMap<MinecraftServer, AutomaticReport>()
    private val areaDispatcher = VmBenchmarkAreaDispatcher()

    fun register(event: RegisterCommandsEvent) = register(event.dispatcher)

    internal fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands
                .literal("compukters")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands
                        .literal("vmbench")
                        .then(
                            Commands
                                .literal("start")
                                .then(
                                    Commands
                                        .argument("count", IntegerArgumentType.integer(1, MAXIMUM_ACTORS))
                                        .then(
                                            Commands
                                                .argument("rounds", IntegerArgumentType.integer(1, MAXIMUM_ROUNDS))
                                                .executes { context ->
                                                    start(
                                                        context.source,
                                                        IntegerArgumentType.getInteger(context, "count"),
                                                        IntegerArgumentType.getInteger(context, "rounds"),
                                                    )
                                                },
                                        ),
                                ),
                        ).then(Commands.literal("status").executes { status(it.source) })
                        .then(Commands.literal("stop").executes { stop(it.source) })
                        .then(
                            Commands
                                .literal("area")
                                .then(
                                    Commands
                                        .argument("from", BlockPosArgument.blockPos())
                                        .then(
                                            Commands
                                                .argument("to", BlockPosArgument.blockPos())
                                                .then(
                                                    Commands
                                                        .argument("rounds", IntegerArgumentType.integer(1, MAXIMUM_ROUNDS))
                                                        .executes { context ->
                                                            area(
                                                                context.source,
                                                                BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                                BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                                IntegerArgumentType.getInteger(context, "rounds"),
                                                            )
                                                        },
                                                ),
                                        ),
                                ),
                        ),
                ),
        )
    }

    fun afterServerTick(event: ServerTickEvent.Post) {
        val fleet = fleets[event.server] ?: return
        val worldTick = event.server.tickCount.toLong()
        fleet.tick(worldTick)
        val report = automaticReports[event.server] ?: return
        val snapshot = fleet.snapshot(event.server.currentMspt())
        if (report.schedule.shouldReport(worldTick, snapshot.status)) {
            report.source.sendSuccess({ Component.literal(snapshot.describe()) }, false)
            if (snapshot.status.isTerminal()) automaticReports.remove(event.server)
        }
    }

    fun onServerStopping(event: ServerStoppingEvent) {
        automaticReports.remove(event.server)
        fleets.remove(event.server)?.stop()
    }

    internal fun snapshot(server: MinecraftServer): HeadlessVmBenchmarkSnapshot? = fleets[server]?.snapshot(server.currentMspt())

    private fun start(
        source: CommandSourceStack,
        count: Int,
        rounds: Int,
    ): Int =
        runCatching {
            val result =
                fleet(source.server).start(
                    count,
                    rounds,
                    source.server.tickCount.toLong(),
                )
            automaticReports[source.server] =
                AutomaticReport(
                    source,
                    VmBenchmarkReportSchedule(source.server.tickCount.toLong()),
                )
            source.sendSuccess(
                {
                    Component.literal(
                        "Headless VM benchmark admitted ${result.admittedActors}/${result.requestedActors} actors " +
                            "for $rounds rounds",
                    )
                },
                false,
            )
            result.admittedActors
        }.getOrElse { failure ->
            source.sendFailure(Component.literal(failure.message ?: "Unable to start headless VM benchmark"))
            0
        }

    private fun status(source: CommandSourceStack): Int {
        val snapshot =
            fleets[source.server]?.snapshot(source.server.currentMspt())
                ?: HeadlessVmBenchmarkSnapshot.idle(
                    source.server.currentMspt(),
                    NeoForgeVmActorServices.metrics(source.server) ?: return idleStatus(source),
                )
        source.sendSuccess({ Component.literal(snapshot.describe()) }, false)
        return snapshot.activeActors
    }

    private fun idleStatus(source: CommandSourceStack): Int {
        source.sendSuccess({ Component.literal("Headless VM benchmark: IDLE; actor service has not been allocated") }, false)
        return 0
    }

    private fun stop(source: CommandSourceStack): Int {
        val stopped = fleets[source.server]?.stop() ?: 0
        source.sendSuccess({ Component.literal("Headless VM benchmark stopping $stopped actors") }, false)
        return stopped
    }

    private fun area(
        source: CommandSourceStack,
        first: net.minecraft.core.BlockPos,
        second: net.minecraft.core.BlockPos,
        rounds: Int,
    ): Int =
        runCatching {
            val dispatch = areaDispatcher.dispatch(MinecraftVmBenchmarkAreaAccess(source.level), first, second, rounds)
            source.sendSuccess(
                {
                    Component.literal(
                        "VM benchmark area scanned ${dispatch.scannedPositions} positions, found " +
                            "${dispatch.discoveredComputers} computers, scheduled ${dispatch.scheduledComputers}, " +
                            "skipped ${dispatch.unloadedPositions} unloaded positions and limited ${dispatch.limitedComputers}",
                    )
                },
                false,
            )
            dispatch.completion.thenAccept { result ->
                source.sendSuccess(
                    {
                        Component.literal(
                            "VM benchmark area delivery: ${result.acceptedComputers} accepted, " +
                                "${result.rejectedComputers} rejected, ${result.limitedComputers} over limit",
                        )
                    },
                    false,
                )
            }
            dispatch.scheduledComputers
        }.getOrElse { failure ->
            source.sendFailure(Component.literal(failure.message ?: "Unable to dispatch VM benchmark area"))
            0
        }

    private fun fleet(server: MinecraftServer): HeadlessVmBenchmarkFleet =
        fleets.computeIfAbsent(server) {
            HeadlessVmBenchmarkFleet(
                ActorServiceBenchmarkRuntime(NeoForgeVmActorServices.service(server)),
                HeadlessVmBenchmarkArtifact.packaged(),
            )
        }

    private fun MinecraftServer.currentMspt(): Double = averageTickTimeNanos / 1_000_000.0

    private fun HeadlessVmBenchmarkSnapshot.describe(): String {
        val processed = (metrics.scheduler.processedMessages - baseline.scheduler.processedMessages).coerceAtLeast(1)
        val drained = (metrics.scheduler.drainedEvents - baseline.scheduler.drainedEvents).coerceAtLeast(1)
        val queueNanos = (metrics.scheduler.totalQueueLatencyNanos - baseline.scheduler.totalQueueLatencyNanos).coerceAtLeast(0)
        val executionNanos = (metrics.scheduler.totalExecutionNanos - baseline.scheduler.totalExecutionNanos).coerceAtLeast(0)
        val resultNanos =
            (metrics.scheduler.totalResultLatencyNanos - baseline.scheduler.totalResultLatencyNanos).coerceAtLeast(0)
        return "Headless VM benchmark: $status; actors=$activeActors active/$completedActors completed/$failedActors failed/" +
            "$admittedActors admitted ($requestedActors requested), closing=$closingActors, rounds=$rounds, " +
            "ticks=$elapsedTicks, MSPT=${"%.3f".format(Locale.ROOT, currentMspt)}, mailbox=${metrics.scheduler.queuedMessages}, " +
            "results=${metrics.scheduler.queuedResults}, workers=${metrics.scheduler.busyWorkers}, " +
            "queueAvgUs=${queueNanos / processed / 1_000}, executionAvgUs=${executionNanos / processed / 1_000}, " +
            "resultAvgUs=${resultNanos / drained / 1_000}, drainedLast=${metrics.lastPumpEvents}, " +
            "pumpLastUs=${metrics.lastPumpNanos / 1_000}, " +
            "mailboxRejected=${metrics.scheduler.mailboxFullRejections - baseline.scheduler.mailboxFullRejections}"
    }

    private const val MAXIMUM_ACTORS = VmActorSchedulerConfig.DEFAULT_MAXIMUM_ACTORS
    private const val MAXIMUM_ROUNDS = 1_000_000

    private data class AutomaticReport(
        val source: CommandSourceStack,
        val schedule: VmBenchmarkReportSchedule,
    )
}

internal class VmBenchmarkReportSchedule(
    startedTick: Long,
    private val intervalTicks: Long = REPORT_INTERVAL_TICKS,
) {
    private var nextReportTick = startedTick + intervalTicks
    private var terminalReported = false

    init {
        require(startedTick >= 0) { "benchmark start tick must not be negative" }
        require(intervalTicks > 0) { "benchmark report interval must be positive" }
    }

    fun shouldReport(
        worldTick: Long,
        status: HeadlessVmBenchmarkStatus,
    ): Boolean {
        require(worldTick >= 0) { "world tick must not be negative" }
        if (terminalReported) return false
        if (status.isTerminal()) {
            terminalReported = true
            return true
        }
        if (worldTick < nextReportTick) return false
        nextReportTick = worldTick + intervalTicks
        return true
    }

    private companion object {
        const val REPORT_INTERVAL_TICKS = 100L
    }
}

private fun HeadlessVmBenchmarkStatus.isTerminal(): Boolean =
    this == HeadlessVmBenchmarkStatus.COMPLETED || this == HeadlessVmBenchmarkStatus.STOPPED
