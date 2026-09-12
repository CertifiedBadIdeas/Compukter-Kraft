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

import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArtifactWriterFailureTest {
    @Test
    fun `module semantic identity is deterministic and defensively copied`() {
        val module = languageRuntimeArtifact().modules.single()

        val first = ArtifactWriter.moduleSemanticHash(module)
        val expected = first.copyOf()
        first[0] = (first[0].toInt() xor 0xff).toByte()

        assertContentEquals(expected, ArtifactWriter.moduleSemanticHash(module))
    }

    @Test
    fun `repeated encoding is byte deterministic`() {
        val first = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact()))
        val second = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact()))

        assertContentEquals(first.bytes, second.bytes)
        assertContentEquals(first.sha256, second.sha256)
    }

    @Test
    fun `non-canonical graph table returns errors and no bytes`() {
        val valid = minimalArtifact()
        val invalidModule = valid.modules.single().copy(strings = listOf(MetadataText.of("app"), MetadataText.of("app")))
        val result = assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(valid.copy(modules = listOf(invalidModule))))

        assertEquals(ArtifactWriteErrorCode.NON_CANONICAL_ORDER, result.errors.single().code)
    }

    @Test
    fun `output limit fails before partial artifact publication`() {
        val result =
            assertIs<ArtifactWriteResult.Failure>(
                ArtifactWriter.write(minimalArtifact(), ArtifactWriteLimits(artifactBytes = 100)),
            )

        assertTrue(result.errors.any { it.code == ArtifactWriteErrorCode.LIMIT_EXCEEDED })
    }

    @Test
    fun `diagnostics stop at the configured bound in stable order`() {
        val invalid =
            Artifact(
                manifest = Manifest.minimal(),
                entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
                modules = emptyList(),
            )

        val result = assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(invalid, ArtifactWriteLimits(diagnostics = 1)))

        assertEquals(1, result.errors.size)
        assertEquals(ArtifactWriteErrorCode.BAD_REFERENCE, result.errors.single().code)
    }
}
