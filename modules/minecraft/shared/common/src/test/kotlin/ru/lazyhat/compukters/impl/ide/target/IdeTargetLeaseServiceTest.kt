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
import ru.lazyhat.compukters.ide.client.target.IdeHeartbeatResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class IdeTargetLeaseServiceTest {
    @Test
    fun `attach binds an opaque lease to one player and renews only the matching live target`() {
        var alive = true
        val resolved = resolvedTarget { alive }
        val service =
            IdeTargetLeaseService(
                resolver = IdeTargetClaimResolver { _, _ -> IdeClaimResolution.Resolved(resolved) },
                targetIds = { IdeTargetId("lease-1") },
                leaseTicks = 20,
            )
        val player = UUID.fromString("00000000-0000-0000-0000-000000000001")

        val attached = assertIs<IdeAttachResult.Attached>(service.attach(player, claim(), tick = 10)).target
        assertEquals(IdeTargetId("lease-1"), attached.id)
        assertEquals(resolved.profile, attached.compileProfile)
        assertEquals(IdeHeartbeatResult.Alive, service.heartbeat(player, attached, tick = 29))
        assertEquals(resolved, service.access(player, attached, tick = 48))

        alive = false
        assertIs<IdeHeartbeatResult.Lost>(service.heartbeat(player, attached, tick = 49))
        assertNull(service.access(player, attached, tick = 49))
    }

    @Test
    fun `forged player stale lease and expired deadline cannot resolve a target`() {
        val service =
            IdeTargetLeaseService(
                resolver = IdeTargetClaimResolver { _, _ -> IdeClaimResolution.Resolved(resolvedTarget()) },
                targetIds = { IdeTargetId("lease-1") },
                leaseTicks = 5,
            )
        val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val attacker = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val attached = assertIs<IdeAttachResult.Attached>(service.attach(owner, claim(), tick = 1)).target
        val forged = attached.copy(id = IdeTargetId("lease-forged"))

        assertIs<IdeHeartbeatResult.Lost>(service.heartbeat(attacker, attached, tick = 2))
        assertIs<IdeHeartbeatResult.Lost>(service.heartbeat(owner, forged, tick = 2))
        assertIs<IdeHeartbeatResult.Lost>(service.heartbeat(owner, attached, tick = 6))
        assertNull(service.access(owner, attached, tick = 6))
    }

    @Test
    fun `wire reference restores only the server owned attached target`() {
        val service =
            IdeTargetLeaseService(
                resolver = IdeTargetClaimResolver { _, _ -> IdeClaimResolution.Resolved(resolvedTarget()) },
                targetIds = { IdeTargetId("lease-1") },
                leaseTicks = 5,
            )
        val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val attacker = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val attached = assertIs<IdeAttachResult.Attached>(service.attach(owner, claim(), tick = 1)).target
        val reference = IdeTargetReference(attached.id, attached.profile)

        assertEquals(attached, service.attached(owner, reference, tick = 2))
        assertNull(service.attached(attacker, reference, tick = 2))
        assertNull(
            service.attached(
                owner,
                reference.copy(profile = IdeTargetProfileId(Hash256.of(ByteArray(32) { 1 }))),
                tick = 2,
            ),
        )
        assertNull(service.attached(owner, reference, tick = 6))
    }

    @Test
    fun `rejected claim remains typed and replacing attachment invalidates the old lease`() {
        var attempts = 0
        val permission = IdeTargetFailure(IdeTargetFailureKind.Permission, "Computer is not interactable")
        val service =
            IdeTargetLeaseService(
                resolver =
                    IdeTargetClaimResolver { _, _ ->
                        attempts++
                        if (attempts == 1) IdeClaimResolution.Rejected(permission) else IdeClaimResolution.Resolved(resolvedTarget())
                    },
                targetIds = sequenceOf("lease-1", "lease-2").map(::IdeTargetId).iterator()::next,
                leaseTicks = 20,
            )
        val player = UUID.fromString("00000000-0000-0000-0000-000000000001")

        assertEquals(IdeAttachResult.Rejected(permission), service.attach(player, claim(), tick = 0))
        val first = assertIs<IdeAttachResult.Attached>(service.attach(player, claim(), tick = 1)).target
        val second = assertIs<IdeAttachResult.Attached>(service.attach(player, claim(), tick = 2)).target

        assertIs<IdeHeartbeatResult.Lost>(service.heartbeat(player, first, tick = 3))
        assertEquals(IdeHeartbeatResult.Alive, service.heartbeat(player, second, tick = 3))
        service.detach(player, second)
        assertNull(service.access(player, second, tick = 3))
    }

    private fun claim() = IdeTargetClaim.of(byteArrayOf(1))

    private fun resolvedTarget(alive: () -> Boolean = { true }): IdeResolvedTarget {
        val profile = TargetCompileProfile(toolchain(), emptyList(), WorkerLimits())
        return IdeResolvedTarget(
            machineIdentity = "overworld:1,2,3:7",
            profileId = IdeTargetProfileId(Hash256.zero()),
            profile = profile,
            capabilities = IdeTargetCapabilities(writableFileSystem = true, canonicalInput = true, terminal = false),
            displayName = "Computer",
            alive = alive,
            deployment = IdeTargetDeploymentOperations(verifyForDeploy = { null }),
        )
    }

    private fun toolchain() =
        ToolchainLockIdentity(
            compilerVersion = "2.4.0",
            languageVersion = "2.4",
            codegenAbi = 1u,
            artifactAbi = 2u,
            artifactWriterVersion = 1u,
            payloadHash = Hash256.zero(),
            platformAbi = Hash256.zero(),
        )
}
