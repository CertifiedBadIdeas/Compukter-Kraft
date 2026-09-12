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

package ru.lazyhat.compukters.worker.process

internal class BoundedByteRing(
    capacity: Int,
) {
    private val bytes = ByteArray(capacity)
    private var start = 0
    private var size = 0

    init {
        require(capacity >= 0) { "ring capacity must not be negative" }
    }

    @Synchronized
    fun append(source: ByteArray) {
        source.forEach { byte ->
            if (bytes.isEmpty()) return
            if (size < bytes.size) {
                bytes[(start + size) % bytes.size] = byte
                size++
            } else {
                bytes[start] = byte
                start = (start + 1) % bytes.size
            }
        }
    }

    @Synchronized
    fun snapshot(): ByteArray = ByteArray(size) { index -> bytes[(start + index) % bytes.size] }
}
