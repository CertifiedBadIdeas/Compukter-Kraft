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
import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
import ru.lazyhat.compukters.ide.client.target.IdeAttachResult
import ru.lazyhat.compukters.ide.client.target.IdeDeployResult
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeRevisionResult
import ru.lazyhat.compukters.ide.client.target.IdeSubmissionResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.client.target.IdeVerificationTicket
import ru.lazyhat.compukters.ide.client.target.IdeVerifyResult
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.impl.network.awaitServerResult
import ru.lazyhat.compukters.impl.network.serverOperation
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeTargetDeploymentServiceTest {
    @Test
    fun `suspended verification reserves staging and closes its candidate after detach`() {
        val completion = java.util.concurrent.CompletableFuture<ProgramDeploymentCandidate?>()
        val fixture = fixture(globalBytes = 4) { completion.awaitServerResult() }
        val artifact = byteArrayOf(1, 2, 3, 4)
        fixture.deployments.beginUpload(fixture.player, fixture.target, sha256(artifact), artifact.size, 2)
        fixture.deployments.appendUpload(fixture.player, fixture.target, 0, artifact, 3)
        val verification = serverOperation { fixture.deployments.verify(fixture.player, fixture.target, 4) }
        assertEquals(false, verification.isDone)
        assertIs<IdeUploadResult.Failed>(
            fixture.deployments.beginUpload(fixture.player, fixture.target, sha256(artifact), artifact.size, 5),
        )
        fixture.leases.detach(fixture.player, fixture.target)
        val candidate = Candidate()
        completion.complete(candidate)
        assertIs<IdeVerifyResult.Failed>(verification.join())
        assertTrue(candidate.closed)
        fixture.deployments.close()
        fixture.leases.close()
    }

    @Test
    fun `ordered bounded upload verifies exact bytes and owns candidate until ticket expiry`() =
        runServerTest {
            val candidate = Candidate()
            var verified: ByteArray? = null
            val fixture =
                fixture { artifact ->
                    verified = artifact.copyOf()
                    candidate
                }
            val artifact = byteArrayOf(1, 2, 3, 4, 5)

            assertEquals(
                IdeUploadResult.Accepted,
                fixture.deployments.beginUpload(fixture.player, fixture.target, sha256(artifact), artifact.size, tick = 2),
            )
            assertEquals(
                IdeUploadResult.Accepted,
                fixture.deployments.appendUpload(fixture.player, fixture.target, offset = 0, byteArrayOf(1, 2), tick = 3),
            )
            assertEquals(
                IdeUploadResult.Accepted,
                fixture.deployments.appendUpload(fixture.player, fixture.target, offset = 2, byteArrayOf(3, 4, 5), tick = 4),
            )

            val ticket = assertIs<IdeVerifyResult.Verified>(fixture.deployments.verify(fixture.player, fixture.target, tick = 5)).ticket
            assertContentEquals(artifact, verified)
            assertContentEquals(byteArrayOf(9), ticket.bytes())
            assertEquals(artifact.size, ticket.artifactBytes)
            assertEquals(sha256(artifact), ticket.artifactHash)
            assertEquals(false, candidate.closed)

            fixture.deployments.expire(tick = 14)
            assertEquals(false, candidate.closed)
            fixture.deployments.expire(tick = 15)
            assertTrue(candidate.closed)
        }

    @Test
    fun `upload rejects wrong order size hash and aggregate quota before native verification`() =
        runServerTest {
            var verifications = 0
            val fixture =
                fixture(
                    artifactBytes = 4,
                    globalBytes = 4,
                ) {
                    verifications++
                    Candidate()
                }
            val artifact = byteArrayOf(1, 2, 3, 4)

            assertEquals(
                IdeTargetFailureKind.Upload,
                assertIs<IdeUploadResult.Failed>(
                    fixture.deployments.beginUpload(fixture.player, fixture.target, sha256(artifact), bytes = 5, tick = 2),
                ).failure.kind,
            )
            assertEquals(
                IdeUploadResult.Accepted,
                fixture.deployments.beginUpload(fixture.player, fixture.target, sha256(artifact), artifact.size, tick = 2),
            )
            assertEquals(
                IdeTargetFailureKind.Upload,
                assertIs<IdeUploadResult.Failed>(
                    fixture.deployments.appendUpload(fixture.player, fixture.target, offset = 1, byteArrayOf(1), tick = 3),
                ).failure.kind,
            )
            assertEquals(
                IdeTargetFailureKind.Upload,
                assertIs<IdeUploadResult.Failed>(
                    fixture.deployments.beginUpload(
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        assertIs<IdeAttachResult.Attached>(
                            fixture.leases.attach(
                                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                                IdeTargetClaim.of(byteArrayOf(7)),
                                tick = 3,
                            ),
                        ).target,
                        sha256(artifact),
                        artifact.size,
                        tick = 3,
                    ),
                ).failure.kind,
            )
            fixture.deployments.appendUpload(fixture.player, fixture.target, offset = 0, byteArrayOf(1, 2, 3, 9), tick = 4)
            assertEquals(
                IdeTargetFailureKind.Upload,
                assertIs<IdeVerifyResult.Failed>(fixture.deployments.verify(fixture.player, fixture.target, tick = 5)).failure.kind,
            )
            assertEquals(0, verifications)
        }

    @Test
    fun `upload rate is bounded per player and server tick`() =
        runServerTest {
            val fixture = fixture(maximumChunkBytes = 2, maximumBytesPerPlayerTick = 3) { Candidate() }
            val artifact = byteArrayOf(1, 2, 3, 4)
            fixture.deployments.beginUpload(fixture.player, fixture.target, sha256(artifact), artifact.size, tick = 2)

            assertEquals(
                IdeUploadResult.Accepted,
                fixture.deployments.appendUpload(fixture.player, fixture.target, 0, byteArrayOf(1, 2), tick = 3),
            )
            assertEquals(
                IdeTargetFailureKind.Upload,
                assertIs<IdeUploadResult.Failed>(
                    fixture.deployments.appendUpload(fixture.player, fixture.target, 2, byteArrayOf(3, 4), tick = 3),
                ).failure.kind,
            )
            assertEquals(
                IdeUploadResult.Accepted,
                fixture.deployments.appendUpload(fixture.player, fixture.target, 2, byteArrayOf(3, 4), tick = 4),
            )
        }

    @Test
    fun `lease replacement and detach destroy server owned verification candidates`() =
        runServerTest {
            val candidates = mutableListOf<Candidate>()
            val fixture = fixture { Candidate().also(candidates::add) }
            val artifact = byteArrayOf(1)
            fixture.uploadAndVerify(artifact)

            fixture.leases.attach(fixture.player, IdeTargetClaim.of(byteArrayOf(2)), tick = 6)
            assertTrue(candidates.single().closed)

            val replacement =
                assertIs<IdeAttachResult.Attached>(
                    fixture.leases.attach(fixture.player, IdeTargetClaim.of(byteArrayOf(3)), tick = 7),
                ).target
            fixture.deployments.beginUpload(fixture.player, replacement, sha256(artifact), artifact.size, tick = 8)
            fixture.deployments.appendUpload(fixture.player, replacement, 0, artifact, tick = 8)
            assertIs<IdeVerifyResult.Verified>(fixture.deployments.verify(fixture.player, replacement, tick = 8))
            fixture.leases.detach(fixture.player, replacement)

            assertTrue(candidates.last().closed)
        }

    @Test
    fun `revision deploy and canonical input use exact live target and consume ticket once`() =
        runServerTest {
            val candidate = Candidate()
            var deployedPath: String? = null
            var deployedExpected: VmExecutableRevision? = null
            var deployedCandidate: ProgramDeploymentCandidate? = null
            var submitted: CharArray? = null
            val fixture =
                fixture(
                    operations =
                        IdeTargetDeploymentOperations(
                            verifyForDeploy = { candidate },
                            executableRevision = { VmExecutableRevision.Absent },
                            deploy = { path, expected, value ->
                                deployedPath = path
                                deployedExpected = expected
                                deployedCandidate = value
                                VmExecutableRevision.Present(3)
                            },
                            submitCanonicalLine = { line ->
                                submitted = line.copyOf()
                                true
                            },
                        ),
                )
            val artifact = byteArrayOf(1, 2)
            val ticket = fixture.uploadAndVerify(artifact)
            val path = IdeDeploymentPath.fromProgramName("demo")

            val forged =
                assertIs<IdeDeployResult.Failed>(
                    fixture.deployments.deploy(
                        fixture.player,
                        fixture.target,
                        path,
                        IdeExecutableRevision.Absent,
                        IdeVerificationTicket.of(ticket.bytes(), fixture.target, ticket.artifactHash, artifactBytes = 99),
                        tick = 5,
                    ),
                )
            assertEquals(IdeTargetFailureKind.Verification, forged.failure.kind)
            assertEquals(false, forged.retryable)

            assertEquals(
                IdeRevisionResult.Observed(IdeExecutableRevision.Absent),
                fixture.deployments.executableRevision(fixture.player, fixture.target, path, tick = 5),
            )
            assertEquals(
                IdeDeployResult.Deployed(IdeExecutableRevision.Present(3)),
                fixture.deployments.deploy(
                    fixture.player,
                    fixture.target,
                    path,
                    IdeExecutableRevision.Absent,
                    ticket,
                    tick = 6,
                ),
            )
            assertEquals("/home/demo", deployedPath)
            assertEquals(VmExecutableRevision.Absent, deployedExpected)
            assertEquals(candidate, deployedCandidate)
            assertTrue(candidate.closed)
            val consumed =
                assertIs<IdeDeployResult.Failed>(
                    fixture.deployments.deploy(
                        fixture.player,
                        fixture.target,
                        path,
                        IdeExecutableRevision.Absent,
                        ticket,
                        tick = 7,
                    ),
                )
            assertEquals(IdeTargetFailureKind.Verification, consumed.failure.kind)
            assertEquals(false, consumed.retryable)
            assertEquals(
                IdeSubmissionResult.Submitted,
                fixture.deployments.submitCanonicalLine(fixture.player, fixture.target, "run demo".toCharArray(), tick = 8),
            )
            assertContentEquals("run demo".toCharArray(), submitted)
        }

    @Test
    fun `lease expiry closes ticket candidate while deployment expiry iterates safely`() =
        runServerTest {
            val candidate = Candidate()
            val fixture = fixture(leaseTicks = 5, ticketLifetimeTicks = 20) { candidate }
            fixture.uploadAndVerify(byteArrayOf(1))

            fixture.deployments.expire(tick = 6)

            assertTrue(candidate.closed)
        }

    private fun fixture(
        artifactBytes: Int = 32,
        globalBytes: Int = 64,
        leaseTicks: Long = 100,
        ticketLifetimeTicks: Long = 10,
        maximumChunkBytes: Int = 32 * 1024,
        maximumBytesPerPlayerTick: Int = 256 * 1024,
        verify: suspend (ByteArray) -> ProgramDeploymentCandidate?,
    ): Fixture =
        fixture(
            artifactBytes = artifactBytes,
            globalBytes = globalBytes,
            leaseTicks = leaseTicks,
            ticketLifetimeTicks = ticketLifetimeTicks,
            maximumChunkBytes = maximumChunkBytes,
            maximumBytesPerPlayerTick = maximumBytesPerPlayerTick,
            operations = IdeTargetDeploymentOperations(verify),
        )

    private fun fixture(
        artifactBytes: Int = 32,
        globalBytes: Int = 64,
        leaseTicks: Long = 100,
        ticketLifetimeTicks: Long = 10,
        maximumChunkBytes: Int = 32 * 1024,
        maximumBytesPerPlayerTick: Int = 256 * 1024,
        operations: IdeTargetDeploymentOperations,
    ): Fixture {
        val profile = TargetCompileProfile(toolchain(), emptyList(), WorkerLimits(artifactBytes = artifactBytes))
        val resolved =
            IdeResolvedTarget(
                machineIdentity = "overworld:1,2,3:7",
                profileId = IdeTargetProfileId(Hash256.zero()),
                profile = profile,
                capabilities = IdeTargetCapabilities(writableFileSystem = true, canonicalInput = true, terminal = false),
                displayName = "Computer",
                alive = { true },
                deployment = operations,
            )
        val leases =
            IdeTargetLeaseService(
                resolver = IdeTargetClaimResolver { _, _ -> IdeClaimResolution.Resolved(resolved) },
                targetIds = sequenceOf("lease-1", "lease-2", "lease-3").map(::IdeTargetId).iterator()::next,
                leaseTicks = leaseTicks,
            )
        val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val target = assertIs<IdeAttachResult.Attached>(leases.attach(player, IdeTargetClaim.of(byteArrayOf(1)), tick = 1)).target
        val deployments =
            IdeTargetDeploymentService(
                leases = leases,
                ticketBytes = { byteArrayOf(9) },
                maximumGlobalStagingBytes = globalBytes,
                maximumChunkBytes = maximumChunkBytes,
                maximumBytesPerPlayerTick = maximumBytesPerPlayerTick,
                uploadTimeoutTicks = 10,
                ticketLifetimeTicks = ticketLifetimeTicks,
            )
        return Fixture(player, target, leases, deployments)
    }

    private data class Fixture(
        val player: UUID,
        val target: ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget,
        val leases: IdeTargetLeaseService,
        val deployments: IdeTargetDeploymentService,
    ) {
        suspend fun uploadAndVerify(artifact: ByteArray): ru.lazyhat.compukters.ide.client.target.IdeVerificationTicket {
            deployments.beginUpload(player, target, sha256(artifact), artifact.size, tick = 2)
            deployments.appendUpload(player, target, 0, artifact, tick = 3)
            return assertIs<IdeVerifyResult.Verified>(deployments.verify(player, target, tick = 4)).ticket
        }
    }

    private class Candidate : ProgramDeploymentCandidate {
        var closed = false

        override fun close() {
            check(!closed)
            closed = true
        }
    }

    private fun toolchain() = ToolchainLockIdentity("2.4.0", "2.4", 1u, 2u, 1u, Hash256.zero(), Hash256.zero())

    private companion object {
        fun sha256(bytes: ByteArray): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
