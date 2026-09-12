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

package ru.lazyhat.compukters.compiler.artifact.write

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LanguageRuntimeGoldenTest {
    @Test
    fun `Kotlin writer reproduces non-trivial Rust language runtime artifact`() {
        val result = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact()))

        assertContentEquals(fixture("language-runtime.cpkt"), result.bytes)
        assertEquals(1952, result.bytes.size)
        assertEquals("0ce5c469e3b918c2dea398a4280b0c698fe32f1806fa89070ad6509c1c856fab", result.sha256.toHex())
        assertEquals(
            "a428be16c623a8cd637de3d4daa39fb2d93772e684ff264b01dcc1d3c81a7845",
            ArtifactWriter.moduleSemanticHash(languageRuntimeArtifact().modules.single()).toHex(),
        )
    }
}
