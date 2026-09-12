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

data class CompilationCachePolicy(
    val maximumEntries: Int = 1_024,
    val maximumArtifactBytes: Long = 256L * 1024 * 1024,
    val maximumSingleArtifactBytes: Int = 16 * 1024 * 1024,
    val maximumStartupEntries: Int = 4_096,
    val maximumMetadataBytes: Int = 4 * 1024,
) {
    init {
        require(maximumEntries > 0) { "cache entry limit must be positive" }
        require(maximumArtifactBytes > 0) { "cache byte limit must be positive" }
        require(maximumSingleArtifactBytes > 0) { "cache artifact limit must be positive" }
        require(maximumStartupEntries > 0) { "cache startup entry limit must be positive" }
        require(maximumMetadataBytes > 0) { "cache metadata limit must be positive" }
        require(maximumSingleArtifactBytes.toLong() <= maximumArtifactBytes) {
            "cache artifact limit must not exceed total byte limit"
        }
    }
}

data class CompilationCacheStats(
    val entries: Int,
    val artifactBytes: Long,
)

class CompilationCacheException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
