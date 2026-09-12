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
}

val gameTest by sourceSets.creating
val commonProject = project(":${name.removeSuffix("-neoforge")}-common")
val redstoneConformanceArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/redstone.cpkt")
val soundConformanceArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/sound.cpkt")

kotlin.target.compilations.named(gameTest.name) {
    associateWith(kotlin.target.compilations.getByName("main"))
}

tasks.named<ProcessResources>(gameTest.processResourcesTaskName) {
    dependsOn(":compiler-k2:generateRedstoneConformanceArtifact")
    dependsOn(":compiler-k2:generateSoundConformanceArtifact")
    from(redstoneConformanceArtifact) { into("fixtures") }
    from(soundConformanceArtifact) { into("fixtures") }
    listOf(
        "filesystem-write.cpkt",
        "filesystem-write-alternate.cpkt",
        "filesystem-compilation-source.cpkt",
        "filesystem-read.cpkt",
        "process-terminal-child.cpkt",
        "process-install-rom-executable.cpkt",
    ).forEach { fixture ->
        from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/$fixture")) {
            into("fixtures")
        }
    }
}

configurations[gameTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[gameTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
gameTest.compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
gameTest.runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output

dependencies {
    add(gameTest.implementationConfigurationName, sourceSets.main.get().output)
    add(gameTest.implementationConfigurationName, commonProject)
}

tasks.named("check") {
    dependsOn(gameTest.classesTaskName)
}

val loom = extensions.getByType<LoomGradleExtensionAPI>()
loom.runs.register("gameTestServer") {
    server()
    forgeTemplate("gameTestServer")
    runDir("run/gameTestServer")
    property("neoforge.enabledGameTestNamespaces", "compukters")
    ideConfigGenerated(true)
    if (buildContext().versionKey == "v261") {
        vmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
    }
    mods {
        maybeCreate("main").apply {
            sourceSet("main")
            sourceSet("main", commonProject.path)
            sourceSet(gameTest.name)
        }
    }
}

tasks.configureEach {
    if (name == "runGameTestServer") {
        dependsOn(gameTest.classesTaskName)
    }
}

val verifyGameTestRunIsolation =
    tasks.register("verifyGameTestRunIsolation") {
        group = "verification"
        description = "Checks that GameTest classes are visible only to the GameTest run."
        doLast {
            val gameTestFiles = gameTest.output.files.map(File::getCanonicalFile).toSet()

            fun effectiveModFiles(runName: String): Set<File> {
                val run = loom.runs.named(runName).get()
                val mods = if (run.mods.isEmpty()) loom.mods else run.mods
                return mods.flatMap { it.modFiles.files }.map(File::getCanonicalFile).toSet()
            }

            listOf("client", "client2", "client3", "server").forEach { runName ->
                val leaked = effectiveModFiles(runName).intersect(gameTestFiles)
                check(leaked.isEmpty()) { "GameTest output leaked into $runName: $leaked" }
            }
            check(effectiveModFiles("gameTestServer").intersect(gameTestFiles).isNotEmpty()) {
                "GameTest output is missing from gameTestServer"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyGameTestRunIsolation)
}
