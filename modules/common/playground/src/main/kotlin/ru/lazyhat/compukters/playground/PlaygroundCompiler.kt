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

package ru.lazyhat.compukters.playground

import ru.lazyhat.compukters.compiler.project.ProjectSnapshotException
import ru.lazyhat.compukters.compiler.project.ProjectSnapshotLoader
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerPolicy
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadLoader
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.ProjectManifestCodec
import ru.lazyhat.compukters.platform.bundle.PackagedPlatformBundleLoader
import ru.lazyhat.compukters.worker.value.Sha256
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

interface PlaygroundCompiler : AutoCloseable {
    fun compile(project: Path): CompileResult
}

class ForkedPlaygroundCompiler(
    payloadRoot: Path,
    javaExecutable: Path,
    private val limits: WorkerLimits = WorkerLimits(),
    policy: CompilerWorkerPolicy =
        CompilerWorkerPolicy(
            startupTimeoutNanos = 30_000_000_000,
            compilationTimeoutNanos = 60_000_000_000,
        ),
) : PlaygroundCompiler {
    private val temporaryRoot = Files.createTempDirectory("compukters-playground-worker-")
    private val controller: CompilerWorkerController
    private val platformCatalog: PlatformCatalog

    init {
        try {
            val payload = WorkerPayloadLoader.loadToolingProfile(payloadRoot)
            val compilerIdentity = payload.manifest.identity
            val platform =
                PackagedPlatformBundleLoader.load(
                    payload.classpath,
                    compilerIdentity.languageVersion,
                    Sha256.of(compilerIdentity.platformAbi.toByteArray()),
                )
            platformCatalog = PlatformCatalog.of(platform)
            val launch =
                WorkerLaunch(
                    javaExecutable = javaExecutable,
                    maximumHeapMiB = 512,
                    maximumMetaspaceMiB = 256,
                    temporaryDirectory = temporaryRoot.resolve("child"),
                    expectedIdentity = compilerIdentity,
                    maximumFrameBytes = limits.frameBytes,
                    maximumStderrBytes = limits.stderrBytes,
                )
            controller = CompilerWorkerController(payload, launch, limits, JdkWorkerProcessFactory(), policy)
        } catch (exception: Exception) {
            temporaryRoot.toFile().deleteRecursively()
            throw exception
        }
    }

    override fun compile(project: Path): CompileResult =
        controller
            .compile(ProjectSnapshotLoader.load(project, limits), platformModules = resolveModules(project))
            .get(CONTROLLER_WAIT_SECONDS, TimeUnit.SECONDS)

    override fun close() {
        try {
            controller.close()
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    private fun resolveModules(project: Path): List<TrustedBundleIdentity> {
        val limits = ProjectLimits()
        val manifestPath = project.resolve(MANIFEST_NAME)
        try {
            if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
                throw ProjectSnapshotException("project manifest is missing")
            }
            if (Files.size(manifestPath) > limits.manifestBytes) {
                throw ProjectSnapshotException("manifest byte count exceeds limit")
            }
            val manifest = ProjectManifestCodec.decode(Files.readString(manifestPath), limits)
            return platformCatalog.resolve(manifest.modules).modules.map { module ->
                TrustedBundleIdentity.of(module.identity.id.value, module.identity.contentHash)
            }
        } catch (exception: ProjectSnapshotException) {
            throw exception
        } catch (exception: Exception) {
            throw ProjectSnapshotException(exception.message ?: "failed to resolve project manifest", exception)
        }
    }

    private companion object {
        const val CONTROLLER_WAIT_SECONDS = 45L
        const val MANIFEST_NAME = "compukter.toml"
    }
}
