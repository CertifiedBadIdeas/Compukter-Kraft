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

package ru.lazyhat.compukters.tooling.bundle

import io.airlift.compress.v3.zstd.ZstdInputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.inputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ToolingBundleCarrierTest {
    @Test
    fun `encodes reproducible frames readable by the pure Java decoder`() {
        val root = createTempDirectory("compukters-tooling-carrier-test-")
        try {
            val expected = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
            val input = root.resolve("input.zip").also { it.writeBytes(expected) }
            val first = root.resolve("first.zip.zst")
            val second = root.resolve("second.zip.zst")

            ToolingBundleCarrier.encode(input, first)
            ToolingBundleCarrier.encode(input, second)

            assertContentEquals(first.readBytes(), second.readBytes())
            val decoded = ZstdInputStream(first.inputStream()).use { it.readAllBytes() }
            assertContentEquals(expected, decoded)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
