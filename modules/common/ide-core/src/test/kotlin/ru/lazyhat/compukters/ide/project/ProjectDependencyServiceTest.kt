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
import ru.lazyhat.compukters.ide.compiler.profile.platformBundle
import ru.lazyhat.compukters.ide.compiler.profile.platformCatalog
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectDependencyServiceTest {
    @Test
    fun `module enablement publishes canonical manifest and lock and can roll back`() {
        val root = createTempDirectory("compukters-dependency-")
        val project = ProjectCatalog.open(root).create("hello")
        val resolution = resolution()
        val module = resolution.catalog.entries.first()
        val service = ProjectDependencyService(project.handle, resolution)

        val published =
            assertIs<ProjectDependencyUpdate.Published>(
                service.enableModule(module.identity.id, module.identity.major),
            )
        val manifestPath = project.handle.canonicalPath.resolve("compukter.toml")
        val lockPath = project.handle.canonicalPath.resolve("compukter.lock")
        val manifest = ProjectManifestCodec.decode(Files.readString(manifestPath))

        assertEquals(module.identity.major, manifest.modules[module.identity.id])
        assertEquals(ProjectManifestCodec.encode(manifest), Files.readString(manifestPath))
        assertTrue(Files.isRegularFile(lockPath))
        assertEquals(ProjectDependencyUpdate.AlreadyDirect, service.enableModule(module.identity.id, module.identity.major))

        assertEquals(ProjectDependencyRollback.Restored, service.rollback(published.receipt))
        assertTrue(ProjectManifestCodec.decode(Files.readString(manifestPath)).modules.isEmpty())
        assertTrue(Files.notExists(lockPath))
    }

    @Test
    fun `rollback refuses to overwrite a newer manifest`() {
        val root = createTempDirectory("compukters-dependency-conflict-")
        val project = ProjectCatalog.open(root).create("hello")
        val resolution = resolution()
        val module = resolution.catalog.entries.first()
        val service = ProjectDependencyService(project.handle, resolution)
        val receipt = assertIs<ProjectDependencyUpdate.Published>(service.enableModule(module.identity.id, module.identity.major)).receipt
        val manifestPath = project.handle.canonicalPath.resolve("compukter.toml")

        Files.writeString(manifestPath, "newer")

        assertIs<ProjectDependencyRollback.Conflict>(service.rollback(receipt))
        assertEquals("newer", Files.readString(manifestPath))
    }

    @Test
    fun `module enablement validates proposed lock before publishing`() {
        val root = createTempDirectory("compukters-dependency-validation-")
        val project = ProjectCatalog.open(root).create("hello")
        val resolution = resolution()
        val module = resolution.catalog.entries.first()
        val service = ProjectDependencyService(project.handle, resolution)

        val result =
            service.enableModule(module.identity.id, module.identity.major) { proposed ->
                val locked = proposed.modules.single { it.identity.id == module.identity.id }
                assertEquals(module.identity.major, locked.identity.major)
                "target profile changed"
            }

        assertEquals(ProjectDependencyUpdate.Conflict("target profile changed"), result)
        assertTrue(ProjectManifestCodec.decode(Files.readString(project.handle.canonicalPath.resolve("compukter.toml"))).modules.isEmpty())
        assertTrue(Files.notExists(project.handle.canonicalPath.resolve("compukter.lock")))
    }

    private fun resolution(): ProjectResolution {
        val bundle = platformBundle()
        return ProjectResolution(
            ToolchainLockIdentity(
                compilerVersion = "2.4.10",
                languageVersion = bundle.identity.languageVersion,
                codegenAbi = 1u,
                artifactAbi = 1u,
                artifactWriterVersion = 1u,
                payloadHash = Hash256.of(ByteArray(32) { 1 }),
                platformAbi = Hash256.of(bundle.identity.contentHash.toByteArray()),
            ),
            platformCatalog(bundle),
        )
    }
}
