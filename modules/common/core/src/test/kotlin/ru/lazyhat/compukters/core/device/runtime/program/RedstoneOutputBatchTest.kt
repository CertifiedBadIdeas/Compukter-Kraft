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

package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RedstoneOutputBatchTest {
    @Test
    fun `ordered side and bulk writes reduce to one candidate without losing identities`() {
        val initial = RedstoneWire.replaceOutput(0, 0, 3)
        val requests =
            listOf(
                setSide(id = 1, side = 2, output = 7),
                setAll(id = 2, packed = RedstoneWire.REGISTER_MASK),
                setSide(id = 3, side = 4, output = 0),
            )

        val batch = RedstoneOutputBatch.reduce(initial, requests)

        assertEquals(RedstoneWire.replaceOutput(RedstoneWire.REGISTER_MASK, 4, 0), batch.packed)
        assertEquals(
            listOf(VmHostRequestIdentity(1, 1), VmHostRequestIdentity(1, 2), VmHostRequestIdentity(1, 3)),
            batch.identities,
        )
    }

    @Test
    fun `malformed built in writes are rejected before a candidate exists`() {
        val valid = setSide(id = 1, side = 0, output = 0)
        val cases =
            listOf(
                valid.copy(capability = CapabilityIdentity("addon", "redstone", 1, 0)),
                valid.copy(operation = 5),
                valid.copy(arguments = listOf(VmValue.I32(0))),
                valid.copy(arguments = listOf(VmValue.I64(0), VmValue.I32(0))),
                setSide(id = 2, side = 6, output = 0),
                setSide(id = 3, side = 0, output = 32),
                setAll(id = 4, packed = 1 shl 30),
            )

        cases.forEach { request ->
            assertFailsWith<IllegalArgumentException> {
                RedstoneOutputBatch.reduce(0, listOf(request))
            }
        }
    }

    private fun setSide(
        id: Long,
        side: Int,
        output: Int,
    ): VmHostRequest = VmHostRequest(id, REDSTONE, 6, listOf(VmValue.I32(side), VmValue.I32(output)))

    private fun setAll(
        id: Long,
        packed: Int,
    ): VmHostRequest = VmHostRequest(id, REDSTONE, 7, listOf(VmValue.I32(packed)))

    private companion object {
        val REDSTONE = CapabilityIdentity("compukter", "redstone", 1, 0)
    }
}
