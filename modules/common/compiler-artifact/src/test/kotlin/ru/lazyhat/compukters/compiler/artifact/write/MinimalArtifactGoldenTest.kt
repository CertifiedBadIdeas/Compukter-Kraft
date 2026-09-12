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

import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MinimalArtifactGoldenTest {
    @Test
    fun `writer publishes format v3 and a canonical no-argument entry tag`() {
        val result = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(minimalArtifact()))

        assertEquals(3, result.bytes[4].toInt() and 0xff)
        assertEquals(0, result.bytes[48].toInt() and 0xff)
        assertContentEquals(ByteArray(15), result.bytes.copyOfRange(49, 64))
    }

    @Test
    fun `Kotlin writer reproduces canonical Rust vector A`() {
        val expected = fixture("vector-a.cpkt")
        val result = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(minimalArtifact()))

        assertEquals(1216, result.bytes.size)
        assertContentEquals(expected, result.bytes)
        assertEquals(
            "787877cb802761cefafef5343769857b5e26bb9105bbd34a9df98d37de464f77",
            result.sha256.toHex(),
        )
    }
}

internal fun fixture(name: String): ByteArray = Path.of(requireNotNull(System.getProperty("compukter.vm.fixtures")), name).readBytes()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
