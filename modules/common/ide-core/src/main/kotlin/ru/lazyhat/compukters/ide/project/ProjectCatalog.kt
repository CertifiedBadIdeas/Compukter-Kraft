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

import ru.lazyhat.compukters.ide.project.fs.ProjectRootIdentity
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFileException
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFiles
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.util.UUID

class ProjectCatalogException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

enum class ProjectCreationStep {
    STAGING_CREATED,
    MANIFEST_WRITTEN,
    SOURCE_WRITTEN,
    BEFORE_PUBLISH,
}

class ProjectCatalog private constructor(
    private val rootIdentity: ProjectRootIdentity,
    private val limits: ProjectLimits,
    private val creationHook: (ProjectCreationStep) -> Unit,
) {
    fun projects(): List<ProjectDescriptor> =
        catalogOperation("list projects") { root ->
            buildList {
                root.forEach { entry ->
                    val name = entry.fileName
                    SecureProjectFiles.validateFilename(name)
                    val directoryName = name.toString()
                    if (directoryName.startsWith(STAGING_PREFIX)) return@forEach
                    validateDirectoryName(directoryName)
                    val attributes = SecureProjectFiles.attributes(root, name)
                    if (attributes.isSymbolicLink || !attributes.isDirectory) {
                        throw ProjectCatalogException("project catalog contains an unsafe entry: $directoryName")
                    }
                    root.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { project ->
                        val manifestSource = SecureProjectFiles.readText(project, MANIFEST_FILENAME, limits.manifestBytes)
                        val manifest =
                            try {
                                ProjectManifestCodec.decode(manifestSource, limits)
                            } catch (exception: ManifestException) {
                                throw ProjectCatalogException("invalid project manifest: $directoryName", exception)
                            }
                        val fileKey =
                            attributes.fileKey() ?: throw ProjectCatalogException("filesystem does not expose stable project identity")
                        val identity =
                            ProjectRootIdentity(
                                rootIdentity.canonicalPath.resolve(directoryName).toRealPath(LinkOption.NOFOLLOW_LINKS),
                                fileKey,
                            )
                        add(ProjectDescriptor(directoryName, manifest, ProjectHandle(directoryName, identity)))
                    }
                }
            }.sortedWith { left, right -> TomlSupport.utf8Comparator.compare(left.directoryName, right.directoryName) }
        }

    fun create(name: String): ProjectDescriptor {
        validateDirectoryName(name)
        val manifest = ProjectManifest.of(name, emptyMap(), limits)
        val stagingName = "$STAGING_PREFIX${UUID.randomUUID()}"
        val stagingPath = rootIdentity.canonicalPath.resolve(stagingName)
        try {
            catalogOperation("create project") { root ->
                if (SecureProjectFiles.attributesOrNull(root, Path.of(name)) != null) throw FileAlreadyExistsException(name)
                Files.createDirectory(stagingPath)
                creationHook(ProjectCreationStep.STAGING_CREATED)
                root.newDirectoryStream(Path.of(stagingName), LinkOption.NOFOLLOW_LINKS).use { staging ->
                    writeNew(staging, MANIFEST_FILENAME, ProjectManifestCodec.encode(manifest).encodeToByteArray())
                    creationHook(ProjectCreationStep.MANIFEST_WRITTEN)
                    Files.createDirectory(stagingPath.resolve(SOURCE_DIRECTORY))
                    staging.newDirectoryStream(Path.of(SOURCE_DIRECTORY), LinkOption.NOFOLLOW_LINKS).use { source ->
                        writeNew(source, MAIN_FILENAME, DEFAULT_MAIN.encodeToByteArray())
                    }
                    creationHook(ProjectCreationStep.SOURCE_WRITTEN)
                }
                creationHook(ProjectCreationStep.BEFORE_PUBLISH)
                if (!SecureProjectFiles.isValid(rootIdentity)) throw ProjectCatalogException("project catalog root was invalidated")
                root.move(Path.of(stagingName), root, Path.of(name))
            }
        } catch (exception: Exception) {
            cleanupStaging(stagingName)
            if (exception is IllegalArgumentException) throw exception
            throw ProjectCatalogException("failed to create project: $name", exception)
        }
        return projects().single { it.directoryName == name }
    }

    private fun validateDirectoryName(name: String) {
        ProjectManifest.validateName(name, limits)
    }

    private fun cleanupStaging(stagingName: String) {
        if (!SecureProjectFiles.isValid(rootIdentity)) return
        runCatching {
            SecureProjectFiles.withDirectory(rootIdentity.canonicalPath) { root, _ ->
                val staging = SecureProjectFiles.attributesOrNull(root, Path.of(stagingName)) ?: return@withDirectory
                if (staging.isSymbolicLink || !staging.isDirectory) return@withDirectory
                root.newDirectoryStream(Path.of(stagingName), LinkOption.NOFOLLOW_LINKS).use { directory ->
                    runCatching {
                        directory.newDirectoryStream(Path.of(SOURCE_DIRECTORY), LinkOption.NOFOLLOW_LINKS).use { source ->
                            runCatching { source.deleteFile(Path.of(MAIN_FILENAME)) }
                        }
                    }
                    runCatching { directory.deleteDirectory(Path.of(SOURCE_DIRECTORY)) }
                    runCatching { directory.deleteFile(Path.of(MANIFEST_FILENAME)) }
                }
                runCatching { root.deleteDirectory(Path.of(stagingName)) }
            }
        }
    }

    private fun <T> catalogOperation(
        description: String,
        action: (SecureDirectoryStream<Path>) -> T,
    ): T =
        try {
            SecureProjectFiles.withDirectory(rootIdentity.canonicalPath) { root, attributes ->
                if (attributes.fileKey() != rootIdentity.fileKey) throw ProjectCatalogException("project catalog root was invalidated")
                action(root)
            }
        } catch (exception: ProjectCatalogException) {
            throw exception
        } catch (exception: Exception) {
            throw ProjectCatalogException("failed to $description", exception)
        }

    private fun writeNew(
        directory: SecureDirectoryStream<Path>,
        name: String,
        content: ByteArray,
    ) {
        directory.newByteChannel(Path.of(name), WRITE_OPTIONS).use { channel ->
            val buffer = ByteBuffer.wrap(content)
            while (buffer.hasRemaining()) channel.write(buffer)
            (channel as? FileChannel)?.force(true)
        }
    }

    companion object {
        fun open(
            projectsRoot: Path,
            limits: ProjectLimits = ProjectLimits(),
        ): ProjectCatalog = ProjectCatalog(SecureProjectFiles.identity(projectsRoot), limits) {}

        internal fun open(
            projectsRoot: Path,
            limits: ProjectLimits = ProjectLimits(),
            creationHook: (ProjectCreationStep) -> Unit,
        ): ProjectCatalog = ProjectCatalog(SecureProjectFiles.identity(projectsRoot), limits, creationHook)

        private const val MANIFEST_FILENAME = "compukter.toml"
        private const val SOURCE_DIRECTORY = "src"
        private const val MAIN_FILENAME = "main.kt"
        private const val DEFAULT_MAIN = "fun main() {\n}\n"
        private const val STAGING_PREFIX = ".creating-"
        private val WRITE_OPTIONS: Set<OpenOption> =
            setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
    }
}
