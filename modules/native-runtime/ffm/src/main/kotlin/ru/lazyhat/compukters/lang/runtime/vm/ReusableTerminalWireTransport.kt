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

package ru.lazyhat.compukters.lang.runtime.vm

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder

internal fun interface TerminalFullStateCall {
    fun call(
        handle: Long,
        output: MemorySegment,
        outputCapacity: Long,
        written: MemorySegment,
    ): Int
}

internal fun interface TerminalChangesSinceCall {
    fun call(
        handle: Long,
        revision: Long,
        output: MemorySegment,
        outputCapacity: Long,
        written: MemorySegment,
    ): Int
}

internal class ReusableTerminalWireTransport(
    private val maximumBytes: Int,
    private val fullStateCall: TerminalFullStateCall,
    private val changesSinceCall: TerminalChangesSinceCall,
) : TerminalWireTransport {
    private val lock = Any()
    private val arena = Arena.ofShared()
    private val output = arena.allocate(maximumBytes.toLong())
    private val written = arena.allocate(ValueLayout.JAVA_LONG)
    private val buffer = output.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
    private var closed = false

    init {
        require(maximumBytes > 0) { "terminal FFM output capacity must be positive" }
    }

    override fun fullState(handle: Long): TerminalState =
        read("terminal full state", {
            fullStateCall.call(handle, output, maximumBytes.toLong(), written)
        }) { decoder ->
            decoder.fullState()
        }

    override fun changesSince(
        handle: Long,
        revision: Long,
    ): TerminalUpdate =
        read("terminal changes", {
            changesSinceCall.call(handle, revision, output, maximumBytes.toLong(), written)
        }) { decoder ->
            decoder.update()
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            arena.close()
        }
    }

    private inline fun <T> read(
        operation: String,
        call: () -> Int,
        decode: (TerminalWireDecoder) -> T,
    ): T =
        synchronized(lock) {
            check(!closed) { "terminal transport is closed" }
            buffer.clear()
            written.set(ValueLayout.JAVA_LONG, 0, 0)
            val status = call()
            if (status != STATUS_OK) throw VmBridgeException("FFM $operation failed with status $status")
            val length = written.get(ValueLayout.JAVA_LONG, 0)
            if (length !in 1..maximumBytes.toLong()) throw VmBridgeException("invalid FFM $operation length")
            buffer.limit(length.toInt())
            decode(TerminalWireDecoder(buffer))
        }

    private companion object {
        const val STATUS_OK = 0
    }
}
