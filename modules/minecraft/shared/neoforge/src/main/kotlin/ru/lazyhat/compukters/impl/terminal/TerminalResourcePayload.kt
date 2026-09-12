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

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.impl.compat.Identifier

internal data class TerminalResourcePayload(
    val position: BlockPos,
    val machineId: Long,
    val gauges: TerminalResourceGauges,
) : CustomPacketPayload {
    init {
        require(machineId > 0) { "terminal machine id must be positive" }
    }

    override fun type(): CustomPacketPayload.Type<TerminalResourcePayload> = TYPE

    companion object {
        val TYPE = resourceType<TerminalResourcePayload>("terminal_resources")
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerminalResourcePayload> =
            resourceCodec(
                { buffer, payload ->
                    TerminalProtocol.writeIdentity(buffer, payload.position, payload.machineId)
                    val budget = payload.gauges.vmBudgetBasisPoints
                    buffer.writeBoolean(budget != null)
                    if (budget != null) buffer.writeVarInt(budget)
                    buffer.writeLong(payload.gauges.heapUsedBytes)
                    buffer.writeLong(payload.gauges.heapCapacityBytes)
                    buffer.writeLong(payload.gauges.diskUsedBytes)
                    buffer.writeLong(payload.gauges.diskCapacityBytes)
                    buffer.writeByte(payload.gauges.activity.ordinal)
                    buffer.writeBoolean(payload.gauges.countersSaturated)
                },
                { buffer ->
                    val identity = TerminalProtocol.readIdentity(buffer)
                    val budget = if (buffer.readBoolean()) buffer.readVarInt() else null
                    val gauges =
                        TerminalResourceGauges(
                            vmBudgetBasisPoints = budget,
                            heapUsedBytes = buffer.readLong(),
                            heapCapacityBytes = buffer.readLong(),
                            diskUsedBytes = buffer.readLong(),
                            diskCapacityBytes = buffer.readLong(),
                            activity =
                                TerminalResourceActivity.entries.getOrNull(buffer.readUnsignedByte().toInt())
                                    ?: throw IllegalArgumentException("unknown terminal resource activity"),
                            countersSaturated = buffer.readBoolean(),
                        )
                    TerminalResourcePayload(identity.position, identity.machineId, gauges)
                },
            )
    }
}

private fun <T : CustomPacketPayload> resourceType(path: String): CustomPacketPayload.Type<T> =
    CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(MOD_ID, path))

private fun <T : Any> resourceCodec(
    encoder: (RegistryFriendlyByteBuf, T) -> Unit,
    decoder: (RegistryFriendlyByteBuf) -> T,
): StreamCodec<RegistryFriendlyByteBuf, T> = StreamCodec.of(StreamEncoder(encoder), StreamDecoder(decoder))
