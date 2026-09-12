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

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(projects.nativeRuntimeApi)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.test {
    filter.excludeTestsMatching("ru.lazyhat.compukters.lang.runtime.integration.*")
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
        "linux" -> "libcompukter_ffi.so"
        "windows" -> "compukter_ffi.dll"
        "macos" -> "libcompukter_ffi.dylib"
        else -> error("unreachable native build operating system: $nativeOs")
    }
val nativeResourcePath = "META-INF/natives/$nativeOs/$nativeArch/$nativeFilename"
val compukterFfiLibrary = rootProject.file(".toolchain/build/cargo/compukter-ffi/release/$nativeFilename")
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
val blockingCallArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/blocking-call.cpkt")

val preparePackagedCompukterFfi =
    tasks.register<Sync>("preparePackagedCompukterFfi") {
        description = "Copies the current-host Compukter FFM library into its stable classpath resource."
        dependsOn(rootProject.tasks.named("cargoBuildCompukterFfi"), ":compiler-k2:generateShellArtifact")
        inputs.property("nativeOs", nativeOs)
        inputs.property("nativeArch", nativeArch)
        inputs.file(compukterFfiLibrary)
        into(generatedDevelopmentNativeResources)
        from(compukterFfiLibrary) {
            into("META-INF/natives/$nativeOs/$nativeArch")
        }
    }

val downloadCompukterRuntimeBundles =
    tasks.register("downloadCompukterRuntimeBundles") {
        description = "Downloads the pinned Linux and Windows Runtime release assets into the Gradle cache."
        group = "build"
        inputs.property("runtimeVersion", runtimeBundleContract.map(RuntimeBundleContract::runtimeVersion))
        inputs.property("runtimeReleaseTag", runtimeBundleContract.map(RuntimeBundleContract::releaseTag))
        outputs.dir(downloadedRuntimeBundleDirectory)
        onlyIf { !runtimeBundleDirectory.isPresent }
        doLast {
            val contract = runtimeBundleContract.get()
            val result =
                RuntimeBundleDownloadSupport.download(
                    downloadedRuntimeBundleDirectory.get().toPath(),
                    contract,
                )
            println("Runtime ${contract.runtimeVersion}: ${result.name.lowercase()} in ${downloadedRuntimeBundleDirectory.get()}")
        }
    }

val preparePackagedReleaseRuntime =
    tasks.register("preparePackagedReleaseRuntime") {
        description = "Validates and stages the pinned Linux and Windows Runtime release bundles."
        group = "build"
        dependsOn(downloadCompukterRuntimeBundles)
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
            )
        }
    }

val releaseRuntimeMode = runtimeBundleDirectory.isPresent || releaseRuntimeRequested
val selectedNativeResources =
    if (releaseRuntimeMode) generatedReleaseNativeResources else generatedDevelopmentNativeResources
val selectedNativePreparation =
    if (releaseRuntimeMode) preparePackagedReleaseRuntime else preparePackagedCompukterFfi

sourceSets.main {
    resources.srcDir(selectedNativeResources)
}

tasks.processResources {
    dependsOn(selectedNativePreparation)
}

val nativeIntegrationTest =
    tasks.register<Test>("nativeIntegrationTest") {
        description = "Runs Kotlin-to-FFM-to-Rust Compukter VM integration tests."
        group = "verification"
        dependsOn(
            rootProject.tasks.named("cargoBuildCompukterFfi"),
            ":compiler-k2:generateShellArtifact",
            ":compiler-k2:generateBlockingCallConformanceArtifact",
        )
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.lang.runtime.integration.*")
        inputs.file(compukterFfiLibrary)
        inputs.file(shellArtifact)
        inputs.file(blockingCallArtifact)
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
        doFirst {
            systemProperty("compukter.ffi.library", compukterFfiLibrary.absolutePath)
            systemProperty("compukters.shell.artifact", shellArtifact.get().asFile.absolutePath)
            systemProperty("compukters.blocking-call.artifact", blockingCallArtifact.get().asFile.absolutePath)
        }
    }

val packagedNativeIntegrationTest =
    tasks.register<Test>("packagedNativeIntegrationTest") {
        description = "Extracts the packaged current-host FFM library and runs the terminal fixture in a fresh JVM."
        group = "verification"
        dependsOn(selectedNativePreparation, ":compiler-k2:generateShellArtifact")
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.lang.runtime.integration.PackagedFfmRuntimeIntegrationTest")
        inputs.file(shellArtifact)
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
        doFirst {
            systemProperty("compukters.shell.artifact", shellArtifact.get().asFile.absolutePath)
        }
    }

val runtimeJar = tasks.named<Jar>("jar")
val verifyNativeRuntimeJarResource =
    tasks.register("verifyNativeRuntimeJarResource") {
        description = "Checks that native-runtime.jar contains exactly the selected FFM resources."
        group = "verification"
        dependsOn(runtimeJar)
        inputs.file(runtimeJar.flatMap { it.archiveFile })
        val expectedNativeResources =
            if (releaseRuntimeMode) {
                listOf(
                    "META-INF/natives/linux/x86_64/libcompukter_ffi.so",
                    "META-INF/natives/windows/x86_64/compukter_ffi.dll",
                )
            } else {
                listOf(nativeResourcePath)
            }
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
    description = "Runs JVM, explicit FFM, packaged FFM, and native resource verification."
    group = "verification"
    dependsOn(tasks.test, nativeIntegrationTest, packagedNativeIntegrationTest, verifyNativeRuntimeJarResource)
}
