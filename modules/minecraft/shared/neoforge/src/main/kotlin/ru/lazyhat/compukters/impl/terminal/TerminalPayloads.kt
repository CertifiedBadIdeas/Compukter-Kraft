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
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalChange
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate

data class TerminalFullPayload(
    val position: BlockPos,
    val machineId: Long,
    val state: TerminalState,
    val openScreen: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TerminalFullPayload> = TYPE

    companion object {
        val TYPE = type<TerminalFullPayload>("terminal_full")
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerminalFullPayload> =
            codec(
                { buffer, payload ->
                    TerminalProtocol.writeIdentity(buffer, payload.position, payload.machineId)
                    TerminalProtocol.writeState(buffer, payload.state)
                    buffer.writeBoolean(payload.openScreen)
                },
                { buffer ->
                    val identity = TerminalProtocol.readIdentity(buffer)
                    TerminalFullPayload(identity.position, identity.machineId, TerminalProtocol.readState(buffer), buffer.readBoolean())
                },
            )
    }
}

data class TerminalDeltaPayload(
    val position: BlockPos,
    val machineId: Long,
    val delta: TerminalUpdate.Delta,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TerminalDeltaPayload> = TYPE

    companion object {
        val TYPE = type<TerminalDeltaPayload>("terminal_delta")
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerminalDeltaPayload> =
            codec(
                { buffer, payload ->
                    TerminalProtocol.writeIdentity(buffer, payload.position, payload.machineId)
                    TerminalProtocol.writeDelta(buffer, payload.delta)
                },
                { buffer ->
                    val identity = TerminalProtocol.readIdentity(buffer)
                    TerminalDeltaPayload(identity.position, identity.machineId, TerminalProtocol.readDelta(buffer))
                },
            )
    }
}

data class TerminalResyncPayload(
    val position: BlockPos,
    val machineId: Long,
    val revision: Long,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TerminalResyncPayload> = TYPE

    companion object {
        val TYPE = type<TerminalResyncPayload>("terminal_resync")
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerminalResyncPayload> =
            codec(
                { buffer, payload ->
                    TerminalProtocol.writeIdentity(buffer, payload.position, payload.machineId)
                    TerminalProtocol.writeRevision(buffer, payload.revision)
                },
                { buffer ->
                    val identity = TerminalProtocol.readIdentity(buffer)
                    TerminalResyncPayload(identity.position, identity.machineId, TerminalProtocol.readRevision(buffer))
                },
            )
    }
}

data class TerminalClosePayload(
    val position: BlockPos,
    val machineId: Long,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TerminalClosePayload> = TYPE

    companion object {
        val TYPE = type<TerminalClosePayload>("terminal_close")
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerminalClosePayload> =
            codec(
                { buffer, payload -> TerminalProtocol.writeIdentity(buffer, payload.position, payload.machineId) },
                { buffer ->
                    val identity = TerminalProtocol.readIdentity(buffer)
                    TerminalClosePayload(identity.position, identity.machineId)
                },
            )
    }
}

data class TerminalKeyPayload(
    val position: BlockPos,
    val machineId: Long,
    val key: TerminalKey,
    val action: TerminalKeyAction,
    val modifiers: Set<TerminalModifier>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TerminalKeyPayload> = TYPE

    companion object {
        val TYPE = type<TerminalKeyPayload>("terminal_key")
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerminalKeyPayload> =
            codec(
                { buffer, payload ->
                    TerminalProtocol.writeIdentity(buffer, payload.position, payload.machineId)
                    buffer.writeVarInt(payload.key.wireCode)
                    buffer.writeByte(payload.action.wireCode)
                    buffer.writeByte(payload.modifiers.fold(0) { bits, modifier -> bits or modifier.mask })
                },
                { buffer ->
                    val identity = TerminalProtocol.readIdentity(buffer)
                    val keyCode = buffer.readVarInt()
                    val actionCode = buffer.readU8()
                    val modifierBits = buffer.readU8()
                    val key = TerminalKey.entries.singleOrNull { it.wireCode == keyCode } ?: invalid("unknown terminal key")
                    val action =
                        TerminalKeyAction.entries.singleOrNull { it.wireCode == actionCode }
                            ?: invalid("unknown terminal key action")
                    val knownModifierBits = TerminalModifier.entries.fold(0) { bits, modifier -> bits or modifier.mask }
                    require(modifierBits and knownModifierBits.inv() == 0) { "unknown terminal modifier" }
                    val modifiers = TerminalModifier.entries.filterTo(linkedSetOf()) { modifierBits and it.mask != 0 }
                    TerminalKeyPayload(identity.position, identity.machineId, key, action, modifiers)
                },
            )
    }
}

data class TerminalTextPayload(
    val position: BlockPos,
    val machineId: Long,
    val text: String,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TerminalTextPayload> = TYPE

    companion object {
        val TYPE = type<TerminalTextPayload>("terminal_text")
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerminalTextPayload> =
            codec(
                { buffer, payload ->
                    TerminalProtocol.writeIdentity(buffer, payload.position, payload.machineId)
                    TerminalProtocol.requireAtomicText(payload.text)
                    buffer.writeUtf(payload.text, TerminalProtocol.MAXIMUM_TEXT_CODE_UNITS)
                },
                { buffer ->
                    val identity = TerminalProtocol.readIdentity(buffer)
                    val text = buffer.readUtf(TerminalProtocol.MAXIMUM_TEXT_CODE_UNITS)
                    TerminalProtocol.requireAtomicText(text)
                    TerminalTextPayload(identity.position, identity.machineId, text)
                },
            )
    }
}

internal object TerminalProtocol {
    const val WIDTH = 51
    const val HEIGHT = 19
    const val CELL_COUNT = WIDTH * HEIGHT
    const val MAXIMUM_TEXT_CODE_UNITS = 4_096
    private const val MAXIMUM_CHANGES = 4_096
    private const val MAXIMUM_ENCODED_DELTA_CELLS = 8_192
    private const val PALETTE_SIZE = 16

    fun validateState(state: TerminalState) {
        require(state.revision >= 0) { "terminal revision must not be negative" }
        require(state.width == WIDTH && state.height == HEIGHT) { "unsupported terminal dimensions" }
        require(state.cells.size == CELL_COUNT) { "invalid terminal cell count" }
        state.cells.forEach(::validateCell)
        validatePosition(state.cursor)
    }

    fun validateDelta(delta: TerminalUpdate.Delta) {
        require(delta.baseRevision >= 0 && delta.targetRevision > delta.baseRevision) { "invalid terminal delta revisions" }
        require(delta.changes.size <= MAXIMUM_CHANGES) { "too many terminal changes" }
        delta.changes.forEach(::validateChange)
        val encodedCells =
            delta.changes.sumOf { change ->
                when (change) {
                    is TerminalChange.Patch -> change.cells.size

                    is TerminalChange.Fill,
                    is TerminalChange.Scroll,
                    -> 1

                    is TerminalChange.Cursor,
                    TerminalChange.Reset,
                    -> 0
                }
            }
        require(encodedCells <= MAXIMUM_ENCODED_DELTA_CELLS) { "terminal delta cell payload is too large" }
    }

    fun writeIdentity(
        buffer: RegistryFriendlyByteBuf,
        position: BlockPos,
        machineId: Long,
    ) {
        require(machineId > 0) { "terminal machine id must be positive" }
        buffer.writeBlockPos(position)
        buffer.writeLong(machineId)
    }

    fun readIdentity(buffer: RegistryFriendlyByteBuf): Identity {
        val position = buffer.readBlockPos()
        val machineId = buffer.readLong()
        require(machineId > 0) { "terminal machine id must be positive" }
        return Identity(position, machineId)
    }

    fun writeState(
        buffer: RegistryFriendlyByteBuf,
        state: TerminalState,
    ) {
        validateState(state)
        writeRevision(buffer, state.revision)
        buffer.writeByte(state.width)
        buffer.writeByte(state.height)
        buffer.writeVarInt(state.cells.size)
        state.cells.forEach { writeCell(buffer, it) }
        writePosition(buffer, state.cursor)
        buffer.writeBoolean(state.cursorVisible)
    }

    fun readState(buffer: RegistryFriendlyByteBuf): TerminalState {
        val revision = readRevision(buffer)
        val width = buffer.readU8()
        val height = buffer.readU8()
        require(width == WIDTH && height == HEIGHT) { "unsupported terminal dimensions" }
        val count = buffer.readVarInt()
        require(count == CELL_COUNT) { "invalid terminal cell count" }
        val cells = List(count) { readCell(buffer) }
        val state = TerminalState(revision, width, height, cells, readPosition(buffer), buffer.readBoolean())
        validateState(state)
        return state
    }

    fun writeDelta(
        buffer: RegistryFriendlyByteBuf,
        delta: TerminalUpdate.Delta,
    ) {
        validateDelta(delta)
        writeRevision(buffer, delta.baseRevision)
        writeRevision(buffer, delta.targetRevision)
        buffer.writeVarInt(delta.changes.size)
        delta.changes.forEach { writeChange(buffer, it) }
    }

    fun readDelta(buffer: RegistryFriendlyByteBuf): TerminalUpdate.Delta {
        val base = readRevision(buffer)
        val target = readRevision(buffer)
        require(target > base) { "invalid terminal delta revisions" }
        val count = buffer.readVarInt()
        require(count in 0..MAXIMUM_CHANGES) { "invalid terminal change count" }
        val budget = CellBudget(MAXIMUM_ENCODED_DELTA_CELLS)
        val delta = TerminalUpdate.Delta(base, target, List(count) { readChange(buffer, budget) })
        validateDelta(delta)
        return delta
    }

    fun writeRevision(
        buffer: RegistryFriendlyByteBuf,
        revision: Long,
    ) {
        require(revision >= 0) { "terminal revision must not be negative" }
        buffer.writeLong(revision)
    }

    fun readRevision(buffer: RegistryFriendlyByteBuf): Long =
        buffer.readLong().also { require(it >= 0) { "terminal revision must not be negative" } }

    fun requireAtomicText(text: String) {
        require(text.length <= MAXIMUM_TEXT_CODE_UNITS) { "terminal text is too long" }
        val scalars = text.codePoints().toArray()
        require(scalars.size <= MAXIMUM_TEXT_CODE_UNITS) { "terminal text has too many scalars" }
        require(scalars.all(::isScalar)) { "terminal text contains an invalid Unicode scalar" }
    }

    private fun writeChange(
        buffer: RegistryFriendlyByteBuf,
        change: TerminalChange,
    ) {
        validateChange(change)
        when (change) {
            is TerminalChange.Patch -> {
                buffer.writeByte(0)
                buffer.writeVarInt(change.start)
                buffer.writeVarInt(change.cells.size)
                change.cells.forEach { writeCell(buffer, it) }
            }

            is TerminalChange.Fill -> {
                buffer.writeByte(1)
                buffer.writeByte(change.x)
                buffer.writeByte(change.y)
                buffer.writeByte(change.width)
                buffer.writeByte(change.height)
                writeCell(buffer, change.cell)
            }

            is TerminalChange.Scroll -> {
                buffer.writeByte(2)
                buffer.writeByte(change.rows)
                writeCell(buffer, change.fill)
            }

            is TerminalChange.Cursor -> {
                buffer.writeByte(3)
                writePosition(buffer, change.position)
                buffer.writeBoolean(change.visible)
            }

            TerminalChange.Reset -> {
                buffer.writeByte(4)
            }
        }
    }

    private fun readChange(
        buffer: RegistryFriendlyByteBuf,
        budget: CellBudget,
    ): TerminalChange =
        when (buffer.readU8()) {
            0 -> {
                val start = buffer.readVarInt()
                val count = buffer.readVarInt()
                require(count in 1..CELL_COUNT && start in 0 until CELL_COUNT && count <= CELL_COUNT - start) {
                    "invalid terminal patch"
                }
                budget.consume(count)
                TerminalChange.Patch(start, List(count) { readCell(buffer) })
            }

            1 -> {
                budget.consume(1)
                TerminalChange
                    .Fill(
                        buffer.readU8(),
                        buffer.readU8(),
                        buffer.readU8(),
                        buffer.readU8(),
                        readCell(buffer),
                    ).also(::validateChange)
            }

            2 -> {
                budget.consume(1)
                TerminalChange.Scroll(buffer.readU8(), readCell(buffer)).also(::validateChange)
            }

            3 -> {
                TerminalChange.Cursor(readPosition(buffer), buffer.readBoolean())
            }

            4 -> {
                TerminalChange.Reset
            }

            else -> {
                invalid("unknown terminal change")
            }
        }

    private fun validateChange(change: TerminalChange) {
        when (change) {
            is TerminalChange.Patch -> {
                require(change.cells.isNotEmpty() && change.start in 0 until CELL_COUNT) { "invalid terminal patch" }
                require(change.cells.size <= CELL_COUNT - change.start) { "invalid terminal patch" }
                change.cells.forEach(::validateCell)
            }

            is TerminalChange.Fill -> {
                require(change.width > 0 && change.height > 0) { "invalid terminal fill" }
                require(change.x in 0 until WIDTH && change.y in 0 until HEIGHT) { "invalid terminal fill" }
                require(change.width <= WIDTH - change.x && change.height <= HEIGHT - change.y) { "invalid terminal fill" }
                validateCell(change.cell)
            }

            is TerminalChange.Scroll -> {
                require(change.rows in 1..HEIGHT) { "invalid terminal scroll" }
                validateCell(change.fill)
            }

            is TerminalChange.Cursor -> {
                validatePosition(change.position)
            }

            TerminalChange.Reset -> {}
        }
    }

    private fun writeCell(
        buffer: RegistryFriendlyByteBuf,
        cell: TerminalCell,
    ) {
        validateCell(cell)
        buffer.writeVarInt(cell.codePoint)
        buffer.writeByte(cell.foreground or (cell.background shl 4))
    }

    private fun readCell(buffer: RegistryFriendlyByteBuf): TerminalCell {
        val codePoint = buffer.readVarInt()
        val colors = buffer.readU8()
        return TerminalCell(codePoint, colors and 0xf, colors ushr 4).also(::validateCell)
    }

    private fun validateCell(cell: TerminalCell) {
        require(isScalar(cell.codePoint)) { "invalid terminal Unicode scalar" }
        require(cell.foreground in 0 until PALETTE_SIZE && cell.background in 0 until PALETTE_SIZE) {
            "invalid terminal palette index"
        }
    }

    private fun writePosition(
        buffer: RegistryFriendlyByteBuf,
        position: TerminalPosition,
    ) {
        validatePosition(position)
        buffer.writeByte(position.x)
        buffer.writeByte(position.y)
    }

    private fun readPosition(buffer: RegistryFriendlyByteBuf): TerminalPosition =
        TerminalPosition(buffer.readU8(), buffer.readU8()).also(::validatePosition)

    private fun validatePosition(position: TerminalPosition) {
        require(position.x in 0 until WIDTH && position.y in 0 until HEIGHT) { "invalid terminal position" }
    }

    private fun isScalar(value: Int): Boolean =
        Character.isValidCodePoint(value) && value !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code

    data class Identity(
        val position: BlockPos,
        val machineId: Long,
    )

    private class CellBudget(
        private var remaining: Int,
    ) {
        fun consume(count: Int) {
            require(count <= remaining) { "terminal delta cell payload is too large" }
            remaining -= count
        }
    }
}

private fun <T : CustomPacketPayload> type(path: String): CustomPacketPayload.Type<T> =
    CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(MOD_ID, path))

private fun <T : Any> codec(
    encoder: (RegistryFriendlyByteBuf, T) -> Unit,
    decoder: (RegistryFriendlyByteBuf) -> T,
): StreamCodec<RegistryFriendlyByteBuf, T> = StreamCodec.of(StreamEncoder(encoder), StreamDecoder(decoder))

private fun RegistryFriendlyByteBuf.readU8(): Int = readUnsignedByte().toInt()

private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
