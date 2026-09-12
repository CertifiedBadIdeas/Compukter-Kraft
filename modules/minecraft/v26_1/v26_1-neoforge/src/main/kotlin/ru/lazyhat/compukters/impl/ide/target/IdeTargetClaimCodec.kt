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

import net.minecraft.core.BlockPos
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface IdeTargetClaimOrigin {
    val dimension: String
    val position: BlockPos

    data class Terminal(
        override val dimension: String,
        override val position: BlockPos,
        val machineId: Long,
    ) : IdeTargetClaimOrigin {
        init {
            require(machineId > 0) { "terminal machine ID must be positive" }
        }
    }

    data class Crosshair(
        override val dimension: String,
        override val position: BlockPos,
    ) : IdeTargetClaimOrigin
}

internal object IdeTargetClaimCodec {
    fun encode(origin: IdeTargetClaimOrigin): IdeTargetClaim {
        val dimension = origin.dimension.encodeToByteArray()
        require(dimension.isNotEmpty() && dimension.size <= MAXIMUM_DIMENSION_BYTES) { "claim dimension is invalid" }
        require(origin.dimension.codePoints().noneMatch(Character::isISOControl)) { "claim dimension contains control characters" }
        val trailing = if (origin is IdeTargetClaimOrigin.Terminal) Long.SIZE_BYTES else 0
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dimension.size + POSITION_BYTES + trailing)
        buffer.put(VERSION)
        buffer.put(if (origin is IdeTargetClaimOrigin.Terminal) TERMINAL else CROSSHAIR)
        buffer.putShort(dimension.size.toShort())
        buffer.put(dimension)
        buffer.putInt(origin.position.x)
        buffer.putInt(origin.position.y)
        buffer.putInt(origin.position.z)
        if (origin is IdeTargetClaimOrigin.Terminal) buffer.putLong(origin.machineId)
        return IdeTargetClaim.of(buffer.array())
    }

    fun decode(claim: IdeTargetClaim): IdeTargetClaimOrigin? = decodeBytes(claim.bytes())

    fun decodeBytes(bytes: ByteArray): IdeTargetClaimOrigin? =
        runCatching {
            if (bytes.size < HEADER_BYTES + POSITION_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes)
            if (buffer.get() != VERSION) return null
            val kind = buffer.get()
            if (kind != TERMINAL && kind != CROSSHAIR) return null
            val dimensionSize = buffer.short.toInt() and 0xffff
            if (dimensionSize !in 1..MAXIMUM_DIMENSION_BYTES) return null
            val trailing = POSITION_BYTES + if (kind == TERMINAL) Long.SIZE_BYTES else 0
            if (buffer.remaining() != dimensionSize + trailing) return null
            val dimensionBytes = ByteArray(dimensionSize).also(buffer::get)
            val dimension =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(dimensionBytes))
                    .toString()
            if (dimension.codePoints().anyMatch(Character::isISOControl)) return null
            val position = BlockPos(buffer.int, buffer.int, buffer.int)
            when (kind) {
                TERMINAL -> IdeTargetClaimOrigin.Terminal(dimension, position, buffer.long)
                CROSSHAIR -> IdeTargetClaimOrigin.Crosshair(dimension, position)
                else -> null
            }
        }.getOrNull()

    private const val VERSION: Byte = 1
    private const val TERMINAL: Byte = 1
    private const val CROSSHAIR: Byte = 2
    private const val HEADER_BYTES = 4
    private const val POSITION_BYTES = Int.SIZE_BYTES * 3
    private const val MAXIMUM_DIMENSION_BYTES = 128
}

internal data class IdeTerminalTargetIdentity(
    val position: BlockPos,
    val machineId: Long,
) {
    init {
        require(machineId > 0) { "terminal machine ID must be positive" }
    }
}

internal object IdeTargetOpeningClaim {
    fun create(
        dimension: String?,
        terminal: IdeTerminalTargetIdentity?,
        crosshair: BlockPos?,
    ): IdeTargetClaim? {
        dimension ?: return null
        val origin =
            when {
                terminal != null -> IdeTargetClaimOrigin.Terminal(dimension, terminal.position, terminal.machineId)
                crosshair != null -> IdeTargetClaimOrigin.Crosshair(dimension, crosshair)
                else -> return null
            }
        return IdeTargetClaimCodec.encode(origin)
    }
}
