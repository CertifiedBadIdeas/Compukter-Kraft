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

import net.fabricmc.loom.task.RemapJarTask
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.v1211)
    alias(libs.plugins.neoforge1211Convention)
    alias(libs.plugins.metadataConvention)
    alias(libs.plugins.minecraftSharedSourcesConvention)
    id("minecraft-gametest-convention")
}

loom {
    mods {
        maybeCreate("main").apply {
            sourceSet("main", project(projects.v1211Common.path))
        }
    }
}

dependencies {
    common(project(path = projects.v1211Common.path)) { isTransitive = false }
    shadowBundle(project(path = projects.v1211Common.path, configuration = "transformProductionNeoForge"))
    testImplementation(project(path = projects.v1211Common.path))
    implementation(projects.nativeRuntimeJni)
    shadowBundle(project(path = projects.nativeRuntimeJni.path)) { isTransitive = false }
    implementation(projects.platformBundle)
    shadowBundle(project(path = projects.platformBundle.path)) { isTransitive = false }
}

val productionJar = tasks.named<RemapJarTask>("remapJar")
val expectedMetadata = readVersionedModProperties()
val nativeOs =
    when {
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("linux") -> "linux"
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("windows") -> "windows"
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("mac") -> "macos"
        else -> error("unsupported native build operating system: ${System.getProperty("os.name")}")
    }
val nativeArch =
    when (System.getProperty("os.arch").trim().lowercase(Locale.ROOT)) {
        "amd64", "x86_64" -> "x86_64"
        "arm64", "aarch64" -> "aarch64"
        else -> error("unsupported native build architecture: ${System.getProperty("os.arch")}")
    }
val nativeFilename =
    when (nativeOs) {
        "linux" -> "libcompukter_jni.so"
        "windows" -> "compukter_jni.dll"
        "macos" -> "libcompukter_jni.dylib"
        else -> error("unreachable native build operating system: $nativeOs")
    }
val nativeResourcePath = "META-INF/natives/$nativeOs/$nativeArch/$nativeFilename"
val releaseRuntimeMode =
    providers.gradleProperty("compukterRuntimeBundleDir").isPresent ||
        requestsUniversalReleaseBuild(gradle.startParameter.taskNames)
val expectedPackagedNativeResources = expectedNativeResources(releaseRuntimeMode, nativeResourcePath, RuntimeTransport.JNI)
val verifyProductionJar =
    tasks.register("verifyProductionJar") {
        group = "verification"
        description = "Checks the remapped NeoForge 1.21.1 archive, Java 21 JNI runtime, and system resources."
        dependsOn(productionJar)
        inputs.file(productionJar.flatMap { it.archiveFile })
        doLast {
            val archive = productionJar.get().archiveFile.get().asFile
            val entries =
                ZipFile(archive).use { zip ->
                    zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
                }
            listOf(
                "META-INF/neoforge.mods.toml",
                "ru/lazyhat/compukters/impl/CompuktersMod.class",
                "ru/lazyhat/compukters/impl/ide/IdeClientBootstrap.class",
                "ru/lazyhat/compukters/impl/ide/IdeRenderer.class",
                "ru/lazyhat/compukters/impl/ide/IdeScreen.class",
                "ru/lazyhat/compukters/impl/ide/target/IdeTargetNetwork.class",
                "ru/lazyhat/compukters/ide/client/target/IdeTargetPort.class",
                "system/programs/boot",
                "system/programs/shell",
                "system/programs/kotlinc",
                "system/programs/edit",
                "system/programs/vmbench",
                "tooling/workers/k2-tooling-workers.bundle",
                "tooling/workers/k2-tooling-workers.zip.xz",
                "assets/compukters/blockstates/compukter.json",
                "assets/compukters/models/block/compukter.json",
                "assets/compukters/models/item/compukter.json",
                "assets/compukters/lang/en_us.json",
                "assets/compukters/textures/gui/ide_toolbar.png",
            ).forEach { required ->
                check(entries.count { it == required } == 1) {
                    "$required is missing or duplicated in ${archive.name}"
                }
            }
            check(entries.size == entries.toSet().size) { "duplicate archive entries found in ${archive.name}" }
            val nativeEntries = entries.filter { it.startsWith("META-INF/natives/") }
            validateNativeResources(nativeEntries, expectedPackagedNativeResources)
            check(entries.none { "compukter_ffi" in it || it.endsWith("/FfmRuntimeBackend.class") }) {
                "Java 25 FFM runtime content leaked into ${archive.name}"
            }
            check(
                entries.none { entry ->
                    entry.startsWith("ru/lazyhat/compukters/") && entry.substringAfterLast('/').contains("GameTest")
                } && entries.none { it.startsWith("fixtures/") },
            ) {
                "GameTest classes or fixtures leaked into ${archive.name}"
            }
            val forbiddenIdeClassPrefixes =
                listOf(
                    "com/intellij/",
                    "dev/architectury/",
                    "org/jetbrains/kotlin/analysis/",
                    "org/jetbrains/kotlin/fir/",
                    "org/jetbrains/kotlin/idea/",
                    "org/jetbrains/kotlin/psi/",
                    "ru/lazyhat/compukters/ide/analysis/k2/",
                )
            fun forbiddenIdeClass(name: String): Boolean =
                forbiddenIdeClassPrefixes.any(name::startsWith) || name.contains("kotlin/compiler")

            check(entries.none(::forbiddenIdeClass)) {
                "forbidden IDE/compiler implementation classes leaked into ${archive.name}"
            }
            ZipFile(archive).use { outer ->
                entries
                    .filter { it.startsWith("META-INF/jars/") && it.endsWith(".jar") }
                    .forEach { nestedName ->
                        val nestedEntry = checkNotNull(outer.getEntry(nestedName))
                        ZipInputStream(outer.getInputStream(nestedEntry)).use { nested ->
                            while (true) {
                                val entry = nested.nextEntry ?: break
                                check(!forbiddenIdeClass(entry.name)) {
                                    "forbidden IDE/compiler class ${entry.name} leaked through $nestedName"
                                }
                                nested.closeEntry()
                            }
                        }
                    }
            }
            val metadata = ZipFile(archive).use { it.getInputStream(it.getEntry("META-INF/neoforge.mods.toml")).reader().readText() }
            check("${'$'}{" !in metadata) { "unexpanded metadata placeholder in ${archive.name}" }
            listOf("minecraft_version_range", "neoforge_mod_version_range").forEach { property ->
                check("versionRange=\"${expectedMetadata.getValue(property)}\"" in metadata) {
                    "wrong $property in ${archive.name}"
                }
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyProductionJar)
}

tasks.named("buildProductionUniversalJar") {
    dependsOn(verifyProductionJar)
}

fun captureReleaseGit(vararg arguments: String): String {
    val process =
        ProcessBuilder("git", *arguments)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().readText().trimEnd()
    check(process.waitFor() == 0) { "git ${arguments.toList()} failed: $output" }
    return output
}

val verifyUniversalReleaseState =
    tasks.register("verifyUniversalReleaseState") {
        description = "Requires the exact clean tagged state used to assemble a universal 1.21.1 release."
        group = "verification"
        doLast {
            validateUniversalReleaseState(
                UniversalReleaseState(
                    version = rootProject.version.toString(),
                    runtimeBundlesConfigured = releaseRuntimeMode,
                    headTags =
                        captureReleaseGit("tag", "--points-at", "HEAD")
                            .lineSequence()
                            .filter(String::isNotBlank)
                            .toSet(),
                    worktreeStatus = captureReleaseGit("status", "--porcelain"),
                    submoduleStatus = captureReleaseGit("submodule", "status", "--recursive"),
                ),
            )
        }
    }

tasks.register("buildReleaseUniversalJar") {
    description = "Builds and verifies the clean tagged NeoForge 1.21.1 release with Linux and Windows JNI natives."
    group = "build"
    dependsOn(
        verifyUniversalReleaseState,
        verifyProductionJar,
        ":native-runtime-jni:packagedNativeIntegrationTest",
    )
}
