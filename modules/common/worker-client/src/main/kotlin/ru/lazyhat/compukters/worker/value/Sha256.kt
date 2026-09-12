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

package ru.lazyhat.compukters.worker.value

class Sha256 private constructor(
    bytes: ByteArray,
) {
    private val bytes = bytes.copyOf()

    fun toByteArray(): ByteArray = bytes.copyOf()

    fun hex(): String = bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    override fun equals(other: Any?): Boolean = other is Sha256 && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        fun of(bytes: ByteArray): Sha256 {
            require(bytes.size == 32) { "SHA-256 value must contain 32 bytes" }
            return Sha256(bytes)
        }
    }
}
