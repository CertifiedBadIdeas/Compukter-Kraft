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

package ru.lazyhat.compukters.ide.compiler

import ru.lazyhat.compukters.compiler.cache.ArtifactVerifier
import ru.lazyhat.compukters.compiler.cache.CompilationCachePolicy
import ru.lazyhat.compukters.compiler.cache.PersistentCompilationCache
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientCompilationCacheTest {
    @Test
    fun `client and server cache roots never share entries`() {
        val root = Files.createTempDirectory("compukters-client-cache-").toAbsolutePath().normalize()
        val policy = CompilationCachePolicy(maximumEntries = 8, maximumArtifactBytes = 1024, maximumSingleArtifactBytes = 512)
        val verifier = ArtifactVerifier { artifact -> artifact.isNotEmpty() }
        val identity = Hash256.of(ByteArray(32) { 7 })
        val artifact = byteArrayOf(1, 2, 3)
        try {
            ClientCompilationCache.open(root, policy, verifier).use { client ->
                PersistentCompilationCache.open(root.resolve("server"), policy, verifier).use { server ->
                    client.put(identity, artifact)

                    assertNull(server.get(identity))
                    assertContentEquals(artifact, client.get(identity))
                    assertTrue(root.resolve("client-compilation/v1").toFile().isDirectory)
                    assertTrue(root.resolve("server/v1").toFile().isDirectory)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
