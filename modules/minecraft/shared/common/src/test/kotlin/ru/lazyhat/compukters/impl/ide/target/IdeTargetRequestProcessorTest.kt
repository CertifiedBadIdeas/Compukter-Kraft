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
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class IdeTargetRequestProcessorTest {
    @Test
    fun `processor owns the complete attach upload verify deploy and input lifecycle`() =
        runServerTest {
            val candidate = Candidate()
            var deployed: String? = null
            var submitted: CharArray? = null
            val fixture =
                fixture(
                    IdeTargetDeploymentOperations(
                        verifyForDeploy = { candidate },
                        executableRevision = { VmExecutableRevision.Absent },
                        deploy = { path, _, _ ->
                            deployed = path
                            VmExecutableRevision.Present(4)
                        },
                        submitCanonicalLine = { line ->
                            submitted = line.copyOf()
                            true
                        },
                    ),
                )
            val attached = assertIs<IdeTargetReply.Attached>(fixture.processor.handle(fixture.player, attach(), tick = 1)).target
            val reference = IdeTargetReference(attached.id, attached.profile)
            val artifact = byteArrayOf(1, 2, 3)

            assertEquals(
                IdeTargetReply.UploadAccepted,
                fixture.processor.handle(
                    fixture.player,
                    IdeTargetRequest.BeginUpload(reference, sha256(artifact), artifact.size),
                    tick = 2,
                ),
            )
            assertEquals(
                IdeTargetReply.UploadAccepted,
                fixture.processor.handle(
                    fixture.player,
                    IdeTargetRequest.UploadChunk(reference, 0, BinaryValue.of(artifact)),
                    tick = 3,
                ),
            )
            val verified =
                assertIs<IdeTargetReply.Verified>(
                    fixture.processor.handle(fixture.player, IdeTargetRequest.Verify(reference), tick = 4),
                )
            assertContentEquals(byteArrayOf(9), verified.ticket.toByteArray())
            assertEquals(reference, verified.target)

            val path = IdeDeploymentPath.fromProgramName("demo")
            assertEquals(
                IdeTargetReply.RevisionObserved(IdeExecutableRevision.Absent),
                fixture.processor.handle(fixture.player, IdeTargetRequest.ExecutableRevision(reference, path), tick = 5),
            )
            assertEquals(
                IdeTargetReply.Deployed(IdeExecutableRevision.Present(4)),
                fixture.processor.handle(
                    fixture.player,
                    IdeTargetRequest.Deploy(
                        reference,
                        verified.ticket,
                        verified.artifactHash,
                        verified.artifactBytes,
                        path,
                        IdeExecutableRevision.Absent,
                    ),
                    tick = 6,
                ),
            )
            assertEquals("/home/demo", deployed)
            assertTrue(candidate.closed)
            assertEquals(
                IdeTargetReply.Submitted,
                fixture.processor.handle(
                    fixture.player,
                    IdeTargetRequest.SubmitCanonicalLine(reference, IdeCanonicalLine.of(charArrayOf('x', '\uD800'))),
                    tick = 7,
                ),
            )
            assertContentEquals(charArrayOf('x', '\uD800'), submitted)
            assertEquals(IdeTargetReply.Alive, fixture.processor.handle(fixture.player, IdeTargetRequest.Heartbeat(reference), tick = 8))
            assertEquals(IdeTargetReply.Detached, fixture.processor.handle(fixture.player, IdeTargetRequest.Detach(reference), tick = 9))
        }

    @Test
    fun `processor rejects another player and forged profile before touching target operations`() =
        runServerTest {
            var calls = 0
            val fixture =
                fixture(
                    IdeTargetDeploymentOperations(verifyForDeploy = {
                        calls++
                        Candidate()
                    }),
                )
            val attached = assertIs<IdeTargetReply.Attached>(fixture.processor.handle(fixture.player, attach(), tick = 1)).target
            val reference = IdeTargetReference(attached.id, attached.profile)
            val attacker = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val forged = reference.copy(profile = IdeTargetProfileId(hash(7)))

            val otherPlayer =
                assertIs<IdeTargetReply.Failed>(
                    fixture.processor.handle(attacker, IdeTargetRequest.Verify(reference), tick = 2),
                )
            val otherProfile =
                assertIs<IdeTargetReply.Failed>(
                    fixture.processor.handle(fixture.player, IdeTargetRequest.Verify(forged), tick = 2),
                )

            assertEquals(IdeTargetFailureKind.TargetLost, otherPlayer.failure.kind)
            assertEquals(IdeTargetFailureKind.TargetLost, otherProfile.failure.kind)
            assertEquals(0, calls)
        }

    private fun fixture(operations: IdeTargetDeploymentOperations): Fixture {
        val profile = TargetCompileProfile(toolchain(), emptyList(), WorkerLimits(artifactBytes = 32))
        val resolved =
            IdeResolvedTarget(
                machineIdentity = "overworld:1,2,3:7",
                profileId = IdeTargetProfileId(hash(1)),
                profile = profile,
                capabilities = IdeTargetCapabilities(writableFileSystem = true, canonicalInput = true, terminal = false),
                displayName = "Computer",
                alive = { true },
                deployment = operations,
            )
        val leases =
            IdeTargetLeaseService(
                resolver = IdeTargetClaimResolver { _, _ -> IdeClaimResolution.Resolved(resolved) },
                targetIds = { IdeTargetId("lease-1") },
                leaseTicks = 20,
            )
        val deployments = IdeTargetDeploymentService(leases, ticketBytes = { byteArrayOf(9) })
        return Fixture(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            IdeTargetRequestProcessor(leases, deployments),
        )
    }

    private fun attach() = IdeTargetRequest.Attach(BinaryValue.of(byteArrayOf(1)))

    private class Candidate : ProgramDeploymentCandidate {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private data class Fixture(
        val player: UUID,
        val processor: IdeTargetRequestProcessor,
    )

    private fun toolchain() = ToolchainLockIdentity("2.4.0", "2.4", 1u, 2u, 1u, hash(2), hash(3))

    private fun hash(seed: Int): Hash256 = Hash256.of(ByteArray(32) { seed.toByte() })

    private fun sha256(bytes: ByteArray): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(bytes))
}
