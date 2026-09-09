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

package ru.lazyhat.compukters.core.device.runtime.program.integration

import ru.lazyhat.compukters.compiler.cache.ArtifactVerifier
import ru.lazyhat.compukters.compiler.cache.PersistentCompilationCache
import ru.lazyhat.compukters.compiler.runtime.CompilerServiceConfiguration
import ru.lazyhat.compukters.compiler.runtime.ServerCompilerService
import ru.lazyhat.compukters.compiler.runtime.WorkerCompilerBackend
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadLoader
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.core.device.computer.ProgramComputer
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.compiler.ServerComputerCompiler
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmArtifactVerifier
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import ru.lazyhat.compukters.platform.bundle.PackagedPlatformBundleLoader
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.worker.payload.PackagedToolingBundle
import ru.lazyhat.compukters.worker.value.Sha256
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgramRuntimeHostIntegrationTest {
    @Test
    fun `headless benchmark accepts rounds through terminal input and halts with deterministic checksum`() {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val artifact = Path.of(requiredProperty("compukters.vmbenchAgentRuntime.artifact")).readBytes()
        assertTrue(VmArtifactVerifier.verify(artifact))

        ProgramRuntimeHost().use { host ->
            assertEquals(ProgramStartResult.Started, host.start(artifact))
            advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
            assertTrue(host.sendTerminalText("2"))
            advanceUntil(host) { it is ProgramRuntimeState.Halted }
            assertEquals(
                "vmbench agent: rounds=2\nvmbench agent: checksum=-365826314\n",
                terminalText(requireNotNull(host.terminalFullState())),
            )
        }
    }

    @Test
    fun `ROM boot runs a foreground child and reboot creates a fresh shell`() {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val boot = Path.of(requiredProperty("compukters.bootRuntime.artifact")).readBytes()
        val shell = Path.of(requiredProperty("compukters.programRuntime.artifact")).readBytes()
        val kotlinc = Path.of(requiredProperty("compukters.kotlincRuntime.artifact")).readBytes()
        val edit = Path.of(requiredProperty("compukters.editRuntime.artifact")).readBytes()
        val child = Path.of(requiredProperty("compukters.processTerminalChild.artifact")).readBytes()
        val installer = Path.of(requiredProperty("compukters.processInstallRomExecutable.artifact")).readBytes()
        assertTrue(VmArtifactVerifier.verify(edit))
        val rom = rom(boot, shell, kotlinc, edit, child)
        val root = Files.createTempDirectory("compukters-foreground-process-").toRealPath()
        try {
            TestCompilerService
                .open(
                    root.resolve("compiler"),
                    Path.of(requiredProperty("compukters.compilerWorker.manifest")),
                    Path.of(requiredProperty("compukters.compilerWorker.payload")),
                ).use { compiler ->
                    WorldFileSystemStore.open(root).use { store ->
                        val computerId = ComputerId.fromLongs(1, 2)
                        val generation =
                            VmSession.openInStore(installer, store, computerId, rom).use { session ->
                                assertEquals(VmOutcome.Halted(VmValue.I32(0)), advanceUntilHalted(session))
                                session.filesystemGeneration()
                            }
                        store.flush(computerId, generation)
                        val computer =
                            ProgramComputer(
                                deviceId = 1,
                                stateSink = { _, _ -> },
                                store = store,
                                computerId = computerId,
                                romImage = rom,
                                tickBudget = ProgramTickBudget(64, 64, 4),
                                compilerRouter = compiler.router,
                            )
                        computer.use {
                            assertEquals(ProgramComputerState.Running, computer.turnOn())
                            advanceUntil(computer) { it == ProgramComputerState.WaitingForInput }
                            assertEquals(">\n", terminalText(requireNotNull(computer.terminalFullState())))

                            submit(computer, "hello")
                            pressEnter(computer)
                            assertTrue(
                                terminalText(requireNotNull(computer.terminalFullState()))
                                    .endsWith("> hello\nnested child ran\n>\n"),
                            )

                            submit(computer, "hello raw tail")
                            pressEnter(computer)
                            val argumentFailure = terminalText(requireNotNull(computer.terminalFullState()))
                            assertTrue(
                                argumentFailure.endsWith(
                                    "> hello raw tail\nprogram entry point does not accept arguments\n>\n",
                                ),
                                argumentFailure,
                            )

                            submit(computer, "kotlinc")
                            pressEnter(computer)
                            assertTrue(
                                terminalText(requireNotNull(computer.terminalFullState()))
                                    .endsWith("> kotlinc\nusage: kotlinc <source.kt> [-o output]\n>\n"),
                            )

                            submit(computer, "missing")
                            pressEnter(computer)
                            val missingOutput = terminalText(requireNotNull(computer.terminalFullState()))
                            assertTrue(
                                missingOutput.endsWith("> missing\nnot found: /rom/missing\n>\n"),
                                missingOutput,
                            )

                            submit(computer, "edit demo.kt")
                            pressEnter(computer)
                            val editorScreen = terminalText(requireNotNull(computer.terminalFullState()))
                            assertTrue(editorScreen.startsWith("Compukters edit"), editorScreen)
                            submit(
                                computer,
                                "import compukter.terminal.Terminal\nfun main() { Terminal.write(\"compiled editor loop\\n\") }",
                            )
                            press(computer, TerminalKey.S, setOf(TerminalModifier.CONTROL))
                            press(computer, TerminalKey.X, setOf(TerminalModifier.CONTROL))
                            submit(computer, "clear")
                            pressEnter(computer)
                            submit(computer, "stat demo.kt")
                            pressEnter(computer)
                            assertTrue(terminalText(requireNotNull(computer.terminalFullState())).contains("file: /home/demo.kt"))
                            submit(computer, "kotlinc demo.kt")
                            pressEnter(computer)
                            submit(computer, "demo")
                            pressEnter(computer)
                            val compiledOutput = terminalText(requireNotNull(computer.terminalFullState()))
                            assertTrue(compiledOutput.contains("compiled editor loop"), compiledOutput)

                            assertEquals(ProgramComputerState.Running, computer.reboot())
                            advanceUntil(computer) { it == ProgramComputerState.WaitingForInput }
                            assertEquals(">\n", terminalText(requireNotNull(computer.terminalFullState())))

                            submit(computer, "hello")
                            pressEnter(computer)
                            assertTrue(
                                terminalText(requireNotNull(computer.terminalFullState()))
                                    .endsWith("> hello\nnested child ran\n>\n"),
                            )
                        }
                    }
                }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `compiled Kotlin shell edits Unicode and dispatches built-ins`() {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val artifact = Path.of(requiredProperty("compukters.programRuntime.artifact")).readBytes()
        val host = ProgramRuntimeHost(ProgramTickBudget(64, 64, 4))

        assertEquals(ProgramStartResult.Started, host.start(artifact))
        advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
        assertEquals(">\n", terminalText(requireNotNull(host.terminalFullState())))

        submit(host, "help")
        pressEnter(host)
        assertEquals(
            "> help\nhelp echo clear pwd ls stat kotlinc edit vmbench\n>\n",
            terminalText(requireNotNull(host.terminalFullState())),
        )

        submit(host, "pwd")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> pwd\n/home\n>\n"))

        submit(host, "stat /home")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> stat /home\ndirectory: /home\n>\n"))

        submit(host, "stat missing")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> stat missing\nnot found: /home/missing\n>\n"))

        submit(host, "ls")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> ls\n>\n"))

        submit(host, "ls a b")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> ls a b\nusage: ls [path]\n>\n"))

        submit(host, "stat")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> stat\nusage: stat <path>\n>\n"))

        submit(host, "missing")
        pressEnter(host)
        val missingOutput = terminalText(requireNotNull(host.terminalFullState()))
        assertTrue(missingOutput.endsWith("> missing\naccess denied: /rom/missing\n>\n"), missingOutput)

        submit(host, "echo λ😀")
        press(host, TerminalKey.BACKSPACE)
        submit(host, "😀")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> echo λ😀\nλ😀\n>\n"))

        submit(host, "wat argument")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("access denied: /rom/wat\n>\n"))

        submit(host, "clear")
        pressEnter(host)
        submit(host, "a".repeat(4095))
        submit(host, "😀")
        val bounded = requireNotNull(host.terminalFullState())
        assertTrue(bounded.cells.any { it.codePoint == 'a'.code })
        assertTrue(bounded.cells.none { it.codePoint == 0x1f600 })
        pressEnter(host)

        submit(host, "clear\u0000\r\n")
        val sanitized = terminalText(requireNotNull(host.terminalFullState()))
        assertTrue(sanitized.endsWith("> clear\n"), sanitized)
        pressEnter(host)
        assertEquals(">\n", terminalText(requireNotNull(host.terminalFullState())))
        assertEquals(ProgramRuntimeState.WaitingForInput, host.state)
        host.close()
    }

    private fun submit(
        host: ProgramRuntimeHost,
        text: String,
    ) {
        assertTrue(host.sendTerminalText(text))
        advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
    }

    private fun submit(
        computer: ProgramComputer,
        text: String,
    ) {
        assertTrue(computer.sendTerminalText(text))
        advanceUntil(computer) { it == ProgramComputerState.WaitingForInput }
    }

    private fun pressEnter(host: ProgramRuntimeHost) = press(host, TerminalKey.ENTER)

    private fun pressEnter(computer: ProgramComputer) {
        assertTrue(computer.sendTerminalKey(TerminalKey.ENTER, TerminalKeyAction.PRESS))
        advanceUntil(computer) { it == ProgramComputerState.WaitingForInput }
    }

    private fun press(
        computer: ProgramComputer,
        key: TerminalKey,
        modifiers: Set<TerminalModifier>,
    ) {
        assertTrue(computer.sendTerminalKey(key, TerminalKeyAction.PRESS, modifiers))
        advanceUntil(computer) { it == ProgramComputerState.WaitingForInput }
    }

    private fun press(
        host: ProgramRuntimeHost,
        key: TerminalKey,
    ) {
        assertTrue(host.sendTerminalKey(key, TerminalKeyAction.PRESS))
        advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
    }

    private fun advanceUntil(
        host: ProgramRuntimeHost,
        predicate: (ProgramRuntimeState) -> Boolean,
    ): ProgramRuntimeState {
        repeat(MAXIMUM_TICKS) {
            val state = host.serverTick()
            if (predicate(state)) return state
            check(state == ProgramRuntimeState.Running) { "program terminated before expected state: $state" }
        }
        error("program did not reach expected state within $MAXIMUM_TICKS ticks; last state was ${host.state}")
    }

    private fun advanceUntil(
        computer: ProgramComputer,
        predicate: (ProgramComputerState) -> Boolean,
    ): ProgramComputerState {
        repeat(MAXIMUM_TICKS) {
            val state = computer.serverTick()
            if (predicate(state)) return state
            check(state == ProgramComputerState.Running || state == ProgramComputerState.WaitingForCompiler) {
                "computer terminated before expected state: $state; terminal=${computer.terminalFullState()?.let(::terminalText)}"
            }
            if (state == ProgramComputerState.WaitingForCompiler) Thread.sleep(1)
        }
        error("computer did not reach expected state within $MAXIMUM_TICKS ticks; last state was ${computer.state}")
    }

    private fun advanceUntilHalted(session: VmSession): VmOutcome.Halted {
        repeat(MAXIMUM_TICKS) {
            when (val outcome = session.advance(64, 64, Int.MAX_VALUE)) {
                VmOutcome.SliceExhausted -> Unit
                is VmOutcome.Halted -> return outcome
                else -> error("installer terminated unexpectedly: $outcome")
            }
        }
        error("installer did not halt within $MAXIMUM_TICKS slices")
    }

    private fun rom(
        boot: ByteArray,
        shell: ByteArray,
        kotlinc: ByteArray,
        edit: ByteArray,
        child: ByteArray,
    ): ByteArray {
        val programs =
            listOf(
                "/rom/boot" to boot,
                "/rom/edit" to edit,
                "/rom/hello" to child,
                "/rom/kotlinc" to kotlinc,
                "/rom/shell" to shell,
            )
        val size =
            programs.fold(16) { total, (path, artifact) ->
                Math.addExact(total, 16 + path.encodeToByteArray().size + artifact.size)
            }
        val payload =
            ByteBuffer
                .allocate(size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTROM\u0000".encodeToByteArray())
                .putShort(1.toShort())
                .putShort(0.toShort())
                .putInt(programs.size)
                .also { buffer ->
                    programs.forEach { (pathText, artifact) ->
                        val path = pathText.encodeToByteArray()
                        buffer
                            .putInt(path.size)
                            .put(path)
                            .put(2.toByte())
                            .put(1.toByte())
                            .putShort(0.toShort())
                            .putLong(artifact.size.toLong())
                            .put(artifact)
                    }
                }.array()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing test system property $name" }

    private fun terminalText(state: TerminalState): String {
        val text = StringBuilder()
        repeat(state.height) { y ->
            val row = StringBuilder()
            repeat(state.width) { x -> row.appendCodePoint(state.cells[y * state.width + x].codePoint) }
            text.append(row.toString().trimEnd()).append('\n')
        }
        return text.toString().trimEnd('\n') + '\n'
    }

    private class TestCompilerService private constructor(
        val router: CompilerCompletionRouter,
        private val service: ServerCompilerService,
        private val executor: ExecutorService,
    ) : AutoCloseable {
        override fun close() {
            try {
                service.close()
            } finally {
                executor.shutdownNow()
            }
        }

        companion object {
            fun open(
                root: Path,
                bundleManifest: Path,
                archive: Path,
            ): TestCompilerService {
                val bundle =
                    Files.newInputStream(bundleManifest).use { manifest ->
                        Files.newInputStream(archive).use { input ->
                            PackagedToolingBundle.publish(manifest, input, root.resolve("payload"))
                        }
                    }
                val payload = WorkerPayloadLoader.loadToolingProfile(bundle.root)
                val limits = WorkerLimits()
                val temporary = root.resolve("temporary")
                Files.createDirectories(temporary)
                val launch =
                    WorkerLaunch(
                        javaExecutable = javaExecutable(),
                        maximumHeapMiB = 256,
                        maximumMetaspaceMiB = 256,
                        temporaryDirectory = temporary,
                        expectedIdentity = payload.manifest.identity,
                        maximumFrameBytes = limits.frameBytes,
                        maximumStderrBytes = limits.stderrBytes,
                    )
                val backend =
                    WorkerCompilerBackend(
                        CompilerWorkerController(payload, launch, limits, JdkWorkerProcessFactory()),
                    )
                val compilerIdentity = payload.manifest.identity
                val platform =
                    PackagedPlatformBundleLoader.load(
                        payload.classpath,
                        compilerIdentity.languageVersion,
                        Sha256.of(compilerIdentity.platformAbi.toByteArray()),
                    )
                val platformModules =
                    platform.modules.map { module ->
                        TrustedBundleIdentity.of(
                            module.id.toString(),
                            Hash256.of(PlatformBundleCodec.moduleContentHash(module).toByteArray()),
                        )
                    }
                val cache =
                    PersistentCompilationCache.open(
                        root.resolve("cache"),
                        verifier = ArtifactVerifier(VmArtifactVerifier::verify),
                    )
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val service =
                        ServerCompilerService(
                            cache,
                            backend,
                            CompilerServiceConfiguration(compilerIdentity, limits, platformModules = platformModules),
                            executor = executor,
                        )
                    return TestCompilerService(
                        CompilerCompletionRouter(ServerComputerCompiler(service, limits)),
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
                val executable = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
                return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().normalize()
            }
        }
    }

    private companion object {
        const val MAXIMUM_TICKS = 10_000
    }
}
