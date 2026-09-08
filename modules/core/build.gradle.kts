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
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(projects.compilerClient)
    implementation(projects.compilerRuntime)
    implementation(projects.nativeRuntime)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.aircompressor)
    testImplementation(projects.platformBundle)
}

val compukterFfiLibrary =
    rootProject.file(".toolchain/build/cargo/compukter-ffi/release/${System.mapLibraryName("compukter_ffi")}")
val programRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/system/shell.cpkt")
val bootRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/system/boot.cpkt")
val kotlincRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/system/kotlinc.cpkt")
val editRuntimeArtifact =
    project(":compiler-k2").layout.buildDirectory.file("generated/system/edit.cpkt")
val compilerWorkerPayload =
    project(":tooling-runtime").layout.buildDirectory.file("distributions/k2-tooling-workers.zip.zst")
val processTerminalChildArtifact =
    rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/process-terminal-child.cpkt")
val processInstallRomExecutableArtifact =
    rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/process-install-rom-executable.cpkt")

tasks.test {
    filter.excludeTestsMatching("ru.lazyhat.compukters.core.device.runtime.program.integration.*")
}

val programRuntimeIntegrationTest =
    tasks.register<Test>("programRuntimeIntegrationTest") {
        description = "Runs a compiler-produced Kotlin artifact through the server runtime host and native VM."
        group = "verification"
        dependsOn(
            ":compiler-k2:generateBootArtifact",
            ":compiler-k2:generateKotlincArtifact",
            ":compiler-k2:generateShellArtifact",
            ":compiler-k2:generateEditArtifact",
            ":tooling-runtime:toolingRuntimeBundle",
            rootProject.tasks.named("cargoBuildCompukterFfi"),
        )
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.core.device.runtime.program.integration.*")
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
        inputs.file(compukterFfiLibrary)
        inputs.file(programRuntimeArtifact)
        inputs.file(bootRuntimeArtifact)
        inputs.file(kotlincRuntimeArtifact)
        inputs.file(editRuntimeArtifact)
        inputs.file(compilerWorkerPayload)
        inputs.file(processTerminalChildArtifact)
        inputs.file(processInstallRomExecutableArtifact)
        doFirst {
            systemProperty("compukters.ffi.library", compukterFfiLibrary.absolutePath)
            systemProperty("compukters.programRuntime.artifact", programRuntimeArtifact.get().asFile.absolutePath)
            systemProperty("compukters.bootRuntime.artifact", bootRuntimeArtifact.get().asFile.absolutePath)
            systemProperty("compukters.kotlincRuntime.artifact", kotlincRuntimeArtifact.get().asFile.absolutePath)
            systemProperty("compukters.editRuntime.artifact", editRuntimeArtifact.get().asFile.absolutePath)
            systemProperty("compukters.compilerWorker.payload", compilerWorkerPayload.get().asFile.absolutePath)
            systemProperty("compukters.processTerminalChild.artifact", processTerminalChildArtifact.asFile.absolutePath)
            systemProperty(
                "compukters.processInstallRomExecutable.artifact",
                processInstallRomExecutableArtifact.asFile.absolutePath,
            )
        }
    }

tasks.check {
    dependsOn(programRuntimeIntegrationTest)
}
