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

package ru.lazyhat.compukters.lang.runtime.vm

import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class FfmAbiFunctionTest {
    @Test
    fun `ABI v9 inventory has 38 unique C status and scalar functions`() {
        val functions = FfmAbiFunction.entries

        assertEquals(38, functions.size)
        assertEquals(functions.size, functions.map(FfmAbiFunction::symbol).toSet().size)
        functions.forEach { function ->
            assert(function.symbol.startsWith("compukter_"))
            assertEquals(
                if (function == FfmAbiFunction.MAXIMUM_CREATE_BYTES ||
                    function == FfmAbiFunction.MAXIMUM_OUTCOME_BYTES
                ) {
                    ValueLayout.JAVA_LONG
                } else {
                    ValueLayout.JAVA_INT
                },
                function.descriptor.returnLayout().orElseThrow(),
                function.symbol,
            )
        }
    }
}
