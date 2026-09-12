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

package ru.lazyhat.compukters.core.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertTrue

class LegacyImplementationRemovalTest {
    @Test
    fun obsoleteProductContoursAreAbsent() {
        val repoRoot = findRepoRoot()
        val forbiddenPaths =
            listOf(
                "models",
                "fixtures/ui-dsl-consumer",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/ClientHooks.kt",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/Config.kt",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/block",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/device/DeviceEvents.kt",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/device/DeviceProperties.kt",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/device/display",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/device/input",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/RuntimeDevice.kt",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/RetainedDisplaySessionTracker.kt",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/ServerThreadPublicationPump.kt",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/ports",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/device/vm",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/gui",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/input",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/platform",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/ui",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/utils",
                "modules/common/core/src/main/kotlin/ru/lazyhat/compukters/core/workbench",
                "modules/common/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/DeviceRuntime.kt",
                "modules/common/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/DeviceVmModels.kt",
                "modules/common/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/IdeStubs.kt",
                "modules/common/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/ScreenBuffer.kt",
                "modules/minecraft/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/common",
                "modules/minecraft/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/TerminalTranscript.kt",
                "modules/minecraft/v26_1/v26_1-common/src/test/kotlin/ru/lazyhat/compukters/common",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/platform",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalDrawList.kt",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalFramebuffer.kt",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalInputLease.kt",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalTranscriptPayloads.kt",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/META-INF/licenses/Spleen-LICENSE.txt",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/META-INF/services",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/blockstates/workbench.json",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/models/block/workbench.json",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/models/item/serial_terminal.json",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/models/item/terminal.json",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/models/item/workbench.json",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/textures/block/workbench",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/textures/gui/term_font.png",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/textures/item/terminal_back.png",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/textures/item/terminal_front.png",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/textures/item/terminal_side.png",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main/resources/" + "compukter" + "craft.mixins.json",
                "vendor/ui-dsl",
                "host/compukter-ffi",
                "host/compukter-jni",
            )

        val present = forbiddenPaths.filter { hasMaterialContent(repoRoot.resolve(it)) }

        assertTrue(present.isEmpty(), "Obsolete product paths remain: ${present.joinToString()}")
    }

    @Test
    fun activeOwnedFilesExcludeLegacyNames() {
        val repoRoot = findRepoRoot()
        val forbiddenNames =
            listOf(
                "compukter" + "kraft",
                "compukter" + "craft",
                "Compukter" + " Kraft",
                "CC" + ":Tweaked",
                "Computer" + "Craft",
                "Craft" + "OS",
                "dan" + "200",
                "RISC" + "-V",
                "RV" + "32",
                "K" + "16",
                "Kraft" + "OS",
            )
        val searchableExtensions = setOf("kt", "kts", "rs", "md", "toml", "json")

        val offenders =
            Files.walk(repoRoot).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) }
                    .filter { path -> path.fileName.toString().substringAfterLast('.', "") in searchableExtensions }
                    .filter { path ->
                        val relative = repoRoot.relativize(path).invariantSeparatorsPathString
                        !(
                            relative.startsWith(".git/") ||
                                relative.startsWith(".gradle/") ||
                                relative.startsWith(".agents/") ||
                                relative.startsWith("docs/superpowers/") ||
                                relative.startsWith("build/") ||
                                relative.contains("/build/") ||
                                relative.contains("/.gradle/")
                        )
                    }.filter { path ->
                        val text = Files.readString(path)
                        forbiddenNames.any { name -> text.contains(name, ignoreCase = true) }
                    }.map { repoRoot.relativize(it).invariantSeparatorsPathString }
                    .sorted()
                    .toList()
            }

        assertTrue(offenders.isEmpty(), "Files with obsolete project names remain: ${offenders.joinToString()}")
    }

    @Test
    fun activeRuntimeExcludesRemovedTerminalImplementations() {
        val repoRoot = findRepoRoot()
        val sourceRoots =
            listOf(
                "host/compukter-ffi/src",
                "host/compukter-vm/src",
                "modules/common/core/src/main",
                "modules/common/native-runtime/src/main",
                "modules/minecraft/v26_1/v26_1-common/src/main",
                "modules/minecraft/v26_1/v26_1-neoforge/src/main",
            ).map(repoRoot::resolve)
        val forbiddenFragments =
            listOf(
                "Terminal" + "Transcript",
                "Terminal" + "SnapshotPayload",
                "Terminal" + "RefreshPayload",
                "Terminal" + "InputPayload",
                "Program" + "TerminalSink",
                "Terminal" + "InputLease",
                "Terminal" + "Framebuffer",
                "Terminal" + "DrawList",
                "jni" + "::",
                "JNI" + "Env",
                "Java_" + "ru_lazyhat_compukters",
            )

        val offenders =
            sourceRoots
                .filter(Files::isDirectory)
                .flatMap { sourceRoot ->
                    Files.walk(sourceRoot).use { paths ->
                        paths
                            .filter(Files::isRegularFile)
                            .filter { path -> path.fileName.toString().substringAfterLast('.', "") in setOf("kt", "rs") }
                            .filter { path -> forbiddenFragments.any(Files.readString(path)::contains) }
                            .map { path -> repoRoot.relativize(path).invariantSeparatorsPathString }
                            .toList()
                    }
                }.sorted()

        assertTrue(offenders.isEmpty(), "Removed terminal implementations returned: ${offenders.joinToString()}")
    }

    private fun findRepoRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (true) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: error("Could not locate repository root")
        }
    }

    private fun hasMaterialContent(path: Path): Boolean {
        if (Files.isRegularFile(path)) return true
        if (!Files.isDirectory(path)) return false
        return Files.walk(path).use { paths ->
            paths.anyMatch { candidate ->
                if (!Files.isRegularFile(candidate)) return@anyMatch false
                val relative = path.relativize(candidate).invariantSeparatorsPathString
                !(
                    relative.startsWith("build/") ||
                        relative.startsWith("target/") ||
                        relative.startsWith(".gradle/") ||
                        relative.startsWith(".gradle-sandbox/") ||
                        relative.contains("/build/") ||
                        relative.contains("/target/") ||
                        relative.contains("/.gradle/") ||
                        relative.contains("/.gradle-sandbox/")
                )
            }
        }
    }
}
