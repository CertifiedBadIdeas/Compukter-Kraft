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
import ru.lazyhat.compukters.ide.project.LockedModule
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ProjectLock
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompileProfileResolverTest {
    @Test
    fun `local profile requires every exact locked module`() {
        val fixture = fixture()
        val sensors = fixture.catalog.require(ModuleId.parse("create:sensors")).identity
        val missing = ProjectLock.of(fixture.toolchain, fixture.lock.modules + LockedModule(sensors, true))

        val limited = PlatformCatalog.forTarget(fixture.bundle, fixture.lock.modules.map { it.identity })
        assertEquals(
            ProfileResolution.Failure.MissingModule(ModuleId.parse("create:sensors")),
            CompileProfileResolver(fixture.toolchain, limited, WorkerLimits()).resolveLocal(missing),
        )
    }

    @Test
    fun `local profile rejects toolchain version and module content differences`() {
        val fixture = fixture()
        val terminalIndex = fixture.lock.modules.indexOfFirst { it.identity.id == ModuleId.parse("std:terminal") }
        val changed = fixture.lock.modules.toMutableList()
        changed[terminalIndex] = changed[terminalIndex].copy(identity = changed[terminalIndex].identity.copy(version = "2.2.0"))

        assertIs<ProfileResolution.Failure.VersionMismatch>(fixture.resolver.resolveLocal(ProjectLock.of(fixture.toolchain, changed)))
        assertIs<ProfileResolution.Failure.ToolchainMismatch>(
            CompileProfileResolver(fixture.toolchain.copy(artifactAbi = 9u), fixture.catalog, WorkerLimits()).resolveLocal(fixture.lock),
        )
    }

    @Test
    fun `target permits available extras but rejects missing closure and changed content`() {
        val fixture = fixture()
        val extras = fixture.catalog.entries.map { it.identity }
        assertIs<ProfileResolution.Resolved>(
            fixture.resolver.resolveTarget(fixture.lock, TargetCompileProfile(fixture.toolchain, extras, WorkerLimits())),
        )

        val withoutRanges = extras.filterNot { it.id == ModuleId.parse("stdlib:ranges") }
        assertIs<ProfileResolution.Failure.MissingModule>(
            fixture.resolver.resolveTarget(fixture.lock, TargetCompileProfile(fixture.toolchain, withoutRanges, WorkerLimits())),
        )

        val changed = extras.map { if (it.id == ModuleId.parse("std:terminal")) it.copy(contentHash = it.contentHash.reversed()) else it }
        assertIs<ProfileResolution.Failure.ContentMismatch>(
            fixture.resolver.resolveTarget(fixture.lock, TargetCompileProfile(fixture.toolchain, changed, WorkerLimits())),
        )
    }

    @Test
    fun `target rejects limits below compilation policy`() {
        val fixture = fixture(WorkerLimits(sourceFiles = 4))
        val target = TargetCompileProfile(fixture.toolchain, fixture.catalog.entries.map { it.identity }, WorkerLimits(sourceFiles = 3))

        assertEquals(
            ProfileResolution.Failure.TargetLimitMismatch("sourceFiles", 4, 3),
            fixture.resolver.resolveTarget(fixture.lock, target),
        )
    }

    @Test
    fun `resolved profile retains topological modules and direct roots`() {
        val fixture = fixture()
        val profile = assertIs<ProfileResolution.Resolved>(fixture.resolver.resolveLocal(fixture.lock)).profile

        assertEquals(listOf("stdlib:core", "stdlib:ranges", "std:terminal"), profile.modules.map { it.identity.id.value })
        assertEquals(setOf(ModuleId.parse("std:terminal")), profile.directModules)
        assertTrue(profile.modules.single { it.identity.id == ModuleId.parse("std:terminal") }.direct)
    }

    private fun fixture(requiredLimits: WorkerLimits = WorkerLimits()): Fixture {
        val bundle = platformBundle()
        val catalog = platformCatalog(bundle)
        val toolchain = platformToolchain(bundle)
        val selection = catalog.resolve(mapOf(ModuleId.parse("std:terminal") to ApiMajor(2)))
        val lock = ProjectLock.of(toolchain, selection.modules.map { LockedModule(it.identity, it.direct) })
        return Fixture(bundle, catalog, toolchain, lock, CompileProfileResolver(toolchain, catalog, requiredLimits))
    }

    private fun Hash256.reversed() = Hash256.of(toByteArray().reversedArray())

    private data class Fixture(
        val bundle: PlatformBundle,
        val catalog: PlatformCatalog,
        val toolchain: ToolchainLockIdentity,
        val lock: ProjectLock,
        val resolver: CompileProfileResolver,
    )
}
