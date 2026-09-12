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

package ru.lazyhat.compukters.lang.runtime.fs

import java.nio.ByteBuffer
import java.nio.ByteOrder

@ConsistentCopyVisibility
data class ComputerId private constructor(
    val highBits: Long,
    val lowBits: Long,
) {
    fun toByteArray(): ByteArray =
        ByteBuffer
            .allocate(BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(highBits)
            .putLong(lowBits)
            .array()

    companion object {
        private const val BYTES = 16

        fun of(bytes: ByteArray): ComputerId {
            require(bytes.size == BYTES) { "computer identity must contain exactly 16 bytes" }
            require(bytes.any { it.toInt() != 0 }) { "computer identity must not be zero" }
            val buffer = ByteBuffer.wrap(bytes.copyOf()).order(ByteOrder.BIG_ENDIAN)
            return ComputerId(buffer.long, buffer.long)
        }

        fun fromLongs(
            highBits: Long,
            lowBits: Long,
        ): ComputerId {
            require(highBits != 0L || lowBits != 0L) { "computer identity must not be zero" }
            return ComputerId(highBits, lowBits)
        }
    }
}
