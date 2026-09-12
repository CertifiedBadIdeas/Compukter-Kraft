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

import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.project.LockedModule
import ru.lazyhat.compukters.ide.project.ProjectLock
import ru.lazyhat.compukters.ide.project.ResolvedModule
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity

class CompileProfileResolver(
    private val localToolchain: ToolchainLockIdentity,
    private val catalog: PlatformCatalog,
    private val requiredLimits: WorkerLimits,
) {
    fun resolveLocal(lock: ProjectLock): ProfileResolution {
        if (lock.toolchain != localToolchain) return ProfileResolution.Failure.ToolchainMismatch(lock.toolchain, localToolchain)
        return resolveBundles(lock, requiredLimits)
    }

    fun resolveTarget(
        lock: ProjectLock,
        target: TargetCompileProfile,
    ): ProfileResolution {
        if (lock.toolchain != target.toolchain) return ProfileResolution.Failure.ToolchainMismatch(lock.toolchain, target.toolchain)
        compareModules(lock.modules.map { it.identity }, target.modules)?.let { return it }
        compareLimits(requiredLimits, target.limits)?.let { return it }
        if (lock.toolchain != localToolchain) return ProfileResolution.Failure.ToolchainMismatch(lock.toolchain, localToolchain)
        return resolveBundles(lock, target.limits)
    }

    private fun resolveBundles(
        lock: ProjectLock,
        limits: WorkerLimits,
    ): ProfileResolution {
        val resolved =
            lock.modules.map { locked ->
                val expected = locked.identity
                val available = catalog.find(expected.id) ?: return ProfileResolution.Failure.MissingModule(expected.id)
                compareModule(expected, available.identity)?.let { return it }
                ResolvedPlatformModule(expected, available.descriptor, locked.direct)
            }
        return ProfileResolution.Resolved(
            CompileProfile(
                lock.toolchain,
                catalog.bundle.identity,
                lock.modules.filter(LockedModule::direct).mapTo(mutableSetOf()) { it.identity.id },
                resolved,
                limits,
            ),
        )
    }

    private fun compareModules(
        expected: List<ResolvedModule>,
        available: List<ResolvedModule>,
    ): ProfileResolution.Failure? {
        val availableById = available.associateBy(ResolvedModule::id)
        expected.forEach { module ->
            val actual = availableById[module.id] ?: return ProfileResolution.Failure.MissingModule(module.id)
            compareModule(module, actual)?.let { return it }
        }
        return null
    }

    private fun compareModule(
        expected: ResolvedModule,
        available: ResolvedModule,
    ): ProfileResolution.Failure? =
        when {
            expected.major != available.major -> {
                ProfileResolution.Failure.MajorMismatch(expected.id, expected.major, available.major)
            }

            expected.version != available.version -> {
                ProfileResolution.Failure.VersionMismatch(expected.id, expected.version, available.version)
            }

            expected.contentHash != available.contentHash -> {
                ProfileResolution.Failure.ContentMismatch(expected.id, expected.contentHash, available.contentHash)
            }

            else -> {
                null
            }
        }
}

private fun compareLimits(
    required: WorkerLimits,
    available: WorkerLimits,
): ProfileResolution.Failure.TargetLimitMismatch? {
    val fields =
        listOf(
            LimitField("sourceFiles", required.sourceFiles.toLong(), available.sourceFiles.toLong()),
            LimitField("sourceFileBytes", required.sourceFileBytes.toLong(), available.sourceFileBytes.toLong()),
            LimitField("sourceBytes", required.sourceBytes.toLong(), available.sourceBytes.toLong()),
            LimitField("frameBytes", required.frameBytes.toLong(), available.frameBytes.toLong()),
            LimitField("artifactBytes", required.artifactBytes.toLong(), available.artifactBytes.toLong()),
            LimitField("diagnostics", required.diagnostics.toLong(), available.diagnostics.toLong()),
            LimitField("diagnosticTextBytes", required.diagnosticTextBytes.toLong(), available.diagnosticTextBytes.toLong()),
            LimitField("stderrBytes", required.stderrBytes.toLong(), available.stderrBytes.toLong()),
            LimitField("temporaryBytes", required.temporaryBytes, available.temporaryBytes),
            LimitField("temporaryFiles", required.temporaryFiles.toLong(), available.temporaryFiles.toLong()),
        )
    val mismatch = fields.firstOrNull { field -> field.available < field.required } ?: return null
    return ProfileResolution.Failure.TargetLimitMismatch(mismatch.name, mismatch.required, mismatch.available)
}

private data class LimitField(
    val name: String,
    val required: Long,
    val available: Long,
)
