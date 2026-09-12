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

package ru.lazyhat.compukters.core.device.computer

import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorEffect
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorLease
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorRequestException
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorService
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeRequestId
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSubmission
import ru.lazyhat.compukters.core.device.runtime.program.ProgramFailure
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneHostPort
import ru.lazyhat.compukters.core.device.runtime.program.SoundCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.SoundHostPort
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Server-side carrier of an actor-owned machine. Only immutable observations and request futures leave the actor.
 * The service must be pumped on this carrier's owning thread.
 */
class ActorProgramComputer(
    private val service: ProgramRuntimeActorService,
    private val lease: ProgramRuntimeActorLease,
    private val redstone: RedstoneHostPort,
    private val sound: SoundHostPort = SoundHostPort { SoundCommitResult.Failed(HostFailureKind.UNAVAILABLE, 0) },
    private val stateSink: (ProgramRuntimeState) -> Unit = {},
) {
    private val owner = Thread.currentThread()
    private var lifecycle = 0L
    private var advance: CompletableFuture<ProgramRuntimeActorReply>? = null
    private var pendingOutput: PendingOutput? = null
    private var pendingSound: PendingSound? = null
    private val pendingRedstoneInputs = ArrayDeque<Int>()
    private var closeResult: CompletableFuture<Long?>? = null
    private var bootRequest: CompletableFuture<ProgramRuntimeActorReply>? = null
    private var lastAdvanceTick = -1L
    private var lastObservedServerTick = -1L
    private var hostCompletionTick: Long? = null

    var state: ProgramRuntimeState = ProgramRuntimeState.Idle
        private set

    var fileSystemGeneration: Long? = null
        private set

    fun turnOn(): CompletableFuture<ProgramRuntimeActorReply> {
        checkOwner()
        if (closeResult != null) return CompletableFuture.failedFuture(IllegalStateException("computer is closed"))
        bootRequest?.let { previous ->
            if (!previous.isDone || state == ProgramRuntimeState.Running ||
                state == ProgramRuntimeState.WaitingForInput || state == ProgramRuntimeState.WaitingForCompiler
            ) {
                return previous.copy()
            }
        }
        return lifecycleRequest(ProgramRuntimeActorCommand::StartBoot).also { bootRequest = it }.copy()
    }

    fun reboot(): CompletableFuture<ProgramRuntimeActorReply> =
        lifecycleRequest(ProgramRuntimeActorCommand::Reboot).also { bootRequest = it }.copy()

    fun shutdown(): CompletableFuture<ProgramRuntimeActorReply> {
        checkOwner()
        bootRequest = null
        return lifecycleRequest(ProgramRuntimeActorCommand::Shutdown)
    }

    fun resourceSnapshot(): CompletableFuture<ProgramRuntimeActorReply> = request(ProgramRuntimeActorCommand::ResourceSnapshot)

    /** Terminal, filesystem, deployment and input operations retain their typed actor command/reply contract. */
    fun request(command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand): CompletableFuture<ProgramRuntimeActorReply> =
        send { id ->
            val prepared = command(id)
            require(
                prepared !is ProgramRuntimeActorCommand.Start &&
                    prepared !is ProgramRuntimeActorCommand.StartBoot &&
                    prepared !is ProgramRuntimeActorCommand.Reboot &&
                    prepared !is ProgramRuntimeActorCommand.Shutdown,
            ) { "use carrier lifecycle and tick operations for machine control" }
            prepared
        }

    fun serverTick(
        worldTick: Long,
        redstoneInput: Int? = null,
    ) {
        checkOwner()
        require(worldTick >= 0)
        redstoneInput?.let(::enqueueRedstoneInput)
        lastObservedServerTick = maxOf(lastObservedServerTick, worldTick)
        if (closeResult != null) return
        if (advance != null || worldTick <= lastAdvanceTick ||
            (
                state != ProgramRuntimeState.Running &&
                    state != ProgramRuntimeState.WaitingForCompiler &&
                    pendingOutput == null &&
                    pendingSound == null &&
                    pendingRedstoneInputs.isEmpty()
            )
        ) {
            return
        }
        val output = pendingOutput
        val requestedSound = pendingSound
        check(output == null || requestedSound == null) { "computer cannot own two pending world requests" }
        val input = pendingRedstoneInputs.firstOrNull()
        val effects =
            buildList {
                output?.let {
                    add(
                        ProgramRuntimeActorEffect.CompleteRedstoneOutput(
                            it.requestId,
                            it.packed,
                            it.result,
                        ),
                    )
                }
                requestedSound?.let {
                    add(ProgramRuntimeActorEffect.CompleteSound(it.requestId, it.result))
                }
                input?.let { add(ProgramRuntimeActorEffect.RedstoneInput(it)) }
            }
        lastAdvanceTick = worldTick
        val currentLifecycle = lifecycle
        val future = observe(service.turn(lease.endpoint, worldTick, effects), lifecycle)
        advance = future
        if (!future.isCompletedExceptionally) {
            pendingOutput = null
            pendingSound = null
            if (input != null) pendingRedstoneInputs.removeFirst()
            hostCompletionTick?.let { completedAt ->
                service.recordHostContinuationDelay((worldTick - completedAt).coerceAtLeast(0))
                hostCompletionTick = null
            }
        }
        future.whenComplete { reply, failure ->
            if (advance === future) advance = null
            if (currentLifecycle != lifecycle || closeResult != null) return@whenComplete
            if (failure != null) {
                failUnlessBusy(failure)
                return@whenComplete
            }
            acceptAdvanceReply(reply, currentLifecycle)
        }
    }

    /** The future completes after accepted work drains and native resources close, without requiring result pumping. */
    fun closeAsync(): CompletableFuture<Long?> {
        checkOwner()
        closeResult?.let { return it.copy() }
        lifecycle++
        pendingOutput = null
        pendingSound = null
        pendingRedstoneInputs.clear()
        hostCompletionTick = null
        val result = lease.closeAsync()
        closeResult = result
        publish(ProgramRuntimeState.Closed)
        return result.copy()
    }

    private fun acceptAdvanceReply(
        reply: ProgramRuntimeActorReply,
        currentLifecycle: Long,
    ) {
        when (val request = reply.value) {
            is ProgramRuntimeActorValue.RedstoneOutputRequested -> {
                val result =
                    try {
                        redstone.commitOutput(request.packed).also {
                            check(it != RedstoneCommitResult.Deferred) { "server redstone port must complete the world mutation" }
                        }
                    } catch (_: Exception) {
                        RedstoneCommitResult.Failed(HostFailureKind.INPUT_OUTPUT, 0)
                    }
                if (currentLifecycle != lifecycle || closeResult != null) return
                pendingOutput = PendingOutput(reply.requestId, request.packed, result)
                hostCompletionTick = lastObservedServerTick
            }

            is ProgramRuntimeActorValue.SoundRequested -> {
                val result =
                    try {
                        sound.emit(request.requests).also {
                            check(it != SoundCommitResult.Deferred) { "server sound port must complete the world mutation" }
                        }
                    } catch (_: Exception) {
                        SoundCommitResult.Failed(HostFailureKind.INPUT_OUTPUT, 0)
                    }
                if (currentLifecycle != lifecycle || closeResult != null) return
                pendingSound = PendingSound(reply.requestId, result)
                hostCompletionTick = lastObservedServerTick
            }

            else -> {
                Unit
            }
        }
    }

    private fun lifecycleRequest(
        command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
    ): CompletableFuture<ProgramRuntimeActorReply> {
        checkOwner()
        if (closeResult != null) return CompletableFuture.failedFuture(IllegalStateException("computer is closed"))
        val submitted = service.request(lease.endpoint, command)
        if (submitted.isCompletedExceptionally) return submitted
        lifecycle++
        advance = null
        pendingOutput = null
        pendingSound = null
        pendingRedstoneInputs.clear()
        hostCompletionTick = null
        return observe(submitted, lifecycle)
    }

    private fun send(command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand): CompletableFuture<ProgramRuntimeActorReply> {
        checkOwner()
        if (closeResult != null) return CompletableFuture.failedFuture(IllegalStateException("computer is closed"))
        return observe(service.request(lease.endpoint, command), lifecycle)
    }

    private fun observe(
        future: CompletableFuture<ProgramRuntimeActorReply>,
        version: Long,
    ): CompletableFuture<ProgramRuntimeActorReply> =
        future.thenApply { reply ->
            checkOwner()
            if (version == lifecycle && closeResult == null) {
                fileSystemGeneration = reply.fileSystemGeneration
                publish(reply.state)
            }
            reply
        }

    private fun failUnlessBusy(failure: Throwable) {
        val cause = if (failure is CompletionException) failure.cause ?: failure else failure
        if (
            cause is ProgramRuntimeActorRequestException &&
            (cause.submission == VmActorSubmission.MAILBOX_FULL || cause.submission == VmActorSubmission.PERMIT_PENDING)
        ) {
            return
        }
        publish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(cause.message ?: "actor request failed")))
    }

    private fun enqueueRedstoneInput(latest: Int) {
        val checkedLatest = RedstoneWire.requireInputPacket(latest)
        if (pendingRedstoneInputs.size < service.redstoneInputCapacity) {
            pendingRedstoneInputs.addLast(checkedLatest)
            return
        }
        val pending = pendingRedstoneInputs.removeLast()
        val changed = RedstoneWire.inputChangedMask(checkedLatest) or RedstoneWire.inputChangedMask(pending)
        pendingRedstoneInputs.addLast((checkedLatest and RedstoneWire.ALL_SIDES_MASK.inv()) or changed)
        service.recordCoalescedRedstoneInput()
    }

    private fun publish(next: ProgramRuntimeState) {
        if (state == next) return
        state = next
        stateSink(next)
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "computer carrier must run on its owning server thread" }

    private data class PendingOutput(
        val requestId: ProgramRuntimeRequestId,
        val packed: Int,
        val result: RedstoneCommitResult,
    )

    private data class PendingSound(
        val requestId: ProgramRuntimeRequestId,
        val result: SoundCommitResult,
    )
}
