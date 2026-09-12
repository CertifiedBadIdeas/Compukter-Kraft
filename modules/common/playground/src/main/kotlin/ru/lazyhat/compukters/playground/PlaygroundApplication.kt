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

import kotlinx.coroutines.CancellationException
import ru.lazyhat.compukters.compiler.project.ProjectSnapshotException
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.CompilerFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import java.io.PrintStream
import java.nio.file.Files

object PlaygroundExit {
    const val SUCCESS = 0
    const val USAGE = 2
    const val INPUT = 3
    const val COMPILATION = 4
    const val COMPILER_PLATFORM = 5
    const val ARTIFACT = 6
    const val VM_START = 7
    const val GUEST_TRAP = 8
    const val VM_FAULT = 9
    const val HOST_FAILURE = 10
    const val QUOTA = 11
    const val RESOURCE = 12
    const val PLATFORM = 13
}

class PlaygroundApplication(
    private val compilerFactory: () -> PlaygroundCompiler,
    private val executor: PlaygroundExecutor,
    private val stderr: PrintStream,
) {
    suspend fun run(arguments: List<String>): Int {
        val options =
            try {
                PlaygroundOptions.parse(arguments)
            } catch (exception: PlaygroundUsageException) {
                stderr.println("usage: <project-directory> [--emit <artifact.cpkt>] [--debug]")
                stderr.println("error: ${exception.message}")
                return PlaygroundExit.USAGE
            }
        return try {
            compilerFactory().use { compiler -> handleCompilation(compiler.compile(options.project), options) }
        } catch (exception: ProjectSnapshotException) {
            failure(PlaygroundExit.INPUT, "project input: ${exception.message}", exception, options.debug)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            failure(PlaygroundExit.PLATFORM, "launcher: ${exception.message}", exception, options.debug)
        }
    }

    private suspend fun handleCompilation(
        result: ru.lazyhat.compukters.compiler.worker.protocol.CompileResult,
        options: PlaygroundOptions,
    ): Int =
        when (result) {
            is CompileSuccess -> {
                result.warnings.forEach(::renderDiagnostic)
                val artifact = result.artifact.toByteArray()
                options.emit?.let { path ->
                    val absolute = path.toAbsolutePath().normalize()
                    Files.createDirectories(absolute.parent)
                    Files.write(absolute, artifact)
                }
                handleExecution(executor.execute(artifact))
            }

            is CompilerFailure -> {
                result.diagnostics.forEach(::renderDiagnostic)
                PlaygroundExit.COMPILATION
            }

            is PlatformFailure -> {
                stderr.println("compiler ${result.failureClass.name.lowercase().replace('_', '-')}: ${oneLine(result.detail)}")
                PlaygroundExit.COMPILER_PLATFORM
            }
        }

    private fun handleExecution(result: PlaygroundExecution): Int =
        when (result) {
            PlaygroundExecution.Success -> {
                PlaygroundExit.SUCCESS
            }

            PlaygroundExecution.VerificationFailure -> {
                report(PlaygroundExit.ARTIFACT, "artifact verification failed")
            }

            is PlaygroundExecution.AdmissionFailure -> {
                report(PlaygroundExit.VM_START, "VM admission failed: ${result.code}")
            }

            is PlaygroundExecution.StartFailure -> {
                report(PlaygroundExit.VM_START, "VM start failed: ${result.code}")
            }

            is PlaygroundExecution.Trap -> {
                report(PlaygroundExit.GUEST_TRAP, "guest trap: ${result.trap.name.lowercase()}")
            }

            is PlaygroundExecution.Fault -> {
                report(PlaygroundExit.VM_FAULT, "VM fault: ${result.fault.name.lowercase()}")
            }

            is PlaygroundExecution.HostFailure -> {
                report(PlaygroundExit.HOST_FAILURE, "host failure: ${result.kind.name.lowercase()} (${result.code})")
            }

            is PlaygroundExecution.Quota -> {
                report(
                    PlaygroundExit.QUOTA,
                    "quota exhausted: ${result.kind.name.lowercase()} ${result.consumed}/${result.limit}",
                )
            }

            is PlaygroundExecution.ResourceFailure -> {
                report(PlaygroundExit.RESOURCE, "VM allocation failed (collection attempted: ${result.collectionAttempted})")
            }

            is PlaygroundExecution.PlatformFailure -> {
                report(PlaygroundExit.PLATFORM, "platform: ${result.detail}")
            }
        }

    private fun renderDiagnostic(diagnostic: WorkerDiagnostic) {
        val path = diagnostic.path?.value ?: "<project>"
        val span =
            if (diagnostic.startUtf16 != null && diagnostic.endUtf16 != null) {
                "@${diagnostic.startUtf16}..${diagnostic.endUtf16}"
            } else {
                ""
            }
        val code = diagnostic.code?.let { "/$it" } ?: ""
        stderr.println(
            "$path$span: ${diagnostic.severity.name.lowercase()} " +
                "[${diagnostic.category.name.lowercase()}$code] ${oneLine(diagnostic.message)}",
        )
    }

    private fun report(
        code: Int,
        message: String,
    ): Int {
        stderr.println(message)
        return code
    }

    private fun failure(
        code: Int,
        message: String,
        exception: Exception,
        debug: Boolean,
    ): Int {
        stderr.println(message)
        if (debug) exception.printStackTrace(stderr)
        return code
    }

    private fun oneLine(value: String): String = value.replace('\r', ' ').replace('\n', ' ')
}
