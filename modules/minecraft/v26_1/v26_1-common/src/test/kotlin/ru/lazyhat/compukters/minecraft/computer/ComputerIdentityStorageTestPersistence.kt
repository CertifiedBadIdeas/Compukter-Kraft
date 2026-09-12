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

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput
import java.util.stream.Stream

internal object ComputerIdentityStorageTestPersistence {
    private val emptyProvider = HolderLookup.Provider.create(Stream.empty())

    fun roundTrip(source: ComputerIdentityStorage): ComputerIdentityStorage {
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, emptyProvider)
        source.save(output)
        return ComputerIdentityStorage().also {
            it.load(TagValueInput.create(ProblemReporter.DISCARDING, emptyProvider, output.buildResult()))
        }
    }

    fun load(
        storage: ComputerIdentityStorage,
        high: Long? = null,
        low: Long? = null,
    ) {
        val tag =
            CompoundTag().also {
                high?.let { value -> it.putLong("computer_id_high", value) }
                low?.let { value -> it.putLong("computer_id_low", value) }
            }
        storage.load(TagValueInput.create(ProblemReporter.DISCARDING, emptyProvider, tag))
    }
}
