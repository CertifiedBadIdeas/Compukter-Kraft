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
    ): HeadlessVmBenchmarkStart {
        checkOwner()
        require(count in 1..maximumActors) { "benchmark actor count must be in 1..$maximumActors" }
        require(rounds in 1..MAXIMUM_ROUNDS) { "benchmark rounds must be in 1..$MAXIMUM_ROUNDS" }
        require(worldTick >= 0) { "world tick must not be negative" }
        check(run?.isActive() != true) { "a headless VM benchmark is already active" }

        generation = if (generation == Long.MAX_VALUE) 1 else generation + 1
        val identity = UUID.randomUUID()
        val next = Run(count, rounds, worldTick, runtime.metrics())
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

                    state is ProgramRuntimeState.Halted -> {
                        finishActor(actor, failed = false)
                    }

                    else -> {
                        finishActor(actor, failed = true)
                    }
                }
            }
        }
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
        )
    }

    private fun startActor(
        current: Run,
        actor: Actor,
    ) {
        actor.runtime
            .start(artifact)
            .thenCompose { state ->
                if (state != ProgramRuntimeState.Running) {
                    CompletableFuture.failedFuture(IllegalStateException("benchmark VM did not start: $state"))
                } else {
                    actor.runtime.sendRounds(current.rounds)
                }
            }.whenComplete { accepted, failure ->
                if (run !== current || current.stopping) return@whenComplete
                if (failure == null && accepted == true) {
                    actor.phase = ActorPhase.READY
                } else {
                    finishActor(actor, failed = true)
                }
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
        val requestedActors: Int,
        val rounds: Int,
        val startedTick: Long,
        val baseline: ProgramRuntimeActorMetrics,
    ) {
        val actors = mutableListOf<Actor>()
        var lastTick = startedTick
        var stopping = false

        fun isActive(): Boolean =
            (!stopping && actors.any { it.phase != ActorPhase.COMPLETED && it.phase != ActorPhase.FAILED }) ||
                actors.any { it.closeFuture?.isDone == false }
    }

    private class Actor(
        val runtime: HeadlessVmBenchmarkActor,
    ) {
        var phase = ActorPhase.STARTING
        var closeFuture: CompletableFuture<*>? = null
    }

    private enum class ActorPhase {
        STARTING,
        READY,
        ADVANCING,
        COMPLETED,
        FAILED,
    }

    private companion object {
        const val MAXIMUM_ACTORS = VmActorSchedulerConfig.DEFAULT_MAXIMUM_ACTORS
        const val MAXIMUM_ROUNDS = 1_000_000

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
        service.request(lease.endpoint) { ProgramRuntimeActorCommand.Advance(it, worldTick) }.thenApply { it.state }

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

internal enum class HeadlessVmBenchmarkStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    STOPPING,
    STOPPED,
}
