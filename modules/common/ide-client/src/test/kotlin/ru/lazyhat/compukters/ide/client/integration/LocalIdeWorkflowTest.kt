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
 */

package ru.lazyhat.compukters.ide.client.integration

import ru.lazyhat.compukters.compiler.cache.ArtifactVerifier
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadLoader
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.client.workspace.DefaultIdeWorkspace
import ru.lazyhat.compukters.ide.client.workspace.IdeSaveRequest
import ru.lazyhat.compukters.ide.client.workspace.ProjectFileOpenResult
import ru.lazyhat.compukters.ide.compiler.ClientBuildResult
import ru.lazyhat.compukters.ide.compiler.ClientBuildSnapshot
import ru.lazyhat.compukters.ide.compiler.ClientCompilationCache
import ru.lazyhat.compukters.ide.compiler.ControllerClientCompilerBackend
import ru.lazyhat.compukters.ide.compiler.DefaultClientCompilationService
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfileResolver
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.ProfileResolution
import ru.lazyhat.compukters.ide.project.ProjectLockService
import ru.lazyhat.compukters.ide.project.ProjectManifestCodec
import ru.lazyhat.compukters.ide.project.ProjectResolution
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalIdeWorkflowTest {
    @Test
    fun `real project resolves builds and reuses global compiler cache`() {
        val root = createTempDirectory("compukters-local-ide-workflow-").toAbsolutePath().normalize()
        val workspace = DefaultIdeWorkspace(root.resolve("projects").createDirectories())
        val payload = WorkerPayloadLoader.loadToolingProfile(Path.of(checkNotNull(System.getProperty("compukters.ide.toolingPayload"))))
        val limits = WorkerLimits()
        val controller =
            CompilerWorkerController(
                payload,
                WorkerLaunch(
                    Path.of(checkNotNull(System.getProperty("compukters.ide.java"))),
                    512,
                    256,
                    root.resolve("worker"),
                    payload.manifest.identity,
                    limits.frameBytes,
                    limits.stderrBytes,
                ),
                limits,
                JdkWorkerProcessFactory(),
            )
        val service =
            DefaultClientCompilationService(
                ClientCompilationCache.open(root.resolve("cache"), verifier = ArtifactVerifier { it.size >= 4 }),
                ControllerClientCompilerBackend(controller),
            )
        try {
            val project = workspace.createProject("demo").get(10, TimeUnit.SECONDS)
            val path = ProjectPath.file("src/main.kt")
            val opened = assertIs<ProjectFileOpenResult.Text>(workspace.open(project.handle, path).get(10, TimeUnit.SECONDS))
            val source = "fun main() { val answer = 42 }"
            workspace
                .save(IdeSaveRequest(project.handle, path, opened.snapshot.revision, source))
                .get(10, TimeUnit.SECONDS)

            val identity = payload.manifest.identity
            val toolchain =
                ToolchainLockIdentity(
                    identity.compilerVersion,
                    identity.languageVersion,
                    identity.codegenAbi,
                    1u,
                    identity.artifactWriterVersion,
                    identity.payloadHash,
                    identity.platformAbi,
                )
            val manifestBytes =
                project.handle.canonicalPath
                    .resolve("compukter.toml")
                    .toFile()
                    .readBytes()
            val manifest = ProjectManifestCodec.decode(manifestBytes.decodeToString())
            val platform =
                payload.classpath
                    .mapNotNull { path ->
                        ZipFile(path.toFile()).use { archive ->
                            archive.getEntry("compukters-platform/compukters-platform.cpb")?.let { entry ->
                                archive.getInputStream(entry).use { PlatformBundleCodec.decode(it.readAllBytes()) }
                            }
                        }
                    }.single()
            val catalog = PlatformCatalog.of(platform)
            val resolution = ProjectResolution(toolchain, catalog)
            ProjectLockService(project.handle.lockFileWriter()).createLock(manifest, resolution)
            val input = workspace.buildInput(project.handle).get(10, TimeUnit.SECONDS)
            assertEquals(
                source,
                input.sources.sources
                    .single()
                    .content
                    .toByteArray()
                    .decodeToString(),
            )
            val profile =
                assertIs<ProfileResolution.Resolved>(
                    CompileProfileResolver(toolchain, catalog, limits)
                        .resolveLocal(
                            ru.lazyhat.compukters.ide.project.ProjectLockCodec
                                .decode(requireNotNull(input.lockBytes).decodeToString()),
                        ),
                ).profile
            val snapshot =
                ClientBuildSnapshot(
                    input.sources,
                    BinaryValue.of(input.manifestBytes),
                    BinaryValue.of(requireNotNull(input.lockBytes)),
                    profile,
                )

            val firstResult = service.build(snapshot).get(90, TimeUnit.SECONDS)
            val first = assertIs<ClientBuildResult.Success>(firstResult, firstResult.toString())
            val secondResult = service.build(snapshot).get(90, TimeUnit.SECONDS)
            val second = assertIs<ClientBuildResult.Success>(secondResult, secondResult.toString())

            assertFalse(first.cacheHit)
            assertTrue(second.cacheHit)
            assertEquals(first.artifactHash, second.artifactHash)
        } finally {
            service.close()
            workspace.close()
            root.toFile().deleteRecursively()
        }
    }
}
