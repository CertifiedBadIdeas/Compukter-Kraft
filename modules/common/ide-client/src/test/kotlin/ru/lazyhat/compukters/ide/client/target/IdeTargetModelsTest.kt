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

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeTargetModelsTest {
    @Test
    fun `claims artifacts and tickets own copied bounded bytes`() {
        val claimBytes = byteArrayOf(1, 2)
        val artifactBytes = byteArrayOf(3, 4)
        val ticketBytes = byteArrayOf(5, 6)
        val claim = IdeTargetClaim.of(claimBytes)
        val artifact = IdeTargetArtifact(hash(3), artifactBytes)
        val ticket = IdeVerificationTicket.of(ticketBytes, target(), artifact)
        claimBytes.fill(0)
        artifactBytes.fill(0)
        ticketBytes.fill(0)

        assertContentEquals(byteArrayOf(1, 2), claim.bytes())
        assertContentEquals(byteArrayOf(3, 4), artifact.bytes())
        assertContentEquals(byteArrayOf(5, 6), ticket.bytes())
        claim.bytes().fill(0)
        artifact.bytes().fill(0)
        ticket.bytes().fill(0)
        assertContentEquals(byteArrayOf(1, 2), claim.bytes())
        assertContentEquals(byteArrayOf(3, 4), artifact.bytes())
        assertContentEquals(byteArrayOf(5, 6), ticket.bytes())
    }

    @Test
    fun `verification ticket can be reconstructed from bounded transport metadata`() {
        val target = target()
        val ticket = IdeVerificationTicket.of(byteArrayOf(7), target, hash(8), artifactBytes = 42)

        assertEquals(target.id, ticket.targetId)
        assertEquals(target.profile, ticket.profileId)
        assertEquals(hash(8), ticket.artifactHash)
        assertEquals(42, ticket.artifactBytes)
    }

    @Test
    fun `deployment path is derived from one safe manifest name`() {
        assertEquals("/home/demo", IdeDeploymentPath.fromProgramName("demo").value)
        listOf("", ".", "..", "a/b", "a\\b", "a\n").forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { IdeDeploymentPath.fromProgramName(invalid) }
        }
        assertFailsWith<IllegalArgumentException> { IdeDeploymentPath.fromProgramName("x".repeat(129)) }
    }

    @Test
    fun `target filesystem paths are absolute canonical and bounded`() {
        assertEquals("/", IdeTargetVirtualPath.of("/").value)
        assertEquals("/home/main.kt", IdeTargetVirtualPath.of("/home/main.kt").value)
        listOf("", "home", "/home/", "/home//main.kt", "/home/./main.kt", "/home/../main.kt", "/home\\main.kt").forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { IdeTargetVirtualPath.of(invalid) }
        }
    }

    @Test
    fun `target capabilities advertise terminal independently from filesystem and canonical input`() {
        val capabilities =
            IdeTargetCapabilities(
                writableFileSystem = false,
                canonicalInput = false,
                terminal = true,
            )
        val submitted =
            IdeTargetState.CommandSubmitted(
                target(),
                IdeDeploymentPath.fromProgramName("demo"),
                IdeExecutableRevision.Present(9),
            )

        assertFalse(capabilities.writableFileSystem)
        assertFalse(capabilities.canonicalInput)
        assertTrue(capabilities.terminal)
        assertEquals("Command submitted", submitted.message)
    }

    private fun target() =
        IdeAttachedTarget(
            IdeTargetId("computer-1"),
            IdeTargetProfileId(hash(1)),
            TargetCompileProfile(toolchain(), emptyList(), WorkerLimits()),
            IdeTargetCapabilities(writableFileSystem = true, canonicalInput = true, terminal = false),
            "Computer",
        )

    private fun hash(seed: Int): Hash256 = Hash256.of(ByteArray(32) { seed.toByte() })

    private fun toolchain() = ToolchainLockIdentity("2.4.10", "2.4", 1u, 1u, 1u, hash(2), hash(3))
}
