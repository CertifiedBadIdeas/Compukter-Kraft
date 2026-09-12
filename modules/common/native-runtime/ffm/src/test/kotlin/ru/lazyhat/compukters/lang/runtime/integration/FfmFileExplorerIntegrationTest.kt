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

import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmFileKind
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadException
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadFailure
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.FfmBridge
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FfmFileExplorerIntegrationTest {
    @Test
    fun `FFM inspects pages and reads generation-checked chunks`() {
        val root = Files.createTempDirectory("compukters-ffm-explorer-").toRealPath()
        try {
            FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
                val artifact = Files.readAllBytes(Path.of(requiredProperty("compukters.shell.artifact")))
                WorldFileSystemStore.open(root, bridge).use { store ->
                    VmSession.bootInStore(store, ComputerId.fromLongs(3, 4), bootRom(artifact)).use { session ->
                        val stat = session.fileStat(VmVirtualPath.of("/rom/boot"))
                        assertEquals(VmFileKind.FILE, stat.metadata.kind)
                        assertEquals(artifact.size.toLong(), stat.metadata.logicalBytes)
                        assertTrue(stat.metadata.executable)

                        val firstRoot = session.fileList(VmVirtualPath.of("/"), null, 1)
                        assertEquals(listOf("home"), firstRoot.entries.map { it.name })
                        assertFalse(firstRoot.complete)
                        val secondRoot = session.fileList(VmVirtualPath.of("/"), "home", 1)
                        assertEquals(listOf("rom"), secondRoot.entries.map { it.name })
                        assertTrue(secondRoot.complete)

                        val first = session.fileRead(VmVirtualPath.of("/rom/boot"), 0, 8, stat.metadata.generation)
                        val second =
                            session.fileRead(
                                VmVirtualPath.of("/rom/boot"),
                                first.nextOffset,
                                8,
                                stat.metadata.generation,
                            )
                        assertContentEquals(artifact.copyOfRange(0, 8), first.bytes)
                        assertContentEquals(artifact.copyOfRange(8, 16), second.bytes)

                        val failure =
                            assertFailsWith<VmFileSystemReadException> {
                                session.fileRead(
                                    VmVirtualPath.of("/rom/boot"),
                                    0,
                                    8,
                                    stat.metadata.generation + 1,
                                )
                            }
                        assertEquals(VmFileSystemReadFailure.STALE_GENERATION, failure.failure)
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing $name test property" }

    private fun bootRom(artifact: ByteArray): ByteArray {
        val path = "/rom/boot".encodeToByteArray()
        val unsigned =
            ByteBuffer
                .allocate(16 + Int.SIZE_BYTES + path.size + 1 + 1 + Short.SIZE_BYTES + Long.SIZE_BYTES + artifact.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTROM\u0000".encodeToByteArray())
                .putShort(1.toShort())
                .putShort(0.toShort())
                .putInt(1)
                .putInt(path.size)
                .put(path)
                .put(2.toByte())
                .put(1.toByte())
                .putShort(0.toShort())
                .putLong(artifact.size.toLong())
                .put(artifact)
                .array()
        return unsigned + MessageDigest.getInstance("SHA-256").digest(unsigned)
    }
}
