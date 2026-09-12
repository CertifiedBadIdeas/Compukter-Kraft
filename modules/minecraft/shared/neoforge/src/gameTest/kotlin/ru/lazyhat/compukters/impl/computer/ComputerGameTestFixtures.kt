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

package ru.lazyhat.compukters.impl.computer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object ComputerGameTestFixtures {
    fun processTestRom(): ByteArray {
        val programs =
            listOf(
                "/rom/boot" to resource("/system/programs/boot"),
                "/rom/edit" to resource("/system/programs/edit"),
                "/rom/hello" to fixture("process-terminal-child.cpkt"),
                "/rom/kotlinc" to resource("/system/programs/kotlinc"),
                "/rom/shell" to resource("/system/programs/shell"),
                "/rom/vmbench" to resource("/system/programs/vmbench"),
            )
        val payloadSize =
            programs.fold(16) { size, (path, artifact) ->
                Math.addExact(size, 16 + path.encodeToByteArray().size + artifact.size)
            }
        val payload = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        payload
            .put("CPKTROM\u0000".encodeToByteArray())
            .putShort(1.toShort())
            .putShort(0.toShort())
            .putInt(programs.size)
        programs.forEach { (pathText, artifact) ->
            val path = pathText.encodeToByteArray()
            payload
                .putInt(path.size)
                .put(path)
                .put(2.toByte())
                .put(1.toByte())
                .putShort(0.toShort())
                .putLong(artifact.size.toLong())
                .put(artifact)
        }
        return payload.array() + MessageDigest.getInstance("SHA-256").digest(payload.array())
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "missing GameTest fixture $name"
        }.use { it.readAllBytes() }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(name)) {
            "missing GameTest resource $name"
        }.use { it.readAllBytes() }
}
