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

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class SystemProgramImageTest {
    @Test
    fun `packaged extensionless programs are present and returned defensively`() {
        listOf(SystemProgramImage::boot, SystemProgramImage::shell).forEach { load ->
            val first = load()
            val second = load()

            assertTrue(first.isNotEmpty())
            assertContentEquals(first, second)
            first[0] = first[0].inc()
            assertTrue(!first.contentEquals(load()))
        }
    }
}
