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

package ru.lazyhat.compukters.impl.compiler

import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import ru.lazyhat.compukters.compiler.cache.ArtifactVerifier
import ru.lazyhat.compukters.compiler.cache.PersistentCompilationCache
import ru.lazyhat.compukters.compiler.runtime.CompilerServiceConfiguration
import ru.lazyhat.compukters.compiler.runtime.ServerCompilerService
import ru.lazyhat.compukters.compiler.runtime.WorkerCompilerBackend
import ru.lazyhat.compukters.compiler.runtime.worker.PackagedWorkerPayload
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.compiler.ServerComputerCompiler
import ru.lazyhat.compukters.ide.compiler.profile.COMPUKTER_ARTIFACT_ABI
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmArtifactVerifier
import ru.lazyhat.compukters.platform.bundle.PackagedPlatformBundleLoader
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.worker.value.Sha256
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class CompilerServiceRegistry<S : AutoCloseable>(
    private val opener: (Path) -> S,
) {
    private val services = mutableMapOf<Path, S>()

    @Synchronized
    fun service(worldRoot: Path): S {
        val root = worldRoot.toRealPath()
        return services.getOrPut(root) { opener(root) }
    }

    @Synchronized
    fun stop(worldRoot: Path) {
        services.remove(worldRoot.toRealPath())?.close()
    }
}

internal data class CompilerServicePaths(
    val cacheRoot: Path,
    val payloadRoot: Path,
    val temporaryRoot: Path,
) {
    companion object {
        fun at(worldRoot: Path): CompilerServicePaths {
            val root = worldRoot.toRealPath()
            return CompilerServicePaths(
                root.resolve("compukters/compiler-cache").normalize(),
                root.resolve("compukters/compiler-worker").normalize(),
                root.resolve("compukters/compiler-temp").normalize(),
            )
        }
    }
}

internal class NeoForgeCompilerService private constructor(
    val router: CompilerCompletionRouter,
    val targetProfile: TargetCompileProfile,
    private val service: ServerCompilerService,
    private val executor: ExecutorService,
) : AutoCloseable {
    override fun close() {
        var failure: Throwable? = null
        try {
            service.close()
        } catch (error: Throwable) {
            failure = error
        } finally {
            executor.shutdownNow()
        }
        failure?.let { throw it }
    }

    companion object {
        fun open(worldRoot: Path): NeoForgeCompilerService {
            check(Runtime.version().feature() >= 25) { "Compukters compiler worker requires JDK 25" }
            val paths = CompilerServicePaths.at(worldRoot)
            Files.createDirectories(paths.temporaryRoot)
            val packaged =
                checkNotNull(NeoForgeCompilerService::class.java.getResourceAsStream(WORKER_RESOURCE)) {
                    "packaged compiler worker is missing: $WORKER_RESOURCE"
                }.use { archive -> PackagedWorkerPayload.publish(archive, paths.payloadRoot) }
            val compilerIdentity = packaged.manifest.identity
            val platform =
                PackagedPlatformBundleLoader.load(
                    packaged.classpath,
                    compilerIdentity.languageVersion,
                    Sha256.of(compilerIdentity.platformAbi.toByteArray()),
                )
            val limits = WorkerLimits()
            val launch =
                WorkerLaunch(
                    javaExecutable = javaExecutable(),
                    maximumHeapMiB = 256,
                    maximumMetaspaceMiB = 256,
                    temporaryDirectory = paths.temporaryRoot,
                    expectedIdentity = packaged.manifest.identity,
                    maximumFrameBytes = limits.frameBytes,
                    maximumStderrBytes = limits.stderrBytes,
                )
            val controller = CompilerWorkerController(packaged, launch, limits, JdkWorkerProcessFactory())
            val platformModules = platformModules(platform)
            val backend = WorkerCompilerBackend(controller)
            val cache =
                PersistentCompilationCache.open(
                    paths.cacheRoot,
                    verifier = ArtifactVerifier(VmArtifactVerifier::verify),
                )
            val executor =
                Executors.newFixedThreadPool(2) { task ->
                    Thread(task, "compukter-compiler-service").apply { isDaemon = true }
                }
            try {
                val service =
                    ServerCompilerService(
                        cache,
                        backend,
                        CompilerServiceConfiguration(packaged.manifest.identity, limits, platformModules = platformModules),
                        executor = executor,
                    )
                val compiler = ServerComputerCompiler(service, limits)
                return NeoForgeCompilerService(
                    CompilerCompletionRouter(compiler),
                    serverTargetProfile(packaged.manifest.identity, platform, limits),
                    service,
                    executor,
                )
            } catch (error: Throwable) {
                executor.shutdownNow()
                runCatching(backend::close)
                runCatching(cache::close)
                throw error
            }
        }

        private fun javaExecutable(): Path {
            val name = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
            return Path.of(System.getProperty("java.home"), "bin", name).toAbsolutePath().normalize()
        }

        private const val WORKER_RESOURCE = "/tooling/workers/k2-tooling-workers.zip.zst"
    }
}

object NeoForgeCompilerServices {
    private val registry = CompilerServiceRegistry(NeoForgeCompilerService::open)

    fun router(server: MinecraftServer): CompilerCompletionRouter = registry.service(worldRoot(server)).router

    fun targetProfile(server: MinecraftServer): TargetCompileProfile = registry.service(worldRoot(server)).targetProfile

    fun onServerStopping(event: ServerStoppingEvent) {
        registry.stop(worldRoot(event.server))
    }

    private fun worldRoot(server: MinecraftServer): Path = server.getWorldPath(LevelResource.ROOT)
}

internal fun serverTargetProfile(
    identity: WorkerIdentity,
    platform: PlatformBundle,
    limits: WorkerLimits,
): TargetCompileProfile =
    TargetCompileProfile(
        ToolchainLockIdentity(
            compilerVersion = identity.compilerVersion,
            languageVersion = identity.languageVersion,
            codegenAbi = identity.codegenAbi,
            artifactAbi = COMPUKTER_ARTIFACT_ABI,
            artifactWriterVersion = identity.artifactWriterVersion,
            payloadHash = identity.payloadHash,
            platformAbi = identity.platformAbi,
        ),
        modules = PlatformCatalog.of(platform).entries.map { it.identity },
        limits = limits,
    )

private fun platformModules(platform: PlatformBundle): List<TrustedBundleIdentity> =
    PlatformCatalog.of(platform).entries.map { entry ->
        TrustedBundleIdentity.of(entry.identity.id.value, entry.identity.contentHash)
    }
