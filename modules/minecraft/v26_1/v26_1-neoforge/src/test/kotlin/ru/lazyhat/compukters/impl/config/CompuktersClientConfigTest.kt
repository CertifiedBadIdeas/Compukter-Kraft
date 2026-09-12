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

package ru.lazyhat.compukters.impl.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompuktersClientConfigTest {
    @Test
    fun `terminal font config defaults to Cozette and accepts catalog IDs only`() {
        val value = CompuktersClientConfig.terminalFontId
        val specification = value.spec

        assertEquals("cozette", value.default)
        assertTrue(specification.test("cozette"))
        assertTrue(specification.test("dina"))
        assertTrue(specification.test("proggy_tiny"))
        assertFalse(specification.test("missing"))
        assertFalse(specification.test(7))
        assertFalse(specification.test(null))
    }

    @Test
    fun `IDE layout config has strict ranges and invalid values recover through admission`() {
        val recovered = CompuktersClientConfig.admitIdeLayout(Int.MAX_VALUE, Int.MIN_VALUE, true)
        assertEquals(4_096, recovered.treeWidth)
        assertEquals(32, recovered.diagnosticsHeight)
        assertTrue(recovered.diagnosticsExpanded)
    }
}
