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

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import java.util.stream.Stream

internal abstract class ComputerBlockEntityTestPersistence(
    type: BlockEntityType<*>,
    position: BlockPos,
    blockState: BlockState,
    carrierFactory: ComputerCarrierFactory,
) : ComputerBlockEntity(type, position, blockState, carrierFactory) {
    fun saveForTest(): CompoundTag = CompoundTag().also { saveAdditional(it, EMPTY_PROVIDER) }

    fun loadForTest(tag: CompoundTag) = loadAdditional(tag, EMPTY_PROVIDER)

    companion object {
        private val EMPTY_PROVIDER = HolderLookup.Provider.create(Stream.empty())

        fun redstoneOutput(tag: CompoundTag): Int = tag.getCompound("compukters").getInt("redstoneOutput")

        fun keys(tag: CompoundTag): Set<String> = tag.allKeys

        fun writeRedstoneOutput(
            tag: CompoundTag,
            value: Int,
        ) {
            tag.getCompound("compukters").putInt("redstoneOutput", value)
        }

        fun replacePayload(
            tag: CompoundTag,
            payload: CompoundTag,
        ) = tag.put("compukters", payload)
    }
}
