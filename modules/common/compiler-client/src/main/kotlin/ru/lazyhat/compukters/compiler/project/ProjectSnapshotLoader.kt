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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.Channels
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

class ProjectSnapshotException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object ProjectSnapshotLoader {
    fun load(
        projectDirectory: Path,
        limits: WorkerLimits,
    ): ProjectSnapshot = load(projectDirectory, null, Path.of(""), limits) { _, open -> open() }

    fun loadSourceSet(
        projectDirectory: Path,
        limits: WorkerLimits,
    ): ProjectSnapshot = load(projectDirectory, Path.of("src"), Path.of("src"), limits) { _, open -> open() }

    internal fun load(
        projectDirectory: Path,
        limits: WorkerLimits,
        aroundSourceOpen: (VirtualSourcePath, () -> ByteArray) -> ByteArray,
    ): ProjectSnapshot = load(projectDirectory, null, Path.of(""), limits, aroundSourceOpen)

    private fun load(
        projectDirectory: Path,
        sourceDirectory: Path?,
        virtualDirectory: Path,
        limits: WorkerLimits,
        aroundSourceOpen: (VirtualSourcePath, () -> ByteArray) -> ByteArray,
    ): ProjectSnapshot {
        val state = LoadingState(limits, aroundSourceOpen)
        try {
            withSecureProjectDirectory(projectDirectory) { directory ->
                if (sourceDirectory == null) {
                    walkSecure(directory, virtualDirectory, state)
                } else {
                    withSecureRelativeDirectory(directory, sourceDirectory) { source ->
                        walkSecure(source, virtualDirectory, state)
                    }
                }
            }
        } catch (exception: ProjectSnapshotException) {
            throw exception
        } catch (exception: Exception) {
            throw ProjectSnapshotException("failed to load project snapshot", exception)
        }
        state.sources.sortWith { left, right -> ProjectSnapshot.compareUtf8(left.path.value, right.path.value) }
        return try {
            ProjectSnapshot.of(state.sources, limits)
        } catch (exception: IllegalArgumentException) {
            throw ProjectSnapshotException(exception.message ?: "invalid project snapshot", exception)
        }
    }

    private fun <T> withSecureRelativeDirectory(
        root: SecureDirectoryStream<Path>,
        relativeDirectory: Path,
        action: (SecureDirectoryStream<Path>) -> T,
    ): T {
        val opened = mutableListOf<SecureDirectoryStream<Path>>()
        try {
            var current = root
            relativeDirectory.forEach { component ->
                validateFilename(component)
                val attrs = attributes(current, component)
                if (attrs.isSymbolicLink || !attrs.isDirectory) {
                    throw ProjectSnapshotException("project source root must be a real directory")
                }
                current = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)
                opened += current
            }
            return action(current)
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun walkSecure(
        directory: SecureDirectoryStream<Path>,
        relativeDirectory: Path,
        state: LoadingState,
    ) {
        directory.forEach { entry ->
            val name = entry.fileName
            validateFilename(name)
            val relative = relativeDirectory.resolve(name)
            val attrs = attributes(directory, name)
            when {
                attrs.isSymbolicLink -> {
                    throw ProjectSnapshotException("project contains a symbolic link")
                }

                attrs.isDirectory -> {
                    directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { child ->
                        walkSecure(child, relative, state)
                    }
                }

                attrs.isRegularFile -> {
                    state.readSource(directory, name, relative, attrs)
                }

                else -> {
                    throw ProjectSnapshotException("project contains a non-regular file")
                }
            }
        }
    }

    private fun validateFilename(name: Path) {
        val decoded = name.toString()
        val reconstructed = name.fileSystem.getPath(decoded)
        if (reconstructed != name) {
            throw ProjectSnapshotException("project filename is not strict UTF-8")
        }
        try {
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(decoded))
        } catch (exception: Exception) {
            throw ProjectSnapshotException("project filename is not strict UTF-8", exception)
        }
    }

    private fun attributes(
        directory: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes =
        directory
            .getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.readAttributes()
            ?: throw ProjectSnapshotException("filesystem cannot securely read project entry attributes")

    private fun <T> withSecureProjectDirectory(
        projectDirectory: Path,
        action: (SecureDirectoryStream<Path>) -> T,
    ): T {
        val absolute = projectDirectory.toAbsolutePath().normalize()
        val root = absolute.root ?: throw ProjectSnapshotException("project root has no filesystem root")
        val opened = mutableListOf<SecureDirectoryStream<Path>>()
        try {
            var current = requireSecure(Files.newDirectoryStream(root))
            opened += current
            root.relativize(absolute).forEach { component ->
                val attrs = attributes(current, component)
                if (attrs.isSymbolicLink || !attrs.isDirectory) {
                    throw ProjectSnapshotException("project root must be a real directory")
                }
                current = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)
                opened += current
            }
            return action(current)
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    internal fun requireSecure(directory: DirectoryStream<Path>): SecureDirectoryStream<Path> {
        if (directory is SecureDirectoryStream<Path>) return directory
        directory.close()
        throw ProjectSnapshotException("filesystem does not support secure project traversal")
    }

    internal fun readSourceBytes(
        file: Path,
        maximumBytes: Int,
    ): ByteArray {
        val absolute = file.toAbsolutePath().normalize()
        val parent = absolute.parent ?: throw ProjectSnapshotException("project source has no parent directory")
        return withSecureProjectDirectory(parent) { directory ->
            readSourceBytes(directory, absolute.fileName, maximumBytes)
        }
    }

    private fun readSourceBytes(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        maximumBytes: Int,
    ): ByteArray =
        Channels
            .newInputStream(
                directory.newByteChannel(
                    name,
                    setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                ),
            ).use { input -> readBounded(input, maximumBytes) }

    private fun readBounded(
        input: java.io.InputStream,
        maximumBytes: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_BYTES))
        val buffer = ByteArray(minOf(maximumBytes.coerceAtLeast(1), DEFAULT_BUFFER_BYTES))
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total = Math.addExact(total, count.toLong())
            if (total > maximumBytes.toLong()) throw ProjectSnapshotException("project source grew beyond per-file limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private class LoadingState(
        private val limits: WorkerLimits,
        private val aroundSourceOpen: (VirtualSourcePath, () -> ByteArray) -> ByteArray,
    ) {
        val sources = mutableListOf<ProjectSource>()
        private var totalBytes = 0L

        fun readSource(
            directory: SecureDirectoryStream<Path>,
            name: Path,
            relativePath: Path,
            attrs: BasicFileAttributes,
        ) {
            val relative = relativePath.joinToString("/") { it.toString() }
            if (!relative.endsWith(".kt")) return
            if (sources.size >= limits.sourceFiles) throw ProjectSnapshotException("project source count exceeds limit")
            val path =
                try {
                    VirtualSourcePath.kotlin(relative)
                } catch (exception: IllegalArgumentException) {
                    throw ProjectSnapshotException("invalid project source path: $relative", exception)
                }
            val declaredBytes = attrs.size()
            if (declaredBytes < 0 || declaredBytes > limits.sourceFileBytes.toLong()) {
                throw ProjectSnapshotException("project source exceeds per-file limit: $relative")
            }
            totalBytes = Math.addExact(totalBytes, declaredBytes)
            if (totalBytes > limits.sourceBytes.toLong()) throw ProjectSnapshotException("project sources exceed total limit")
            val content = aroundSourceOpen(path) { readSourceBytes(directory, name, limits.sourceFileBytes) }
            if (content.size.toLong() != declaredBytes) {
                throw ProjectSnapshotException("project source changed while loading: $relative")
            }
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
            } catch (exception: Exception) {
                throw ProjectSnapshotException("project source is not strict UTF-8: $relative", exception)
            }
            sources += ProjectSource(path, BinaryValue.of(content))
        }
    }

    private const val DEFAULT_BUFFER_BYTES = 8192
}
