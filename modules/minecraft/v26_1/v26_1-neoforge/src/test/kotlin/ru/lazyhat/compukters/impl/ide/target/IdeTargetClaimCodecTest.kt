/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package ru.lazyhat.compukters.impl.ide.target

import net.minecraft.core.BlockPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdeTargetClaimCodecTest {
    @Test
    fun `terminal and crosshair claims round trip without client authority`() {
        val terminal = IdeTargetClaimOrigin.Terminal("minecraft:overworld", BlockPos(1, -20, 3), 17)
        val crosshair = IdeTargetClaimOrigin.Crosshair("minecraft:the_nether", BlockPos(-4, 70, 9))

        assertEquals(terminal, IdeTargetClaimCodec.decode(IdeTargetClaimCodec.encode(terminal)))
        assertEquals(crosshair, IdeTargetClaimCodec.decode(IdeTargetClaimCodec.encode(crosshair)))
    }

    @Test
    fun `malformed version kind trailing bytes and oversized dimension are rejected`() {
        val valid = IdeTargetClaimCodec.encode(IdeTargetClaimOrigin.Crosshair("minecraft:overworld", BlockPos.ZERO)).bytes()

        assertNull(IdeTargetClaimCodec.decodeBytes(valid.copyOf().also { it[0] = 2 }))
        assertNull(IdeTargetClaimCodec.decodeBytes(valid.copyOf().also { it[1] = 9 }))
        assertNull(IdeTargetClaimCodec.decodeBytes(valid + 0))
        assertNull(IdeTargetClaimCodec.decodeBytes(byteArrayOf(1, 2, 0, 0)))
    }

    @Test
    fun `opening origin prefers terminal then crosshair and otherwise stays local`() {
        val terminal = IdeTerminalTargetIdentity(BlockPos(1, 2, 3), machineId = 7)
        val hit = BlockPos(4, 5, 6)

        assertEquals(
            IdeTargetClaimOrigin.Terminal("minecraft:overworld", terminal.position, terminal.machineId),
            IdeTargetClaimCodec.decode(checkNotNull(IdeTargetOpeningClaim.create("minecraft:overworld", terminal, hit))),
        )
        assertEquals(
            IdeTargetClaimOrigin.Crosshair("minecraft:overworld", hit),
            IdeTargetClaimCodec.decode(checkNotNull(IdeTargetOpeningClaim.create("minecraft:overworld", null, hit))),
        )
        assertNull(IdeTargetOpeningClaim.create("minecraft:overworld", null, null))
        assertNull(IdeTargetOpeningClaim.create(null, terminal, hit))
    }
}
