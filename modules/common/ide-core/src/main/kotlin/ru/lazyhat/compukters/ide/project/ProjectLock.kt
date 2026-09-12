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

package ru.lazyhat.compukters.ide.project

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import java.util.Collections

data class ToolchainLockIdentity(
    val compilerVersion: String,
    val languageVersion: String,
    val codegenAbi: UInt,
    val artifactAbi: UInt,
    val artifactWriterVersion: UInt,
    val payloadHash: Hash256,
    val platformAbi: Hash256,
) {
    init {
        validateLockText("compiler version", compilerVersion)
        validateLockText("language version", languageVersion)
    }
}

data class ResolvedModule(
    val id: ModuleId,
    val major: ApiMajor,
    val version: String,
    val contentHash: Hash256,
) {
    init {
        validateLockText("module version", version)
    }
}

data class LockedModule(
    val identity: ResolvedModule,
    val direct: Boolean,
)

class ProjectLock private constructor(
    val format: Int,
    val toolchain: ToolchainLockIdentity,
    modules: List<LockedModule>,
) {
    val modules: List<LockedModule> = Collections.unmodifiableList(modules.toList())

    override fun equals(other: Any?): Boolean =
        other is ProjectLock && format == other.format && toolchain == other.toolchain && modules == other.modules

    override fun hashCode(): Int = 31 * (31 * format + toolchain.hashCode()) + modules.hashCode()

    override fun toString(): String = "ProjectLock(format=$format, toolchain=$toolchain, modules=$modules)"

    companion object {
        const val FORMAT = 2

        fun of(
            toolchain: ToolchainLockIdentity,
            modules: List<LockedModule>,
            limits: ProjectLimits = ProjectLimits(),
        ): ProjectLock {
            require(modules.size <= limits.modules) { "project module count exceeds limit" }
            val copied = modules.toList()
            require(copied.map { it.identity.id }.toSet().size == copied.size) { "locked module IDs must be unique" }
            return ProjectLock(FORMAT, toolchain, copied)
        }
    }
}

internal val RESOLVED_MODULE_COMPARATOR =
    Comparator<ResolvedModule> { left, right ->
        val provider = TomlSupport.utf8Comparator.compare(left.id.provider, right.id.provider)
        if (provider != 0) provider else TomlSupport.utf8Comparator.compare(left.id.module, right.id.module)
    }

internal fun validateLockText(
    description: String,
    value: String,
) {
    val bytes =
        try {
            TomlSupport.strictUtf8(value)
        } catch (exception: Exception) {
            throw IllegalArgumentException("$description must be strict UTF-8", exception)
        }
    require(bytes.isNotEmpty()) { "$description must not be empty" }
    require(bytes.size <= 128) { "$description exceeds 128 UTF-8 bytes" }
    require(value.codePoints().noneMatch(Character::isISOControl)) { "$description cannot contain control characters" }
}
