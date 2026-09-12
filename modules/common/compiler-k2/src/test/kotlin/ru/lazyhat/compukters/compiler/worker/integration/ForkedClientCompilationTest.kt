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

package ru.lazyhat.compukters.compiler.worker.integration

import ru.lazyhat.compukters.compiler.cache.ArtifactVerifier
import ru.lazyhat.compukters.compiler.cache.CompilationCachePolicy
import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerPolicy
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadLoader
import ru.lazyhat.compukters.compiler.worker.controller.WorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.compiler.ClientBuildResult
import ru.lazyhat.compukters.ide.compiler.ClientBuildSnapshot
import ru.lazyhat.compukters.ide.compiler.ClientCompilationCache
import ru.lazyhat.compukters.ide.compiler.ClientCompilerBackend
import ru.lazyhat.compukters.ide.compiler.ControllerClientCompilerBackend
import ru.lazyhat.compukters.ide.compiler.DefaultClientCompilationService
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfileResolver
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.ProfileResolution
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ProjectLock
import ru.lazyhat.compukters.ide.project.ProjectLockCodec
import ru.lazyhat.compukters.ide.project.ProjectManifest
import ru.lazyhat.compukters.ide.project.ProjectManifestCodec
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import ru.lazyhat.compukters.worker.value.Sha256
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ForkedClientCompilationTest {
    @Test
    fun `real client path compiles multi-file project and reuses global cache`() =
        withClient { fixture ->
            val input = fixture.input("fun shared() = 41", "fun main() { shared() + 1 }")

            val first = assertIs<ClientBuildResult.Success>(fixture.service.build(input).get(90, TimeUnit.SECONDS))
            val second = assertIs<ClientBuildResult.Success>(fixture.service.build(input).get(90, TimeUnit.SECONDS))

            assertTrue(first.artifact.toByteArray().isNotEmpty())
            assertEquals(first.artifactHash, second.artifactHash)
            assertEquals(1, fixture.backend.compileCalls)
            assertEquals(1, fixture.processStarts)
        }

    @Test
    fun `target mismatch is rejected before process launch and unicode diagnostics remain typed`() =
        withClient { fixture ->
            val incompatible =
                TargetCompileProfile(
                    fixture.toolchain.copy(languageVersion = "2.5"),
                    emptyList(),
                    fixture.limits,
                )
            assertIs<ProfileResolution.Failure.ToolchainMismatch>(fixture.resolver.resolveTarget(fixture.lock, incompatible))
            assertEquals(0, fixture.processStarts)

            val diagnostic = fixture.input(null, "fun main() { val π: Missing = 1 }")
            val result = assertIs<ClientBuildResult.Diagnostics>(fixture.service.build(diagnostic).get(90, TimeUnit.SECONDS))
            assertTrue(result.values.isNotEmpty())
            assertEquals(1, fixture.processStarts)
        }

    private fun withClient(block: (Fixture) -> Unit) {
        val payload = WorkerPayloadLoader.loadToolingProfile(Path.of(checkNotNull(System.getProperty("compukters.worker.payload"))))
        val root = createTempDirectory("compukters-forked-client-").toAbsolutePath().normalize()
        val limits = WorkerLimits()
        var starts = 0
        val processFactory = WorkerProcessFactory { launch -> starts++.let { JdkWorkerProcessFactory().start(launch) } }
        val controller =
            CompilerWorkerController(
                payload,
                WorkerLaunch(
                    Path.of(checkNotNull(System.getProperty("compukters.worker.java"))),
                    512,
                    256,
                    root.resolve("worker-temp"),
                    payload.manifest.identity,
                    limits.frameBytes,
                    limits.stderrBytes,
                ),
                limits,
                processFactory,
                CompilerWorkerPolicy(startupTimeoutNanos = 30_000_000_000, compilationTimeoutNanos = 60_000_000_000),
            )
        val backend = CountingBackend(ControllerClientCompilerBackend(controller))
        val cache =
            ClientCompilationCache.open(
                root.resolve("cache"),
                CompilationCachePolicy(),
                ArtifactVerifier { artifact -> artifact.size >= 4 },
            )
        val service = DefaultClientCompilationService(cache, backend)
        val toolchain =
            ToolchainLockIdentity(
                payload.manifest.identity.compilerVersion,
                payload.manifest.identity.languageVersion,
                payload.manifest.identity.codegenAbi,
                1u,
                payload.manifest.identity.artifactWriterVersion,
                payload.manifest.identity.payloadHash,
                payload.manifest.identity.platformAbi,
            )
        val lock = ProjectLock.of(toolchain, emptyList())
        val resolver = CompileProfileResolver(toolchain, emptyPlatformCatalog(toolchain), limits)
        val profile = assertIs<ProfileResolution.Resolved>(resolver.resolveLocal(lock)).profile
        val manifest = ProjectManifest.of("forked-client", emptyMap())
        try {
            block(
                Fixture(
                    service,
                    backend,
                    resolver,
                    toolchain,
                    lock,
                    limits,
                    profile,
                    ProjectManifestCodec.encode(manifest),
                    ProjectLockCodec.encode(lock),
                    { starts },
                ),
            )
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun emptyPlatformCatalog(toolchain: ToolchainLockIdentity): PlatformCatalog =
        PlatformCatalog.of(
            PlatformBundle(
                PlatformIdentity(
                    toolchain.languageVersion,
                    PlatformBundleCodec.SUPPORTED_PLATFORM_ABI,
                    Sha256.of(toolchain.platformAbi.toByteArray()),
                ),
                PlatformModule(
                    PlatformModuleId("compukters", "builtins"),
                    "1.0.0",
                    emptyList(),
                    ImmutableBytes.of(byteArrayOf()),
                    null,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                emptyList(),
            ),
        )

    private class Fixture(
        val service: DefaultClientCompilationService,
        val backend: CountingBackend,
        val resolver: CompileProfileResolver,
        val toolchain: ToolchainLockIdentity,
        val lock: ProjectLock,
        val limits: WorkerLimits,
        private val profile: ru.lazyhat.compukters.ide.compiler.profile.CompileProfile,
        private val manifest: String,
        private val lockText: String,
        private val starts: () -> Int,
    ) {
        val processStarts: Int get() = starts()

        fun input(
            helper: String?,
            main: String,
        ): ClientBuildSnapshot {
            val sources =
                buildList {
                    if (helper != null) {
                        add(ProjectSource(VirtualSourcePath.kotlin("project/Helper.kt"), BinaryValue.of(helper.encodeToByteArray())))
                    }
                    add(ProjectSource(VirtualSourcePath.kotlin("project/Main.kt"), BinaryValue.of(main.encodeToByteArray())))
                }
            return ClientBuildSnapshot(
                ProjectSnapshot.of(sources, limits),
                BinaryValue.of(manifest.encodeToByteArray()),
                BinaryValue.of(lockText.encodeToByteArray()),
                profile,
            )
        }
    }

    private class CountingBackend(
        private val delegate: ClientCompilerBackend,
    ) : ClientCompilerBackend {
        var compileCalls = 0
            private set

        override fun compile(request: CompileRequest): CompletableFuture<CompileResult> {
            compileCalls++
            return delegate.compile(request)
        }

        override fun cancel(future: CompletableFuture<CompileResult>): Boolean = delegate.cancel(future)

        override fun close() = delegate.close()
    }
}
