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

plugins {
    id("kotlin-convention")
    id("dev.architectury.loom-no-remap")
    id("loom-runs-convention")
    id("architectury-plugin")
    id("com.gradleup.shadow")
}

val libs = libsCatalog()

setLoaderKind(LoaderKind.NEOFORGE)

// Loader-leaf archive version = "<mc>-<loader>-<modVersion>".
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

    implementation(project(":native-runtime"))
    shadowBundle(project(path = ":native-runtime")) { isTransitive = false }

    implementation(project(":core"))
    shadowBundle(project(":core")) { isTransitive = false }

    implementation(project(":compiler-client"))
    shadowBundle(project(":compiler-client")) { isTransitive = false }

    implementation(project(":compiler-runtime"))
    shadowBundle(project(":compiler-runtime")) { isTransitive = false }

    implementation(project(":worker-client"))
    shadowBundle(project(":worker-client")) { isTransitive = false }

    implementation(project(":ide-core"))
    shadowBundle(project(":ide-core")) { isTransitive = false }

    implementation(project(":ide-analysis-client"))
    shadowBundle(project(":ide-analysis-client")) { isTransitive = false }

    implementation(project(":ide-client"))
    shadowBundle(project(":ide-client")) { isTransitive = false }

    neoForgeImplementation(libs.findLibrary("kotlin-stdlib").get())
    neoForgeImplementation(libs.findLibrary("kotlin-logging").get())
    neoForgeImplementation(libs.findLibrary("kotlinx-coroutines-core").get())
    neoForgeImplementation(libs.findLibrary("tomlj").get())
    neoForgeImplementation(libs.findLibrary("antlr4-runtime").get())
    neoForgeImplementation(libs.findLibrary("checker-qual").get())
    neoForgeImplementation(libs.findLibrary("xz").get())
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

val productionJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations = listOf(shadowBundle)
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

loom {
    nestJars(productionJar, configurations.named("include"))
}

tasks.named("assemble") {
    dependsOn(productionJar)
}

fun <T : ModuleDependency> DependencyHandler.neoForgeImplementation(dependency: Provider<T>) {
    val resolvedDependency = dependency.get()
    val implementationDependency = create(resolvedDependency) as ModuleDependency
    val runtimeDependency = create(resolvedDependency) as ModuleDependency
    val includedDependency = create(resolvedDependency)

    implementation(implementationDependency) {
        isTransitive = false
    }
    runtimeDependency.isTransitive = false
    add("forgeRuntimeLibrary", runtimeDependency)
    include(includedDependency)
}

fun <T : ModuleDependency> DependencyHandler.neoForgeImplementation(dependency: T) {
    val implementationDependency = create(dependency) as ModuleDependency
    val runtimeDependency = create(dependency) as ModuleDependency
    val includedDependency = create(dependency)

    implementation(implementationDependency) {
        isTransitive = false
    }
    runtimeDependency.isTransitive = false
    add("forgeRuntimeLibrary", runtimeDependency)
    include(includedDependency)
}
