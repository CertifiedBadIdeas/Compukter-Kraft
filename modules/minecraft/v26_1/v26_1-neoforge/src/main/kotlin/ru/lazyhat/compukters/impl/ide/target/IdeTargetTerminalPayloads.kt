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

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.impl.terminal.TerminalProtocol
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import java.util.UUID

internal data class IdeTerminalOpenPayload(
    val generation: Long,
    val target: IdeTargetReference,
) : CustomPacketPayload {
    init {
        requireGeneration(generation)
    }

    override fun type() = TYPE

    companion object {
        val TYPE = terminalType<IdeTerminalOpenPayload>("ide_terminal_open")
        val STREAM_CODEC =
            terminalCodec<IdeTerminalOpenPayload>(
                { buffer, payload ->
                    buffer.writeVarLong(payload.generation)
                    buffer.writeTarget(payload.target)
                },
                { buffer -> IdeTerminalOpenPayload(buffer.readVarLong(), buffer.readTarget()) },
            )
    }
}

internal data class IdeTerminalOpenedPayload(
    val generation: Long,
    val token: UUID,
    val machineId: Long,
    val state: TerminalState,
) : CustomPacketPayload {
    init {
        requireGeneration(generation)
        requireSession(token, machineId)
        TerminalProtocol.validateState(state)
    }

    override fun type() = TYPE

    companion object {
        val TYPE = terminalType<IdeTerminalOpenedPayload>("ide_terminal_opened")
        val STREAM_CODEC =
            terminalCodec<IdeTerminalOpenedPayload>(
                { buffer, payload ->
                    buffer.writeVarLong(payload.generation)
                    buffer.writeSession(payload.token, payload.machineId)
                    TerminalProtocol.writeState(buffer, payload.state)
                },
                { buffer ->
                    val generation = buffer.readVarLong()
                    val session = buffer.readSession()
                    IdeTerminalOpenedPayload(generation, session.token, session.machineId, TerminalProtocol.readState(buffer))
                },
            )
    }
}

internal data class IdeTerminalFullPayload(
    val token: UUID,
    val machineId: Long,
    val state: TerminalState,
) : CustomPacketPayload {
    init {
        requireSession(token, machineId)
        TerminalProtocol.validateState(state)
    }

    override fun type() = TYPE

    companion object {
        val TYPE = terminalType<IdeTerminalFullPayload>("ide_terminal_full")
        val STREAM_CODEC =
            terminalCodec<IdeTerminalFullPayload>(
                { buffer, payload ->
                    buffer.writeSession(payload.token, payload.machineId)
                    TerminalProtocol.writeState(buffer, payload.state)
                },
                { buffer ->
                    val session = buffer.readSession()
                    IdeTerminalFullPayload(session.token, session.machineId, TerminalProtocol.readState(buffer))
                },
            )
    }
}

internal data class IdeTerminalDeltaPayload(
    val token: UUID,
    val machineId: Long,
    val delta: TerminalUpdate.Delta,
) : CustomPacketPayload {
    init {
        requireSession(token, machineId)
        TerminalProtocol.validateDelta(delta)
    }

    override fun type() = TYPE

    companion object {
        val TYPE = terminalType<IdeTerminalDeltaPayload>("ide_terminal_delta")
        val STREAM_CODEC =
            terminalCodec<IdeTerminalDeltaPayload>(
                { buffer, payload ->
                    buffer.writeSession(payload.token, payload.machineId)
                    TerminalProtocol.writeDelta(buffer, payload.delta)
                },
                { buffer ->
                    val session = buffer.readSession()
                    IdeTerminalDeltaPayload(session.token, session.machineId, TerminalProtocol.readDelta(buffer))
                },
            )
    }
}

internal data class IdeTerminalResyncPayload(
    val token: UUID,
    val machineId: Long,
    val revision: Long,
) : CustomPacketPayload {
    init {
        requireSession(token, machineId)
        require(revision >= 0) { "terminal revision must not be negative" }
    }

    override fun type() = TYPE

    companion object {
        val TYPE = terminalType<IdeTerminalResyncPayload>("ide_terminal_resync")
        val STREAM_CODEC =
            terminalCodec<IdeTerminalResyncPayload>(
                { buffer, payload ->
                    buffer.writeSession(payload.token, payload.machineId)
                    TerminalProtocol.writeRevision(buffer, payload.revision)
                },
                { buffer ->
                    val session = buffer.readSession()
                    IdeTerminalResyncPayload(session.token, session.machineId, TerminalProtocol.readRevision(buffer))
                },
            )
    }
}

internal data class IdeTerminalKeyPayload(
    val token: UUID,
    val machineId: Long,
    val key: TerminalKey,
    val action: TerminalKeyAction,
    val modifiers: Set<TerminalModifier>,
) : CustomPacketPayload {
    init {
        requireSession(token, machineId)
    }

    override fun type() = TYPE

    companion object {
        val TYPE = terminalType<IdeTerminalKeyPayload>("ide_terminal_key")
        val STREAM_CODEC =
            terminalCodec<IdeTerminalKeyPayload>(
                { buffer, payload ->
                    buffer.writeSession(payload.token, payload.machineId)
                    buffer.writeVarInt(payload.key.wireCode)
                    buffer.writeByte(payload.action.wireCode)
                    buffer.writeByte(payload.modifiers.fold(0) { bits, modifier -> bits or modifier.mask })
                },
                { buffer ->
                    val session = buffer.readSession()
                    val keyCode = buffer.readVarInt()
                    val actionCode = buffer.readUnsignedByte().toInt()
                    val modifierBits = buffer.readUnsignedByte().toInt()
                    val key =
                        TerminalKey.entries.singleOrNull { it.wireCode == keyCode }
                            ?: throw IllegalArgumentException("unknown terminal key")
                    val action =
                        TerminalKeyAction.entries.singleOrNull { it.wireCode == actionCode }
                            ?: throw IllegalArgumentException("unknown terminal key action")
                    val known = TerminalModifier.entries.fold(0) { bits, modifier -> bits or modifier.mask }
                    require(modifierBits and known.inv() == 0) { "unknown terminal modifier" }
                    val modifiers = TerminalModifier.entries.filterTo(linkedSetOf()) { modifierBits and it.mask != 0 }
                    IdeTerminalKeyPayload(session.token, session.machineId, key, action, modifiers)
                },
            )
    }
}

internal data class IdeTerminalTextPayload(
    val token: UUID,
    val machineId: Long,
    val text: String,
) : CustomPacketPayload {
    init {
        requireSession(token, machineId)
        TerminalProtocol.requireAtomicText(text)
    }

    override fun type() = TYPE

    companion object {
        val TYPE = terminalType<IdeTerminalTextPayload>("ide_terminal_text")
        val STREAM_CODEC =
            terminalCodec<IdeTerminalTextPayload>(
                { buffer, payload ->
                    buffer.writeSession(payload.token, payload.machineId)
                    buffer.writeUtf(payload.text, TerminalProtocol.MAXIMUM_TEXT_CODE_UNITS)
                },
                { buffer ->
                    val session = buffer.readSession()
                    IdeTerminalTextPayload(
                        session.token,
                        session.machineId,
                        buffer.readUtf(TerminalProtocol.MAXIMUM_TEXT_CODE_UNITS),
                    )
                },
            )
    }
}

internal data class IdeTerminalClosePayload(
    val token: UUID,
) : CustomPacketPayload {
    init {
        requireToken(token)
    }

    override fun type() = TYPE

    companion object {
        val TYPE = terminalType<IdeTerminalClosePayload>("ide_terminal_close")
        val STREAM_CODEC =
            terminalCodec<IdeTerminalClosePayload>(
                { buffer, payload -> buffer.writeUUID(payload.token) },
                { buffer -> IdeTerminalClosePayload(buffer.readUUID()) },
            )
    }
}

internal data class IdeTerminalFailedPayload(
    val generation: Long,
    val token: UUID?,
    val kind: IdeTargetFailureKind,
    val detail: String,
    val retryable: Boolean,
) : CustomPacketPayload {
    init {
        requireGeneration(generation)
        token?.let(::requireToken)
        require(detail.length <= MAXIMUM_FAILURE_DETAIL_CODE_UNITS) { "terminal failure detail is too long" }
    }

    override fun type() = TYPE

    companion object {
        val TYPE = terminalType<IdeTerminalFailedPayload>("ide_terminal_failed")
        val STREAM_CODEC =
            terminalCodec<IdeTerminalFailedPayload>(
                { buffer, payload ->
                    buffer.writeVarLong(payload.generation)
                    buffer.writeBoolean(payload.token != null)
                    payload.token?.let(buffer::writeUUID)
                    buffer.writeVarInt(payload.kind.ordinal)
                    buffer.writeUtf(payload.detail, MAXIMUM_FAILURE_DETAIL_CODE_UNITS)
                    buffer.writeBoolean(payload.retryable)
                },
                { buffer ->
                    val generation = buffer.readVarLong()
                    val token = if (buffer.readBoolean()) buffer.readUUID() else null
                    val kindOrdinal = buffer.readVarInt()
                    val kind =
                        IdeTargetFailureKind.entries.getOrNull(kindOrdinal)
                            ?: throw IllegalArgumentException("unknown terminal failure kind")
                    IdeTerminalFailedPayload(
                        generation,
                        token,
                        kind,
                        buffer.readUtf(MAXIMUM_FAILURE_DETAIL_CODE_UNITS),
                        buffer.readBoolean(),
                    )
                },
            )
    }
}

private data class SessionIdentity(
    val token: UUID,
    val machineId: Long,
)

private fun RegistryFriendlyByteBuf.writeSession(
    token: UUID,
    machineId: Long,
) {
    requireSession(token, machineId)
    writeUUID(token)
    writeLong(machineId)
}

private fun RegistryFriendlyByteBuf.readSession(): SessionIdentity =
    SessionIdentity(readUUID(), readLong()).also { requireSession(it.token, it.machineId) }

private fun requireSession(
    token: UUID,
    machineId: Long,
) {
    requireToken(token)
    require(machineId > 0) { "terminal machine ID must be positive" }
}

private fun requireToken(token: UUID) {
    require(token.mostSignificantBits != 0L || token.leastSignificantBits != 0L) { "terminal session token must not be zero" }
}

private fun requireGeneration(generation: Long) {
    require(generation > 0) { "terminal client generation must be positive" }
}

private fun <T : CustomPacketPayload> terminalType(path: String) =
    CustomPacketPayload.Type<T>(Identifier.fromNamespaceAndPath(MOD_ID, path))

private fun <T : Any> terminalCodec(
    encoder: (RegistryFriendlyByteBuf, T) -> Unit,
    decoder: (RegistryFriendlyByteBuf) -> T,
): StreamCodec<RegistryFriendlyByteBuf, T> = StreamCodec.of(StreamEncoder(encoder), StreamDecoder(decoder))

private const val MAXIMUM_FAILURE_DETAIL_CODE_UNITS = 512
