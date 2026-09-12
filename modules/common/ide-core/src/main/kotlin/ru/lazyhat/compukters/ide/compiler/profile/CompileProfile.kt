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

package ru.lazyhat.compukters.ide.compiler.profile

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ResolvedModule
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformIdentity
import java.util.Collections

class CompileProfile(
    val toolchain: ToolchainLockIdentity,
    val platform: PlatformIdentity,
    directModules: Set<ModuleId>,
    modules: List<ResolvedPlatformModule>,
    val limits: WorkerLimits,
) {
    val directModules: Set<ModuleId> = Collections.unmodifiableSet(directModules.toSet())
    val modules: List<ResolvedPlatformModule> = Collections.unmodifiableList(modules.toList())

    init {
        require(toolchain.languageVersion == platform.languageVersion) { "compile profile language version does not match platform" }
        require(toolchain.platformAbi.toByteArray().contentEquals(platform.contentHash.toByteArray())) {
            "compile profile platform ABI does not match platform"
        }
        val ids = this.modules.map { it.identity.id }
        require(ids.size == ids.toSet().size) { "compile profile module IDs must be unique" }
        require(this.directModules == this.modules.filter(ResolvedPlatformModule::direct).mapTo(mutableSetOf()) { it.identity.id }) {
            "compile profile direct module set does not match resolved modules"
        }
    }
}

class TargetCompileProfile(
    val toolchain: ToolchainLockIdentity,
    modules: List<ResolvedModule>,
    val limits: WorkerLimits,
) {
    val modules: List<ResolvedModule> =
        Collections.unmodifiableList(
            modules.sortedWith {
                left,
                right,
                ->
                compareValuesBy(left.id, right.id, ModuleId::provider, ModuleId::module)
            },
        )

    init {
        require(this.modules.zipWithNext().none { (left, right) -> left.id == right.id }) { "target module IDs must be unique" }
    }

    override fun equals(other: Any?): Boolean =
        other is TargetCompileProfile && toolchain == other.toolchain && modules == other.modules && limits == other.limits

    override fun hashCode(): Int = 31 * (31 * toolchain.hashCode() + modules.hashCode()) + limits.hashCode()
}

sealed interface ProfileResolution {
    data class Resolved(
        val profile: CompileProfile,
    ) : ProfileResolution

    sealed interface Failure : ProfileResolution {
        data class ToolchainMismatch(
            val expected: ToolchainLockIdentity,
            val available: ToolchainLockIdentity,
        ) : Failure

        data class MissingModule(
            val id: ModuleId,
        ) : Failure

        data class MajorMismatch(
            val id: ModuleId,
            val expected: ApiMajor,
            val available: ApiMajor,
        ) : Failure

        data class VersionMismatch(
            val id: ModuleId,
            val expected: String,
            val available: String,
        ) : Failure

        data class ContentMismatch(
            val id: ModuleId,
            val expected: Hash256,
            val available: Hash256,
        ) : Failure

        data class TargetLimitMismatch(
            val field: String,
            val required: Long,
            val available: Long,
        ) : Failure
    }
}
