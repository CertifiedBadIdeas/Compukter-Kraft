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
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.v1211)
    alias(libs.plugins.neoforge1211Convention)
    alias(libs.plugins.metadataConvention)
}

sourceSets.main {
    kotlin.srcDir(rootProject.file("modules/v26_1/v26_1-neoforge/src/main/kotlin"))
    kotlin.exclude(
        "**/CompuktersMod.kt",
        "**/VmBenchmarkCommands.kt",
        "**/ide/**",
        "**/CompuktersClientConfig.kt",
        "**/CompuktersRegistry.kt",
        "**/TerminalFontProfile.kt",
        "**/TerminalGridRenderer.kt",
        "**/TerminalScreen.kt",
        "**/CompuktersUiViewport.kt",
    )
    resources.srcDir(rootProject.file("modules/v26_1/v26_1-neoforge/src/main/resources"))
}

loom {
    runs {
        named("client") {
            runDir("run/client")
            ideConfigGenerated(true)
        }
        named("server") {
            runDir("run/server")
            ideConfigGenerated(true)
        }
    }
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
                "system/programs/boot",
                "system/programs/shell",
                "system/programs/kotlinc",
                "system/programs/edit",
                "tooling/workers/k2-tooling-workers.bundle",
                "tooling/workers/k2-tooling-workers.zip.xz",
            ).forEach { required -> check(required in entries) { "$required is missing from ${archive.name}" } }
            check(entries.any { it.matches(Regex("META-INF/natives/[^/]+/[^/]+/(lib)?compukter_jni\\.(so|dll|dylib)")) }) {
                "the Java 21 JNI runtime is missing from ${archive.name}"
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
