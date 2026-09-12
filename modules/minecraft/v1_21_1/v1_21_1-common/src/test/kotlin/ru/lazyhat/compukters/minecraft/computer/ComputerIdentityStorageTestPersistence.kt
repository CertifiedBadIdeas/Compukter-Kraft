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

import net.minecraft.nbt.CompoundTag

internal object ComputerIdentityStorageTestPersistence {
    fun roundTrip(source: ComputerIdentityStorage): ComputerIdentityStorage {
        val tag = CompoundTag()
        source.save(tag)
        return ComputerIdentityStorage().also { it.load(tag) }
    }

    fun load(
        storage: ComputerIdentityStorage,
        high: Long? = null,
        low: Long? = null,
    ) {
        storage.load(
            CompoundTag().also { tag ->
                high?.let { tag.putLong("computer_id_high", it) }
                low?.let { tag.putLong("computer_id_low", it) }
            },
        )
    }
}
