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

import net.minecraft.nbt.CompoundTag
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ComputerIdentityStorage1211Test {
    @Test
    fun `identity survives the 1_21_1 CompoundTag format`() {
        val original = ComputerIdentityStorage()
        val output = CompoundTag()
        original.save(output)
        val restored = ComputerIdentityStorage()
        restored.load(output)

        assertTrue(original.id().toByteArray().any { it != 0.toByte() })
        assertEquals(original.id(), restored.id())
    }

    @Test
    fun `missing partial and zero identities are replaced`() {
        val replacements =
            ArrayDeque(
                listOf(
                    ComputerId.fromLongs(9, 9),
                    ComputerId.fromLongs(1, 2),
                    ComputerId.fromLongs(3, 4),
                    ComputerId.fromLongs(5, 6),
                ),
            )
        val storage = ComputerIdentityStorage { replacements.removeFirst() }
        val initial = storage.id()

        storage.load(CompoundTag())
        assertNotEquals(initial, storage.id())
        assertEquals(ComputerId.fromLongs(1, 2), storage.id())
        storage.load(CompoundTag().also { it.putLong("computer_id_high", 7) })
        assertEquals(ComputerId.fromLongs(3, 4), storage.id())
        storage.load(
            CompoundTag().also {
                it.putLong("computer_id_high", 0)
                it.putLong("computer_id_low", 0)
            },
        )
        assertEquals(ComputerId.fromLongs(5, 6), storage.id())
    }
}
