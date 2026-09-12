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
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput
import java.util.stream.Stream

internal abstract class ComputerBlockEntityTestPersistence(
    type: BlockEntityType<*>,
    position: BlockPos,
    blockState: BlockState,
    carrierFactory: ComputerCarrierFactory,
) : ComputerBlockEntity(type, position, blockState, carrierFactory) {
    fun saveForTest(): CompoundTag {
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, EMPTY_PROVIDER)
        saveAdditional(output)
        return output.buildResult()
    }

    fun loadForTest(tag: CompoundTag) = loadAdditional(TagValueInput.create(ProblemReporter.DISCARDING, EMPTY_PROVIDER, tag))

    companion object {
        private val EMPTY_PROVIDER = HolderLookup.Provider.create(Stream.empty())

        fun redstoneOutput(tag: CompoundTag): Int = tag.getCompoundOrEmpty("compukters").getIntOr("redstoneOutput", -1)

        fun keys(tag: CompoundTag): Set<String> = tag.keySet()

        fun writeRedstoneOutput(
            tag: CompoundTag,
            value: Int,
        ) {
            tag.getCompoundOrEmpty("compukters").putInt("redstoneOutput", value)
        }

        fun replacePayload(
            tag: CompoundTag,
            payload: CompoundTag,
        ) = tag.put("compukters", payload)
    }
}
