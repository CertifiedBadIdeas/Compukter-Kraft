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
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeDeployResult
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeFileListResult
import ru.lazyhat.compukters.ide.client.target.IdeFileReadResult
import ru.lazyhat.compukters.ide.client.target.IdeFileStatResult
import ru.lazyhat.compukters.ide.client.target.IdeHeartbeatResult
import ru.lazyhat.compukters.ide.client.target.IdeRevisionResult
import ru.lazyhat.compukters.ide.client.target.IdeSubmissionResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetArtifact
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetPort
import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import ru.lazyhat.compukters.ide.client.target.IdeVerificationTicket
import ru.lazyhat.compukters.ide.client.target.IdeVerifyResult
import java.util.concurrent.CompletableFuture

internal class NeoForgeIdeTargetPort(
    private val channel: IdeTargetRequestChannel,
) : IdeTargetPort,
    AutoCloseable {
    override fun attach(claim: IdeTargetClaim): CompletableFuture<IdeAttachResult> =
        channel.request(IdeTargetRequest.Attach(BinaryValue.of(claim.bytes()))).thenApply { reply ->
            when (reply) {
                is IdeTargetReply.Attached -> IdeAttachResult.Attached(reply.target)
                is IdeTargetReply.Failed -> IdeAttachResult.Rejected(reply.failure)
                else -> IdeAttachResult.Rejected(protocolFailure("Unexpected attach reply"))
            }
        }

    override fun verify(
        target: IdeAttachedTarget,
        artifact: IdeTargetArtifact,
    ): CompletableFuture<IdeVerifyResult> {
        val reference = target.reference()
        val bytes = artifact.bytes()
        var stage =
            channel.request(IdeTargetRequest.BeginUpload(reference, artifact.hash, bytes.size))
        for (offset in bytes.indices step IdeTargetWireProtocol.MAXIMUM_CHUNK_BYTES) {
            val end = minOf(offset + IdeTargetWireProtocol.MAXIMUM_CHUNK_BYTES, bytes.size)
            stage =
                stage.thenCompose { reply ->
                    if (reply != IdeTargetReply.UploadAccepted) {
                        CompletableFuture.completedFuture(reply)
                    } else {
                        channel.request(
                            IdeTargetRequest.UploadChunk(
                                reference,
                                offset,
                                BinaryValue.of(bytes.copyOfRange(offset, end)),
                            ),
                        )
                    }
                }
        }
        return stage
            .thenCompose { reply ->
                if (reply == IdeTargetReply.UploadAccepted) {
                    channel.request(IdeTargetRequest.Verify(reference))
                } else {
                    CompletableFuture.completedFuture(reply)
                }
            }.thenApply { reply -> mapVerification(reply, target, artifact) }
    }

    override fun executableRevision(
        target: IdeAttachedTarget,
        path: IdeDeploymentPath,
    ): CompletableFuture<IdeRevisionResult> =
        channel.request(IdeTargetRequest.ExecutableRevision(target.reference(), path)).thenApply { reply ->
            when (reply) {
                is IdeTargetReply.RevisionObserved -> IdeRevisionResult.Observed(reply.revision)
                is IdeTargetReply.Failed -> IdeRevisionResult.Failed(reply.failure)
                else -> IdeRevisionResult.Failed(protocolFailure("Unexpected executable revision reply"))
            }
        }

    override fun fileStat(
        target: IdeAttachedTarget,
        path: IdeTargetVirtualPath,
    ): CompletableFuture<IdeFileStatResult> =
        channel.request(IdeTargetRequest.FileStat(target.reference(), path)).thenApply { reply ->
            when (reply) {
                is IdeTargetReply.FileStatObserved -> IdeFileStatResult.Observed(reply.stat)
                is IdeTargetReply.Failed -> IdeFileStatResult.Failed(reply.failure)
                else -> IdeFileStatResult.Failed(protocolFailure("Unexpected filesystem stat reply"))
            }
        }

    override fun fileList(
        target: IdeAttachedTarget,
        path: IdeTargetVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ): CompletableFuture<IdeFileListResult> =
        channel.request(IdeTargetRequest.FileList(target.reference(), path, startAfter, maximumEntries)).thenApply { reply ->
            when (reply) {
                is IdeTargetReply.FileListed -> IdeFileListResult.Listed(reply.listing)
                is IdeTargetReply.Failed -> IdeFileListResult.Failed(reply.failure)
                else -> IdeFileListResult.Failed(protocolFailure("Unexpected filesystem list reply"))
            }
        }

    override fun fileRead(
        target: IdeAttachedTarget,
        path: IdeTargetVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): CompletableFuture<IdeFileReadResult> =
        channel
            .request(
                IdeTargetRequest.FileRead(
                    target.reference(),
                    path,
                    offset,
                    maximumBytes,
                    expectedGeneration,
                ),
            ).thenApply { reply ->
                when (reply) {
                    is IdeTargetReply.FileRead -> IdeFileReadResult.Read(reply.chunk)
                    is IdeTargetReply.Failed -> IdeFileReadResult.Failed(reply.failure)
                    else -> IdeFileReadResult.Failed(protocolFailure("Unexpected filesystem read reply"))
                }
            }

    override fun deploy(
        target: IdeAttachedTarget,
        ticket: IdeVerificationTicket,
        path: IdeDeploymentPath,
        expected: IdeExecutableRevision,
    ): CompletableFuture<IdeDeployResult> =
        channel
            .request(
                IdeTargetRequest.Deploy(
                    target.reference(),
                    BinaryValue.of(ticket.bytes()),
                    ticket.artifactHash,
                    ticket.artifactBytes,
                    path,
                    expected,
                ),
            ).thenApply { reply ->
                when (reply) {
                    is IdeTargetReply.Deployed -> IdeDeployResult.Deployed(reply.revision)
                    is IdeTargetReply.StaleRevision -> IdeDeployResult.StaleRevision(reply.actual)
                    is IdeTargetReply.Failed -> IdeDeployResult.Failed(reply.failure, reply.retryable)
                    else -> IdeDeployResult.Failed(protocolFailure("Unexpected deployment reply"), retryable = false)
                }
            }

    override fun submitCanonicalLine(
        target: IdeAttachedTarget,
        line: CharArray,
    ): CompletableFuture<IdeSubmissionResult> =
        channel
            .request(IdeTargetRequest.SubmitCanonicalLine(target.reference(), IdeCanonicalLine.of(line)))
            .thenApply { reply ->
                when (reply) {
                    IdeTargetReply.Submitted -> IdeSubmissionResult.Submitted
                    is IdeTargetReply.Failed -> IdeSubmissionResult.Failed(reply.failure)
                    else -> IdeSubmissionResult.Failed(protocolFailure("Unexpected canonical input reply"))
                }
            }

    override fun heartbeat(target: IdeAttachedTarget): CompletableFuture<IdeHeartbeatResult> =
        channel.request(IdeTargetRequest.Heartbeat(target.reference())).thenApply { reply ->
            when (reply) {
                IdeTargetReply.Alive -> IdeHeartbeatResult.Alive
                is IdeTargetReply.Failed -> IdeHeartbeatResult.Lost(reply.failure)
                else -> IdeHeartbeatResult.Lost(protocolFailure("Unexpected heartbeat reply"))
            }
        }

    override fun detach(target: IdeAttachedTarget): CompletableFuture<Unit> =
        channel.request(IdeTargetRequest.Detach(target.reference())).thenApply { reply ->
            when (reply) {
                IdeTargetReply.Detached -> Unit
                is IdeTargetReply.Failed -> throw IdeTargetProtocolException("Target detach failed: ${reply.failure.detail}")
                else -> throw IdeTargetProtocolException("Unexpected detach reply")
            }
        }

    override fun close() = channel.disconnect()

    private fun mapVerification(
        reply: IdeTargetReply,
        target: IdeAttachedTarget,
        artifact: IdeTargetArtifact,
    ): IdeVerifyResult =
        when (reply) {
            is IdeTargetReply.Verified -> {
                if (
                    reply.target != target.reference() ||
                    reply.artifactHash != artifact.hash ||
                    reply.artifactBytes != artifact.size
                ) {
                    IdeVerifyResult.Failed(protocolFailure("Verification ticket scope mismatch"))
                } else {
                    IdeVerifyResult.Verified(
                        IdeVerificationTicket.of(
                            reply.ticket.toByteArray(),
                            target,
                            reply.artifactHash,
                            reply.artifactBytes,
                        ),
                    )
                }
            }

            is IdeTargetReply.Failed -> {
                IdeVerifyResult.Failed(reply.failure)
            }

            else -> {
                IdeVerifyResult.Failed(protocolFailure("Unexpected artifact upload reply"))
            }
        }

    private fun IdeAttachedTarget.reference() = IdeTargetReference(id, profile)

    private fun protocolFailure(detail: String) = IdeTargetFailure(IdeTargetFailureKind.Protocol, detail)
}

internal class IdeTargetProtocolException(
    message: String,
) : IllegalStateException(message)
