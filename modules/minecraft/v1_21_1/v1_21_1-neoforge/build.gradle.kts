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
            check(entries.any { it.matches(Regex("META-INF/natives/[^/]+/[^/]+/(lib)?compukter_jni\\.(so|dll|dylib)")) }) {
                "the Java 21 JNI runtime is missing from ${archive.name}"
            }
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
