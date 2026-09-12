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
import ru.lazyhat.compukters.compiler.cache.CompilationCacheStats
import ru.lazyhat.compukters.compiler.cache.PersistentCompilationCache
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import java.nio.file.Path

class ClientCompilationCache private constructor(
    private val delegate: PersistentCompilationCache,
) : AutoCloseable {
    fun get(identity: Hash256): ByteArray? = delegate.get(identity)

    fun put(
        identity: Hash256,
        artifact: ByteArray,
    ): ByteArray = delegate.put(identity, artifact)

    fun stats(): CompilationCacheStats = delegate.stats()

    override fun close() = delegate.close()

    companion object {
        fun open(
            root: Path,
            policy: CompilationCachePolicy = CompilationCachePolicy(),
            verifier: ArtifactVerifier,
        ): ClientCompilationCache =
            ClientCompilationCache(
                PersistentCompilationCache.open(root.resolve(CLIENT_DIRECTORY), policy, verifier),
            )

        private const val CLIENT_DIRECTORY = "client-compilation"
    }
}
