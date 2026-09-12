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

package ru.lazyhat.compukters.compiler.cache

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistentCompilationCacheTest {
    @Test
    fun `miss publishes immutable verified artifact and survives restart`() =
        withRoot { root ->
            val verifier = RecordingVerifier()
            val identity = identity(1)
            val artifact = byteArrayOf(1, 2, 3, 4)

            PersistentCompilationCache.open(root, policy(), verifier).use { cache ->
                assertNull(cache.get(identity))
                cache.put(identity, artifact)
                artifact[0] = 99
                assertContentEquals(byteArrayOf(1, 2, 3, 4), cache.get(identity))
                assertEquals(1, cache.stats().entries)
                assertEquals(4, cache.stats().artifactBytes)
            }

            PersistentCompilationCache.open(root, policy(), verifier).use { cache ->
                val hit = requireNotNull(cache.get(identity))
                hit[0] = 88
                assertContentEquals(byteArrayOf(1, 2, 3, 4), cache.get(identity))
            }
            assertTrue(verifier.artifacts.size >= 4)
        }

    @Test
    fun `capacity evicts least recently used entry deterministically`() =
        withRoot { root ->
            val first = identity(1)
            val second = identity(2)
            val third = identity(3)
            PersistentCompilationCache
                .open(
                    root,
                    policy(maximumEntries = 2, maximumArtifactBytes = 6, maximumSingleArtifactBytes = 3),
                    RecordingVerifier(),
                ).use { cache ->
                    cache.put(first, byteArrayOf(1, 1, 1))
                    cache.put(second, byteArrayOf(2, 2, 2))
                    assertContentEquals(byteArrayOf(1, 1, 1), cache.get(first))
                    cache.put(third, byteArrayOf(3, 3, 3))
                    assertNull(cache.get(second))
                    assertContentEquals(byteArrayOf(1, 1, 1), cache.get(first))
                    assertContentEquals(byteArrayOf(3, 3, 3), cache.get(third))
                }
        }

    @Test
    fun `duplicate publication reuses validated winner`() =
        withRoot { root ->
            val identity = identity(4)
            PersistentCompilationCache.open(root, policy(), RecordingVerifier()).use { cache ->
                assertContentEquals(byteArrayOf(4, 5), cache.put(identity, byteArrayOf(4, 5)))
                assertContentEquals(byteArrayOf(4, 5), cache.put(identity, byteArrayOf(9, 9)))
                assertContentEquals(byteArrayOf(4, 5), cache.get(identity))
            }
        }

    @Test
    fun `corrupt and partial entries are removed as misses`() =
        withRoot { root ->
            val corrupt = identity(5)
            val partial = identity(6)
            PersistentCompilationCache.open(root, policy(), RecordingVerifier()).use { cache ->
                cache.put(corrupt, byteArrayOf(5, 5, 5))
            }
            root
                .resolve("v1")
                .resolve(corrupt.hex())
                .resolve("artifact")
                .writeBytes(byteArrayOf(0))
            Files.createDirectories(root.resolve("v1").resolve(partial.hex())).resolve("artifact").writeBytes(byteArrayOf(6))

            PersistentCompilationCache.open(root, policy(), RecordingVerifier()).use { cache ->
                assertNull(cache.get(corrupt))
                assertNull(cache.get(partial))
                assertEquals(0, cache.stats().entries)
            }
            assertFalse(root.resolve("v1").resolve(corrupt.hex()).isDirectory())
            assertFalse(root.resolve("v1").resolve(partial.hex()).isDirectory())
        }

    @Test
    fun `startup removes abandoned staging and persists no guest information`() =
        withRoot { root ->
            val sourceText = "private source marker"
            val guestPath = "/home/private-name.kt"
            val computerId = "computer-secret-id"
            val versionRoot = root.resolve("v1")
            Files.createDirectories(versionRoot.resolve(".staging-abandoned")).resolve("leak").writeBytes(byteArrayOf(1))

            PersistentCompilationCache.open(root, policy(), RecordingVerifier()).use { cache ->
                cache.put(identity(7), byteArrayOf(7, 8, 9))
            }

            assertFalse(versionRoot.resolve(".staging-abandoned").isDirectory())
            Files.walk(versionRoot).use { paths ->
                paths.forEach { path ->
                    val relative = versionRoot.relativize(path).toString()
                    assertFalse(sourceText in relative || guestPath in relative || computerId in relative)
                    if (Files.isRegularFile(path) && path.fileName.toString() != "artifact") {
                        val text = path.readText()
                        assertFalse(sourceText in text || guestPath in text || computerId in text)
                    }
                }
            }
        }

    @Test
    fun `invalid policy oversized artifacts and rejected artifacts fail closed`() =
        withRoot { root ->
            assertFailsWith<IllegalArgumentException> {
                PersistentCompilationCache.open(root, policy(maximumEntries = 0), RecordingVerifier())
            }
            PersistentCompilationCache
                .open(
                    root,
                    policy(maximumSingleArtifactBytes = 2),
                    ArtifactVerifier { bytes -> bytes.firstOrNull() != 0.toByte() },
                ).use { cache ->
                    assertFailsWith<IllegalArgumentException> { cache.put(identity(8), byteArrayOf(1, 2, 3)) }
                    assertFailsWith<CompilationCacheException> { cache.put(identity(9), byteArrayOf(0)) }
                    assertEquals(0, cache.stats().entries)
                }
        }

    @Test
    fun `startup scan is bounded before cache entries are loaded`() =
        withRoot { root ->
            val versionRoot = root.resolve("v1")
            Files.createDirectories(versionRoot.resolve("invalid-a"))
            Files.createDirectories(versionRoot.resolve("invalid-b"))

            assertFailsWith<CompilationCacheException> {
                PersistentCompilationCache.open(
                    root,
                    policy(maximumStartupEntries = 1),
                    RecordingVerifier(),
                )
            }
        }

    private fun policy(
        maximumEntries: Int = 8,
        maximumArtifactBytes: Long = 1024,
        maximumSingleArtifactBytes: Int = 512,
        maximumStartupEntries: Int = 64,
    ) = CompilationCachePolicy(
        maximumEntries,
        maximumArtifactBytes,
        maximumSingleArtifactBytes,
        maximumStartupEntries,
    )

    private fun identity(value: Int): Hash256 = Hash256.of(ByteArray(32) { value.toByte() })

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("compukters-cache-test-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class RecordingVerifier : ArtifactVerifier {
        val artifacts = mutableListOf<ByteArray>()

        override fun verify(artifact: ByteArray): Boolean {
            artifacts += artifact.copyOf()
            return artifact.isNotEmpty()
        }
    }
}
