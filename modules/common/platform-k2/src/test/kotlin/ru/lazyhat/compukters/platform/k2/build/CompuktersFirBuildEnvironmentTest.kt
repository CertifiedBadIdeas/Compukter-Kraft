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

package ru.lazyhat.compukters.platform.k2.build

import org.jetbrains.kotlin.fir.FirSession
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.CompuktersPlatforms
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CompuktersFirBuildEnvironmentTest {
    @Test
    fun `source modules use native Compukters FIR sessions in dependency order`() {
        CompuktersFirBuildEnvironment.create().use { environment ->
            val builtins =
                environment.compile(
                    PlatformModuleId("kotlin", "builtins"),
                    listOf(source("Builtins.kt", "package kotlin")),
                    emptyList(),
                )
            val library =
                environment.compile(
                    PlatformModuleId("test", "library"),
                    listOf(source("Library.kt", "package sample")),
                    listOf(builtins),
                )

            assertEquals(CompuktersPlatforms.default, builtins.moduleData.platform)
            assertEquals(CompuktersPlatforms.default, library.moduleData.platform)
            assertEquals(FirSession.Kind.Source, builtins.moduleData.session.kind)
            assertEquals(builtins.moduleData, library.moduleData.dependencies.last())
            assertFalse(builtins.diagnostics.hasErrors)
            assertFalse(library.diagnostics.hasErrors)
        }
    }

    private fun source(
        path: String,
        content: String,
    ): PlatformSource = PlatformSource(path, ImmutableBytes.of(content.encodeToByteArray()))
}
