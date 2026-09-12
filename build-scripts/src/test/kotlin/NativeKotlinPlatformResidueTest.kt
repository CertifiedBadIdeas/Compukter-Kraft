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

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

class NativeKotlinPlatformResidueTest {
    @Test
    fun bootstrapIntrinsicRegistryIsAbsent() {
        val repoRoot = findRepoRoot()
        val forbiddenPaths =
            listOf(
                "modules/common/compiler-k2-engine/src/main/kotlin/ru/lazyhat/compukters/compiler/k2/engine/TrustedIntrinsicRegistry.kt",
                "modules/common/compiler-k2-engine/src/test/kotlin/ru/lazyhat/compukters/compiler/k2/engine/TrustedIntrinsicRegistryTest.kt",
            )
        val presentPaths = forbiddenPaths.filter { Files.exists(repoRoot.resolve(it)) }

        val forbiddenFragments =
            listOf(
                "TrustedCallableOrigin",
                "TrustedCallableIdentity",
                "TrustedValueType",
                "TrustedCapabilityIdentity",
                "TrustedIntrinsic.StandardOutput",
                "PINNED_KOTLIN_STDLIB",
                "kotlin-stdlib@2.4.10",
                "compukter.stdio-api@1",
                "compukter.terminal-api@2",
                "compukter.process-api@2",
                "compukter.filesystem-api@1",
                "compukter.compiler-api@1",
                "compukter.redstone-api@1",
                "trustedApiSourceIdentities",
                "trustedStandardLibraryIdentity",
                "trustedApiIdentity(",
            )
        val offenders =
            Files.walk(repoRoot.resolve("modules")).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { path -> path.fileName.toString().endsWith(".kt") }
                    .filter { path -> !repoRoot.relativize(path).invariantSeparatorsPathString.contains("/build/") }
                    .filter { path -> forbiddenFragments.any(Files.readString(path)::contains) }
                    .map { path -> repoRoot.relativize(path).invariantSeparatorsPathString }
                    .sorted()
                    .toList()
            }

        assertTrue(presentPaths.isEmpty(), "Bootstrap intrinsic registry paths remain: ${presentPaths.joinToString()}")
        assertTrue(offenders.isEmpty(), "Bootstrap intrinsic registry references remain: ${offenders.joinToString()}")
    }

    private fun findRepoRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (true) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts")) && Files.isDirectory(current.resolve("modules"))) {
                return current
            }
            current = current.parent ?: error("Could not locate repository root")
        }
    }
}
