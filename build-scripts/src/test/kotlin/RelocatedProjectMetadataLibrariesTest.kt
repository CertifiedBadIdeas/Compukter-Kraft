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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RelocatedProjectMetadataLibrariesTest {
    private val relocatedEntries =
        listOf(
            "ru/lazyhat/compukters/internal/vendor/tomlj/Toml.class",
            "ru/lazyhat/compukters/internal/vendor/antlr/v4/runtime/Parser.class",
        )

    @Test
    fun acceptsPrivateRelocatedMetadataLibraries() {
        validateRelocatedProjectMetadataLibraries(relocatedEntries, "compukters.jar")
    }

    @Test
    fun rejectsAutomaticMetadataModulesNestedIntoTheMod() {
        assertThrows<IllegalStateException> {
            validateRelocatedProjectMetadataLibraries(
                relocatedEntries + "META-INF/jars/antlr4-runtime-4.11.1.jar",
                "compukters.jar",
            )
        }
    }

    @Test
    fun rejectsUnrelocatedMetadataClasses() {
        assertThrows<IllegalStateException> {
            validateRelocatedProjectMetadataLibraries(
                relocatedEntries + "org/antlr/v4/runtime/Parser.class",
                "compukters.jar",
            )
        }
    }
}
