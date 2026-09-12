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

package ru.lazyhat.compukters.minecraft.computer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SystemRomImageTest {
    @Test
    fun `program ROM is canonical executable and deterministic`() {
        val boot = byteArrayOf(9, 8)
        val shell = byteArrayOf(1, 2, 3, 4)
        val kotlinc = byteArrayOf(5, 6, 7)
        val edit = byteArrayOf(10, 11, 12)
        val vmbench = byteArrayOf(13, 14)
        val first = SystemRomImage.encodePrograms(boot, shell, kotlinc, edit, vmbench)
        boot.fill(0)
        shell.fill(0)
        kotlinc.fill(0)
        edit.fill(0)
        vmbench.fill(0)
        val second =
            SystemRomImage.encodePrograms(
                byteArrayOf(9, 8),
                byteArrayOf(1, 2, 3, 4),
                byteArrayOf(5, 6, 7),
                byteArrayOf(10, 11, 12),
                byteArrayOf(13, 14),
            )
        assertContentEquals(first, second)

        val payload = first.copyOf(first.size - 32)
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(payload), first.copyOfRange(payload.size, first.size))
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("CPKTROM\u0000".encodeToByteArray(), ByteArray(8).also(buffer::get))
        assertEquals(1, buffer.short.toInt())
        assertEquals(0, buffer.short.toInt())
        assertEquals(5, buffer.int)
        assertEntry(buffer, "/rom/boot", byteArrayOf(9, 8))
        assertEntry(buffer, "/rom/edit", byteArrayOf(10, 11, 12))
        assertEntry(buffer, "/rom/kotlinc", byteArrayOf(5, 6, 7))
        assertEntry(buffer, "/rom/shell", byteArrayOf(1, 2, 3, 4))
        assertEntry(buffer, "/rom/vmbench", byteArrayOf(13, 14))
        assertEquals(0, buffer.remaining())
    }

    private fun assertEntry(
        buffer: ByteBuffer,
        expectedPath: String,
        expectedArtifact: ByteArray,
    ) {
        val path = ByteArray(buffer.int).also(buffer::get).decodeToString()
        assertEquals(expectedPath, path)
        assertEquals(2, buffer.get().toInt())
        assertEquals(1, buffer.get().toInt())
        assertEquals(0, buffer.short.toInt())
        assertEquals(expectedArtifact.size.toLong(), buffer.long)
        assertContentEquals(expectedArtifact, ByteArray(expectedArtifact.size).also(buffer::get))
    }
}
