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
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdeTargetRequestPayloadTest {
    @Test
    fun `all target requests round trip through one bounded envelope`() {
        val target = IdeTargetReference(IdeTargetId("lease-1"), IdeTargetProfileId(hash(1)))
        val path = IdeDeploymentPath.fromProgramName("demo")
        val requests =
            listOf(
                IdeTargetRequest.Attach(BinaryValue.of(byteArrayOf(1, 2))),
                IdeTargetRequest.BeginUpload(target, hash(2), bytes = 12),
                IdeTargetRequest.UploadChunk(target, offset = 4, BinaryValue.of(byteArrayOf(3, 4))),
                IdeTargetRequest.Verify(target),
                IdeTargetRequest.ExecutableRevision(target, path),
                IdeTargetRequest.Deploy(
                    target,
                    ticket = BinaryValue.of(byteArrayOf(5)),
                    artifactHash = hash(2),
                    artifactBytes = 12,
                    path = path,
                    expected = IdeExecutableRevision.Present(7),
                ),
                IdeTargetRequest.SubmitCanonicalLine(target, IdeCanonicalLine.of(charArrayOf('a', '\uD83D', '\uDE00'))),
                IdeTargetRequest.Heartbeat(target),
                IdeTargetRequest.Detach(target),
            )

        requests.forEachIndexed { index, request ->
            val payload = IdeTargetRequestPayload(index.toLong() + 1, request)
            assertEquals(payload, roundTrip(IdeTargetRequestPayload.STREAM_CODEC, payload))
        }
    }

    @Test
    fun `request envelope rejects reserved IDs and oversized atomic values`() {
        assertFailsWith<IllegalArgumentException> {
            IdeTargetRequestPayload(0, IdeTargetRequest.Attach(BinaryValue.of(byteArrayOf(1))))
        }
        assertFailsWith<IllegalArgumentException> {
            roundTrip(
                IdeTargetRequestPayload.STREAM_CODEC,
                IdeTargetRequestPayload(
                    1,
                    IdeTargetRequest.UploadChunk(
                        IdeTargetReference(IdeTargetId("lease-1"), IdeTargetProfileId(hash(1))),
                        0,
                        BinaryValue.of(ByteArray(32 * 1024 + 1)),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> { IdeCanonicalLine.of(CharArray(4_097) { 'x' }) }
    }

    private fun <T : Any> roundTrip(
        codec: StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
    ): T {
        val buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(Unpooled.buffer())
        codec.encode(buffer, value)
        return codec.decode(buffer)
    }

    private fun hash(seed: Int): Hash256 = Hash256.of(ByteArray(32) { seed.toByte() })
}
