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
 */

package ru.lazyhat.compukters.impl.ide.target

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeDeployResult
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeRevisionResult
import ru.lazyhat.compukters.ide.client.target.IdeSubmissionResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeVerificationTicket
import ru.lazyhat.compukters.ide.client.target.IdeVerifyResult
import ru.lazyhat.compukters.lang.runtime.vm.VmCanonicalLineException
import ru.lazyhat.compukters.lang.runtime.vm.VmCanonicalLineFailure
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentAdmissionException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentConflictException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentFileSystemException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentProfileChangedException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentWrongMachineException
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision as NativeExecutableRevision

sealed interface IdeUploadResult {
    data object Accepted : IdeUploadResult

    data class Failed(
        val failure: IdeTargetFailure,
    ) : IdeUploadResult
}

class IdeTargetDeploymentService(
    private val leases: IdeTargetLeaseService,
    private val ticketBytes: () -> ByteArray = { ByteArray(DEFAULT_TICKET_BYTES).also(TICKET_RANDOM::nextBytes) },
    private val maximumGlobalStagingBytes: Int = DEFAULT_GLOBAL_STAGING_BYTES,
    private val maximumChunkBytes: Int = DEFAULT_CHUNK_BYTES,
    private val maximumBytesPerPlayerTick: Int = DEFAULT_BYTES_PER_PLAYER_TICK,
    private val uploadTimeoutTicks: Long = DEFAULT_UPLOAD_TIMEOUT_TICKS,
    private val ticketLifetimeTicks: Long = DEFAULT_TICKET_LIFETIME_TICKS,
) : AutoCloseable {
    private val uploads = mutableMapOf<UUID, Upload>()
    private val verifying = mutableMapOf<UUID, Upload>()
    private val tickets = mutableMapOf<String, Ticket>()
    private val leaseObservation = leases.observeRemovals(::discard)
    private var reservedBytes = 0L
    private var closed = false

    init {
        require(maximumGlobalStagingBytes > 0) { "global staging limit must be positive" }
        require(maximumChunkBytes in 1..DEFAULT_CHUNK_BYTES) { "upload chunk limit must be within 1..$DEFAULT_CHUNK_BYTES bytes" }
        require(maximumBytesPerPlayerTick >= maximumChunkBytes) { "per-player upload rate must admit at least one maximum chunk" }
        require(uploadTimeoutTicks > 0) { "upload timeout must be positive" }
        require(ticketLifetimeTicks > 0) { "ticket lifetime must be positive" }
    }

    fun beginUpload(
        player: UUID,
        target: IdeAttachedTarget,
        hash: Hash256,
        bytes: Int,
        tick: Long,
    ): IdeUploadResult {
        checkOpen()
        requireTick(tick)
        expire(tick)
        val resolved =
            leases.access(player, target, tick) ?: return failed(IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable")
        if (bytes <= 0 || bytes > resolved.profile.limits.artifactBytes) {
            return failed(IdeTargetFailureKind.Upload, "Artifact exceeds the target upload limit")
        }
        val previous = uploads[player]
        val replacementBytes = reservedBytes - (previous?.bytes?.size ?: 0) + bytes
        if (replacementBytes > maximumGlobalStagingBytes) {
            return failed(IdeTargetFailureKind.Upload, "Server upload staging quota is exhausted")
        }
        removeUpload(player)
        uploads[player] =
            Upload(
                target,
                hash,
                ByteArray(bytes),
                received = 0,
                rateTick = tick,
                rateBytes = 0,
                expiresAt = deadline(tick, uploadTimeoutTicks),
            )
        reservedBytes += bytes
        return IdeUploadResult.Accepted
    }

    fun appendUpload(
        player: UUID,
        target: IdeAttachedTarget,
        offset: Int,
        bytes: ByteArray,
        tick: Long,
    ): IdeUploadResult {
        checkOpen()
        requireTick(tick)
        expire(tick)
        if (leases.access(player, target, tick) == null) {
            removeUpload(player)
            return failed(IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable")
        }
        val upload = uploads[player] ?: return failed(IdeTargetFailureKind.Upload, "No artifact upload is active")
        if (upload.target != target || bytes.isEmpty() || bytes.size > maximumChunkBytes || offset != upload.received) {
            return failed(IdeTargetFailureKind.Upload, "Artifact upload chunk is invalid or out of order")
        }
        if (offset > upload.bytes.size - bytes.size) {
            return failed(IdeTargetFailureKind.Upload, "Artifact upload exceeds its declared size")
        }
        val consumedThisTick = if (upload.rateTick == tick) upload.rateBytes else 0
        if (bytes.size > maximumBytesPerPlayerTick - consumedThisTick) {
            return failed(IdeTargetFailureKind.Upload, "Per-player upload rate is exceeded")
        }
        bytes.copyInto(upload.bytes, destinationOffset = offset)
        uploads[player] =
            upload.copy(
                received = offset + bytes.size,
                rateTick = tick,
                rateBytes = consumedThisTick + bytes.size,
                expiresAt = deadline(tick, uploadTimeoutTicks),
            )
        return IdeUploadResult.Accepted
    }

    suspend fun verify(
        player: UUID,
        target: IdeAttachedTarget,
        tick: Long,
    ): IdeVerifyResult {
        checkOpen()
        requireTick(tick)
        expire(tick)
        if (player in verifying) return verifyFailed(IdeTargetFailureKind.Admission, "Artifact verification is already pending")
        val resolved =
            leases.access(player, target, tick)
                ?: return verifyFailed(IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable")
        val upload = uploads[player] ?: return verifyFailed(IdeTargetFailureKind.Upload, "No complete artifact upload is active")
        if (upload.target != target || upload.received != upload.bytes.size) {
            return verifyFailed(IdeTargetFailureKind.Upload, "Artifact upload is incomplete")
        }
        removeUpload(player)
        if (sha256(upload.bytes) != upload.hash) {
            return verifyFailed(IdeTargetFailureKind.Upload, "Artifact hash does not match the declared hash")
        }
        verifying[player] = upload
        reservedBytes += upload.bytes.size
        val candidate =
            try {
                resolved.deployment.verifyForDeploy(upload.bytes)
                    ?: return verifyFailed(IdeTargetFailureKind.TargetLost, "Target VM is unavailable")
            } catch (_: VmVerificationException) {
                return verifyFailed(IdeTargetFailureKind.Verification, "Target VM rejected the artifact")
            } catch (_: VmDeploymentAdmissionException) {
                return verifyFailed(IdeTargetFailureKind.Admission, "Target VM rejected deployment admission")
            } finally {
                verifying.remove(player)
                reservedBytes -= upload.bytes.size
            }
        if (closed || leases.access(player, target, tick) !== resolved) {
            candidate.close()
            return verifyFailed(IdeTargetFailureKind.TargetLost, "Target lease ended during verification")
        }
        return try {
            val rawTicket = ticketBytes()
            val ticket = IdeVerificationTicket.of(rawTicket, target, upload.hash, upload.bytes.size)
            val key = key(rawTicket)
            check(key !in tickets) { "verification ticket generator produced a duplicate" }
            tickets[key] =
                Ticket(
                    player,
                    target,
                    resolved.machineIdentity,
                    upload.hash,
                    upload.bytes.size,
                    candidate,
                    deadline(tick, ticketLifetimeTicks),
                )
            IdeVerifyResult.Verified(ticket)
        } catch (error: RuntimeException) {
            candidate.close()
            throw error
        }
    }

    suspend fun executableRevision(
        player: UUID,
        target: IdeAttachedTarget,
        path: IdeDeploymentPath,
        tick: Long,
    ): IdeRevisionResult {
        checkOpen()
        requireTick(tick)
        expire(tick)
        val resolved =
            leases.access(player, target, tick)
                ?: return revisionFailed(IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable")
        if (!target.capabilities.writableFileSystem) {
            return revisionFailed(IdeTargetFailureKind.Unsupported, "Target has no writable filesystem")
        }
        val revision =
            resolved.deployment.executableRevision(path.value)
                ?: return revisionFailed(IdeTargetFailureKind.TargetLost, "Target VM is unavailable")
        return IdeRevisionResult.Observed(revision.toIde())
    }

    suspend fun deploy(
        player: UUID,
        target: IdeAttachedTarget,
        path: IdeDeploymentPath,
        expected: IdeExecutableRevision,
        ticket: IdeVerificationTicket,
        tick: Long,
    ): IdeDeployResult {
        checkOpen()
        requireTick(tick)
        expire(tick)
        val resolved =
            leases.access(player, target, tick)
                ?: return deployFailed(IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable")
        if (!target.capabilities.writableFileSystem) {
            return deployFailed(IdeTargetFailureKind.Unsupported, "Target has no writable filesystem")
        }
        val key = key(ticket.bytes())
        val stored = tickets[key]
        if (
            stored == null ||
            stored.player != player ||
            stored.target != target ||
            stored.machineIdentity != resolved.machineIdentity ||
            stored.artifactHash != ticket.artifactHash ||
            stored.artifactBytes != ticket.artifactBytes ||
            ticket.targetId != target.id ||
            ticket.profileId != target.profile
        ) {
            return deployFailed(IdeTargetFailureKind.Verification, "Verification ticket is invalid for this target")
        }
        tickets.remove(key)
        return try {
            val revision =
                resolved.deployment.deploy(path.value, expected.toVm(), stored.candidate)
                    ?: return deployFailed(IdeTargetFailureKind.TargetLost, "Target VM is unavailable")
            when (val value = revision.toIde()) {
                is IdeExecutableRevision.Present -> IdeDeployResult.Deployed(value)
                IdeExecutableRevision.Absent -> deployFailed(IdeTargetFailureKind.FileSystem, "Deployment did not create an executable")
            }
        } catch (_: VmDeploymentConflictException) {
            val actual =
                resolved.deployment.executableRevision(path.value)?.toIde()
                    ?: return deployFailed(IdeTargetFailureKind.TargetLost, "Target VM is unavailable")
            IdeDeployResult.StaleRevision(actual)
        } catch (_: VmDeploymentWrongMachineException) {
            deployFailed(IdeTargetFailureKind.Verification, "Verification ticket belongs to another VM")
        } catch (_: VmDeploymentProfileChangedException) {
            deployFailed(IdeTargetFailureKind.Profile, "Target execution profile changed")
        } catch (_: VmDeploymentFileSystemException) {
            deployFailed(IdeTargetFailureKind.FileSystem, "Target filesystem rejected deployment")
        } catch (_: VmDeploymentAdmissionException) {
            deployFailed(IdeTargetFailureKind.Admission, "Target VM rejected deployment admission")
        } finally {
            stored.candidate.close()
        }
    }

    suspend fun submitCanonicalLine(
        player: UUID,
        target: IdeAttachedTarget,
        line: CharArray,
        tick: Long,
    ): IdeSubmissionResult {
        checkOpen()
        requireTick(tick)
        expire(tick)
        val resolved =
            leases.access(player, target, tick)
                ?: return submissionFailed(IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable")
        if (!target.capabilities.canonicalInput) {
            return submissionFailed(IdeTargetFailureKind.Unsupported, "Target has no canonical input")
        }
        return try {
            if (resolved.deployment.submitCanonicalLine(line.copyOf())) {
                IdeSubmissionResult.Submitted
            } else {
                submissionFailed(IdeTargetFailureKind.TargetLost, "Target VM is unavailable")
            }
        } catch (error: VmCanonicalLineException) {
            submissionFailed(error.failure.toIdeFailure(), "Target rejected canonical input: ${error.failure.name.lowercase()}")
        }
    }

    fun expire(tick: Long) {
        checkOpen()
        requireTick(tick)
        uploads
            .filterValues { upload -> tick >= upload.expiresAt }
            .keys
            .toList()
            .forEach(::removeUpload)
        tickets.keys.toList().forEach { key ->
            val ticket = tickets[key] ?: return@forEach
            if (tick >= ticket.expiresAt || leases.access(ticket.player, ticket.target, tick) == null) removeTicket(key)
        }
    }

    override fun close() {
        if (closed) return
        uploads.keys.toList().forEach(::removeUpload)
        tickets.keys.toList().forEach(::removeTicket)
        leaseObservation.close()
        closed = true
    }

    private fun discard(
        player: UUID,
        target: IdeAttachedTarget,
    ) {
        if (uploads[player]?.target == target) removeUpload(player)
        tickets
            .filterValues { ticket -> ticket.player == player && ticket.target == target }
            .keys
            .toList()
            .forEach(::removeTicket)
    }

    private fun removeUpload(player: UUID) {
        val removed = uploads.remove(player) ?: return
        reservedBytes -= removed.bytes.size
    }

    private fun removeTicket(key: String) {
        tickets.remove(key)?.candidate?.close()
    }

    private fun checkOpen() = check(!closed) { "target deployment service is closed" }

    private fun requireTick(tick: Long) = require(tick >= 0) { "server tick must not be negative" }

    private data class Upload(
        val target: IdeAttachedTarget,
        val hash: Hash256,
        val bytes: ByteArray,
        val received: Int,
        val rateTick: Long,
        val rateBytes: Int,
        val expiresAt: Long,
    )

    private data class Ticket(
        val player: UUID,
        val target: IdeAttachedTarget,
        val machineIdentity: String,
        val artifactHash: Hash256,
        val artifactBytes: Int,
        val candidate: ProgramDeploymentCandidate,
        val expiresAt: Long,
    )

    private companion object {
        const val DEFAULT_CHUNK_BYTES = 32 * 1024
        const val DEFAULT_GLOBAL_STAGING_BYTES = 64 * 1024 * 1024
        const val DEFAULT_BYTES_PER_PLAYER_TICK = 256 * 1024
        const val DEFAULT_TICKET_BYTES = 32
        const val DEFAULT_UPLOAD_TIMEOUT_TICKS = 200L
        const val DEFAULT_TICKET_LIFETIME_TICKS = 100L
        val TICKET_RANDOM = SecureRandom()

        fun deadline(
            tick: Long,
            lifetime: Long,
        ): Long =
            try {
                Math.addExact(tick, lifetime)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }

        fun sha256(bytes: ByteArray): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun key(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun failed(
            kind: IdeTargetFailureKind,
            detail: String,
        ) = IdeUploadResult.Failed(IdeTargetFailure(kind, detail))

        fun verifyFailed(
            kind: IdeTargetFailureKind,
            detail: String,
        ) = IdeVerifyResult.Failed(IdeTargetFailure(kind, detail))

        fun revisionFailed(
            kind: IdeTargetFailureKind,
            detail: String,
        ) = IdeRevisionResult.Failed(IdeTargetFailure(kind, detail))

        fun deployFailed(
            kind: IdeTargetFailureKind,
            detail: String,
        ) = IdeDeployResult.Failed(IdeTargetFailure(kind, detail), retryable = false)

        fun submissionFailed(
            kind: IdeTargetFailureKind,
            detail: String,
        ) = IdeSubmissionResult.Failed(IdeTargetFailure(kind, detail))
    }
}

private fun NativeExecutableRevision.toIde(): IdeExecutableRevision =
    when (this) {
        NativeExecutableRevision.Absent -> IdeExecutableRevision.Absent
        is NativeExecutableRevision.Present -> IdeExecutableRevision.Present(generation)
    }

private fun IdeExecutableRevision.toVm(): NativeExecutableRevision =
    when (this) {
        IdeExecutableRevision.Absent -> NativeExecutableRevision.Absent
        is IdeExecutableRevision.Present -> NativeExecutableRevision.Present(generation)
    }

private fun VmCanonicalLineFailure.toIdeFailure(): IdeTargetFailureKind =
    when (this) {
        VmCanonicalLineFailure.NO_PENDING_READ -> IdeTargetFailureKind.InputUnavailable

        VmCanonicalLineFailure.INPUT_BUSY -> IdeTargetFailureKind.InputBusy

        VmCanonicalLineFailure.PARTIAL_INPUT -> IdeTargetFailureKind.InputPartial

        VmCanonicalLineFailure.LINE_TOO_LONG -> IdeTargetFailureKind.InputTooLong

        VmCanonicalLineFailure.UNSUPPORTED_CODE_UNIT,
        VmCanonicalLineFailure.TERMINAL,
        VmCanonicalLineFailure.RESUME,
        -> IdeTargetFailureKind.Other
    }
