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

import java.nio.file.Path

sealed interface VmRuntimeLoadSource {
    data class ExplicitPath(
        val path: Path,
    ) : VmRuntimeLoadSource

    data class PackagedResource(
        val resourcePath: String,
    ) : VmRuntimeLoadSource
}

sealed interface VmRuntimeLoadFailure {
    data class UnsupportedPlatform(
        val osName: String,
        val osArch: String,
    ) : VmRuntimeLoadFailure

    data class InvalidExplicitPath(
        val path: String,
        val detail: String,
    ) : VmRuntimeLoadFailure

    data class MissingResource(
        val resourcePath: String,
    ) : VmRuntimeLoadFailure

    data class ResourceExtraction(
        val resourcePath: String,
        val detail: String,
    ) : VmRuntimeLoadFailure

    data class NativeLink(
        val source: String,
        val detail: String,
    ) : VmRuntimeLoadFailure
}

sealed interface VmRuntimeLoadResult {
    data class Loaded(
        val source: VmRuntimeLoadSource,
    ) : VmRuntimeLoadResult

    data class Failed(
        val failure: VmRuntimeLoadFailure,
    ) : VmRuntimeLoadResult
}

class VmRuntimeLoadException(
    val failure: VmRuntimeLoadFailure,
) : IllegalStateException("Compukter FFM runtime load failed: $failure")
