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

import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorLease
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorMetrics
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorService
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig
import ru.lazyhat.compukters.core.device.runtime.program.ProgramResourceSnapshot
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal class HeadlessVmBenchmarkFleet(
    private val runtime: HeadlessVmBenchmarkRuntime,
    artifact: ByteArray,
    private val maximumActors: Int = MAXIMUM_ACTORS,
) {
    private val owner = Thread.currentThread()
    private val artifact = artifact.copyOf()
    private var generation = 0L
    private var run: Run? = null

    init {
        require(artifact.isNotEmpty()) { "headless VM benchmark artifact must not be empty" }
        require(maximumActors > 0) { "maximum benchmark actors must be positive" }
    }

    fun start(
        count: Int,
        rounds: Int,
        worldTick: Long,
    ): HeadlessVmBenchmarkStart = start(count, rounds, worldTick, HeadlessVmBenchmarkMode.CPU)

    fun startCapacity(
        count: Int,
        rounds: Int,
        worldTick: Long,
    ): HeadlessVmBenchmarkStart = start(count, rounds, worldTick, HeadlessVmBenchmarkMode.CAPACITY)

    private fun start(
        count: Int,
        rounds: Int,
        worldTick: Long,
        mode: HeadlessVmBenchmarkMode,
    ): HeadlessVmBenchmarkStart {
        checkOwner()
        require(count in 1..maximumActors) { "benchmark actor count must be in 1..$maximumActors" }
        require(rounds in 1..MAXIMUM_ROUNDS) { "benchmark rounds must be in 1..$MAXIMUM_ROUNDS" }
        require(worldTick >= 0) { "world tick must not be negative" }
        check(run?.isActive() != true) { "a headless VM benchmark is already active" }

        generation = if (generation == Long.MAX_VALUE) 1 else generation + 1
        val identity = UUID.randomUUID()
        val next = Run(mode, count, rounds, worldTick, runtime.metrics())
        run = next
        repeat(count) { index ->
            val endpoint = benchmarkEndpoint(identity, generation, index)
            runtime.attach(endpoint)?.let { actor ->
                Actor(actor).also {
                    next.actors += it
                    startActor(next, it)
                }
            }
        }
        return HeadlessVmBenchmarkStart(count, next.actors.size)
    }

    fun tick(worldTick: Long) {
        checkOwner()
        require(worldTick >= 0) { "world tick must not be negative" }
        val current = run ?: return
        current.lastTick = worldTick
        if (current.stopping) return
        current.actors.forEach { actor ->
            if (actor.phase != ActorPhase.READY) return@forEach
            actor.phase = ActorPhase.ADVANCING
            actor.runtime.advance(worldTick).whenComplete { state, failure ->
                if (run !== current || current.stopping) return@whenComplete
                when {
                    failure != null -> {
                        finishActor(actor, failed = true)
                    }

                    state == ProgramRuntimeState.Running || state == ProgramRuntimeState.WaitingForCompiler -> {
                        actor.phase = ActorPhase.READY
                    }

                    state == ProgramRuntimeState.WaitingForInput && current.mode == HeadlessVmBenchmarkMode.CAPACITY &&
                        current.phase == HeadlessVmBenchmarkPhase.SETTLING -> {
                        actor.phase = ActorPhase.WAITING
                        actor.settleTicks = current.elapsedTicks()
                    }

                    state is ProgramRuntimeState.Halted -> {
                        actor.completionTicks = current.wakeStartedTick?.let { (current.lastTick - it).coerceAtLeast(0) }
                        finishActor(actor, failed = false)
                    }

                    else -> {
                        finishActor(actor, failed = true)
                    }
                }
            }
        }
        advanceCapacityPhase(current)
        advanceTerminalPhase(current)
    }

    fun stop(): Int {
        checkOwner()
        val current = run ?: return 0
        val stopping = current.actors.count { it.closeFuture?.isDone != true }
        if (!current.stopping) {
            current.stopping = true
            current.actors.forEach(::closeActor)
        }
        return stopping
    }

    fun snapshot(currentMspt: Double): HeadlessVmBenchmarkSnapshot {
        checkOwner()
        require(currentMspt >= 0.0) { "Minecraft MSPT must not be negative" }
        val current =
            run
                ?: return HeadlessVmBenchmarkSnapshot.idle(currentMspt, runtime.metrics())
        val closing = current.actors.count { it.closeFuture?.isDone == false }
        val completed = current.actors.count { it.phase == ActorPhase.COMPLETED }
        val failed = current.actors.count { it.phase == ActorPhase.FAILED }
        val active = current.actors.size - completed - failed
        val status =
            when {
                current.stopping && closing > 0 -> HeadlessVmBenchmarkStatus.STOPPING
                current.stopping -> HeadlessVmBenchmarkStatus.STOPPED
                active == 0 && closing == 0 -> HeadlessVmBenchmarkStatus.COMPLETED
                else -> HeadlessVmBenchmarkStatus.RUNNING
            }
        return HeadlessVmBenchmarkSnapshot(
            status = status,
            requestedActors = current.requestedActors,
            admittedActors = current.actors.size,
            activeActors = active,
            completedActors = completed,
            failedActors = failed,
            closingActors = closing,
            rounds = current.rounds,
            elapsedTicks = (current.lastTick - current.startedTick).coerceAtLeast(0),
            currentMspt = currentMspt,
            baseline = current.baseline,
            metrics = runtime.metrics(),
            mode = current.mode,
            phase = current.reportPhase(status),
            waitingActors = current.actors.count { it.phase == ActorPhase.WAITING },
            settleTicks = VmBenchmarkTickDistribution.from(current.actors.mapNotNull(Actor::settleTicks)),
            wakeTicks = VmBenchmarkTickDistribution.from(current.actors.mapNotNull(Actor::wakeTicks)),
            completionTicks = VmBenchmarkTickDistribution.from(current.actors.mapNotNull(Actor::completionTicks)),
            memory = VmBenchmarkMemorySummary.from(current.actors.filter { it.resourceRequested }.map(Actor::resources)),
        )
    }

    private fun startActor(
        current: Run,
        actor: Actor,
    ) {
        actor.runtime.start(artifact).whenComplete { state, failure ->
            if (run !== current || current.stopping) return@whenComplete
            if (failure != null || state != ProgramRuntimeState.Running) {
                finishActor(actor, failed = true)
            } else if (current.mode == HeadlessVmBenchmarkMode.CAPACITY) {
                actor.phase = ActorPhase.READY
            } else {
                wakeActor(current, actor)
            }
        }
    }

    private fun advanceCapacityPhase(current: Run) {
        when (current.phase) {
            HeadlessVmBenchmarkPhase.SETTLING -> {
                if (current.actors.none { it.phase.isStartingOrRunnable() }) {
                    if (current.actors.any { it.phase == ActorPhase.WAITING }) {
                        current.phase = HeadlessVmBenchmarkPhase.IDLE
                        current.phaseStartedTick = current.lastTick
                    }
                }
            }

            HeadlessVmBenchmarkPhase.IDLE -> {
                if (current.lastTick - current.phaseStartedTick > IDLE_OBSERVATION_TICKS) startSampling(current)
            }

            HeadlessVmBenchmarkPhase.SAMPLING -> {
                if (current.actors.none { it.phase == ActorPhase.SAMPLING }) startWakeup(current)
            }

            HeadlessVmBenchmarkPhase.WAKING -> {
                if (current.actors.none { it.phase == ActorPhase.WAKING }) current.phase = HeadlessVmBenchmarkPhase.CPU
            }

            else -> {}
        }
    }

    private fun startSampling(current: Run) {
        current.phase = HeadlessVmBenchmarkPhase.SAMPLING
        current.phaseStartedTick = current.lastTick
        current.actors.filter { it.phase == ActorPhase.WAITING }.forEach { actor ->
            actor.phase = ActorPhase.SAMPLING
            actor.resourceRequested = true
            actor.runtime.resourceSnapshot().whenComplete { snapshot, _ ->
                if (run !== current || current.stopping) return@whenComplete
                actor.resources = snapshot as? ProgramResourceSnapshot.Available
                actor.phase = ActorPhase.WAITING
            }
        }
    }

    private fun startWakeup(current: Run) {
        current.phase = HeadlessVmBenchmarkPhase.WAKING
        current.phaseStartedTick = current.lastTick
        current.wakeStartedTick = current.lastTick
        current.actors.filter { it.phase == ActorPhase.WAITING }.forEach { wakeActor(current, it) }
    }

    private fun wakeActor(
        current: Run,
        actor: Actor,
    ) {
        actor.phase = ActorPhase.WAKING
        actor.runtime.sendRounds(current.rounds).whenComplete { accepted, failure ->
            if (run !== current || current.stopping) return@whenComplete
            if (failure == null && accepted == true) {
                actor.wakeTicks = current.wakeStartedTick?.let { (current.lastTick - it).coerceAtLeast(0) }
                actor.phase = ActorPhase.READY
            } else {
                finishActor(actor, failed = true)
            }
        }
    }

    private fun advanceTerminalPhase(current: Run) {
        if (current.actors.any { !it.phase.isTerminal() }) return
        current.phase =
            if (current.actors.any { it.closeFuture?.isDone == false }) {
                HeadlessVmBenchmarkPhase.CLOSING
            } else {
                HeadlessVmBenchmarkPhase.COMPLETED
            }
    }

    private fun finishActor(
        actor: Actor,
        failed: Boolean,
    ) {
        actor.phase = if (failed) ActorPhase.FAILED else ActorPhase.COMPLETED
        closeActor(actor)
    }

    private fun closeActor(actor: Actor) {
        if (actor.closeFuture == null) actor.closeFuture = actor.runtime.closeAsync()
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "benchmark fleet must run on its server thread" }

    private class Run(
        val mode: HeadlessVmBenchmarkMode,
        val requestedActors: Int,
        val rounds: Int,
        val startedTick: Long,
        val baseline: ProgramRuntimeActorMetrics,
    ) {
        val actors = mutableListOf<Actor>()
        var lastTick = startedTick
        var stopping = false
        var phase = if (mode == HeadlessVmBenchmarkMode.CAPACITY) HeadlessVmBenchmarkPhase.SETTLING else HeadlessVmBenchmarkPhase.CPU
        var phaseStartedTick = startedTick
        var wakeStartedTick: Long? = if (mode == HeadlessVmBenchmarkMode.CPU) startedTick else null

        fun elapsedTicks(): Long = (lastTick - startedTick).coerceAtLeast(0)

        fun reportPhase(status: HeadlessVmBenchmarkStatus): HeadlessVmBenchmarkPhase =
            when (status) {
                HeadlessVmBenchmarkStatus.STOPPING -> HeadlessVmBenchmarkPhase.CLOSING
                HeadlessVmBenchmarkStatus.STOPPED -> HeadlessVmBenchmarkPhase.STOPPED
                HeadlessVmBenchmarkStatus.COMPLETED -> HeadlessVmBenchmarkPhase.COMPLETED
                else -> phase
            }

        fun isActive(): Boolean =
            (!stopping && actors.any { it.phase != ActorPhase.COMPLETED && it.phase != ActorPhase.FAILED }) ||
                actors.any { it.closeFuture?.isDone == false }
    }

    private class Actor(
        val runtime: HeadlessVmBenchmarkActor,
    ) {
        var phase = ActorPhase.STARTING
        var closeFuture: CompletableFuture<*>? = null
        var settleTicks: Long? = null
        var wakeTicks: Long? = null
        var completionTicks: Long? = null
        var resourceRequested = false
        var resources: ProgramResourceSnapshot.Available? = null
    }

    private enum class ActorPhase {
        STARTING,
        READY,
        ADVANCING,
        WAITING,
        SAMPLING,
        WAKING,
        COMPLETED,
        FAILED,
        ;

        fun isStartingOrRunnable(): Boolean = this == STARTING || this == READY || this == ADVANCING

        fun isTerminal(): Boolean = this == COMPLETED || this == FAILED
    }

    private companion object {
        const val MAXIMUM_ACTORS = VmActorSchedulerConfig.DEFAULT_MAXIMUM_ACTORS
        const val MAXIMUM_ROUNDS = 1_000_000
        const val IDLE_OBSERVATION_TICKS = 100L

        fun benchmarkEndpoint(
            identity: UUID,
            epoch: Long,
            index: Int,
        ): VmActorEndpoint {
            val low = identity.leastSignificantBits + index.toLong() + 1
            val high = identity.mostSignificantBits.takeUnless { it == 0L && low == 0L } ?: 1L
            return VmActorEndpoint(ComputerId.fromLongs(high, low), epoch)
        }
    }
}

internal interface HeadlessVmBenchmarkRuntime {
    fun attach(endpoint: VmActorEndpoint): HeadlessVmBenchmarkActor?

    fun metrics(): ProgramRuntimeActorMetrics
}

internal interface HeadlessVmBenchmarkActor {
    fun start(artifact: ByteArray): CompletableFuture<ProgramRuntimeState>

    fun sendRounds(rounds: Int): CompletableFuture<Boolean>

    fun advance(worldTick: Long): CompletableFuture<ProgramRuntimeState>

    fun resourceSnapshot(): CompletableFuture<ProgramResourceSnapshot?>

    fun closeAsync(): CompletableFuture<*>
}

internal class ActorServiceBenchmarkRuntime(
    private val service: ProgramRuntimeActorService,
) : HeadlessVmBenchmarkRuntime {
    override fun attach(endpoint: VmActorEndpoint): HeadlessVmBenchmarkActor? =
        service.attachStandalone(endpoint)?.let { ActorServiceBenchmarkActor(service, it) }

    override fun metrics(): ProgramRuntimeActorMetrics = service.runtimeMetrics()
}

private class ActorServiceBenchmarkActor(
    private val service: ProgramRuntimeActorService,
    private val lease: ProgramRuntimeActorLease,
) : HeadlessVmBenchmarkActor {
    override fun start(artifact: ByteArray): CompletableFuture<ProgramRuntimeState> =
        service.request(lease.endpoint) { ProgramRuntimeActorCommand.Start(it, artifact) }.thenApply { it.state }

    override fun sendRounds(rounds: Int): CompletableFuture<Boolean> =
        service.request(lease.endpoint) { ProgramRuntimeActorCommand.SendTerminalText(it, rounds.toString()) }.thenApply {
            (it.value as? ProgramRuntimeActorValue.Accepted)?.accepted == true
        }

    override fun advance(worldTick: Long): CompletableFuture<ProgramRuntimeState> =
        service.turn(lease.endpoint, worldTick).thenApply { it.state }

    override fun resourceSnapshot(): CompletableFuture<ProgramResourceSnapshot?> =
        service.request(lease.endpoint) { ProgramRuntimeActorCommand.ResourceSnapshot(it) }.thenApply {
            (it.value as? ProgramRuntimeActorValue.ResourceSnapshotValue)?.snapshot
        }

    override fun closeAsync(): CompletableFuture<*> = lease.closeAsync()
}

internal data class HeadlessVmBenchmarkStart(
    val requestedActors: Int,
    val admittedActors: Int,
)

internal data class HeadlessVmBenchmarkSnapshot(
    val status: HeadlessVmBenchmarkStatus,
    val requestedActors: Int,
    val admittedActors: Int,
    val activeActors: Int,
    val completedActors: Int,
    val failedActors: Int,
    val closingActors: Int,
    val rounds: Int,
    val elapsedTicks: Long,
    val currentMspt: Double,
    val baseline: ProgramRuntimeActorMetrics,
    val metrics: ProgramRuntimeActorMetrics,
    val mode: HeadlessVmBenchmarkMode? = null,
    val phase: HeadlessVmBenchmarkPhase = HeadlessVmBenchmarkPhase.IDLE,
    val waitingActors: Int = 0,
    val settleTicks: VmBenchmarkTickDistribution = VmBenchmarkTickDistribution.EMPTY,
    val wakeTicks: VmBenchmarkTickDistribution = VmBenchmarkTickDistribution.EMPTY,
    val completionTicks: VmBenchmarkTickDistribution = VmBenchmarkTickDistribution.EMPTY,
    val memory: VmBenchmarkMemorySummary = VmBenchmarkMemorySummary.EMPTY,
) {
    companion object {
        fun idle(
            currentMspt: Double,
            metrics: ProgramRuntimeActorMetrics,
        ) = HeadlessVmBenchmarkSnapshot(
            HeadlessVmBenchmarkStatus.IDLE,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            currentMspt,
            metrics,
            metrics,
        )
    }
}

internal enum class HeadlessVmBenchmarkMode {
    CPU,
    CAPACITY,
}

internal enum class HeadlessVmBenchmarkPhase {
    IDLE,
    SETTLING,
    SAMPLING,
    WAKING,
    CPU,
    CLOSING,
    COMPLETED,
    STOPPED,
}

internal data class VmBenchmarkTickDistribution(
    val samples: Int,
    val medianTicks: Long?,
    val p95Ticks: Long?,
    val maximumTicks: Long?,
) {
    companion object {
        val EMPTY = VmBenchmarkTickDistribution(0, null, null, null)

        fun from(values: List<Long>): VmBenchmarkTickDistribution {
            if (values.isEmpty()) return EMPTY
            require(values.all { it >= 0 }) { "benchmark tick samples must not be negative" }
            val sorted = values.sorted()
            return VmBenchmarkTickDistribution(
                samples = sorted.size,
                medianTicks = percentile(sorted, 50),
                p95Ticks = percentile(sorted, 95),
                maximumTicks = sorted.last(),
            )
        }

        private fun percentile(
            sorted: List<Long>,
            percentile: Int,
        ): Long {
            val rank = (sorted.size * percentile + 99) / 100
            return sorted[(rank - 1).coerceAtLeast(0)]
        }
    }
}

internal data class VmBenchmarkMemorySummary(
    val availableSamples: Int,
    val unavailableSamples: Int,
    val heapUsedBytes: Long,
    val heapCapacityBytes: Long,
    val executionResidentBytes: Long,
) {
    companion object {
        val EMPTY = VmBenchmarkMemorySummary(0, 0, 0, 0, 0)

        fun from(samples: List<ProgramResourceSnapshot.Available?>): VmBenchmarkMemorySummary {
            if (samples.isEmpty()) return EMPTY
            val available = samples.filterNotNull()
            return VmBenchmarkMemorySummary(
                availableSamples = available.size,
                unavailableSamples = samples.size - available.size,
                heapUsedBytes = available.saturatingSum(ProgramResourceSnapshot.Available::heapUsedBytes),
                heapCapacityBytes = available.saturatingSum(ProgramResourceSnapshot.Available::heapCapacityBytes),
                executionResidentBytes = available.saturatingSum(ProgramResourceSnapshot.Available::mutableExecutionResidentBytes),
            )
        }

        private fun <T> List<T>.saturatingSum(value: (T) -> Long): Long {
            var total = 0L
            forEach { item ->
                val next = value(item)
                total = if (total > Long.MAX_VALUE - next) Long.MAX_VALUE else total + next
            }
            return total
        }
    }
}

internal enum class HeadlessVmBenchmarkStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    STOPPING,
    STOPPED,
}
