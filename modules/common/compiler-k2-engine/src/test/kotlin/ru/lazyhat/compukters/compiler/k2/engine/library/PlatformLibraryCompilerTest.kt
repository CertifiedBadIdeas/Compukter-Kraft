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

package ru.lazyhat.compukters.compiler.k2.engine.library

import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformLibraryCompilerTest {
    @Test
    fun `fragment preserves compiled artifact bytes deterministically`() {
        val artifact = ImmutableBytes.of(byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte(), 1))
        val fragment = PlatformLibraryFragment(PlatformModuleId("stdlib", "core"), artifact)

        val first = PlatformLibraryFragmentCodec.encode(fragment)
        val second = PlatformLibraryFragmentCodec.encode(fragment)
        val decoded = PlatformLibraryFragmentCodec.decode(first)

        assertContentEquals(first.toByteArray(), second.toByteArray())
        assertEquals(fragment.module, decoded.module)
        assertContentEquals(artifact.toByteArray(), decoded.artifact.toByteArray())
    }

    @Test
    fun `fragment rejects source-like payloads`() {
        val source = ImmutableBytes.of("fun answer() = 42".encodeToByteArray())
        val encoded = PlatformLibraryFragmentCodec.encode(PlatformLibraryFragment(PlatformModuleId("stdlib", "core"), source))

        assertFailsWith<IllegalArgumentException> { PlatformLibraryFragmentCodec.decode(encoded) }
    }
}
