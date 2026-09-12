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

package ru.lazyhat.compukters.compiler.artifact.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdsTest {
    @Test
    fun `module-local identities reserve the import selector bit`() {
        assertEquals(0x7fff_ffffu, TypeId.of(0x7fff_ffffu).value)
        assertFailsWith<IllegalArgumentException> { TypeId.of(0x8000_0000u) }
    }

    @Test
    fun `register identity reserves the absent destination sentinel`() {
        assertEquals(65534u.toUShort(), RegisterId.of(65534u).value)
        assertFailsWith<IllegalArgumentException> { RegisterId.of(65535u) }
    }

    @Test
    fun `local and imported references remain distinct`() {
        val local = TypeRef.Local(TypeId.of(7u))
        val imported = TypeRef.Imported(ImportId.of(7u))

        assertEquals(7u, local.id.value)
        assertEquals(7u, imported.id.value)
    }
}
