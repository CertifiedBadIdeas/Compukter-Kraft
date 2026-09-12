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

package ru.lazyhat.compukters.ide.project.tree

import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectDescriptor
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.document.FileRevision
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFileException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProjectTreeStoreTest {
    @Test
    fun `tree is bounded ordered and distinguishes strict text from binary`() =
        withProject { project ->
            write(project, "src/z.kt", "fun z() = Unit".encodeToByteArray())
            write(project, "notes.txt", "hello".encodeToByteArray())
            write(project, "blob.bin", byteArrayOf(0xC3.toByte(), 0x28))

            val tree = ProjectTreeStore(project.handle, ProjectLimits()).scan()

            assertEquals(
                listOf("blob.bin", "compukter.toml", "notes.txt", "src", "src/main.kt", "src/z.kt"),
                tree.flatten().map { it.path.value },
            )
            assertIs<ProjectFileKind.Binary>(tree.entry(ProjectPath.file("blob.bin")).kind)
            assertIs<ProjectFileKind.Text>(tree.entry(ProjectPath.file("notes.txt")).kind)
            assertIs<FileRevision.Present>(tree.entry(ProjectPath.file("notes.txt")).revision)
            assertEquals(null, tree.entry(ProjectPath.file("src")).revision)
        }

    @Test
    fun `tree ordering compares unsigned UTF-8 bytes`() =
        withProject { project ->
            write(project, "\uE000.txt", byteArrayOf())
            write(project, "\uD800\uDC00.txt", byteArrayOf())

            val paths = ProjectTreeStore(project.handle, ProjectLimits()).scan().flatten().map { it.path.value }

            assertEquals(listOf("\uE000.txt", "\uD800\uDC00.txt"), paths.filter { it.endsWith(".txt") })
        }

    @Test
    fun `tree entries expose an immutable snapshot`() =
        withProject { project ->
            val entries = ProjectTreeStore(project.handle, ProjectLimits()).scan().flatten()

            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (entries as MutableList<ProjectTreeEntry>).clear()
            }
            assertTrue(entries.isNotEmpty())
        }

    @Test
    fun `tree limits must be non-negative`() {
        val invalid =
            listOf(
                { ProjectLimits(treeEntries = -1) },
                { ProjectLimits(treeDepth = -1) },
                { ProjectLimits(pathUtf8Bytes = -1) },
                { ProjectLimits(treeMetadataBytes = -1) },
                { ProjectLimits(projectFileBytes = -1) },
                { ProjectLimits(projectBytes = -1) },
            )

        invalid.forEach { create -> assertFailsWith<IllegalArgumentException> { create() } }
    }

    @Test
    fun `scan rejects depth path entry metadata file and project byte limit violations`() =
        withProject { project ->
            write(project, "src/deep/leaf.kt", "leaf".encodeToByteArray())

            listOf(
                ProjectLimits(treeDepth = 2),
                ProjectLimits(pathUtf8Bytes = 4),
                ProjectLimits(treeEntries = 3),
                ProjectLimits(treeMetadataBytes = 4),
                ProjectLimits(projectFileBytes = 3),
                ProjectLimits(projectBytes = 3),
            ).forEach { limits ->
                assertFailsWith<SecureProjectFileException>(limits.toString()) {
                    ProjectTreeStore(project.handle, limits).scan()
                }
            }
        }

    @Test
    fun `scan rejects symbolic links`() =
        withProject { project ->
            val outside =
                project.handle.canonicalPath.parent
                    .resolve("outside.txt")
            Files.writeString(outside, "outside")
            Files.createSymbolicLink(project.handle.canonicalPath.resolve("linked.txt"), outside)

            assertFailsWith<SecureProjectFileException> {
                ProjectTreeStore(project.handle, ProjectLimits()).scan()
            }
        }

    @Test
    fun `scan rejects special files`() =
        withProject { project ->
            val socket = project.handle.canonicalPath.resolve("service.sock")
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.bind(UnixDomainSocketAddress.of(socket))

                assertFailsWith<SecureProjectFileException> {
                    ProjectTreeStore(project.handle, ProjectLimits()).scan()
                }
            }
        }

    @Test
    fun `scan rejects a replaced project root`() {
        val catalogRoot = createTempDirectory("compukters-tree-root-")
        val project = ProjectCatalog.open(catalogRoot).create("hello")
        val store = ProjectTreeStore(project.handle, ProjectLimits())
        val originalIdentity = project.handle.identity
        val moved = catalogRoot.resolve("moved")
        Files.move(project.handle.canonicalPath, moved)
        Files.createDirectory(project.handle.canonicalPath)

        val replacementAttributes =
            Files.readAttributes(
                project.handle.canonicalPath,
                BasicFileAttributes::class.java,
            )
        assertNotEquals(originalIdentity.fileKey, replacementAttributes.fileKey())
        assertFailsWith<SecureProjectFileException> { store.scan() }
    }

    private fun withProject(action: (ProjectDescriptor) -> Unit) {
        val root = createTempDirectory("compukters-tree-")
        action(ProjectCatalog.open(root).create("hello"))
    }

    private fun write(
        project: ProjectDescriptor,
        relative: String,
        content: ByteArray,
    ) {
        val target = project.handle.canonicalPath.resolve(relative)
        target.parent.createDirectories()
        target.writeBytes(content)
    }
}
