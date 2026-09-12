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

package ru.lazyhat.compukters.worker.process

import kotlin.test.Test
import kotlin.test.assertContentEquals

class BoundedByteRingTest {
    @Test
    fun `ring retains only newest bytes`() {
        val ring = BoundedByteRing(5)
        ring.append(byteArrayOf(1, 2, 3))
        ring.append(byteArrayOf(4, 5, 6, 7))
        assertContentEquals(byteArrayOf(3, 4, 5, 6, 7), ring.snapshot())
    }
}
