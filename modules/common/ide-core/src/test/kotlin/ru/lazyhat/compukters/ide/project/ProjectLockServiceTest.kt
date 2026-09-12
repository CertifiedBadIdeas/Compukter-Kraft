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

import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.platformBundle
import ru.lazyhat.compukters.ide.compiler.profile.platformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.platformToolchain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectLockServiceTest {
    @Test
    fun `resolution writes deterministic complete closure with direct roots`() {
        val resolution = resolution()
        val lock = ProjectLockService(RecordingLockFileWriter()).resolve(manifest(), resolution)

        assertEquals(listOf("stdlib:core", "stdlib:ranges", "std:terminal"), lock.modules.map { it.identity.id.value })
        assertEquals(listOf(false, false, true), lock.modules.map(LockedModule::direct))
        assertEquals(lock, ProjectLockCodec.decode(ProjectLockCodec.encode(lock)))
    }

    @Test
    fun `declared unavailable module fails while undeclared available module stays out of lock`() {
        val service = ProjectLockService(RecordingLockFileWriter())
        val lock = service.resolve(manifest(), resolution())

        assertTrue(lock.modules.none { it.identity.id == ModuleId.parse("std:filesystem") })
        assertFailsWith<ProjectResolutionException> {
            service.resolve(ProjectManifest.of("hello", mapOf(ModuleId.parse("missing:module") to ApiMajor(1))), resolution())
        }
    }

    @Test
    fun `declaring a transitive dependency promotes only its direct flag`() {
        val promoted =
            ProjectManifest.of(
                "hello",
                mapOf(ModuleId.parse("std:terminal") to ApiMajor(2), ModuleId.parse("stdlib:ranges") to ApiMajor(1)),
            )

        val lock = ProjectLockService(RecordingLockFileWriter()).resolve(promoted, resolution())

        assertTrue(lock.modules.single { it.identity.id == ModuleId.parse("stdlib:ranges") }.direct)
    }

    @Test
    fun `validation reports typed toolchain module and closure differences`() {
        val service = ProjectLockService(RecordingLockFileWriter())
        val resolution = resolution()
        val lock = service.resolve(manifest(), resolution)

        assertEquals(emptyList(), service.validate(manifest(), lock, resolution))
        val changedToolchain = resolution.copy(toolchain = resolution.toolchain.copy(artifactAbi = 9u))
        assertTrue(service.validate(manifest(), lock, changedToolchain).any { it is ProjectLockMismatch.Toolchain })

        val changedBundle = platformBundle(terminalVersion = "2.2.0")
        val changedResolution = ProjectResolution(platformToolchain(changedBundle), PlatformCatalog.of(changedBundle))
        val mismatches = service.validate(manifest(), lock, changedResolution)
        assertTrue(mismatches.any { it is ProjectLockMismatch.ModuleVersion })
        assertTrue(mismatches.any { it is ProjectLockMismatch.ModuleContent })

        val withoutDependency = ProjectLock.of(lock.toolchain, lock.modules.filterNot { it.identity.id == ModuleId.parse("stdlib:ranges") })
        assertIs<ProjectLockMismatch.ManifestModuleMissing>(
            service.validate(manifest(), withoutDependency, resolution).first { it is ProjectLockMismatch.ManifestModuleMissing },
        )

        val indirectRoot =
            ProjectLock.of(
                lock.toolchain,
                lock.modules.map { if (it.identity.id == ModuleId.parse("std:terminal")) it.copy(direct = false) else it },
            )
        assertIs<ProjectLockMismatch.ModuleDirect>(
            service.validate(manifest(), indirectRoot, resolution).first { it is ProjectLockMismatch.ModuleDirect },
        )

        val reordered = ProjectLock.of(lock.toolchain, lock.modules.reversed())
        assertIs<ProjectLockMismatch.ModuleOrder>(
            service.validate(manifest(), reordered, resolution).first { it is ProjectLockMismatch.ModuleOrder },
        )
    }

    @Test
    fun `create and update are distinct explicit persistence operations`() {
        val writer = RecordingLockFileWriter()
        val service = ProjectLockService(writer)
        val resolution = resolution()

        val created = service.createLock(manifest(), resolution)
        assertEquals(created, ProjectLockCodec.decode(writer.content!!.decodeToString()))
        assertFailsWith<IllegalStateException> { service.createLock(manifest(), resolution) }

        val updated = service.updateLock(manifest(), resolution)
        assertEquals(updated, ProjectLockCodec.decode(writer.content!!.decodeToString()))
        assertEquals(1, writer.createCalls)
        assertEquals(1, writer.updateCalls)
    }

    @Test
    fun `failed resolution and publication preserve prior lock bytes`() {
        val writer = RecordingLockFileWriter("prior".encodeToByteArray(), failUpdates = true)
        val service = ProjectLockService(writer)
        val before = writer.content!!.copyOf()

        assertFailsWith<ProjectResolutionException> {
            service.updateLock(ProjectManifest.of("hello", mapOf(ModuleId.parse("missing:module") to ApiMajor(1))), resolution())
        }
        assertTrue(before.contentEquals(writer.content))
        assertFailsWith<IllegalStateException> { service.updateLock(manifest(), resolution()) }
        assertTrue(before.contentEquals(writer.content))
    }

    private fun manifest() = ProjectManifest.of("hello", mapOf(ModuleId.parse("std:terminal") to ApiMajor(2)))

    private fun resolution(): ProjectResolution {
        val bundle = platformBundle()
        return ProjectResolution(platformToolchain(bundle), platformCatalog(bundle))
    }

    private class RecordingLockFileWriter(
        var content: ByteArray? = null,
        private val failUpdates: Boolean = false,
    ) : LockFileWriter {
        var createCalls = 0
        var updateCalls = 0

        override fun create(content: ByteArray) {
            check(this.content == null) { "lock already exists" }
            createCalls++
            this.content = content.copyOf()
        }

        override fun update(content: ByteArray) {
            check(this.content != null) { "lock does not exist" }
            updateCalls++
            if (failUpdates) error("injected update failure")
            this.content = content.copyOf()
        }
    }
}
