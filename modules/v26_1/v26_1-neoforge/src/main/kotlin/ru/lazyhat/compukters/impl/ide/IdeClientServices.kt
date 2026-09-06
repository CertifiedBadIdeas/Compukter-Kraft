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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.compiler.cache.ArtifactVerifier
import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSemanticSettings
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisRequestCoordinator
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisScheduledTask
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisServiceLifetime
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisTaskScheduler
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerController
import ru.lazyhat.compukters.ide.analysis.controller.DefaultAnalysisRequestCoordinator
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisModule
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisPlatform
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisCoordinator
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisInputLoader
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisRequestFactory
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisSnapshotFactory
import ru.lazyhat.compukters.ide.client.analysis.IdeAttachedSourceCatalog
import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencyTrace
import ru.lazyhat.compukters.ide.client.build.IdeBuildCoordinator
import ru.lazyhat.compukters.ide.client.build.IdeBuildServices
import ru.lazyhat.compukters.ide.client.controller.IdeAnalysisFailureReporter
import ru.lazyhat.compukters.ide.client.controller.IdeClientController
import ru.lazyhat.compukters.ide.client.controller.IdeClientTooling
import ru.lazyhat.compukters.ide.client.controller.IdeControllerClock
import ru.lazyhat.compukters.ide.client.state.BoundedIdeEventQueue
import ru.lazyhat.compukters.ide.client.target.IdeTargetCoordinator
import ru.lazyhat.compukters.ide.client.workspace.DefaultIdeWorkspace
import ru.lazyhat.compukters.ide.compiler.ClientCompilationCache
import ru.lazyhat.compukters.ide.compiler.ControllerClientCompilerBackend
import ru.lazyhat.compukters.ide.compiler.DefaultClientCompilationService
import ru.lazyhat.compukters.ide.compiler.profile.COMPUKTER_ARTIFACT_ABI
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfile
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfileResolver
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.ProfileResolution
import ru.lazyhat.compukters.ide.project.ProjectLockCodec
import ru.lazyhat.compukters.ide.project.ProjectLockService
import ru.lazyhat.compukters.ide.project.ProjectResolution
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.impl.config.CompuktersClientConfig
import ru.lazyhat.compukters.impl.ide.target.IdeTargetClientNetwork
import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalClient
import ru.lazyhat.compukters.lang.runtime.vm.VmArtifactVerifier
import ru.lazyhat.compukters.platform.bundle.PackagedPlatformBundleLoader
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.worker.payload.PackagedToolingBundle
import ru.lazyhat.compukters.worker.process.JdkWorkerProcessFactory
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import ru.lazyhat.compukters.worker.process.WorkerProcessFactory
import ru.lazyhat.compukters.worker.value.Sha256
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory as CompilerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch as CompilerWorkerLaunch

internal data class IdeClientPaths(
    val projects: Path,
    val compilerCache: Path,
    val toolingWorkers: Path,
    val compilerTemporary: Path,
    val analysisTemporary: Path,
    val preferences: Path,
) {
    companion object {
        fun at(gameRoot: Path): IdeClientPaths {
            val root = gameRoot.toAbsolutePath().normalize().resolve("compukters/ide")
            return IdeClientPaths(
                projects = root.resolve("projects"),
                compilerCache = root.resolve("cache/compiler"),
                toolingWorkers = root.resolve("workers/tooling"),
                compilerTemporary = root.resolve("tmp/compiler"),
                analysisTemporary = root.resolve("tmp/analysis"),
                preferences = root.resolve("session.preferences"),
            )
        }
    }
}

internal class IdeClientSession<A : AutoCloseable> internal constructor(
    val application: A,
    private val release: (IdeClientSession<A>) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) release(this)
    }
}

internal class IdeClientServices<A : AutoCloseable>(
    gameRoot: Path,
    private val lifetime: AutoCloseable? = null,
    private val opener: (IdeClientPaths) -> A,
) : AutoCloseable {
    val paths: IdeClientPaths = IdeClientPaths.at(gameRoot)
    private var active: IdeClientSession<A>? = null
    private var closed = false

    @Synchronized
    fun open(): IdeClientSession<A> {
        check(!closed) { "IDE client services are closed" }
        check(active == null) { "an IDE application session is already open" }
        return IdeClientSession(opener(paths), ::release).also { active = it }
    }

    @Synchronized
    private fun release(session: IdeClientSession<A>) {
        if (active !== session) return
        active = null
        session.application.close()
    }

    override fun close() {
        val session =
            synchronized(this) {
                if (closed) return
                closed = true
                active.also { active = null }
            }
        session?.application?.close()
        lifetime?.close()
    }
}

internal class IdeClientApplication(
    val controller: IdeClientController,
    val preferences: IdeClientPreferences,
    val targetTerminal: IdeTargetTerminalClient,
    val visibleLatency: IdeVisibleLatencyTrace,
    private val targetPort: AutoCloseable,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try {
            IdeTargetClientNetwork.release(targetTerminal)
        } catch (error: Throwable) {
            failure = error
        }
        try {
            controller.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            targetPort.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        failure?.let { throw it }
    }
}

internal fun productionIdeClientServices(gameRoot: Path): IdeClientServices<IdeClientApplication> {
    val runtime = ProductionIdeRuntime(IdeClientPaths.at(gameRoot))
    return IdeClientServices(gameRoot, lifetime = runtime, opener = runtime::open)
}

private class ProductionIdeRuntime(
    paths: IdeClientPaths,
) : AutoCloseable {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "compukters-ide-tooling").apply { isDaemon = true }
        }
    private val prepared = CompletableFuture.supplyAsync({ ProductionIdeApplicationFactory.prepare(paths) }, executor)

    fun open(paths: IdeClientPaths): IdeClientApplication =
        ProductionIdeApplicationFactory.open(paths) { workspace ->
            prepared.thenApplyAsync({ prepared ->
                ProductionIdeApplicationFactory.createTooling(paths, workspace, prepared)
            }, executor)
        }

    override fun close() {
        executor.shutdownNow()
    }
}

internal data class IdeAnalysisTiming(
    val presentationDebounceNanos: Long,
    val automaticCompletionDebounceNanos: Long,
    val hoverDebounceNanos: Long,
)

internal object ProductionIdeApplicationFactory {
    val analysisTiming = IdeAnalysisTiming(150_000_000L, 75_000_000L, 400_000_000L)

    data class PreparedWorkers(
        val compilerPayload: ru.lazyhat.compukters.compiler.worker.controller.PublishedWorkerPayload,
        val analysisPayload: ru.lazyhat.compukters.worker.payload.PublishedToolingProfile,
        val platform: PlatformBundle,
        val platformSourceRoot: Path,
        val attachedSources: IdeAttachedSourceCatalog,
        val java: Path,
        val workerLimits: WorkerLimits,
        val analysisLimits: AnalysisLimits,
    )

    fun prepare(paths: IdeClientPaths): PreparedWorkers {
        check(Runtime.version().feature() >= 25) { "Compukters IDE workers require JDK 25" }
        val workerLimits = WorkerLimits()
        val analysisLimits = AnalysisLimits()
        val bundle = resource(TOOLING_WORKER_RESOURCE).use { archive -> PackagedToolingBundle.publish(archive, paths.toolingWorkers) }
        val compilerProfile = bundle.profile("compiler")
        val compilerPayload =
            ru.lazyhat.compukters.compiler.worker.controller.PublishedWorkerPayload(
                compilerProfile.root,
                ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadManifest.fromToolingProfile(
                    compilerProfile.manifest,
                    bundle.manifest.files,
                ),
                compilerProfile.classpath,
            )
        val compilerIdentity = compilerPayload.manifest.identity
        val platform = loadPackagedPlatform(compilerProfile.classpath, compilerIdentity)
        val analysisPayload = bundle.profile("analysis")
        check(analysisPayload.manifest.identityProperties.getValue("compiler") == compilerIdentity.compilerVersion) {
            "analysis worker compiler identity does not match compiler worker"
        }
        check(analysisPayload.manifest.identityProperties.getValue("language") == compilerIdentity.languageVersion) {
            "analysis worker language identity does not match compiler worker"
        }
        val guestApi =
            compilerProfile.classpath.single { path ->
                path.fileName.toString().startsWith("guest-platform-")
            }
        val attachedSources =
            loadAttachedSources(
                (listOf(platform.builtins) + platform.modules).associate { module ->
                    analysisModule(module).identity to module.sources.mapTo(mutableSetOf()) { it.path }
                },
                guestApi,
                analysisLimits,
            )
        val java = javaExecutable()
        return PreparedWorkers(compilerPayload, analysisPayload, platform, guestApi, attachedSources, java, workerLimits, analysisLimits)
    }

    fun createTooling(
        paths: IdeClientPaths,
        workspace: DefaultIdeWorkspace,
        prepared: PreparedWorkers,
        visibleLatency: IdeVisibleLatencyTrace = IdeVisibleLatencyTrace.None,
        analysisProcessFactory: WorkerProcessFactory = JdkWorkerProcessFactory(),
    ): IdeClientTooling {
        val compilerPayload = prepared.compilerPayload
        val analysisPayload = prepared.analysisPayload
        val java = prepared.java
        val workerLimits = prepared.workerLimits
        val analysisLimits = prepared.analysisLimits
        val compilerIdentity = compilerPayload.manifest.identity
        val compilerController =
            CompilerWorkerController(
                compilerPayload,
                compilerLaunch(paths, prepared),
                workerLimits,
                CompilerProcessFactory(),
            )
        val compilation =
            try {
                DefaultClientCompilationService(
                    ClientCompilationCache.open(
                        paths.compilerCache,
                        verifier = ArtifactVerifier(VmArtifactVerifier::verify),
                    ),
                    ControllerClientCompilerBackend(compilerController),
                )
            } catch (error: Throwable) {
                compilerController.close()
                throw error
            }
        val analysisIdentity =
            AnalysisWorkerIdentity(
                compilerIdentity.compilerVersion,
                compilerIdentity.languageVersion,
                Hash256.of(analysisPayload.manifest.payloadHash.toByteArray()),
                compilerIdentity.platformAbi,
            )
        val analysisLaunch = analysisLaunch(paths, prepared)
        val analysisService =
            AnalysisServiceLifetime(TimeUnit.SECONDS.toNanos(ANALYSIS_IDLE_SECONDS)) {
                AnalysisWorkerController(
                    analysisLaunch,
                    analysisIdentity,
                    analysisLimits,
                    analysisProcessFactory,
                )
            }
        try {
            val tooling =
                composeTooling(
                    workspace,
                    compilerIdentity,
                    prepared.platform,
                    workerLimits,
                    analysisLimits,
                    prepared.platformSourceRoot,
                    prepared.attachedSources,
                    compilation,
                    analysisService,
                    visibleLatency,
                )
            return tooling
        } catch (error: Throwable) {
            runCatching(compilation::close)
            runCatching(analysisService::close)
            throw error
        }
    }

    internal fun compilerLaunch(
        paths: IdeClientPaths,
        prepared: PreparedWorkers,
    ): CompilerWorkerLaunch =
        CompilerWorkerLaunch(
            javaExecutable = prepared.java,
            maximumHeapMiB = COMPILER_HEAP_MIB,
            maximumMetaspaceMiB = COMPILER_METASPACE_MIB,
            temporaryDirectory = paths.compilerTemporary,
            expectedIdentity = prepared.compilerPayload.manifest.identity,
            maximumFrameBytes = prepared.workerLimits.frameBytes,
            maximumStderrBytes = prepared.workerLimits.stderrBytes,
        )

    internal fun analysisLaunch(
        paths: IdeClientPaths,
        prepared: PreparedWorkers,
    ): WorkerLaunch =
        WorkerLaunch(
            javaExecutable = prepared.java,
            classpath = prepared.analysisPayload.classpath,
            mainClass = prepared.analysisPayload.manifest.mainClass,
            maximumHeapMiB = ANALYSIS_HEAP_MIB,
            maximumMetaspaceMiB = ANALYSIS_METASPACE_MIB,
            temporaryDirectory = paths.analysisTemporary,
            maximumFrameBytes = prepared.analysisLimits.frameBytes,
            maximumStderrBytes = ANALYSIS_STDERR_BYTES,
        )

    fun open(
        paths: IdeClientPaths,
        visibleLatency: IdeVisibleLatencyTrace = IdeVisibleLatencyTrace.None,
        tooling: (DefaultIdeWorkspace) -> CompletableFuture<IdeClientTooling>,
    ): IdeClientApplication {
        val clientLimits = IdeClientLimits()
        val workspace = DefaultIdeWorkspace(paths.projects, clientLimits = clientLimits)
        val clock = IdeControllerClock.System
        val targetPort = IdeTargetClientNetwork.openPort()
        val targetTerminal = IdeTargetClientNetwork.openTerminal()
        val target = IdeTargetCoordinator(targetPort, clock, clientLimits)
        val preferences = IdeClientPreferences(paths.preferences, CompuktersClientConfig.IdeLayout)
        val controller =
            IdeClientController(
                workspace = workspace,
                preferences = preferences,
                clock = clock,
                events = BoundedIdeEventQueue(clientLimits.eventQueueCapacity),
                limits = clientLimits,
                targetCoordinator = target,
                tooling = tooling(workspace),
                visibleLatency = visibleLatency,
                analysisFailureReporter =
                    IdeAnalysisFailureReporter { failure ->
                        LOGGER.warn {
                            "IDE analysis unavailable for ${failure.path.value} at document revision " +
                                "${failure.documentRevision}: ${failure.detail}"
                        }
                    },
            )
        controller.start()
        return IdeClientApplication(controller, preferences, targetTerminal, visibleLatency, targetPort)
    }

    private fun composeTooling(
        workspace: DefaultIdeWorkspace,
        compilerIdentity: WorkerIdentity,
        platform: PlatformBundle,
        workerLimits: WorkerLimits,
        analysisLimits: AnalysisLimits,
        platformSourceRoot: Path,
        attachedSources: IdeAttachedSourceCatalog,
        compilation: DefaultClientCompilationService,
        analysisService: AnalysisServiceLifetime,
        visibleLatency: IdeVisibleLatencyTrace,
    ): IdeClientTooling {
        val clientLimits = IdeClientLimits()
        val toolchain =
            ToolchainLockIdentity(
                compilerVersion = compilerIdentity.compilerVersion,
                languageVersion = compilerIdentity.languageVersion,
                codegenAbi = compilerIdentity.codegenAbi,
                artifactAbi = COMPUKTER_ARTIFACT_ABI,
                artifactWriterVersion = compilerIdentity.artifactWriterVersion,
                payloadHash = compilerIdentity.payloadHash,
                platformAbi = compilerIdentity.platformAbi,
            )
        val catalog = PlatformCatalog.of(platform)
        val resolution = ProjectResolution(toolchain, catalog)
        val profileResolver = CompileProfileResolver(toolchain, catalog, workerLimits)
        val clock = IdeControllerClock.System
        val build =
            IdeBuildCoordinator(
                IdeBuildServices(
                    localResolution = resolution,
                    profileResolver = profileResolver,
                    lockServices = { project -> ProjectLockService(project.lockFileWriter()) },
                    compilation = compilation,
                ),
                clock,
                clientLimits,
            )
        val analysis =
            IdeAnalysisCoordinator(
                inputLoader = IdeAnalysisInputLoader(workspace::buildInput),
                snapshotFactory =
                    IdeAnalysisSnapshotFactory { input, activePath, activeText ->
                        analysisSnapshot(
                            input,
                            activePath,
                            activeText,
                            profileResolver,
                            analysisLimits,
                            platform,
                            platformSourceRoot,
                        )
                    },
                requestFactory =
                    IdeAnalysisRequestFactory { sink ->
                        val session = analysisService.openSession()
                        val scheduler = IdeAnalysisTaskScheduler()
                        val delegate =
                            DefaultAnalysisRequestCoordinator(
                                session.client,
                                scheduler,
                                analysisTiming.presentationDebounceNanos,
                                analysisTiming.automaticCompletionDebounceNanos,
                                analysisTiming.hoverDebounceNanos,
                                resultSink = sink,
                            )
                        ClosingAnalysisRequestCoordinator(delegate, session, scheduler)
                    },
                limits = clientLimits,
                attachedSources = attachedSources,
                platformCatalog = catalog,
                visibleLatency = visibleLatency,
            )
        return IdeClientTooling(build, analysis, analysisService)
    }

    internal fun loadAttachedSources(
        moduleSources: Map<AnalysisModuleIdentity, Set<String>>,
        sourceRoot: Path,
        limits: AnalysisLimits,
    ): IdeAttachedSourceCatalog {
        require(moduleSources.size <= limits.modules) { "attached source module count exceeds analysis limit" }
        var sourceCount = 0
        var totalBytes = 0L
        require(sourceRoot.isAbsolute && sourceRoot.normalize() == sourceRoot) {
            "platform source root must be absolute and normalized"
        }
        require(!Files.isSymbolicLink(sourceRoot) && Files.isRegularFile(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            "platform source root is missing or is not a regular file"
        }
        val platformSources = linkedMapOf<VirtualSourcePath, String>()
        ZipFile(sourceRoot.toFile()).use { archive ->
            archive.entries().asSequence().filterNot { it.isDirectory }.filter { it.name.endsWith(".kt") }.forEach { entry ->
                require(sourceCount < limits.sourceFiles) { "platform source count exceeds analysis limit" }
                val bytes = archive.getInputStream(entry).use { it.readNBytes(limits.sourceFileBytes + 1) }
                require(bytes.size <= limits.sourceFileBytes) { "platform source file exceeds analysis limit" }
                sourceCount = Math.incrementExact(sourceCount)
                totalBytes = Math.addExact(totalBytes, bytes.size.toLong())
                require(totalBytes <= limits.sourceBytes.toLong()) { "platform source bytes exceed analysis limit" }
                val path = VirtualSourcePath.kotlin(entry.name)
                val text = bytes.decodeToString(throwOnInvalidSequence = true)
                require(platformSources.put(path, text) == null) { "duplicate platform source path: ${entry.name}" }
            }
        }
        val sources =
            moduleSources.mapValues { (_, expectedPaths) ->
                platformSources.filterKeys { path ->
                    expectedPaths.any { expected -> path.value == expected || path.value.endsWith("/$expected") }
                }
            }
        return IdeAttachedSourceCatalog.of(
            sources,
            limits.modules,
            limits.sourceFiles,
            limits.sourceFileBytes,
            limits.sourceBytes,
        )
    }

    private fun analysisSnapshot(
        input: ru.lazyhat.compukters.ide.client.workspace.IdeBuildInput,
        activePath: ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath,
        activeText: String,
        resolver: CompileProfileResolver,
        limits: AnalysisLimits,
        platform: PlatformBundle,
        platformSourceRoot: Path,
    ): AdmittedAnalysisSnapshot {
        val lockBytes = checkNotNull(input.lockBytes) { "resolve compukter.lock before analysis" }
        val lock = ProjectLockCodec.decode(lockBytes.decodeToString())
        val resolved = resolver.resolveLocal(lock)
        val profile =
            (resolved as? ProfileResolution.Resolved)?.profile ?: error("analysis profile does not match local toolchain: $resolved")
        val sources =
            ProjectSnapshot.of(
                input.sources.sources.map { source ->
                    if (source.path == activePath) ProjectSource(activePath, BinaryValue.of(activeText.encodeToByteArray())) else source
                },
                WorkerLimits(
                    sourceFiles = limits.sourceFiles,
                    sourceFileBytes = limits.sourceFileBytes,
                    sourceBytes = limits.sourceBytes,
                    frameBytes = limits.frameBytes,
                ),
            )
        val admittedModules = admittedAnalysisModules(platform, profile.modules.map { module -> module.descriptor })
        val profileIdentity = analysisProfile(profile, ProjectLockCodec.encode(lock).encodeToByteArray(), admittedModules)
        val admittedPlatform =
            AdmittedAnalysisPlatform(profile.toolchain.platformAbi, admittedModules, platformSourceRoot.toString())
        return AdmittedAnalysisSnapshot(
            AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profileIdentity),
            sources,
            AdmittedAnalysisProfile(profileIdentity, admittedPlatform),
            limits,
        )
    }

    private fun analysisProfile(
        profile: CompileProfile,
        lockBytes: ByteArray,
        modules: List<AdmittedAnalysisModule>,
    ): AnalysisProfileIdentity =
        AnalysisProfileIdentity.of(
            profile.toolchain,
            BinaryValue.of(lockBytes),
            modules.map(AdmittedAnalysisModule::identity),
            AnalysisSemanticSettings(profile.toolchain.languageVersion, profile.toolchain.languageVersion, false),
        )

    private fun analysisModule(module: PlatformModule): AdmittedAnalysisModule =
        AdmittedAnalysisModule(
            AnalysisModuleIdentity(
                module.id.toString(),
                Hash256.of(PlatformBundleCodec.moduleContentHash(module).toByteArray()),
            ),
        )

    internal fun admittedAnalysisModules(
        platform: PlatformBundle,
        modules: List<PlatformModule>,
    ): List<AdmittedAnalysisModule> = (listOf(platform.builtins) + modules).sortedBy { it.id }.map(::analysisModule)

    internal fun loadPackagedPlatform(
        classpath: List<Path>,
        compilerIdentity: WorkerIdentity,
    ): PlatformBundle =
        PackagedPlatformBundleLoader.load(
            classpath,
            compilerIdentity.languageVersion,
            Sha256.of(compilerIdentity.platformAbi.toByteArray()),
        )

    internal fun admitPlatform(
        platform: PlatformBundle,
        compilerIdentity: WorkerIdentity,
    ): PlatformBundle =
        PackagedPlatformBundleLoader.admit(
            platform,
            compilerIdentity.languageVersion,
            Sha256.of(compilerIdentity.platformAbi.toByteArray()),
        )

    private fun resource(path: String) =
        checkNotNull(ProductionIdeApplicationFactory::class.java.getResourceAsStream(path)) {
            "packaged IDE worker is missing: $path"
        }

    private fun javaExecutable(): Path {
        val name = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", name).toAbsolutePath().normalize()
    }

    private const val TOOLING_WORKER_RESOURCE = "/tooling/workers/k2-tooling-workers.zip"
    private const val COMPILER_HEAP_MIB = 256
    private const val COMPILER_METASPACE_MIB = 256
    private const val ANALYSIS_HEAP_MIB = 384
    private const val ANALYSIS_METASPACE_MIB = 384
    private const val ANALYSIS_STDERR_BYTES = 64 * 1024
    private const val ANALYSIS_IDLE_SECONDS = 30L
}

private class IdeAnalysisTaskScheduler : AnalysisTaskScheduler {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "compukter-ide-analysis-debounce").apply { isDaemon = true }
        }

    override fun schedule(
        delayNanos: Long,
        action: () -> Unit,
    ): AnalysisScheduledTask {
        val future = executor.schedule(action, delayNanos, TimeUnit.NANOSECONDS)
        return AnalysisScheduledTask { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

private class ClosingAnalysisRequestCoordinator(
    private val delegate: AnalysisRequestCoordinator,
    private val session: ru.lazyhat.compukters.ide.analysis.controller.AnalysisSessionHandle,
    private val scheduler: AnalysisTaskScheduler,
) : AnalysisRequestCoordinator by delegate {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        delegate.close()
        scheduler.close()
        session.close()
    }
}
