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

import ru.lazyhat.compukters.lang.runtime.vm.LowLevelVmBridge
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class WorldFileSystemStore private constructor(
    handle: Long,
    private val bridge: LowLevelVmBridge,
) : AutoCloseable {
    private val handle = AtomicLong(handle)
    private val operationLock = ReentrantLock(true)

    fun health(): FileSystemStoreHealth =
        operationLock.withLock {
            decodeNative { StoreWireDecoder(bridge.storeHealth(requireHandle())).health() }
        }

    fun durableGeneration(id: ComputerId): Long =
        operationLock.withLock {
            decodeNative {
                StoreWireDecoder(bridge.storeDurableGeneration(requireHandle(), id.toByteArray())).generation()
            }
        }

    fun flush(
        id: ComputerId,
        generation: Long,
    ) = operationLock.withLock {
        require(generation >= 0) { "filesystem generation must not be negative" }
        bridge.storeFlush(requireHandle(), id.toByteArray(), generation)
    }

    fun tombstone(id: ComputerId) =
        operationLock.withLock {
            bridge.storeTombstone(requireHandle(), id.toByteArray())
        }

    fun recover(id: ComputerId) =
        operationLock.withLock {
            bridge.storeRecover(requireHandle(), id.toByteArray())
        }

    internal fun createMachine(
        id: ComputerId,
        romImage: ByteArray,
        artifact: ByteArray,
    ): Pair<LowLevelVmBridge, ByteArray> =
        operationLock.withLock {
            bridge to bridge.createInStore(requireHandle(), id.toByteArray(), romImage, artifact)
        }

    internal fun createBootMachine(
        id: ComputerId,
        romImage: ByteArray,
    ): Pair<LowLevelVmBridge, ByteArray> =
        operationLock.withLock {
            bridge to bridge.createBootInStore(requireHandle(), id.toByteArray(), romImage)
        }

    override fun close() =
        operationLock.withLock {
            val closing = handle.getAndSet(CLOSED)
            if (closing == CLOSED) return
            try {
                bridge.storeClose(closing)
            } catch (error: Throwable) {
                handle.compareAndSet(CLOSED, closing)
                throw error
            }
        }

    private fun requireHandle(): Long = handle.get().takeIf { it != CLOSED } ?: error("filesystem store is closed")

    companion object {
        private const val CLOSED = 0L

        fun open(root: Path): WorldFileSystemStore = open(root, VmRuntime.bridge())

        internal fun open(
            root: Path,
            bridge: LowLevelVmBridge,
        ): WorldFileSystemStore {
            require(root.isAbsolute) { "filesystem root must be absolute" }
            require(root.normalize() == root) { "filesystem root must already be normalized" }
            val rootBytes = root.toString().encodeToByteArray()
            val handle = decodeNative { StoreWireDecoder(bridge.storeOpen(rootBytes.copyOf(), ByteArray(0))).openedHandle() }
            return WorldFileSystemStore(handle, bridge)
        }

        private inline fun <T> decodeNative(block: () -> T): T =
            try {
                block()
            } catch (error: FileSystemStoreOpenException) {
                throw error
            } catch (error: VmBridgeException) {
                throw error
            } catch (error: Exception) {
                throw VmBridgeException("invalid native filesystem store result", error)
            }
    }
}

private class StoreWireDecoder(
    bytes: ByteArray,
) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun openedHandle(): Long {
        version()
        return when (val code = u8()) {
            0 -> i64().also { require(it != 0L) { "native filesystem store returned a zero handle" } }

            in 1..5 -> throw FileSystemStoreOpenException(
                FileSystemStoreOpenFailure.entries.first { it.wireCode == code },
            )

            6 -> throw VmBridgeException("native filesystem store handle allocation failed with code ${u8()}")

            else -> invalid()
        }.also { end() }
    }

    fun health(): FileSystemStoreHealth {
        version()
        return FileSystemStoreHealth.entries
            .firstOrNull { it.wireCode == u8() }
            ?.also { end() }
            ?: invalid()
    }

    fun generation(): Long {
        version()
        return i64().also {
            require(it >= 0) { "native filesystem generation exceeds the JVM range" }
            end()
        }
    }

    private fun version() = require(u8() == 1) { "unsupported native filesystem wire version" }

    private fun u8(): Int = buffer.get().toInt() and 0xff

    private fun i64(): Long = buffer.long

    private fun end() = require(!buffer.hasRemaining()) { "native filesystem result contains trailing bytes" }

    private fun invalid(): Nothing = throw IllegalArgumentException("invalid native filesystem store result")
}
