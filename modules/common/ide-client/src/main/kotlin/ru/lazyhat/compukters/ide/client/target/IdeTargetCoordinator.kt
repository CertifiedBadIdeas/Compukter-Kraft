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

package ru.lazyhat.compukters.ide.client.target

import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.controller.IdeControllerClock
import ru.lazyhat.compukters.ide.client.files.IdeComputerFileAccess
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class IdeTargetCoordinator(
    private val port: IdeTargetPort,
    private val clock: IdeControllerClock,
    limits: IdeClientLimits = IdeClientLimits(),
) : AutoCloseable,
    IdeComputerFileAccess {
    private val owner = Thread.currentThread()
    private val events = ArrayBlockingQueue<TargetEvent>(limits.eventQueueCapacity)
    private val overflow = AtomicBoolean()
    private var generation = 0L
    private var operationGeneration = 0L
    private var nextOperationId = 1L
    private var current: IdeTargetState = IdeTargetState.LocalOnly
    private var attached: IdeAttachedTarget? = null
    private var heartbeatPending = false
    private var lastHeartbeatMillis = 0L
    private var cachedTicket: IdeVerificationTicket? = null
    private val approvedRevisions = mutableMapOf<IdeDeploymentPath, IdeExecutableRevision.Present>()
    private var confirmation: PendingDeployment? = null
    private var closed = false

    fun state(): IdeTargetState {
        checkOwner()
        return current
    }

    fun attachedTarget(): IdeAttachedTarget? {
        checkOwner()
        return attached
    }

    override fun stat(path: IdeTargetVirtualPath): CompletableFuture<IdeFileStatResult> {
        val target = requireReadableFileSystem()
        return port.fileStat(target, path)
    }

    override fun list(
        path: IdeTargetVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ): CompletableFuture<IdeFileListResult> {
        val target = requireReadableFileSystem()
        return port.fileList(target, path, startAfter, maximumEntries)
    }

    override fun read(
        path: IdeTargetVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): CompletableFuture<IdeFileReadResult> {
        val target = requireReadableFileSystem()
        return port.fileRead(target, path, offset, maximumBytes, expectedGeneration)
    }

    fun attach(claim: IdeTargetClaim) {
        checkActive()
        releaseAttached()
        val eventGeneration = advanceGeneration()
        val operationId = nextOperationId++
        current = IdeTargetState.Attaching(operationId)
        try {
            port.attach(claim).whenComplete { result, failure ->
                enqueue(TargetEvent.Attach(eventGeneration, result, failure))
            }
        } catch (failure: Throwable) {
            enqueue(TargetEvent.Attach(eventGeneration, null, failure))
        }
    }

    fun detach() {
        checkOwner()
        if (closed) return
        advanceGeneration()
        releaseAttached()
        current = IdeTargetState.LocalOnly
    }

    fun verify(artifact: IdeTargetArtifact) {
        val target = requireTarget()
        beginVerification(target, artifact, VerificationIntent.VerifyOnly)
    }

    fun deploy(
        artifact: IdeTargetArtifact,
        path: IdeDeploymentPath,
    ) = startDeployment(artifact, path, null)

    fun run(
        artifact: IdeTargetArtifact,
        path: IdeDeploymentPath,
        strategy: IdeLaunchStrategy = IdeLaunchStrategy.CanonicalInput,
    ) {
        val target = requireTarget()
        if (strategy == IdeLaunchStrategy.CanonicalInput && !target.capabilities.canonicalInput) {
            current = IdeTargetState.Failed(target, unsupportedFailure("Target canonical input is unavailable"))
            return
        }
        startDeployment(artifact, path, strategy)
    }

    private fun startDeployment(
        artifact: IdeTargetArtifact,
        path: IdeDeploymentPath,
        launch: IdeLaunchStrategy?,
    ) {
        val target = requireTarget()
        if (!target.capabilities.writableFileSystem) {
            current = IdeTargetState.Failed(target, unsupportedFailure("Target file system is not writable"))
            return
        }
        val ticket = cachedTicket?.takeIf { it.matches(target, artifact) }
        if (ticket == null) {
            beginVerification(target, artifact, VerificationIntent.Deploy(path, launch))
        } else {
            beginObservation(target, artifact, ticket, path, launch, nextOperationGeneration())
        }
    }

    fun confirmDeployment() {
        checkActive()
        val pending = confirmation ?: error("no deployment confirmation is pending")
        check(current is IdeTargetState.ConfirmationRequired) { "no deployment confirmation is pending" }
        confirmation = null
        beginDeployment(pending, pending.expected, nextOperationGeneration())
    }

    fun cancelDeployment() {
        checkActive()
        check(current is IdeTargetState.ConfirmationRequired && confirmation != null) { "no deployment confirmation is pending" }
        confirmation = null
        nextOperationGeneration()
        current = IdeTargetState.Attached(checkNotNull(attached))
    }

    fun tick() {
        checkActive()
        if (overflow.compareAndSet(true, false)) {
            val target = attached
            advanceGeneration()
            releaseAttached()
            current =
                if (target == null) {
                    IdeTargetState.Failed(null, protocolFailure())
                } else {
                    IdeTargetState.Detached(protocolFailure())
                }
            events.clear()
            return
        }
        while (true) accept(events.poll() ?: break)
        requestHeartbeatIfDue()
    }

    override fun close() {
        checkOwner()
        if (closed) return
        advanceGeneration()
        releaseAttached()
        events.clear()
        current = IdeTargetState.LocalOnly
        closed = true
    }

    private fun accept(event: TargetEvent) {
        if (event.generation != generation) {
            if (event is TargetEvent.Attach) {
                val target = (event.result as? IdeAttachResult.Attached)?.target
                if (target != null) runCatching { port.detach(target) }
            }
            return
        }
        when (event) {
            is TargetEvent.Attach -> acceptAttach(event)
            is TargetEvent.Heartbeat -> acceptHeartbeat(event)
            is TargetEvent.Verify -> if (event.operationGeneration == operationGeneration) acceptVerify(event)
            is TargetEvent.Revision -> if (event.operationGeneration == operationGeneration) acceptRevision(event)
            is TargetEvent.Deploy -> if (event.operationGeneration == operationGeneration) acceptDeploy(event)
            is TargetEvent.Submission -> if (event.operationGeneration == operationGeneration) acceptSubmission(event)
        }
    }

    private fun acceptAttach(event: TargetEvent.Attach) {
        val result = event.result
        if (event.failure != null || result == null) {
            current = IdeTargetState.Failed(null, operationFailure())
            return
        }
        when (result) {
            is IdeAttachResult.Attached -> {
                attached = result.target
                lastHeartbeatMillis = clock.nowMillis().coerceAtLeast(0)
                current = IdeTargetState.Attached(result.target)
            }

            is IdeAttachResult.Rejected -> {
                current = IdeTargetState.Failed(null, result.failure)
            }
        }
    }

    private fun acceptHeartbeat(event: TargetEvent.Heartbeat) {
        heartbeatPending = false
        val target = attached ?: return
        val result = event.result
        if (event.failure != null || result == null) {
            loseTarget(operationFailure())
            return
        }
        when (result) {
            IdeHeartbeatResult.Alive -> lastHeartbeatMillis = clock.nowMillis().coerceAtLeast(0)
            is IdeHeartbeatResult.Lost -> loseTarget(result.failure)
        }
        if (attached != target) heartbeatPending = false
    }

    private fun acceptVerify(event: TargetEvent.Verify) {
        val result = event.result
        if (event.failure != null || result == null) {
            current = IdeTargetState.Failed(event.target, operationFailure())
            return
        }
        when (result) {
            is IdeVerifyResult.Failed -> {
                current = IdeTargetState.Failed(event.target, result.failure)
            }

            is IdeVerifyResult.Verified -> {
                if (!result.ticket.matches(event.target, event.artifact)) {
                    cachedTicket = null
                    current = IdeTargetState.Failed(event.target, protocolFailure("Verification ticket scope mismatch"))
                    return
                }
                cachedTicket = result.ticket
                when (val intent = event.intent) {
                    VerificationIntent.VerifyOnly -> {
                        current = IdeTargetState.Verified(event.target, event.artifact.hash)
                    }

                    is VerificationIntent.Deploy -> {
                        beginObservation(
                            event.target,
                            event.artifact,
                            result.ticket,
                            intent.path,
                            intent.launch,
                            event.operationGeneration,
                        )
                    }
                }
            }
        }
    }

    private fun acceptRevision(event: TargetEvent.Revision) {
        val result = event.result
        if (event.failure != null || result == null) {
            current = IdeTargetState.Failed(event.pending.target, operationFailure())
            return
        }
        when (result) {
            is IdeRevisionResult.Failed -> {
                current = IdeTargetState.Failed(event.pending.target, result.failure)
            }

            is IdeRevisionResult.Observed -> {
                val revision = result.revision
                if (revision is IdeExecutableRevision.Present && approvedRevisions[event.pending.path] != revision) {
                    val pending = event.pending.copy(expected = revision)
                    confirmation = pending
                    current = IdeTargetState.ConfirmationRequired(pending.target, pending.path, revision)
                } else {
                    beginDeployment(event.pending, revision, event.operationGeneration)
                }
            }
        }
    }

    private fun acceptDeploy(event: TargetEvent.Deploy) {
        val result = event.result
        if (event.failure != null || result == null) {
            current = IdeTargetState.Failed(event.pending.target, operationFailure())
            return
        }
        when (result) {
            is IdeDeployResult.Deployed -> {
                cachedTicket = null
                confirmation = null
                approvedRevisions[event.pending.path] = result.revision
                val deployed = IdeTargetState.Deployed(event.pending.target, event.pending.path, result.revision)
                when (event.pending.launch) {
                    null -> current = deployed
                    IdeLaunchStrategy.CanonicalInput -> beginSubmission(deployed, event.operationGeneration)
                }
            }

            is IdeDeployResult.Failed -> {
                if (!result.retryable) cachedTicket = null
                current = IdeTargetState.Failed(event.pending.target, result.failure)
            }

            is IdeDeployResult.StaleRevision -> {
                val actual = result.actual
                if (actual is IdeExecutableRevision.Present) {
                    val pending = event.pending.copy(expected = actual)
                    confirmation = pending
                    current = IdeTargetState.ConfirmationRequired(pending.target, pending.path, actual)
                } else {
                    current =
                        IdeTargetState.Failed(
                            event.pending.target,
                            IdeTargetFailure(IdeTargetFailureKind.Conflict, "Executable revision changed; retry deployment"),
                        )
                }
            }
        }
    }

    private fun acceptSubmission(event: TargetEvent.Submission) {
        val result = event.result
        if (event.failure != null || result == null) {
            current = IdeTargetState.Failed(event.deployed.target, operationFailure(), event.deployed)
            return
        }
        current =
            when (result) {
                IdeSubmissionResult.Submitted -> {
                    IdeTargetState.CommandSubmitted(
                        event.deployed.target,
                        event.deployed.path,
                        event.deployed.revision,
                    )
                }

                is IdeSubmissionResult.Failed -> {
                    IdeTargetState.Failed(event.deployed.target, result.failure, event.deployed)
                }
            }
    }

    private fun beginVerification(
        target: IdeAttachedTarget,
        artifact: IdeTargetArtifact,
        intent: VerificationIntent,
    ) {
        val operation = nextOperationGeneration()
        confirmation = null
        cachedTicket = null
        current = IdeTargetState.Uploading(target, artifact.hash)
        try {
            port.verify(target, artifact).whenComplete { result, failure ->
                enqueue(TargetEvent.Verify(generation, operation, target, artifact, intent, result, failure))
            }
        } catch (failure: Throwable) {
            enqueue(TargetEvent.Verify(generation, operation, target, artifact, intent, null, failure))
        }
    }

    private fun beginObservation(
        target: IdeAttachedTarget,
        artifact: IdeTargetArtifact,
        ticket: IdeVerificationTicket,
        path: IdeDeploymentPath,
        launch: IdeLaunchStrategy?,
        operation: Long,
    ) {
        confirmation = null
        val pending = PendingDeployment(target, artifact, ticket, path, IdeExecutableRevision.Absent, launch)
        current = IdeTargetState.Observing(target, path)
        try {
            port.executableRevision(target, path).whenComplete { result, failure ->
                enqueue(TargetEvent.Revision(generation, operation, pending, result, failure))
            }
        } catch (failure: Throwable) {
            enqueue(TargetEvent.Revision(generation, operation, pending, null, failure))
        }
    }

    private fun beginDeployment(
        pending: PendingDeployment,
        expected: IdeExecutableRevision,
        operation: Long,
    ) {
        val exact = pending.copy(expected = expected)
        current = IdeTargetState.Deploying(exact.target, exact.path)
        try {
            port.deploy(exact.target, exact.ticket, exact.path, expected).whenComplete { result, failure ->
                enqueue(TargetEvent.Deploy(generation, operation, exact, result, failure))
            }
        } catch (failure: Throwable) {
            enqueue(TargetEvent.Deploy(generation, operation, exact, null, failure))
        }
    }

    private fun beginSubmission(
        deployed: IdeTargetState.Deployed,
        operation: Long,
    ) {
        current = IdeTargetState.Submitting(deployed.target, deployed.path, deployed.revision)
        try {
            port.submitCanonicalLine(deployed.target, deployed.path.value.toCharArray()).whenComplete { result, failure ->
                enqueue(TargetEvent.Submission(generation, operation, deployed, result, failure))
            }
        } catch (failure: Throwable) {
            enqueue(TargetEvent.Submission(generation, operation, deployed, null, failure))
        }
    }

    private fun requestHeartbeatIfDue() {
        val target = attached ?: return
        if (heartbeatPending) return
        val now = clock.nowMillis().coerceAtLeast(0)
        if (now - lastHeartbeatMillis < HEARTBEAT_INTERVAL_MILLIS) return
        heartbeatPending = true
        val eventGeneration = generation
        try {
            port.heartbeat(target).whenComplete { result, failure ->
                enqueue(TargetEvent.Heartbeat(eventGeneration, result, failure))
            }
        } catch (failure: Throwable) {
            enqueue(TargetEvent.Heartbeat(eventGeneration, null, failure))
        }
    }

    private fun loseTarget(failure: IdeTargetFailure) {
        advanceGeneration()
        releaseAttached()
        current = IdeTargetState.Detached(failure)
    }

    private fun releaseAttached() {
        val target = attached
        attached = null
        heartbeatPending = false
        nextOperationGeneration()
        cachedTicket = null
        approvedRevisions.clear()
        confirmation = null
        if (target != null) runCatching { port.detach(target) }
    }

    private fun advanceGeneration(): Long {
        generation = if (generation == Long.MAX_VALUE) 1 else generation + 1
        return generation
    }

    private fun nextOperationGeneration(): Long {
        operationGeneration = if (operationGeneration == Long.MAX_VALUE) 1 else operationGeneration + 1
        return operationGeneration
    }

    private fun enqueue(event: TargetEvent) {
        if (!events.offer(event)) overflow.set(true)
    }

    private fun checkActive() {
        checkOwner()
        check(!closed) { "target coordinator is closed" }
    }

    private fun requireTarget(): IdeAttachedTarget {
        checkActive()
        return checkNotNull(attached) { "no target is attached" }
    }

    private fun requireReadableFileSystem(): IdeAttachedTarget {
        val target = requireTarget()
        check(target.capabilities.readableFileSystem) { "target filesystem is unavailable" }
        return target
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "target coordinator must run on its owner thread" }

    private fun operationFailure(): IdeTargetFailure = IdeTargetFailure(IdeTargetFailureKind.Other, "Target operation failed")

    private fun protocolFailure(detail: String = "Target event queue overflow"): IdeTargetFailure =
        IdeTargetFailure(IdeTargetFailureKind.Protocol, detail)

    private fun unsupportedFailure(detail: String): IdeTargetFailure = IdeTargetFailure(IdeTargetFailureKind.Unsupported, detail)

    private data class PendingDeployment(
        val target: IdeAttachedTarget,
        val artifact: IdeTargetArtifact,
        val ticket: IdeVerificationTicket,
        val path: IdeDeploymentPath,
        val expected: IdeExecutableRevision,
        val launch: IdeLaunchStrategy?,
    )

    private sealed interface VerificationIntent {
        data object VerifyOnly : VerificationIntent

        data class Deploy(
            val path: IdeDeploymentPath,
            val launch: IdeLaunchStrategy?,
        ) : VerificationIntent
    }

    private sealed interface TargetEvent {
        val generation: Long

        data class Attach(
            override val generation: Long,
            val result: IdeAttachResult?,
            val failure: Throwable?,
        ) : TargetEvent

        data class Heartbeat(
            override val generation: Long,
            val result: IdeHeartbeatResult?,
            val failure: Throwable?,
        ) : TargetEvent

        data class Verify(
            override val generation: Long,
            val operationGeneration: Long,
            val target: IdeAttachedTarget,
            val artifact: IdeTargetArtifact,
            val intent: VerificationIntent,
            val result: IdeVerifyResult?,
            val failure: Throwable?,
        ) : TargetEvent

        data class Revision(
            override val generation: Long,
            val operationGeneration: Long,
            val pending: PendingDeployment,
            val result: IdeRevisionResult?,
            val failure: Throwable?,
        ) : TargetEvent

        data class Deploy(
            override val generation: Long,
            val operationGeneration: Long,
            val pending: PendingDeployment,
            val result: IdeDeployResult?,
            val failure: Throwable?,
        ) : TargetEvent

        data class Submission(
            override val generation: Long,
            val operationGeneration: Long,
            val deployed: IdeTargetState.Deployed,
            val result: IdeSubmissionResult?,
            val failure: Throwable?,
        ) : TargetEvent
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MILLIS = 5_000L
    }
}
