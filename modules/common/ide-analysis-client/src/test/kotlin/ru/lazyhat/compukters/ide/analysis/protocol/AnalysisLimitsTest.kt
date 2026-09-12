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

package ru.lazyhat.compukters.ide.analysis.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisLimitsTest {
    @Test
    fun `worker capability supports equal and component-wise lower limits`() {
        val capability = limits(10)

        assertTrue(capability.supports(capability))
        assertTrue(capability.supports(limits(9)))
    }

    @Test
    fun `worker capability rejects every individually excessive limit`() {
        val capability = limits(10)
        val excessiveRequests =
            listOf(
                capability.copy(sourceFiles = 11),
                capability.copy(sourceFileBytes = 11),
                capability.copy(sourceBytes = 11),
                capability.copy(frameBytes = 11),
                capability.copy(modules = 11),
                capability.copy(diagnostics = 11),
                capability.copy(diagnosticTextBytes = 11),
                capability.copy(semanticTokens = 11),
                capability.copy(completionItems = 11),
                capability.copy(declarationLocations = 11),
                capability.copy(references = 11),
                capability.copy(detailTextBytes = 11),
            )

        excessiveRequests.forEach { assertFalse(capability.supports(it), it.toString()) }
    }

    private fun limits(value: Int) =
        AnalysisLimits(
            sourceFiles = value,
            sourceFileBytes = value,
            sourceBytes = value,
            frameBytes = value,
            modules = value,
            diagnostics = value,
            diagnosticTextBytes = value,
            semanticTokens = value,
            completionItems = value,
            declarationLocations = value,
            references = value,
            detailTextBytes = value,
        )
}
