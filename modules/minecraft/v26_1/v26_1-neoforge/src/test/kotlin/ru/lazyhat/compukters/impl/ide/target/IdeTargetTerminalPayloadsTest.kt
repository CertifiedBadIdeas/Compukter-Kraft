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

import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
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
import kotlin.test.assertFailsWith

class IdeTargetTerminalPayloadsTest {
    @Test
    fun `session payloads round trip exact target token state delta and input`() {
        val open = IdeTerminalOpenPayload(7, TARGET)
        val opened = IdeTerminalOpenedPayload(7, TOKEN, 11, state(3))
        val full = IdeTerminalFullPayload(TOKEN, 11, state(4))
        val delta =
            IdeTerminalDeltaPayload(
                TOKEN,
                11,
                TerminalUpdate.Delta(4, 5, listOf(TerminalChange.Patch(0, listOf(cell('x'))))),
            )
        val resync = IdeTerminalResyncPayload(TOKEN, 11, 4)
        val key =
            IdeTerminalKeyPayload(
                TOKEN,
                11,
                TerminalKey.S,
                TerminalKeyAction.PRESS,
                setOf(TerminalModifier.CONTROL),
            )
        val text = IdeTerminalTextPayload(TOKEN, 11, "λ😀")
        val close = IdeTerminalClosePayload(TOKEN)
        val failed =
            IdeTerminalFailedPayload(
                generation = 7,
                token = TOKEN,
                kind = IdeTargetFailureKind.Protocol,
                detail = "revision gap",
                retryable = true,
            )

        assertEquals(open, roundTrip(IdeTerminalOpenPayload.STREAM_CODEC, open))
        assertEquals(opened, roundTrip(IdeTerminalOpenedPayload.STREAM_CODEC, opened))
        assertEquals(full, roundTrip(IdeTerminalFullPayload.STREAM_CODEC, full))
        assertEquals(delta, roundTrip(IdeTerminalDeltaPayload.STREAM_CODEC, delta))
        assertEquals(resync, roundTrip(IdeTerminalResyncPayload.STREAM_CODEC, resync))
        assertEquals(key, roundTrip(IdeTerminalKeyPayload.STREAM_CODEC, key))
        assertEquals(text, roundTrip(IdeTerminalTextPayload.STREAM_CODEC, text))
        assertEquals(close, roundTrip(IdeTerminalClosePayload.STREAM_CODEC, close))
        assertEquals(failed, roundTrip(IdeTerminalFailedPayload.STREAM_CODEC, failed))
    }

    @Test
    fun `session identity and bounded text reject invalid values before publication`() {
        assertFailsWith<IllegalArgumentException> { IdeTerminalOpenPayload(0, TARGET) }
        assertFailsWith<IllegalArgumentException> { IdeTerminalClosePayload(UUID(0, 0)) }
        assertFailsWith<IllegalArgumentException> { IdeTerminalTextPayload(TOKEN, 0, "x") }
        assertFailsWith<IllegalArgumentException> {
            roundTrip(
                IdeTerminalTextPayload.STREAM_CODEC,
                IdeTerminalTextPayload(TOKEN, 1, "x".repeat(4_097)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            IdeTerminalFailedPayload(1, null, IdeTargetFailureKind.Other, "x".repeat(513), false)
        }
    }

    @Suppress("DEPRECATION")
    private fun <T : Any> roundTrip(
        codec: StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
    ): T {
        val buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(Unpooled.buffer())
        codec.encode(buffer, value)
        return codec.decode(buffer)
    }

    private companion object {
        val TOKEN: UUID = UUID.fromString("d3354610-5460-4546-8546-000000000001")
        val TARGET = IdeTargetReference(IdeTargetId("target-1"), IdeTargetProfileId(Hash256.zero()))

        fun cell(value: Char) = TerminalCell(value.code, 15, 0)

        fun state(revision: Long) =
            TerminalState(
                revision,
                51,
                19,
                List(51 * 19) { cell(' ') },
                TerminalPosition(0, 0),
                true,
            )
    }
}
