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

package ru.lazyhat.compukters.compiler.project

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.moveTo
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectSnapshotTest {
    @Test
    fun `source-set loader includes only canonical Kotlin files below src`() {
        val root = createTempDirectory("compukter-source-set-")
        root.resolve("src/nested").createDirectories()
        root.resolve("src/z.kt").writeText("val z = 2")
        root.resolve("src/nested/a.kt").writeText("val a = 1")
        root.resolve("src/notes.txt").writeText("ignored")
        root.resolve("outside.kt").writeText("error(\"ignored\")")
        root.resolve("generated/also.kt").apply {
            parent.createDirectories()
            writeText("error(\"ignored\")")
        }

        val snapshot = ProjectSnapshotLoader.loadSourceSet(root, WorkerLimits())

        assertEquals(listOf("src/nested/a.kt", "src/z.kt"), snapshot.sources.map { it.path.value })
        assertEquals(
            listOf("val a = 1", "val z = 2"),
            snapshot.sources.map { it.content.toByteArray().decodeToString() },
        )
    }

    @Test
    fun `source-set loader rejects a missing or empty source directory`() {
        val missing = createTempDirectory("compukter-source-set-missing-")
        val empty = createTempDirectory("compukter-source-set-empty-")
        empty.resolve("src").createDirectories()
        empty.resolve("src/README.md").writeText("ignored")

        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.loadSourceSet(missing, WorkerLimits()) }
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.loadSourceSet(empty, WorkerLimits()) }
    }

    @Test
    fun `source-set loader rejects invalid linked special and over-limit input`() {
        val invalid = createTempDirectory("compukter-source-set-invalid-")
        invalid.resolve("src").createDirectories()
        invalid.resolve("src/bad.kt").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.loadSourceSet(invalid, WorkerLimits()) }

        val linked = createTempDirectory("compukter-source-set-linked-")
        linked.resolve("src").createDirectories()
        linked.resolve("src/main.kt").writeText("val main = 1")
        Files.createSymbolicLink(linked.resolve("src/alias.kt"), linked.resolve("src/main.kt"))
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.loadSourceSet(linked, WorkerLimits()) }

        val special = createTempDirectory("compukter-source-set-special-")
        special.resolve("src").createDirectories()
        special.resolve("src/main.kt").writeText("val main = 1")
        val fifo = special.resolve("src/events.kt")
        val mkfifo = ProcessBuilder("mkfifo", fifo.toString()).start()
        assertEquals(0, mkfifo.waitFor(), mkfifo.errorStream.bufferedReader().readText())
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.loadSourceSet(special, WorkerLimits()) }

        val bounded = createTempDirectory("compukter-source-set-bounded-")
        bounded.resolve("src").createDirectories()
        bounded.resolve("src/a.kt").writeText("aa")
        bounded.resolve("src/b.kt").writeText("bb")
        assertFailsWith<ProjectSnapshotException> {
            ProjectSnapshotLoader.loadSourceSet(bounded, WorkerLimits(sourceFiles = 1))
        }
        assertFailsWith<ProjectSnapshotException> {
            ProjectSnapshotLoader.loadSourceSet(bounded, WorkerLimits(sourceFileBytes = 1))
        }
        assertFailsWith<ProjectSnapshotException> {
            ProjectSnapshotLoader.loadSourceSet(bounded, WorkerLimits(sourceBytes = 3))
        }
    }

    @Test
    fun `loader returns strict UTF-8 Kotlin sources in canonical path order`() {
        val root = createTempDirectory("compukter-project-")
        root.resolve("z.kt").writeText("package sample\nval z = 2")
        root.resolve("nested").createDirectories()
        root.resolve("nested/a.kt").writeText("package sample\nval a = 1")
        root.resolve("compukter.toml").writeText("main = \"nested/a.kt\"")

        val snapshot = ProjectSnapshotLoader.load(root, WorkerLimits())

        assertEquals(listOf("nested/a.kt", "z.kt"), snapshot.sources.map { it.path.value })
        assertEquals(
            listOf("package sample\nval a = 1", "package sample\nval z = 2"),
            snapshot.sources.map {
                it.content.toByteArray().decodeToString()
            },
        )
    }

    @Test
    fun `snapshot owns source bytes and source list`() {
        val bytes = "val answer = 42".encodeToByteArray()
        val mutable = mutableListOf(ProjectSource(VirtualSourcePath.kotlin("main.kt"), BinaryValue.of(bytes)))
        val snapshot = ProjectSnapshot.of(mutable, WorkerLimits())

        bytes.fill(0)
        mutable.clear()

        assertEquals(1, snapshot.sources.size)
        assertContentEquals(
            "val answer = 42".encodeToByteArray(),
            snapshot.sources
                .single()
                .content
                .toByteArray(),
        )
    }

    @Test
    fun `Kotlin source paths reject host and non-Kotlin paths`() {
        listOf(
            "",
            "/main.kt",
            "C:/main.kt",
            "C:main.kt",
            "//server/share/main.kt",
            "../main.kt",
            "a/./main.kt",
            "a//main.kt",
            "a\\main.kt",
            "main.kts",
            "main.txt",
            "main.kt\u0000",
            "\ud800.kt",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { VirtualSourcePath.kotlin(value) }
        }
        assertEquals("src/main.kt", VirtualSourcePath.kotlin("src/main.kt").value)
        // Diagnostic paths remain protocol-safe but are not limited to Kotlin source suffixes.
        assertEquals("generated/report.txt", VirtualSourcePath.of("generated/report.txt").value)
    }

    @Test
    fun `snapshot rejects empty unordered duplicate invalid UTF-8 and over-limit sources`() {
        val a = source("a.kt", "a")
        val b = source("b.kt", "bb")
        val invalid = ProjectSource(VirtualSourcePath.kotlin("bad.kt"), BinaryValue.of(byteArrayOf(0xc3.toByte(), 0x28)))

        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(emptyList(), WorkerLimits()) }
        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(listOf(b, a), WorkerLimits()) }
        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(listOf(a, a), WorkerLimits()) }
        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(listOf(invalid), WorkerLimits()) }
        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(listOf(a, b), WorkerLimits(sourceFiles = 1)) }
        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(listOf(b), WorkerLimits(sourceFileBytes = 1)) }
        assertFailsWith<IllegalArgumentException> { ProjectSnapshot.of(listOf(a, b), WorkerLimits(sourceBytes = 2)) }
    }

    @Test
    fun `loader rejects empty invalid UTF-8 symlinks and bounded source input`() {
        val empty = createTempDirectory("compukter-empty-")
        empty.resolve("README.md").writeText("ignored")
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(empty, WorkerLimits()) }

        val invalid = createTempDirectory("compukter-invalid-")
        invalid.resolve("bad.kt").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(invalid, WorkerLimits()) }

        val linked = createTempDirectory("compukter-linked-")
        linked.resolve("main.kt").writeText("val main = 1")
        Files.createSymbolicLink(linked.resolve("alias.kt"), linked.resolve("main.kt"))
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(linked, WorkerLimits()) }

        val bounded = createTempDirectory("compukter-bounded-")
        bounded.resolve("a.kt").writeText("aa")
        bounded.resolve("b.kt").writeText("bb")
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(bounded, WorkerLimits(sourceFiles = 1)) }
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(bounded, WorkerLimits(sourceFileBytes = 1)) }
        assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(bounded, WorkerLimits(sourceBytes = 3)) }
    }

    @Test
    fun `loader rejects a Kotlin filename whose raw bytes are not UTF-8`() {
        val root = createTempDirectory("compukter-raw-filename-")
        val fixture =
            ProcessBuilder(
                "sh",
                "-c",
                "name=\$(printf '\\303'); printf 'val answer = 42' > \"\$RAW_PROJECT/\$name.kt\"",
            ).apply {
                environment()["RAW_PROJECT"] = root.toString()
            }.start()
        assertEquals(0, fixture.waitFor(), fixture.errorStream.bufferedReader().readText())
        val rawName = Files.list(root).use { it.toList().single().fileName }
        // Some providers cannot preserve non-UTF-8 names. The behavior is only applicable when they can.
        if (rawName == rawName.fileSystem.getPath(rawName.toString())) return

        val failure = assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(root, WorkerLimits()) }

        assertTrue(failure.message.orEmpty().contains("filename"))
    }

    @Test
    fun `distinct lossy filename decodings cannot collapse to one virtual path`() {
        val root = createTempDirectory("compukter-lossy-filenames-")
        val fixture =
            ProcessBuilder(
                "sh",
                "-c",
                "first=\$(printf '\\303'); second=\$(printf '\\304'); " +
                    "printf 'val first = 1' > \"\$RAW_PROJECT/\$first.kt\"; " +
                    "printf 'val second = 2' > \"\$RAW_PROJECT/\$second.kt\"",
            ).apply {
                environment()["RAW_PROJECT"] = root.toString()
            }.start()
        assertEquals(0, fixture.waitFor(), fixture.errorStream.bufferedReader().readText())
        val rawNames = Files.list(root).use { entries -> entries.map { it.fileName }.toList() }
        if (rawNames.all { it == it.fileSystem.getPath(it.toString()) }) return

        val failure = assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.load(root, WorkerLimits()) }

        assertTrue(failure.message.orEmpty().contains("filename"))
    }

    @Test
    fun `bounded source open does not follow a link presented after validation`() {
        val root = createTempDirectory("compukter-open-link-")
        val outside = root.resolve("outside.txt").also { it.writeText("outside") }
        val source = root.resolve("main.kt")
        Files.createSymbolicLink(source, outside)

        assertFailsWith<Exception> { ProjectSnapshotLoader.readSourceBytes(source, WorkerLimits().sourceFileBytes) }
    }

    @Test
    fun `loader remains anchored when an opened parent is replaced by a symlink`() {
        val root = createTempDirectory("compukter-parent-swap-")
        val project = root.resolve("project").also { it.createDirectories() }
        val nested = project.resolve("nested").also { it.createDirectories() }
        nested.resolve("main.kt").writeText("val value = 1")
        val outside = root.resolve("outside").also { it.createDirectories() }
        outside.resolve("main.kt").writeText("val value = 2")
        var swapped = false
        val original = project.resolve("nested-original")

        val snapshot =
            ProjectSnapshotLoader.load(project, WorkerLimits()) { path, open ->
                if (!swapped && path.value == "nested/main.kt") {
                    swapped = true
                    nested.moveTo(original)
                    Files.createSymbolicLink(nested, outside)
                }
                try {
                    open()
                } finally {
                    if (Files.isSymbolicLink(nested)) {
                        Files.delete(nested)
                        original.moveTo(nested)
                    }
                }
            }

        assertEquals(true, swapped)
        assertEquals(
            "val value = 1",
            snapshot.sources
                .single()
                .content
                .toByteArray()
                .decodeToString(),
        )
    }

    @Test
    fun `loader closes and rejects a filesystem without secure directory streams`() {
        var closed = false
        val unsupported =
            object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> = mutableListOf<Path>().iterator()

                override fun close() {
                    closed = true
                }
            }

        val failure = assertFailsWith<ProjectSnapshotException> { ProjectSnapshotLoader.requireSecure(unsupported) }

        assertContains(failure.message.orEmpty(), "secure project traversal")
        assertEquals(true, closed)
    }

    private fun source(
        path: String,
        content: String,
    ) = ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(content.encodeToByteArray()))
}
