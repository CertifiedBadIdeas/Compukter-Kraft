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
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.client.target.IdeAttachResult
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeDeployResult
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeHeartbeatResult
import ru.lazyhat.compukters.ide.client.target.IdeRevisionResult
import ru.lazyhat.compukters.ide.client.target.IdeSubmissionResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetArtifact
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.client.target.IdeVerificationTicket
import ru.lazyhat.compukters.ide.client.target.IdeVerifyResult
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class NeoForgeIdeTargetPortTest {
    @Test
    fun `verification uploads exactly one ordered 32 KiB chunk at a time before verify`() {
        val channel = Channel()
        val port = NeoForgeIdeTargetPort(channel)
        val target = target()
        val bytes = ByteArray(65_537) { index -> index.toByte() }
        val artifact = IdeTargetArtifact(sha256(bytes), bytes)

        val result = port.verify(target, artifact)
        val begin = assertIs<IdeTargetRequest.BeginUpload>(channel.singleRequest())
        assertEquals(bytes.size, begin.bytes)
        assertFalse(result.isDone)

        channel.complete(IdeTargetReply.UploadAccepted)
        assertChunk(channel.singleRequest(), offset = 0, bytes.copyOfRange(0, 32 * 1024))
        channel.complete(IdeTargetReply.UploadAccepted)
        assertChunk(channel.singleRequest(), offset = 32 * 1024, bytes.copyOfRange(32 * 1024, 64 * 1024))
        channel.complete(IdeTargetReply.UploadAccepted)
        assertChunk(channel.singleRequest(), offset = 64 * 1024, byteArrayOf(bytes.last()))
        channel.complete(IdeTargetReply.UploadAccepted)
        assertIs<IdeTargetRequest.Verify>(channel.singleRequest())
        channel.complete(
            IdeTargetReply.Verified(
                ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
                    .of(byteArrayOf(9)),
                IdeTargetReference(target.id, target.profile),
                artifact.hash,
                artifact.size,
            ),
        )

        val verified = assertIs<IdeVerifyResult.Verified>(result.join())
        assertContentEquals(byteArrayOf(9), verified.ticket.bytes())
        assertEquals(artifact.size, verified.ticket.artifactBytes)
    }

    @Test
    fun `port maps target operations and closes its request channel`() {
        val channel = Channel()
        val port = NeoForgeIdeTargetPort(channel)
        val target = target()
        val path = IdeDeploymentPath.fromProgramName("demo")
        val ticket = IdeVerificationTicket.of(byteArrayOf(4), target, hash(5), artifactBytes = 7)

        val attach = port.attach(IdeTargetClaim.of(byteArrayOf(1)))
        assertIs<IdeTargetRequest.Attach>(channel.singleRequest())
        channel.complete(IdeTargetReply.Attached(target))
        assertEquals(IdeAttachResult.Attached(target), attach.join())

        val revision = port.executableRevision(target, path)
        assertIs<IdeTargetRequest.ExecutableRevision>(channel.singleRequest())
        channel.complete(IdeTargetReply.RevisionObserved(IdeExecutableRevision.Absent))
        assertEquals(IdeRevisionResult.Observed(IdeExecutableRevision.Absent), revision.join())

        val deployment = port.deploy(target, ticket, path, IdeExecutableRevision.Absent)
        assertIs<IdeTargetRequest.Deploy>(channel.singleRequest())
        channel.complete(IdeTargetReply.Deployed(IdeExecutableRevision.Present(2)))
        assertEquals(IdeDeployResult.Deployed(IdeExecutableRevision.Present(2)), deployment.join())

        val submission = port.submitCanonicalLine(target, charArrayOf('x'))
        assertIs<IdeTargetRequest.SubmitCanonicalLine>(channel.singleRequest())
        channel.complete(IdeTargetReply.Submitted)
        assertEquals(IdeSubmissionResult.Submitted, submission.join())

        val heartbeat = port.heartbeat(target)
        assertIs<IdeTargetRequest.Heartbeat>(channel.singleRequest())
        channel.complete(IdeTargetReply.Alive)
        assertEquals(IdeHeartbeatResult.Alive, heartbeat.join())

        val detached = port.detach(target)
        assertIs<IdeTargetRequest.Detach>(channel.singleRequest())
        channel.complete(IdeTargetReply.Detached)
        detached.join()
        port.close()
        assertEquals(true, channel.disconnected)
    }

    private fun assertChunk(
        request: IdeTargetRequest,
        offset: Int,
        bytes: ByteArray,
    ) {
        val chunk = assertIs<IdeTargetRequest.UploadChunk>(request)
        assertEquals(offset, chunk.offset)
        assertContentEquals(bytes, chunk.bytes.toByteArray())
    }

    private class Channel : IdeTargetRequestChannel {
        private val calls = ArrayDeque<Call>()
        var disconnected = false

        override fun request(request: IdeTargetRequest): CompletableFuture<IdeTargetReply> =
            CompletableFuture<IdeTargetReply>().also { future -> calls += Call(request, future) }

        override fun disconnect() {
            disconnected = true
        }

        fun singleRequest(): IdeTargetRequest = calls.single().request

        fun complete(reply: IdeTargetReply) {
            calls.removeFirst().future.complete(reply)
        }

        private data class Call(
            val request: IdeTargetRequest,
            val future: CompletableFuture<IdeTargetReply>,
        )
    }

    private fun target() =
        IdeAttachedTarget(
            IdeTargetId("lease-1"),
            IdeTargetProfileId(hash(1)),
            TargetCompileProfile(toolchain(), emptyList(), WorkerLimits()),
            IdeTargetCapabilities(writableFileSystem = true, canonicalInput = true, terminal = false),
            "Computer",
        )

    private fun toolchain() = ToolchainLockIdentity("2.4.0", "2.4", 1u, 2u, 1u, hash(2), hash(3))

    private fun hash(seed: Int): Hash256 = Hash256.of(ByteArray(32) { seed.toByte() })

    private fun sha256(bytes: ByteArray): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(bytes))
}
