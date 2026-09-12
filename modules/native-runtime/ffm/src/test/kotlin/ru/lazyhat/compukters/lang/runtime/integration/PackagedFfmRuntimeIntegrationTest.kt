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

package ru.lazyhat.compukters.lang.runtime.integration

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.FileSystemStoreHealth
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.FfmRuntimeBackend
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntimeLoadResult
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PackagedFfmRuntimeIntegrationTest {
    init {
        VmRuntime.install(FfmRuntimeBackend)
    }

    @Test
    fun `packaged FFM resource loads and executes terminal fixture`() =
        runBlocking {
            assertIs<VmRuntimeLoadResult.Loaded>(VmRuntime.ensureLoaded())
            ShellProgram.run(Path.of(requiredProperty("compukters.shell.artifact")))
        }

    @Test
    fun `packaged FFM resource exposes ABI v2 store lifecycle`() {
        assertIs<VmRuntimeLoadResult.Loaded>(VmRuntime.ensureLoaded())
        val root = Files.createTempDirectory("compukters-packaged-store-").toRealPath()
        try {
            WorldFileSystemStore.open(root).use { store ->
                val computerId = ComputerId.fromLongs(41, 42)
                assertEquals(FileSystemStoreHealth.ACTIVE, store.health())
                VmSession
                    .openInStore(
                        Files.readAllBytes(Path.of(requiredProperty("compukters.shell.artifact"))),
                        store,
                        computerId,
                        emptyRom(),
                    ).use { }
                assertEquals(0, store.durableGeneration(computerId))
                store.flush(computerId, 0)
                store.tombstone(computerId)
                store.recover(computerId)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing test system property $name" }

    private fun emptyRom(): ByteArray {
        val header =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTROM\u0000".encodeToByteArray())
                .putShort(1.toShort())
                .putShort(0.toShort())
                .putInt(0)
                .array()
        return header + MessageDigest.getInstance("SHA-256").digest(header)
    }
}
