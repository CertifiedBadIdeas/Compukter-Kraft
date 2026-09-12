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

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.ide.client.target.IdeAttachResult
import ru.lazyhat.compukters.ide.client.target.IdeDeployResult
import ru.lazyhat.compukters.ide.client.target.IdeFileListResult
import ru.lazyhat.compukters.ide.client.target.IdeFileReadResult
import ru.lazyhat.compukters.ide.client.target.IdeFileStatResult
import ru.lazyhat.compukters.ide.client.target.IdeHeartbeatResult
import ru.lazyhat.compukters.ide.client.target.IdeRevisionResult
import ru.lazyhat.compukters.ide.client.target.IdeSubmissionResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeVerificationTicket
import ru.lazyhat.compukters.ide.client.target.IdeVerifyResult
import java.util.UUID

class IdeTargetRequestProcessor(
    private val leases: IdeTargetLeaseService,
    private val deployments: IdeTargetDeploymentService,
    private val files: IdeTargetFileSystemService = IdeTargetFileSystemService(leases),
) {
    suspend fun handle(
        player: UUID,
        request: IdeTargetRequest,
        tick: Long,
    ): IdeTargetReply =
        when (request) {
            is IdeTargetRequest.Attach -> {
                attach(player, request, tick)
            }

            is IdeTargetRequest.BeginUpload -> {
                withTarget(player, request.target, tick) { target ->
                    when (val result = deployments.beginUpload(player, target, request.artifactHash, request.bytes, tick)) {
                        IdeUploadResult.Accepted -> IdeTargetReply.UploadAccepted
                        is IdeUploadResult.Failed -> result.failure.failed()
                    }
                }
            }

            is IdeTargetRequest.UploadChunk -> {
                withTarget(player, request.target, tick) { target ->
                    when (val result = deployments.appendUpload(player, target, request.offset, request.bytes.toByteArray(), tick)) {
                        IdeUploadResult.Accepted -> IdeTargetReply.UploadAccepted
                        is IdeUploadResult.Failed -> result.failure.failed()
                    }
                }
            }

            is IdeTargetRequest.Verify -> {
                withTarget(player, request.target, tick) { target ->
                    when (val result = deployments.verify(player, target, tick)) {
                        is IdeVerifyResult.Verified -> {
                            val ticket = result.ticket
                            IdeTargetReply.Verified(
                                BinaryValue.of(ticket.bytes()),
                                IdeTargetReference(ticket.targetId, ticket.profileId),
                                ticket.artifactHash,
                                ticket.artifactBytes,
                            )
                        }

                        is IdeVerifyResult.Failed -> {
                            result.failure.failed()
                        }
                    }
                }
            }

            is IdeTargetRequest.ExecutableRevision -> {
                withTarget(player, request.target, tick) { target ->
                    when (val result = deployments.executableRevision(player, target, request.path, tick)) {
                        is IdeRevisionResult.Observed -> IdeTargetReply.RevisionObserved(result.revision)
                        is IdeRevisionResult.Failed -> result.failure.failed()
                    }
                }
            }

            is IdeTargetRequest.Deploy -> {
                withTarget(player, request.target, tick) { target ->
                    val ticket =
                        IdeVerificationTicket.of(
                            request.ticket.toByteArray(),
                            target,
                            request.artifactHash,
                            request.artifactBytes,
                        )
                    when (val result = deployments.deploy(player, target, request.path, request.expected, ticket, tick)) {
                        is IdeDeployResult.Deployed -> IdeTargetReply.Deployed(result.revision)
                        is IdeDeployResult.StaleRevision -> IdeTargetReply.StaleRevision(result.actual)
                        is IdeDeployResult.Failed -> IdeTargetReply.Failed(result.failure, result.retryable)
                    }
                }
            }

            is IdeTargetRequest.SubmitCanonicalLine -> {
                withTarget(player, request.target, tick) { target ->
                    when (val result = deployments.submitCanonicalLine(player, target, request.line.chars(), tick)) {
                        IdeSubmissionResult.Submitted -> IdeTargetReply.Submitted
                        is IdeSubmissionResult.Failed -> result.failure.failed()
                    }
                }
            }

            is IdeTargetRequest.Heartbeat -> {
                withTarget(player, request.target, tick) { target ->
                    when (val result = leases.heartbeat(player, target, tick)) {
                        IdeHeartbeatResult.Alive -> IdeTargetReply.Alive
                        is IdeHeartbeatResult.Lost -> result.failure.failed()
                    }
                }
            }

            is IdeTargetRequest.Detach -> {
                withTarget(player, request.target, tick) { target ->
                    leases.detach(player, target)
                    IdeTargetReply.Detached
                }
            }

            is IdeTargetRequest.FileStat -> {
                withTarget(player, request.target, tick) { target ->
                    when (val result = files.stat(player, target, request.path, tick)) {
                        is IdeFileStatResult.Observed -> IdeTargetReply.FileStatObserved(result.stat)
                        is IdeFileStatResult.Failed -> result.failure.failed()
                    }
                }
            }

            is IdeTargetRequest.FileList -> {
                withTarget(player, request.target, tick) { target ->
                    when (val result = files.list(player, target, request.path, request.startAfter, request.maximumEntries, tick)) {
                        is IdeFileListResult.Listed -> IdeTargetReply.FileListed(result.listing)
                        is IdeFileListResult.Failed -> result.failure.failed()
                    }
                }
            }

            is IdeTargetRequest.FileRead -> {
                withTarget(player, request.target, tick) { target ->
                    when (
                        val result =
                            files.read(
                                player,
                                target,
                                request.path,
                                request.offset,
                                request.maximumBytes,
                                request.expectedGeneration,
                                tick,
                            )
                    ) {
                        is IdeFileReadResult.Read -> IdeTargetReply.FileRead(result.chunk)
                        is IdeFileReadResult.Failed -> result.failure.failed()
                    }
                }
            }
        }

    private fun attach(
        player: UUID,
        request: IdeTargetRequest.Attach,
        tick: Long,
    ): IdeTargetReply =
        when (val result = leases.attach(player, IdeTargetClaim.of(request.claim.toByteArray()), tick)) {
            is IdeAttachResult.Attached -> IdeTargetReply.Attached(result.target)
            is IdeAttachResult.Rejected -> result.failure.failed()
        }

    private suspend inline fun withTarget(
        player: UUID,
        reference: IdeTargetReference,
        tick: Long,
        operation: suspend (ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget) -> IdeTargetReply,
    ): IdeTargetReply {
        val target = leases.attached(player, reference, tick) ?: return targetLost()
        return operation(target)
    }

    private fun IdeTargetFailure.failed(retryable: Boolean = false) = IdeTargetReply.Failed(this, retryable)

    private fun targetLost() =
        IdeTargetReply.Failed(
            IdeTargetFailure(IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable"),
            retryable = false,
        )
}
