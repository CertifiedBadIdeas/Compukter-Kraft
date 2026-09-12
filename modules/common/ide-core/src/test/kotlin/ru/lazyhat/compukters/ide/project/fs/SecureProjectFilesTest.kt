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

package ru.lazyhat.compukters.ide.project.fs

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.compiler.profile.platformBundle
import ru.lazyhat.compukters.ide.compiler.profile.platformCatalog
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectLockCodec
import ru.lazyhat.compukters.ide.project.ProjectLockService
import ru.lazyhat.compukters.ide.project.ProjectManifest
import ru.lazyhat.compukters.ide.project.ProjectResolution
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureProjectFilesTest {
    @Test
    fun `canonical project paths classify sources without restricting ordinary files`() {
        assertEquals("notes/readme.txt", ProjectPath.file("notes/readme.txt").value)
        assertFalse(ProjectPath.file("notes/readme.txt").isKotlinSource)
        assertTrue(ProjectPath.file("src/main.kt").isKotlinSource)
        assertFalse(ProjectPath.file("main.kt").isKotlinSource)
        assertFailsWith<IllegalArgumentException> { ProjectPath.file("../escape.kt") }
        assertFailsWith<IllegalArgumentException> { ProjectPath.file("src\\main.kt") }
    }

    @Test
    fun `project source paths cannot escape or address non-Kotlin files`() {
        assertEquals("src/main.kt", ProjectPath.source("src/main.kt").value)
        listOf("", "main.kt", "/src/main.kt", "src/../main.kt", "src/a/../../main.kt", "src/main.txt", "src\\main.kt").forEach {
            assertFailsWith<IllegalArgumentException>(it) { ProjectPath.source(it) }
        }
    }

    @Test
    fun `lock writer separates create from replacement`() {
        val root = createTempDirectory("compukters-lock-writer-")
        val project = ProjectCatalog.open(root).create("hello")
        val service = ProjectLockService(project.handle.lockFileWriter())
        val manifest = ProjectManifest.of("hello", emptyMap())
        val first = resolution(1)
        val second = resolution(2)

        service.createLock(manifest, first)
        val firstBytes =
            project.handle.canonicalPath
                .resolve("compukter.lock")
                .readBytes()
        assertFailsWith<IllegalStateException> { service.createLock(manifest, first) }
        assertTrue(
            firstBytes.contentEquals(
                project.handle.canonicalPath
                    .resolve("compukter.lock")
                    .readBytes(),
            ),
        )

        service.updateLock(manifest, second)
        val decoded =
            ProjectLockCodec.decode(
                project.handle.canonicalPath
                    .resolve("compukter.lock")
                    .readBytes()
                    .decodeToString(),
            )
        assertEquals(second.toolchain, decoded.toolchain)
    }

    @Test
    fun `lock writer rejects a symlink target and invalidated root`() {
        val root = createTempDirectory("compukters-lock-symlink-")
        val project = ProjectCatalog.open(root).create("hello")
        val outside = root.resolve("outside").also { Files.writeString(it, "outside") }
        Files.createSymbolicLink(project.handle.canonicalPath.resolve("compukter.lock"), outside)

        val service = ProjectLockService(project.handle.lockFileWriter())
        assertFailsWith<Exception> { service.updateLock(ProjectManifest.of("hello", emptyMap()), resolution(1)) }
        assertEquals("outside", Files.readString(outside))

        Files.delete(project.handle.canonicalPath.resolve("compukter.lock"))
        Files.move(project.handle.canonicalPath, root.resolve("moved"))
        assertFailsWith<Exception> { service.createLock(ProjectManifest.of("hello", emptyMap()), resolution(1)) }
    }

    private fun resolution(seed: Int): ProjectResolution =
        platformBundle().let { platform ->
            ProjectResolution(
                ToolchainLockIdentity(
                    compilerVersion = "2.4.10",
                    languageVersion = platform.identity.languageVersion,
                    codegenAbi = seed.toUInt(),
                    artifactAbi = 1u,
                    artifactWriterVersion = 1u,
                    payloadHash = hash(seed),
                    platformAbi = Hash256.of(platform.identity.contentHash.toByteArray()),
                ),
                platformCatalog(platform),
            )
        }

    private fun hash(seed: Int) = Hash256.of(ByteArray(32) { seed.toByte() })
}
