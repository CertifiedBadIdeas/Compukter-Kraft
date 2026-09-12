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

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfile
import ru.lazyhat.compukters.ide.compiler.profile.ResolvedPlatformModule
import ru.lazyhat.compukters.ide.compiler.profile.platformBundle
import ru.lazyhat.compukters.ide.compiler.profile.platformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.platformToolchain
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ClientCompileRequestFactoryTest {
    @Test
    fun `factory creates canonical worker request from exact profile`() {
        val prepared = ClientCompileRequestFactory.prepare(baseline())

        assertEquals(baseline().profile.modules.map { it.identity.id.value }, prepared.request.platformModules.map { it.name })
        assertEquals(SourceSnapshotIdentity.of(baseline().sources), prepared.sourceSnapshotId)
        assertEquals(baseline().profile.toolchain.payloadHash, prepared.request.expectedIdentity.payloadHash)
        assertEquals(TargetSettings.KOTLIN_2_4_JVM_17, prepared.request.target)
    }

    @Test
    fun `every semantic client input changes compilation identity`() {
        val baseline = baseline()
        val identity = ClientCompileRequestFactory.prepare(baseline).identity
        val changedLimits =
            listOf(
                baseline.limits().copy(sourceFiles = baseline.limits().sourceFiles + 1),
                baseline.limits().copy(sourceFileBytes = baseline.limits().sourceFileBytes + 1),
                baseline.limits().copy(sourceBytes = baseline.limits().sourceBytes + 1),
                baseline.limits().copy(frameBytes = baseline.limits().frameBytes + 1),
                baseline.limits().copy(artifactBytes = baseline.limits().artifactBytes + 1),
                baseline.limits().copy(diagnostics = baseline.limits().diagnostics + 1),
                baseline.limits().copy(diagnosticTextBytes = baseline.limits().diagnosticTextBytes + 1),
                baseline.limits().copy(stderrBytes = baseline.limits().stderrBytes + 1),
                baseline.limits().copy(temporaryBytes = baseline.limits().temporaryBytes + 1),
                baseline.limits().copy(temporaryFiles = baseline.limits().temporaryFiles + 1),
            )
        val variants =
            buildList {
                add(baseline.copy(sources = snapshot("fun main() = 2", baseline.limits())))
                add(baseline.copy(manifestBytes = BinaryValue.of("changed manifest".encodeToByteArray())))
                add(baseline.copy(lockBytes = BinaryValue.of("changed lock".encodeToByteArray())))
                add(baseline.copy(profile = baseline.profile.withToolchain(baseline.profile.toolchain.copy(artifactAbi = 9u))))
                add(baseline.copy(profile = baseline.profile.withChangedModuleHash()))
                changedLimits.forEach { limits -> add(baseline.copy(profile = baseline.profile.withLimits(limits))) }
            }

        variants.forEach { variant ->
            assertNotEquals(
                identity,
                ClientCompileRequestFactory.prepare(variant).identity,
                "variant must have a distinct identity: $variant",
            )
        }
    }

    private fun baseline(): ClientBuildSnapshot {
        val limits = WorkerLimits(sourceFiles = 4, sourceFileBytes = 1024, sourceBytes = 2048)
        val bundle = platformBundle()
        val selection =
            platformCatalog(bundle).resolve(
                mapOf(ModuleId.parse("std:terminal") to ApiMajor(2), ModuleId.parse("create:sensors") to ApiMajor(1)),
            )
        val toolchain = platformToolchain(bundle)
        return ClientBuildSnapshot(
            snapshot("fun main() = 1", limits),
            BinaryValue.of("manifest".encodeToByteArray()),
            BinaryValue.of("lock".encodeToByteArray()),
            CompileProfile(toolchain, bundle.identity, selection.directModules, selection.modules, limits),
            TargetSettings.KOTLIN_2_4_JVM_17,
        )
    }

    private fun ClientBuildSnapshot.limits() = profile.limits

    private fun CompileProfile.withLimits(limits: WorkerLimits) = CompileProfile(toolchain, platform, directModules, modules, limits)

    private fun CompileProfile.withToolchain(toolchain: ToolchainLockIdentity) =
        CompileProfile(toolchain, platform, directModules, modules, limits)

    private fun CompileProfile.withChangedModuleHash(): CompileProfile {
        val changed =
            modules.mapIndexed { index, module ->
                if (index == modules.lastIndex) {
                    module.copy(identity = module.identity.copy(contentHash = module.identity.contentHash.reversed()))
                } else {
                    module
                }
            }
        return CompileProfile(toolchain, platform, directModules, changed, limits)
    }

    private fun snapshot(
        source: String,
        limits: WorkerLimits,
    ) = ProjectSnapshot.of(
        listOf(ProjectSource(VirtualSourcePath.kotlin("project/main.kt"), BinaryValue.of(source.encodeToByteArray()))),
        limits,
    )

    private fun Hash256.reversed() = Hash256.of(toByteArray().reversedArray())
}
