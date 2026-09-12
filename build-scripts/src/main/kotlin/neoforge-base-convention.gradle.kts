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

import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("kotlin-convention")
    id("architectury-plugin")
    id("com.gradleup.shadow")
}

val libs = libsCatalog()
val needsRemap = !pluginManager.hasPlugin("dev.architectury.loom-no-remap")

setLoaderKind(LoaderKind.NEOFORGE)
version = computeModArchiveVersion()

architectury {
    platformSetupLoomIde()
    neoForge()
}

val common = configurations.create("common")
val shadowBundle = configurations.create("shadowBundle")

configurations {
    compileClasspath { extendsFrom(common) }
    runtimeClasspath { extendsFrom(common) }
}

dependencies {
    add("neoForge", versionLibrary("neoforge"))
    listOf(
        ":native-runtime-api",
        ":core",
        ":compiler-client",
        ":compiler-runtime",
        ":worker-client",
        ":ide-core",
        ":ide-analysis-client",
        ":ide-client",
    ).forEach { projectPath ->
        implementation(project(projectPath))
        shadowBundle(project(projectPath)) { isTransitive = false }
    }
    listOf(
        "kotlin-stdlib",
        "kotlin-logging",
        "kotlinx-coroutines-core",
        "tomlj",
        "antlr4-runtime",
        "checker-qual",
        "xz",
    ).forEach { alias -> neoForgeImplementation(libs.findLibrary(alias).get()) }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

val productionJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations = listOf(shadowBundle)
    archiveClassifier.set(if (needsRemap) "shadow-dev" else "")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

extensions.getByType<LoomGradleExtensionAPI>().nestJars(productionJar, configurations.named("include"))

val finalProductionJar: TaskProvider<out Task> =
    if (needsRemap) {
        tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
            inputFile.set(productionJar.flatMap { it.archiveFile })
            archiveClassifier.set("")
            addNestedDependencies.set(false)
            dependsOn(productionJar)
        }
    } else {
        productionJar
    }

tasks.named("assemble") {
    dependsOn(finalProductionJar)
}

tasks.register("buildProductionUniversalJar") {
    group = "build"
    description = if (needsRemap) "Build the remapped production mod jar." else "Build the unobfuscated production mod jar."
    dependsOn(finalProductionJar)
}

fun <T : ModuleDependency> DependencyHandler.neoForgeImplementation(dependency: Provider<T>) {
    val resolvedDependency = dependency.get()
    val implementationDependency = create(resolvedDependency) as ModuleDependency
    val runtimeDependency = create(resolvedDependency) as ModuleDependency
    val includedDependency = create(resolvedDependency)
    implementation(implementationDependency) { isTransitive = false }
    runtimeDependency.isTransitive = false
    add("forgeRuntimeLibrary", runtimeDependency)
    add("include", includedDependency)
}
