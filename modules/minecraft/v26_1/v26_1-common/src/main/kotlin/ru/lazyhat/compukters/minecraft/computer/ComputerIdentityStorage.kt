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

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import java.util.UUID

internal class ComputerIdentityStorage(
    private val identitySource: () -> ComputerId = ::randomComputerId,
) {
    private var identity = identitySource()

    fun id(): ComputerId = identity

    fun save(output: ValueOutput) {
        output.putLong(HIGH_KEY, identity.highBits)
        output.putLong(LOW_KEY, identity.lowBits)
    }

    fun load(input: ValueInput?) {
        val high = input?.getLong(HIGH_KEY)?.orElse(null)
        val low = input?.getLong(LOW_KEY)?.orElse(null)
        identity =
            if (high == null || low == null || (high == 0L && low == 0L)) {
                identitySource()
            } else {
                ComputerId.fromLongs(high, low)
            }
    }

    private companion object {
        const val HIGH_KEY = "computer_id_high"
        const val LOW_KEY = "computer_id_low"
    }
}

private fun randomComputerId(): ComputerId {
    while (true) {
        val candidate = UUID.randomUUID()
        if (candidate.mostSignificantBits != 0L || candidate.leastSignificantBits != 0L) {
            return ComputerId.fromLongs(candidate.mostSignificantBits, candidate.leastSignificantBits)
        }
    }
}
