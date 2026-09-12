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

package ru.lazyhat.compukters.worker.value

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Sha256Test {
    @Test
    fun `value defensively copies bytes and compares by content`() {
        val source = ByteArray(32) { it.toByte() }
        val value = Sha256.of(source)
        source[0] = 99
        val exposed = value.toByteArray()
        exposed[1] = 99

        assertContentEquals(ByteArray(32) { it.toByte() }, value.toByteArray())
        assertEquals(Sha256.of(ByteArray(32) { it.toByte() }), value)
    }

    @Test
    fun `value rejects non sha256 lengths`() {
        assertFailsWith<IllegalArgumentException> { Sha256.of(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { Sha256.of(ByteArray(33)) }
    }
}
