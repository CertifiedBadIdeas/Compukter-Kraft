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
import ru.lazyhat.compukters.ide.project.TomlSupport
import ru.lazyhat.compukters.ide.project.document.FileRevision
import ru.lazyhat.compukters.ide.project.document.ProjectWriteStep
import ru.lazyhat.compukters.ide.project.tree.ProjectImport
import ru.lazyhat.compukters.ide.project.tree.ProjectImportEntry
import ru.lazyhat.compukters.ide.project.tree.ProjectImportStep
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

class SecureProjectFileException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal object SecureProjectFiles {
    fun identity(directory: Path): ProjectRootIdentity =
        withDirectory(directory) { _, attributes ->
            val fileKey = attributes.fileKey() ?: throw SecureProjectFileException("filesystem does not expose stable file identity")
            ProjectRootIdentity(directory.toRealPath(LinkOption.NOFOLLOW_LINKS), fileKey)
        }

    fun isValid(identity: ProjectRootIdentity): Boolean =
        try {
            identity(identity.canonicalPath) == identity
        } catch (_: Exception) {
            false
        }

    fun readText(
        directory: SecureDirectoryStream<Path>,
        name: String,
        maximumBytes: Int,
    ): String = TomlSupport.decodeStrictUtf8(readBytes(directory, Path.of(name), maximumBytes))

    fun readFile(
        identity: ProjectRootIdentity,
        path: ProjectPath,
        maximumBytes: Int,
    ): ByteArray? =
        withValidProject(identity) { root ->
            withParentDirectory(root, path) { parent, target ->
                if (attributesOrNull(parent, target) == null) null else readBytes(parent, target, maximumBytes)
            }
        }

    fun readSource(
        identity: ProjectRootIdentity,
        path: ProjectPath,
        maximumBytes: Int,
    ): ByteArray? = readFile(identity, path, maximumBytes)

    fun writeFile(
        identity: ProjectRootIdentity,
        path: ProjectPath,
        expected: FileRevision,
        content: ByteArray,
        maximumBytes: Int,
        hook: (ProjectWriteStep) -> Unit,
    ): SecureWriteResult =
        synchronized(identity) {
            withValidProject(identity) { root ->
                withParentDirectory(root, path) { parent, target ->
                    val actual = revision(parent, target, maximumBytes)
                    if (actual != expected) return@withParentDirectory SecureWriteResult.Conflict(actual)
                    val temporary = Path.of(".compukter-save-${UUID.randomUUID()}.tmp")
                    try {
                        parent.newByteChannel(temporary, WRITE_NEW_OPTIONS).use { channel ->
                            hook(ProjectWriteStep.TEMPORARY_CREATED)
                            writeFully(channel, content)
                            (channel as? FileChannel)?.force(true)
                            hook(ProjectWriteStep.TEMPORARY_WRITTEN)
                        }
                        hook(ProjectWriteStep.BEFORE_PUBLISH)
                        if (!isValid(identity)) throw SecureProjectFileException("project root was invalidated")
                        val latest = revision(parent, target, maximumBytes)
                        if (latest != expected) return@withParentDirectory SecureWriteResult.Conflict(latest)
                        when (expected) {
                            FileRevision.Absent -> {
                                parent.move(temporary, parent, target)
                            }

                            is FileRevision.Present -> {
                                try {
                                    Files.move(
                                        identity.canonicalPath.resolve(path.value).resolveSibling(temporary.toString()),
                                        identity.canonicalPath.resolve(path.value),
                                        StandardCopyOption.ATOMIC_MOVE,
                                        StandardCopyOption.REPLACE_EXISTING,
                                    )
                                } catch (exception: AtomicMoveNotSupportedException) {
                                    throw SecureProjectFileException("filesystem does not support atomic source replacement", exception)
                                }
                            }
                        }
                        SecureWriteResult.Saved(FileRevision.Present(hash(content)))
                    } finally {
                        runCatching { parent.deleteFile(temporary) }
                    }
                }
            }
        }

    fun writeSource(
        identity: ProjectRootIdentity,
        path: ProjectPath,
        expected: FileRevision,
        content: ByteArray,
        maximumBytes: Int,
        hook: (ProjectWriteStep) -> Unit,
    ): SecureWriteResult = writeFile(identity, path, expected, content, maximumBytes, hook)

    internal fun replaceBatch(
        identity: ProjectRootIdentity,
        replacements: List<SecureBatchReplacement>,
        hook: (ProjectPath, SecureBatchWriteStep) -> Unit = { _, _ -> },
    ): SecureBatchWriteResult =
        synchronized(identity) {
            require(replacements.isNotEmpty()) { "secure batch must not be empty" }
            require(replacements.map(SecureBatchReplacement::path).toSet().size == replacements.size) {
                "secure batch contains duplicate paths"
            }
            require(replacements.all { it.path.components.size == 1 }) { "secure batch supports direct project files only" }
            withValidProject(identity) { root ->
                data class Staged(
                    val replacement: SecureBatchReplacement,
                    val target: Path,
                    val temporary: Path?,
                    val backup: Path,
                    var backedUp: Boolean = false,
                    var published: Boolean = false,
                )

                val token = UUID.randomUUID().toString()
                val staged =
                    replacements.mapIndexed { index, replacement ->
                        require((replacement.content?.size ?: 0) <= replacement.maximumBytes) {
                            "secure batch content exceeds byte limit: ${replacement.path.value}"
                        }
                        val temporary = replacement.content?.let { Path.of(".compukter-batch-$token-$index.tmp") }
                        if (temporary != null) {
                            root.newByteChannel(temporary, WRITE_NEW_OPTIONS).use { channel ->
                                writeFully(channel, requireNotNull(replacement.content))
                                (channel as? FileChannel)?.force(true)
                            }
                        }
                        hook(replacement.path, SecureBatchWriteStep.STAGED)
                        Staged(
                            replacement,
                            Path.of(replacement.path.value),
                            temporary,
                            Path.of(".compukter-batch-$token-$index.bak"),
                        )
                    }
                try {
                    hook(replacements.first().path, SecureBatchWriteStep.BEFORE_PUBLISH)
                    if (!isValid(identity)) throw SecureProjectFileException("project root was invalidated")
                    staged.forEach { item ->
                        val actual = revision(root, item.target, item.replacement.maximumBytes)
                        if (actual != item.replacement.expected) {
                            return@withValidProject SecureBatchWriteResult.Conflict(item.replacement.path, actual)
                        }
                    }
                    staged.forEach { item ->
                        if (attributesOrNull(root, item.target) != null) {
                            root.move(item.target, root, item.backup)
                            item.backedUp = true
                        }
                        item.temporary?.let { root.move(it, root, item.target) }
                        item.published = true
                        hook(item.replacement.path, SecureBatchWriteStep.PUBLISHED)
                    }
                    val revisions =
                        staged.associate { item ->
                            item.replacement.path to revision(root, item.target, item.replacement.maximumBytes)
                        }
                    staged.forEach { item ->
                        if (item.backedUp) root.deleteFile(item.backup)
                        item.backedUp = false
                    }
                    SecureBatchWriteResult.Published(revisions)
                } catch (failure: Throwable) {
                    staged.asReversed().forEach { item ->
                        try {
                            if (item.published && attributesOrNull(root, item.target) != null) root.deleteFile(item.target)
                            if (item.backedUp) root.move(item.backup, root, item.target)
                            item.backedUp = false
                        } catch (rollback: Throwable) {
                            failure.addSuppressed(rollback)
                        }
                    }
                    throw failure
                } finally {
                    staged.forEach { item ->
                        item.temporary?.let { runCatching { root.deleteFile(it) } }
                        if (!item.backedUp) runCatching { root.deleteFile(item.backup) }
                    }
                }
            }
        }

    internal fun createFile(
        identity: ProjectRootIdentity,
        path: ProjectPath,
    ): Boolean =
        synchronized(identity) {
            withValidProject(identity) { root ->
                withParentDirectory(root, path) { parent, target ->
                    if (attributesOrNull(parent, target) != null) return@withParentDirectory false
                    parent.newByteChannel(target, WRITE_NEW_OPTIONS).use { channel ->
                        (channel as? FileChannel)?.force(true)
                    }
                    true
                }
            }
        }

    internal fun createDirectory(
        identity: ProjectRootIdentity,
        path: ProjectPath,
    ): Boolean =
        synchronized(identity) {
            withValidProject(identity) { root ->
                withParentDirectory(root, path) { parent, target ->
                    if (attributesOrNull(parent, target) != null) return@withParentDirectory false
                    val absolute = identity.canonicalPath.resolve(path.value)
                    Files.createDirectory(absolute)
                    val created = attributes(parent, target)
                    if (created.isSymbolicLink || !created.isDirectory) {
                        throw SecureProjectFileException("created project entry is not a real directory")
                    }
                    if (!isValid(identity)) throw SecureProjectFileException("project root was invalidated")
                    true
                }
            }
        }

    internal fun move(
        identity: ProjectRootIdentity,
        source: ProjectPath,
        target: ProjectPath,
    ): Boolean =
        synchronized(identity) {
            withValidProject(identity) { root ->
                val opened = mutableListOf<SecureDirectoryStream<Path>>()
                try {
                    val sourceParent = openParentDirectory(root, source, opened)
                    val targetParent =
                        if (source.components.dropLast(1) == target.components.dropLast(1)) {
                            sourceParent
                        } else {
                            openParentDirectory(root, target, opened)
                        }
                    val sourceName = Path.of(source.components.last())
                    val targetName = Path.of(target.components.last())
                    val sourceAttributes = attributesOrNull(sourceParent, sourceName) ?: return@withValidProject false
                    if (sourceAttributes.isSymbolicLink || (!sourceAttributes.isDirectory && !sourceAttributes.isRegularFile)) {
                        throw SecureProjectFileException("project move source must be a real file or directory")
                    }
                    if (attributesOrNull(targetParent, targetName) != null) return@withValidProject false
                    sourceParent.move(sourceName, targetParent, targetName)
                    true
                } finally {
                    opened.asReversed().forEach { runCatching { it.close() } }
                }
            }
        }

    internal fun deleteFile(
        identity: ProjectRootIdentity,
        path: ProjectPath,
        expected: FileRevision.Present,
        maximumBytes: Int,
    ): Boolean =
        synchronized(identity) {
            withValidProject(identity) { root ->
                withParentDirectory(root, path) { parent, target ->
                    val attributes = attributesOrNull(parent, target) ?: return@withParentDirectory false
                    if (attributes.isSymbolicLink || !attributes.isRegularFile) return@withParentDirectory false
                    if (revision(parent, target, maximumBytes) != expected) return@withParentDirectory false
                    parent.deleteFile(target)
                    true
                }
            }
        }

    internal fun deleteEmptyDirectory(
        identity: ProjectRootIdentity,
        path: ProjectPath,
    ): Boolean =
        synchronized(identity) {
            withValidProject(identity) { root ->
                withParentDirectory(root, path) { parent, target ->
                    val attributes = attributesOrNull(parent, target) ?: return@withParentDirectory false
                    if (attributes.isSymbolicLink || !attributes.isDirectory) return@withParentDirectory false
                    parent.newDirectoryStream(target, LinkOption.NOFOLLOW_LINKS).use { child ->
                        if (child.iterator().hasNext()) return@withParentDirectory false
                    }
                    parent.deleteDirectory(target)
                    true
                }
            }
        }

    internal fun <T> importTree(
        identity: ProjectRootIdentity,
        import: ProjectImport,
        expectedStillCurrent: () -> Boolean,
        validatePublished: () -> T,
        hook: (ProjectImportStep) -> Unit,
    ): SecureImportResult<T> =
        synchronized(identity) {
            withValidProject(identity) { root ->
                withParentDirectory(root, import.destination) { parent, target ->
                    val existing = attributesOrNull(parent, target)
                    if (existing != null && !import.replace) return@withParentDirectory SecureImportResult.Conflict
                    if (existing == null && import.replace) return@withParentDirectory SecureImportResult.Conflict
                    if (existing != null && (existing.isSymbolicLink || (!existing.isDirectory && !existing.isRegularFile))) {
                        throw SecureProjectFileException("project import destination must be a real file or directory")
                    }

                    val token = UUID.randomUUID().toString()
                    val temporary = Path.of(".compukter-import-$token.tmp")
                    val backup = Path.of(".compukter-import-$token.bak")
                    val parentAbsolute =
                        import.destination.components.dropLast(1).fold(identity.canonicalPath) { current, component ->
                            current.resolve(component)
                        }
                    val temporaryAbsolute = parentAbsolute.resolve(temporary.toString())
                    var backedUp = false
                    var published = false
                    try {
                        materializeImport(temporaryAbsolute, import.entries)
                        val staged = attributes(parent, temporary)
                        if (staged.isSymbolicLink || (!staged.isDirectory && !staged.isRegularFile)) {
                            throw SecureProjectFileException("staged project import is not a real file or directory")
                        }
                        hook(ProjectImportStep.STAGED)
                        hook(ProjectImportStep.BEFORE_PUBLISH)
                        if (!isValid(identity)) throw SecureProjectFileException("project root was invalidated")
                        if (!expectedStillCurrent()) return@withParentDirectory SecureImportResult.Conflict

                        if (existing != null) {
                            parent.move(target, parent, backup)
                            backedUp = true
                            hook(ProjectImportStep.EXISTING_BACKED_UP)
                        }
                        parent.move(temporary, parent, target)
                        published = true
                        hook(ProjectImportStep.PUBLISHED)
                        val validated = validatePublished()
                        if (backedUp) {
                            deleteRecursively(parent, backup)
                            backedUp = false
                        }
                        SecureImportResult.Published(validated)
                    } catch (failure: Throwable) {
                        if (published) {
                            runCatching { parent.move(target, parent, temporary) }
                            published = false
                        }
                        if (backedUp) {
                            try {
                                parent.move(backup, parent, target)
                                backedUp = false
                            } catch (rollback: Throwable) {
                                failure.addSuppressed(rollback)
                            }
                        }
                        throw failure
                    } finally {
                        runCatching { deleteRecursively(parent, temporary) }
                        if (!backedUp) runCatching { deleteRecursively(parent, backup) }
                    }
                }
            }
        }

    internal fun isImportArtifactName(name: String): Boolean =
        name.startsWith(".compukter-import-") && (name.endsWith(".tmp") || name.endsWith(".bak"))

    fun writeNew(
        identity: ProjectRootIdentity,
        name: String,
        content: ByteArray,
    ) = publish(identity, ProjectPath.direct(name), content, replace = false)

    fun replace(
        identity: ProjectRootIdentity,
        name: String,
        content: ByteArray,
    ) = publish(identity, ProjectPath.direct(name), content, replace = true)

    fun <T> withDirectory(
        directory: Path,
        action: (SecureDirectoryStream<Path>, BasicFileAttributes) -> T,
    ): T {
        val absolute = directory.toAbsolutePath().normalize()
        val filesystemRoot = absolute.root ?: throw SecureProjectFileException("directory has no filesystem root")
        val opened = mutableListOf<SecureDirectoryStream<Path>>()
        try {
            var current = requireSecure(Files.newDirectoryStream(filesystemRoot))
            opened += current
            var currentAttributes = Files.readAttributes(filesystemRoot, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            filesystemRoot.relativize(absolute).forEach { component ->
                validateFilename(component)
                currentAttributes = attributes(current, component)
                if (currentAttributes.isSymbolicLink || !currentAttributes.isDirectory) {
                    throw SecureProjectFileException("directory path must contain only real directories")
                }
                current = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)
                opened += current
            }
            return action(current, currentAttributes)
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    internal fun <T> withValidProject(
        identity: ProjectRootIdentity,
        action: (SecureDirectoryStream<Path>) -> T,
    ): T =
        withDirectory(identity.canonicalPath) { directory, attributes ->
            if (attributes.fileKey() != identity.fileKey) throw SecureProjectFileException("project root was invalidated")
            action(directory)
        }

    internal fun <T> withParentDirectory(
        root: SecureDirectoryStream<Path>,
        path: ProjectPath,
        action: (SecureDirectoryStream<Path>, Path) -> T,
    ): T {
        val opened = mutableListOf<SecureDirectoryStream<Path>>()
        try {
            val current = openParentDirectory(root, path, opened)
            return action(current, Path.of(path.components.last()))
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun openParentDirectory(
        root: SecureDirectoryStream<Path>,
        path: ProjectPath,
        opened: MutableList<SecureDirectoryStream<Path>>,
    ): SecureDirectoryStream<Path> {
        var current = root
        path.components.dropLast(1).forEach { component ->
            val name = Path.of(component)
            val attributes = attributes(current, name)
            if (attributes.isSymbolicLink || !attributes.isDirectory) {
                throw SecureProjectFileException("project file parent must be a real directory")
            }
            current = current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
            opened += current
        }
        return current
    }

    fun attributes(
        directory: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes =
        directory
            .getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.readAttributes()
            ?: throw SecureProjectFileException("filesystem cannot securely read entry attributes")

    fun attributesOrNull(
        directory: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes? =
        try {
            attributes(directory, name)
        } catch (_: NoSuchFileException) {
            null
        }

    fun validateFilename(name: Path) {
        val decoded = name.toString()
        if (name.fileSystem.getPath(decoded) != name) throw SecureProjectFileException("filename is not strict UTF-8")
        try {
            TomlSupport.strictUtf8(decoded)
        } catch (exception: Exception) {
            throw SecureProjectFileException("filename is not strict UTF-8", exception)
        }
    }

    private fun publish(
        identity: ProjectRootIdentity,
        path: ProjectPath,
        content: ByteArray,
        replace: Boolean,
    ) {
        synchronized(identity) {
            withDirectory(identity.canonicalPath) { directory, attributes ->
                if (attributes.fileKey() != identity.fileKey) throw SecureProjectFileException("project root was invalidated")
                val target = Path.of(path.components.single())
                val existing = attributesOrNull(directory, target)
                if (replace) {
                    check(existing != null) { "lock does not exist" }
                    if (existing.isSymbolicLink || !existing.isRegularFile) {
                        throw SecureProjectFileException("lock target must be a real regular file")
                    }
                } else {
                    check(existing == null) { "lock already exists" }
                }
                val temporary = Path.of(".compukter-lock-${UUID.randomUUID()}.tmp")
                try {
                    directory.newByteChannel(temporary, WRITE_NEW_OPTIONS).use { channel ->
                        writeFully(channel, content)
                        (channel as? FileChannel)?.force(true)
                    }
                    if (!isValid(identity)) throw SecureProjectFileException("project root was invalidated")
                    if (replace) {
                        try {
                            Files.move(
                                identity.canonicalPath.resolve(temporary.toString()),
                                identity.canonicalPath.resolve(target.toString()),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        } catch (exception: AtomicMoveNotSupportedException) {
                            throw SecureProjectFileException("filesystem does not support atomic lock replacement", exception)
                        }
                    } else {
                        directory.move(temporary, directory, target)
                    }
                } finally {
                    runCatching { directory.deleteFile(temporary) }
                }
            }
        }
    }

    private fun materializeImport(
        temporary: Path,
        entries: List<ProjectImportEntry>,
    ) {
        val root = entries.first()
        when (root) {
            is ProjectImportEntry.Directory -> Files.createDirectory(temporary)
            is ProjectImportEntry.File -> writeNewAbsolute(temporary, root.ownedBytes())
        }
        entries.drop(1).forEach { entry ->
            val relative = ProjectPath.file(entry.relativePath).components.drop(1)
            val target = relative.fold(temporary) { current, component -> current.resolve(component) }
            when (entry) {
                is ProjectImportEntry.Directory -> Files.createDirectory(target)
                is ProjectImportEntry.File -> writeNewAbsolute(target, entry.ownedBytes())
            }
            val attributes = Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile)) {
                throw SecureProjectFileException("staged project import contains a special entry")
            }
        }
    }

    private fun writeNewAbsolute(
        path: Path,
        content: ByteArray,
    ) {
        Files.newByteChannel(path, WRITE_NEW_OPTIONS).use { channel ->
            writeFully(channel, content)
            (channel as? FileChannel)?.force(true)
        }
    }

    private fun deleteRecursively(
        parent: SecureDirectoryStream<Path>,
        name: Path,
    ) {
        val attributes = attributesOrNull(parent, name) ?: return
        if (attributes.isDirectory && !attributes.isSymbolicLink) {
            parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { child ->
                child.toList().forEach { entry -> deleteRecursively(child, entry.fileName) }
            }
            parent.deleteDirectory(name)
        } else {
            parent.deleteFile(name)
        }
    }

    internal fun readBytes(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        maximumBytes: Int,
    ): ByteArray {
        val attributes = attributes(directory, name)
        if (attributes.isSymbolicLink || !attributes.isRegularFile) {
            throw SecureProjectFileException("project entry must be a real regular file")
        }
        if (attributes.size() > maximumBytes.toLong()) throw SecureProjectFileException("project entry exceeds byte limit")
        return java.nio.channels.Channels
            .newInputStream(directory.newByteChannel(name, READ_OPTIONS))
            .use { input ->
                val output = ByteArrayOutputStream(minOf(maximumBytes, 8192))
                val buffer = ByteArray(minOf(maximumBytes.toLong() + 1L, 8192L).toInt().coerceAtLeast(1))
                var total = 0
                while (true) {
                    val remainingThroughLimit = maximumBytes.toLong() - total.toLong() + 1L
                    val requested = minOf(buffer.size.toLong(), remainingThroughLimit).toInt().coerceAtLeast(1)
                    val count = input.read(buffer, 0, requested)
                    if (count < 0) break
                    total = Math.addExact(total, count)
                    if (total > maximumBytes) throw SecureProjectFileException("project entry grew beyond byte limit")
                    output.write(buffer, 0, count)
                }
                if (total.toLong() != attributes.size()) throw SecureProjectFileException("project entry changed while reading")
                output.toByteArray()
            }
    }

    private fun revision(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        maximumBytes: Int,
    ): FileRevision =
        if (attributesOrNull(directory, name) == null) {
            FileRevision.Absent
        } else {
            FileRevision.Present(hash(readBytes(directory, name, maximumBytes)))
        }

    private fun hash(content: ByteArray): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(content))

    private fun writeFully(
        channel: SeekableByteChannel,
        content: ByteArray,
    ) {
        val buffer = ByteBuffer.wrap(content)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun requireSecure(directory: DirectoryStream<Path>): SecureDirectoryStream<Path> {
        if (directory is SecureDirectoryStream<Path>) return directory
        directory.close()
        throw SecureProjectFileException("filesystem does not support secure project traversal")
    }

    private val READ_OPTIONS: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    private val WRITE_NEW_OPTIONS: Set<OpenOption> =
        setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
}

internal sealed interface SecureWriteResult {
    data class Saved(
        val revision: FileRevision.Present,
    ) : SecureWriteResult

    data class Conflict(
        val actual: FileRevision,
    ) : SecureWriteResult
}

internal data class SecureBatchReplacement(
    val path: ProjectPath,
    val expected: FileRevision,
    val content: ByteArray?,
    val maximumBytes: Int,
)

internal enum class SecureBatchWriteStep {
    STAGED,
    BEFORE_PUBLISH,
    PUBLISHED,
}

internal sealed interface SecureBatchWriteResult {
    data class Published(
        val revisions: Map<ProjectPath, FileRevision>,
    ) : SecureBatchWriteResult

    data class Conflict(
        val path: ProjectPath,
        val actual: FileRevision,
    ) : SecureBatchWriteResult
}

internal sealed interface SecureImportResult<out T> {
    data object Conflict : SecureImportResult<Nothing>

    data class Published<T>(
        val value: T,
    ) : SecureImportResult<T>
}
