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

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalChange
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeTargetTerminalClientTest {
    @Test
    fun `visible attached target opens lazily while hiding preserves the active replica`() {
        val transport = RecordingTransport()
        val client = IdeTargetTerminalClient(transport)
        client.setTarget(TARGET)
        assertIs<IdeTargetTerminalState.Closed>(client.state())

        client.setVisible(true)
        assertEquals(IdeTerminalOpenPayload(1, TARGET), transport.sent.single())
        assertIs<IdeTargetTerminalState.Opening>(client.state())
        client.accept(IdeTerminalOpenedPayload(1, TOKEN, 7, state(4)))
        client.setVisible(false)

        val active = assertIs<IdeTargetTerminalState.Active>(client.state())
        assertEquals(4, active.replica.state.revision)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun `revision gap requests one resync and full state resumes deltas`() {
        val transport = RecordingTransport()
        val client = activeClient(transport)
        val invalid = IdeTerminalDeltaPayload(TOKEN, 7, TerminalUpdate.Delta(2, 5, listOf(TerminalChange.Reset)))

        client.accept(invalid)
        client.accept(invalid)

        assertIs<IdeTargetTerminalState.Resyncing>(client.state())
        assertEquals(IdeTerminalResyncPayload(TOKEN, 7, 4), transport.sent.last())
        assertEquals(2, transport.sent.size)
        client.accept(IdeTerminalFullPayload(TOKEN, 7, state(6)))
        client.accept(IdeTerminalDeltaPayload(TOKEN, 7, TerminalUpdate.Delta(6, 7, emptyList())))
        assertEquals(7, assertIs<IdeTargetTerminalState.Active>(client.state()).replica.state.revision)
    }

    @Test
    fun `stale generation token and machine payloads cannot replace current state`() {
        val transport = RecordingTransport()
        val client = IdeTargetTerminalClient(transport)
        client.setTarget(TARGET)
        client.setVisible(true)

        client.accept(IdeTerminalOpenedPayload(2, TOKEN, 7, state(9)))
        assertIs<IdeTargetTerminalState.Opening>(client.state())
        client.accept(IdeTerminalOpenedPayload(1, TOKEN, 7, state(4)))
        client.accept(IdeTerminalFullPayload(OTHER_TOKEN, 7, state(9)))
        client.accept(IdeTerminalFullPayload(TOKEN, 8, state(9)))

        assertEquals(4, assertIs<IdeTargetTerminalState.Active>(client.state()).replica.state.revision)
    }

    @Test
    fun `failed open can retry and replacing target closes the old token`() {
        val transport = RecordingTransport()
        val client = IdeTargetTerminalClient(transport)
        client.setTarget(TARGET)
        client.setVisible(true)
        client.accept(IdeTerminalFailedPayload(1, null, IdeTargetFailureKind.TargetLost, "lost", true))
        assertIs<IdeTargetTerminalState.Failed>(client.state())

        client.setVisible(true)
        assertEquals(IdeTerminalOpenPayload(2, TARGET), transport.sent.last())
        client.accept(IdeTerminalOpenedPayload(2, TOKEN, 7, state(4)))
        client.setTarget(OTHER_TARGET)

        assertEquals(IdeTerminalClosePayload(TOKEN), transport.sent[2])
        assertEquals(IdeTerminalOpenPayload(3, OTHER_TARGET), transport.sent[3])
    }

    @Test
    fun `detach close and connection loss clear ownership without duplicate closes`() {
        val transport = RecordingTransport()
        val client = activeClient(transport)
        client.setTarget(null)
        assertEquals(IdeTerminalClosePayload(TOKEN), transport.sent.last())
        assertIs<IdeTargetTerminalState.Closed>(client.state())

        client.close()
        assertEquals(2, transport.sent.size)

        val disconnected = activeClient(transport)
        disconnected.connectionLost()
        assertIs<IdeTargetTerminalState.Closed>(disconnected.state())
        assertTrue(transport.sent.last() !is IdeTerminalClosePayload)
    }

    @Test
    fun `machine loss invalidates the token once and remains retryable`() {
        val transport = RecordingTransport()
        val client = activeClient(transport)

        client.accept(IdeTerminalFailedPayload(1, TOKEN, IdeTargetFailureKind.TargetLost, "rebooted", true))
        val failed = assertIs<IdeTargetTerminalState.Failed>(client.state())
        assertEquals("rebooted", failed.detail)
        assertTrue(failed.retryable)

        client.close()
        assertEquals(1, transport.sent.filterIsInstance<IdeTerminalOpenPayload>().size)
        assertTrue(transport.sent.none { it is IdeTerminalClosePayload })
    }

    @Test
    fun `closing an active client is idempotent and closes its token exactly once`() {
        val transport = RecordingTransport()
        val client = activeClient(transport)

        client.close()
        client.close()

        assertEquals(listOf(IdeTerminalClosePayload(TOKEN)), transport.sent.filterIsInstance<IdeTerminalClosePayload>())
    }

    private fun activeClient(transport: RecordingTransport): IdeTargetTerminalClient =
        IdeTargetTerminalClient(transport).also { client ->
            client.setTarget(TARGET)
            client.setVisible(true)
            client.accept(IdeTerminalOpenedPayload(1, TOKEN, 7, state(4)))
        }

    private class RecordingTransport : IdeTargetTerminalTransport {
        val sent = mutableListOf<CustomPacketPayload>()

        override fun send(payload: CustomPacketPayload) {
            sent += payload
        }
    }

    private companion object {
        val TOKEN: UUID = UUID.fromString("d3354610-5460-4546-8546-000000000001")
        val OTHER_TOKEN: UUID = UUID.fromString("d3354610-5460-4546-8546-000000000002")
        val TARGET = IdeTargetReference(IdeTargetId("target-1"), IdeTargetProfileId(Hash256.zero()))
        val OTHER_TARGET = IdeTargetReference(IdeTargetId("target-2"), IdeTargetProfileId(Hash256.zero()))

        fun state(revision: Long) =
            TerminalState(
                revision,
                51,
                19,
                List(51 * 19) { TerminalCell(' '.code, 15, 0) },
                TerminalPosition(0, 0),
                true,
            )
    }
}
