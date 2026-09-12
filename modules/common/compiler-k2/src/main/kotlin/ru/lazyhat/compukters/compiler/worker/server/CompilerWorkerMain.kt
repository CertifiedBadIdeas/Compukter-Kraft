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

package ru.lazyhat.compukters.compiler.worker.server

import ru.lazyhat.compukters.compiler.worker.k2.K2CompilerAdapter
import ru.lazyhat.compukters.compiler.worker.k2.K2CompilerInputs
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.worker.payload.ToolingBundleLoader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.TimeZone
import kotlin.system.exitProcess

fun main() {
    Locale.setDefault(Locale.ROOT)
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    try {
        val bootstrap = WorkerBootstrap.load()
        val adapter =
            K2CompilerAdapter(
                K2CompilerInputs(
                    temporaryRoot = bootstrap.temporaryRoot,
                    workerJar = bootstrap.workerJar,
                    expectedIdentity = bootstrap.identity,
                ),
            )
        val exit =
            CompilerWorkerServer(
                bootstrap.identity,
                WorkerLimits(),
                BufferedInputStream(System.`in`),
                BufferedOutputStream(System.out),
                adapter::compile,
            ).run()
        if (exit == WorkerServerExit.PROTOCOL_ERROR) exitProcess(3)
    } catch (exception: Exception) {
        System.err.println("compiler worker initialization failed: ${exception.message ?: exception::class.java.simpleName}")
        exitProcess(2)
    }
}

internal data class WorkerBootstrap(
    val identity: WorkerIdentity,
    val workerJar: Path,
    val temporaryRoot: Path,
) {
    companion object {
        fun load(): WorkerBootstrap {
            val codeLocation = CompilerWorkerServer::class.java.protectionDomain.codeSource.location
            val rawWorkerJar = Path.of(codeLocation.toURI())
            val workerJar = rawWorkerJar.toAbsolutePath().normalize()
            require(Files.isRegularFile(workerJar)) { "worker code source is not a jar" }
            val payloadRoot = toolingRoot(workerJar)
            val profile = ToolingBundleLoader.load(payloadRoot).profile("compiler")
            val properties = profile.manifest.identityProperties
            require(properties.getValue("compiler") == COMPILER_VERSION) { "worker compiler version mismatch" }
            require(properties.getValue("language") == LANGUAGE_VERSION) { "worker language version mismatch" }
            require(properties.getValue("codegenAbi") == CODEGEN_ABI.toString()) { "worker codegen ABI mismatch" }
            require(properties.getValue("artifactWriter") == ARTIFACT_WRITER_VERSION.toString()) {
                "worker artifact writer mismatch"
            }
            require(profile.manifest.mainClass == MAIN_CLASS) { "worker main class mismatch" }
            val payloadHash = Hash256.of(profile.manifest.payloadHash.toByteArray())
            val temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).resolve("requests").normalize()
            return WorkerBootstrap(
                WorkerIdentity(
                    COMPILER_VERSION,
                    LANGUAGE_VERSION,
                    CODEGEN_ABI,
                    ARTIFACT_WRITER_VERSION,
                    payloadHash,
                    Hash256.fromHex(properties.getValue("platformAbi")),
                ),
                workerJar,
                temporaryRoot,
            )
        }

        private fun toolingRoot(workerJar: Path): Path {
            var candidate = workerJar.parent
            repeat(4) {
                val current = candidate ?: error("worker jar is outside a tooling bundle")
                if (Files.isRegularFile(current.resolve("tooling.bundle"))) return current
                candidate = current.parent
            }
            error("worker jar is outside a tooling bundle")
        }

        private const val COMPILER_VERSION = "2.4.10"
        private const val LANGUAGE_VERSION = "2.4"
        private const val CODEGEN_ABI = 1u
        private const val ARTIFACT_WRITER_VERSION = 1u
        private const val MAIN_CLASS = "ru.lazyhat.compukters.compiler.worker.server.CompilerWorkerMainKt"
    }
}
