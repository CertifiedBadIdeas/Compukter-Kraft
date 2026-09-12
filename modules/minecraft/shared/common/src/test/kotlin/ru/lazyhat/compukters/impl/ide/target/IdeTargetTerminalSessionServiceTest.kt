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
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.impl.network.awaitServerResult
import ru.lazyhat.compukters.impl.network.serverOperation
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalChange
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class IdeTargetTerminalSessionServiceTest {
    @Test
    fun `a late open reply cannot replace a newer viewer session`() {
        val firstSnapshot = java.util.concurrent.CompletableFuture<TerminalState?>()
        val secondSnapshot = java.util.concurrent.CompletableFuture<TerminalState?>()
        var calls = 0
        val fixture =
            fixture(readState = {
                if (calls++ == 0) firstSnapshot.awaitServerResult() else secondSnapshot.awaitServerResult()
            })
        val first = serverOperation { fixture.service.open(OWNER, 1, fixture.reference, 1) }
        val second = serverOperation { fixture.service.open(OWNER, 2, fixture.reference, 1) }
        assertFalse(first.isDone)
        assertFalse(second.isDone)
        secondSnapshot.complete(state(1))
        assertEquals(2L, assertIs<IdeTerminalOpened>(second.join()).generation)
        firstSnapshot.complete(state(1))
        assertIs<IdeTerminalFailed>(first.join())
        fixture.service.close()
        fixture.leases.close()
    }

    @Test
    fun `polling stays single flight and discards a replaced machine snapshot`() {
        val completion = java.util.concurrent.CompletableFuture<TerminalUpdate?>()
        var polls = 0
        val fixture =
            fixture(readChanges = {
                polls++
                completion.awaitServerResult()
            })
        runServerTest { fixture.service.open(OWNER, 1, fixture.reference, 1) }
        repeat(4) { assertTrue(fixture.service.publish(it.toLong() + 2).isEmpty()) }
        assertEquals(1, polls)
        fixture.machineId = 8
        completion.complete(TerminalUpdate.Full(state(2)))
        val delivery = fixture.service.publish(6).single()
        assertIs<IdeTerminalFailed>(delivery.event)
        fixture.service.close()
        fixture.leases.close()
    }

    @Test
    fun `open publishes the initial state and subsequent deltas for the leased target`() =
        runServerTest {
            val fixture = fixture()
            val opened = assertIs<IdeTerminalOpened>(fixture.service.open(OWNER, 3, fixture.reference, tick = 2))

            assertEquals(TOKEN, opened.token)
            assertEquals(7, opened.machineId)
            assertEquals(state(1), opened.state)

            fixture.update = TerminalUpdate.Delta(1, 2, listOf(TerminalChange.Cursor(TerminalPosition(1, 0), true)))
            val delivery = fixture.service.publish(tick = 3).single()
            val delta = assertIs<IdeTerminalDelta>(delivery.event)
            assertEquals(OWNER, delivery.player)
            assertEquals(TOKEN, delta.token)
            assertEquals(2, delta.delta.targetRevision)
            assertTrue(fixture.service.publish(tick = 4).isEmpty())
        }

    @Test
    fun `resync returns a delta or full state only to the session owner`() =
        runServerTest {
            var currentState = state(1)
            val fixture = fixture(readState = { currentState })
            fixture.service.open(OWNER, 1, fixture.reference, tick = 1)
            fixture.update = TerminalUpdate.Delta(1, 2, listOf(TerminalChange.Reset))

            assertIs<IdeTerminalDelta>(
                fixture.service.resync(OWNER, IdeTerminalResync(TOKEN, 7, 1), tick = 2),
            )
            assertEquals(null, fixture.service.resync(ATTACKER, IdeTerminalResync(TOKEN, 7, 1), tick = 2))

            fixture.update = null
            currentState = state(2)
            assertIs<IdeTerminalFull>(
                fixture.service.resync(OWNER, IdeTerminalResync(TOKEN, 7, 2), tick = 2),
            )
        }

    @Test
    fun `a late resync snapshot cannot roll back a newer published revision`() {
        val snapshot = java.util.concurrent.CompletableFuture<TerminalState?>()
        var reads = 0
        val fixture = fixture(readState = { if (reads++ == 0) state(1) else snapshot.awaitServerResult() })
        runServerTest { fixture.service.open(OWNER, 1, fixture.reference, 1) }
        fixture.update = null
        val resync =
            serverOperation {
                fixture.service.resync(OWNER, IdeTerminalResync(TOKEN, 7, 1), 2)
            }
        assertFalse(resync.isDone)
        fixture.update = TerminalUpdate.Delta(1, 2, listOf(TerminalChange.Reset))
        assertIs<IdeTerminalDelta>(
            fixture.service
                .publish(3)
                .single()
                .event,
        )
        snapshot.complete(state(1))
        assertEquals(null, resync.join())
        fixture.update = TerminalUpdate.Delta(2, 3, listOf(TerminalChange.Reset))
        assertIs<IdeTerminalDelta>(
            fixture.service
                .publish(4)
                .single()
                .event,
        )
        fixture.service.close()
        fixture.leases.close()
    }

    @Test
    fun `input checks token player machine and one shared admission boundary`() =
        runServerTest {
            var admitted = 0
            val fixture = fixture(inputAdmission = { player, tick -> player == OWNER && tick == 5L && admitted++ == 0 })
            fixture.service.open(OWNER, 1, fixture.reference, tick = 1)

            assertTrue(
                fixture.service.key(
                    OWNER,
                    IdeTerminalKeyInput(TOKEN, 7, TerminalKey.ENTER, TerminalKeyAction.PRESS, setOf(TerminalModifier.CONTROL)),
                    tick = 5,
                ),
            )
            assertFalse(fixture.service.text(OWNER, IdeTerminalTextInput(TOKEN, 7, "x"), tick = 5))
            assertFalse(fixture.service.text(ATTACKER, IdeTerminalTextInput(TOKEN, 7, "x"), tick = 6))
            assertFalse(fixture.service.text(OWNER, IdeTerminalTextInput(TOKEN, 8, "x"), tick = 6))
            assertEquals(listOf("key:ENTER:PRESS:CONTROL"), fixture.inputs)
        }

    @Test
    fun `lease removal and machine replacement end the exact session`() =
        runServerTest {
            val fixture = fixture()
            fixture.service.open(OWNER, 8, fixture.reference, tick = 1)
            fixture.machineId = 9

            val failed =
                assertIs<IdeTerminalFailed>(
                    fixture.service
                        .publish(tick = 2)
                        .single()
                        .event,
                )
            assertEquals(8, failed.generation)
            assertEquals(TOKEN, failed.token)
            assertEquals(IdeTargetFailureKind.TargetLost, failed.kind)
            assertFalse(fixture.service.close(OWNER, IdeTerminalClose(TOKEN)))

            fixture.machineId = 7
            fixture.service.open(OWNER, 9, fixture.reference, tick = 3)
            fixture.leases.detach(OWNER, fixture.attached)
            assertFalse(fixture.service.close(OWNER, IdeTerminalClose(TOKEN)))
            assertIs<IdeTerminalFailed>(
                fixture.service
                    .publish(tick = 4)
                    .single()
                    .event,
            )
        }

    @Test
    fun `targets without terminal support fail without allocating a session`() =
        runServerTest {
            val fixture = fixture(terminal = false)

            val failed = assertIs<IdeTerminalFailed>(fixture.service.open(OWNER, 1, fixture.reference, tick = 1))

            assertEquals(null, failed.token)
            assertEquals(IdeTargetFailureKind.Unsupported, failed.kind)
            assertFalse(failed.retryable)
        }

    private fun fixture(
        terminal: Boolean = true,
        inputAdmission: (UUID, Long) -> Boolean = { _, _ -> true },
        readState: suspend () -> TerminalState? = { state(1) },
        readChanges: (suspend (Long) -> TerminalUpdate?)? = null,
    ): Fixture {
        var machineId = 7L
        var update: TerminalUpdate? = TerminalUpdate.Unchanged(1)
        val inputs = mutableListOf<String>()
        val profile = TargetCompileProfile(toolchain(), emptyList(), WorkerLimits())
        val resolved =
            IdeResolvedTarget(
                machineIdentity = "overworld:1,2,3:7",
                profileId = IdeTargetProfileId(Hash256.zero()),
                profile = profile,
                capabilities = IdeTargetCapabilities(true, true, terminal),
                displayName = "Computer",
                alive = { true },
                deployment = IdeTargetDeploymentOperations(verifyForDeploy = { null }),
                terminal =
                    if (terminal) {
                        IdeTargetTerminalOperations(
                            machineId = { machineId },
                            fullState = readState,
                            changesSince = readChanges ?: { update },
                            submitKey = { key, action, modifiers ->
                                inputs += "key:$key:$action:${modifiers.joinToString()}"
                                true
                            },
                            submitText = { text ->
                                inputs += "text:$text"
                                true
                            },
                        )
                    } else {
                        null
                    },
            )
        val leases =
            IdeTargetLeaseService(
                resolver = IdeTargetClaimResolver { _, _ -> IdeClaimResolution.Resolved(resolved) },
                targetIds = { IdeTargetId("lease-1") },
            )
        val attached = assertIs<IdeAttachResult.Attached>(leases.attach(OWNER, IdeTargetClaim.of(byteArrayOf(1)), 0)).target
        val service = IdeTargetTerminalSessionService(leases, tokens = { TOKEN }, inputAdmission = inputAdmission)
        return Fixture(
            leases,
            attached,
            IdeTargetReference(attached.id, attached.profile),
            service,
            inputs,
            machineId = { machineId },
            setMachineId = { machineId = it },
            update = { update },
            setUpdate = { update = it },
        )
    }

    private class Fixture(
        val leases: IdeTargetLeaseService,
        val attached: ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget,
        val reference: IdeTargetReference,
        val service: IdeTargetTerminalSessionService,
        val inputs: List<String>,
        private val machineId: () -> Long,
        private val setMachineId: (Long) -> Unit,
        private val update: () -> TerminalUpdate?,
        private val setUpdate: (TerminalUpdate?) -> Unit,
    ) {
        var machineIdValue: Long
            get() = machineId()
            set(value) = setMachineId(value)

        var updateValue: TerminalUpdate?
            get() = update()
            set(value) = setUpdate(value)
    }

    private var Fixture.machineId: Long
        get() = machineIdValue
        set(value) {
            machineIdValue = value
        }

    private var Fixture.update: TerminalUpdate?
        get() = updateValue
        set(value) {
            updateValue = value
        }

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ATTACKER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val TOKEN: UUID = UUID.fromString("d3354610-5460-4546-8546-000000000001")

        fun state(revision: Long) =
            TerminalState(
                revision,
                51,
                19,
                List(51 * 19) { TerminalCell(' '.code, 15, 0) },
                TerminalPosition(0, 0),
                true,
            )

        fun toolchain() =
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
}
