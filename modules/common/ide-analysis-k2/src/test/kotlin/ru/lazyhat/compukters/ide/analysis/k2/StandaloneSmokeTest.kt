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

package ru.lazyhat.compukters.ide.analysis.k2

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.k2.query.K2QueryFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StandaloneSmokeTest {
    @Test
    fun `native standalone session resolves an inferred string type`() {
        val source = "val answer = \"ok\""
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.indexOf("\"ok\"") + 1,
                    ),
                ) as AnalysisResult.ExpressionInfo

            assertEquals("kotlin.String", assertNotNull(result.value).renderedType)
        }
    }
}
