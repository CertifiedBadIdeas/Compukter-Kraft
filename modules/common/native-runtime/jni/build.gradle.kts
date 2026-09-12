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

import java.util.Locale
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(projects.nativeRuntimeApi)
    implementation(libs.kotlin.stdlib)
    testImplementation(kotlin("test"))
}

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
val compukterJniLibrary = rootProject.file(".toolchain/build/cargo/compukter-jni/release/$nativeFilename")
val generatedDevelopmentNativeResources = layout.buildDirectory.dir("generated/native-resources")
val generatedReleaseNativeResources = layout.buildDirectory.dir("generated/release-native-resources")
val runtimeBundleDirectory = providers.gradleProperty("compukterRuntimeBundleDir").map(rootProject::file)
val releaseRuntimeRequested = requestsUniversalReleaseBuild(gradle.startParameter.taskNames)
val compukterVmRoot = rootProject.file("host/compukter-vm")
val compukterVmCommit =
    providers.exec {
        workingDir(compukterVmRoot)
        commandLine("git", "rev-parse", "HEAD")
    }.standardOutput.asText.map(String::trim)
val runtimeBundleContract = compukterVmCommit.map(::currentRuntimeBundleContract)
val downloadedRuntimeBundleDirectory =
    providers.provider {
        rootProject.gradle.gradleUserHomeDir
            .resolve("caches/compukters/runtime/${runtimeBundleContract.get().runtimeVersion}")
    }
val selectedReleaseRuntimeBundleDirectory = runtimeBundleDirectory.orElse(downloadedRuntimeBundleDirectory)
val shellArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/system/shell.cpkt")

val preparePackagedCompukterJni =
    tasks.register<Sync>("preparePackagedCompukterJni") {
        description = "Copies the current-host Compukter JNI library into its stable classpath resource."
        dependsOn(rootProject.tasks.named("cargoBuildCompukterJni"))
        inputs.property("nativeOs", nativeOs)
        inputs.property("nativeArch", nativeArch)
        inputs.file(compukterJniLibrary)
        into(generatedDevelopmentNativeResources)
        from(compukterJniLibrary) {
            into("META-INF/natives/$nativeOs/$nativeArch")
        }
    }

val preparePackagedReleaseRuntime =
    tasks.register("preparePackagedReleaseRuntime") {
        description = "Validates the dual-transport Runtime bundles and stages their Linux and Windows JNI libraries."
        group = "build"
        dependsOn(rootProject.tasks.named("downloadCompukterRuntimeBundles"))
        inputs.dir(selectedReleaseRuntimeBundleDirectory)
        inputs.property("compukterVmCommit", compukterVmCommit)
        outputs.dir(generatedReleaseNativeResources)
        doLast {
            val output = generatedReleaseNativeResources.get().asFile.toPath()
            delete(output)
            RuntimeBundleSupport.validateAndStage(
                selectedReleaseRuntimeBundleDirectory.get().toPath(),
                output,
                runtimeBundleContract.get(),
                RuntimeTransport.JNI,
            )
        }
    }

val releaseRuntimeMode = runtimeBundleDirectory.isPresent || releaseRuntimeRequested
val selectedNativeResources =
    if (releaseRuntimeMode) generatedReleaseNativeResources else generatedDevelopmentNativeResources
val selectedNativePreparation =
    if (releaseRuntimeMode) preparePackagedReleaseRuntime else preparePackagedCompukterJni

sourceSets.main {
    resources.srcDir(selectedNativeResources)
}

tasks.processResources {
    dependsOn(selectedNativePreparation)
}

tasks.test {
    filter.excludeTestsMatching("ru.lazyhat.compukters.lang.runtime.integration.*")
    filter.excludeTestsMatching("ru.lazyhat.compukters.lang.runtime.vm.JniNativeEntryPointIntegrationTest")
}

val nativeIntegrationTest =
    tasks.register<Test>("nativeIntegrationTest") {
        description = "Runs Java 21 Kotlin-to-JNI-to-Rust Compukter VM integration tests."
        group = "verification"
        dependsOn(rootProject.tasks.named("cargoBuildCompukterJni"), ":compiler-k2:generateShellArtifact")
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.lang.runtime.integration.JniBridgeIntegrationTest")
        filter.includeTestsMatching("ru.lazyhat.compukters.lang.runtime.vm.JniNativeEntryPointIntegrationTest")
        inputs.file(compukterJniLibrary)
        inputs.file(shellArtifact)
        doFirst {
            systemProperty("compukter.jni.library", compukterJniLibrary.absolutePath)
            systemProperty("compukters.shell.artifact", shellArtifact.get().asFile.absolutePath)
        }
    }

val packagedNativeIntegrationTest =
    tasks.register<Test>("packagedNativeIntegrationTest") {
        description = "Extracts the packaged current-host JNI library and executes a VM fixture in a fresh JVM."
        group = "verification"
        dependsOn(selectedNativePreparation, ":compiler-k2:generateShellArtifact")
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.lang.runtime.integration.PackagedJniRuntimeIntegrationTest")
        inputs.file(shellArtifact)
        doFirst {
            systemProperty("compukters.shell.artifact", shellArtifact.get().asFile.absolutePath)
        }
    }

val runtimeJar = tasks.named<Jar>("jar")
val verifyNativeRuntimeJarResource =
    tasks.register("verifyNativeRuntimeJarResource") {
        description = "Checks that the JNI runtime jar contains exactly the selected native resources."
        group = "verification"
        dependsOn(runtimeJar)
        inputs.file(runtimeJar.flatMap { it.archiveFile })
        val expectedNativeResources = expectedNativeResources(releaseRuntimeMode, nativeResourcePath, RuntimeTransport.JNI)
        inputs.property("expectedNativeResources", expectedNativeResources)
        doLast {
            val archive = runtimeJar.get().archiveFile.get().asFile
            val nativeEntries =
                ZipFile(archive).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filterNot { it.isDirectory }
                        .map { it.name }
                        .filter { it.startsWith("META-INF/natives/") }
                        .toList()
                }
            check(nativeEntries.sorted() == expectedNativeResources) {
                "expected $expectedNativeResources in ${archive.name}, found $nativeEntries"
            }
        }
    }

tasks.check {
    dependsOn(nativeIntegrationTest, packagedNativeIntegrationTest, verifyNativeRuntimeJarResource)
}

tasks.register("verifyNativeRuntime") {
    description = "Runs JVM, explicit JNI, packaged JNI, and native resource verification."
    group = "verification"
    dependsOn(tasks.test, nativeIntegrationTest, packagedNativeIntegrationTest, verifyNativeRuntimeJarResource)
}
