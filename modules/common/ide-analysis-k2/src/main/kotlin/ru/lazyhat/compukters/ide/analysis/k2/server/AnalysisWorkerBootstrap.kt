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

package ru.lazyhat.compukters.ide.analysis.k2.server

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.worker.payload.ToolingBundleLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

internal data class AnalysisWorkerBootstrap(
    val identity: AnalysisWorkerIdentity,
    val platform: PlatformBundle,
    val temporaryRoot: Path,
) {
    companion object {
        fun load(): AnalysisWorkerBootstrap {
            val workerJar =
                Path
                    .of(
                        AnalysisWorkerBootstrap::class.java.protectionDomain.codeSource.location
                            .toURI(),
                    ).toAbsolutePath()
                    .normalize()
            require(Files.isRegularFile(workerJar)) { "analysis worker code source is not a jar" }
            val tooling = ToolingBundleLoader.load(toolingRoot(workerJar))
            val payload = tooling.profile("analysis")
            require(payload.manifest.mainClass == MAIN_CLASS) { "analysis worker main class mismatch" }
            val compiler = payload.manifest.identityProperties.getValue("compiler")
            val language = payload.manifest.identityProperties.getValue("language")
            val platform =
                tooling
                    .profile("compiler")
                    .classpath
                    .mapNotNull { path ->
                        if (!path.fileName.toString().startsWith("compiler-k2-")) return@mapNotNull null
                        ZipFile(path.toFile()).use { archive ->
                            val entry = archive.getEntry(PLATFORM_ENTRY) ?: return@use null
                            require(entry.size in 0..MAX_PLATFORM_BYTES) { "packaged Compukters platform exceeds its byte limit" }
                            archive.getInputStream(entry).use { input ->
                                val bytes = input.readNBytes((MAX_PLATFORM_BYTES + 1).toInt())
                                require(bytes.size.toLong() <= MAX_PLATFORM_BYTES) {
                                    "packaged Compukters platform exceeds its byte limit"
                                }
                                PlatformBundleCodec.decode(bytes)
                            }
                        }
                    }.single()
            return AnalysisWorkerBootstrap(
                AnalysisWorkerIdentity(
                    compiler,
                    language,
                    Hash256.of(payload.manifest.payloadHash.toByteArray()),
                    Hash256.of(platform.identity.contentHash.toByteArray()),
                ),
                platform,
                Path
                    .of(System.getProperty("java.io.tmpdir"))
                    .resolve("snapshots")
                    .toAbsolutePath()
                    .normalize(),
            )
        }

        private fun toolingRoot(workerJar: Path): Path {
            var candidate = workerJar.parent
            repeat(4) {
                val current = candidate ?: error("analysis worker jar is outside a tooling bundle")
                if (Files.isRegularFile(current.resolve("tooling.bundle"))) return current
                candidate = current.parent
            }
            error("analysis worker jar is outside a tooling bundle")
        }

        private const val MAIN_CLASS = "ru.lazyhat.compukters.ide.analysis.k2.server.AnalysisWorkerMainKt"
        private const val PLATFORM_ENTRY = "compukters-platform/compukters-platform.cpb"
        private const val MAX_PLATFORM_BYTES = 128L * 1024 * 1024
    }
}
