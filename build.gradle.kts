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
    base
    id("media-license-convention")
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.releaseConvention)
}

val compukterVmBuildJobs =
    providers
        .gradleProperty("compukterVmBuildJobs")
        .orElse(Runtime.getRuntime().availableProcessors().toString())
        .get()
val compukterVmTargetRoot = rootProject.file(".toolchain/build/cargo/compukter-vm")
val compukterVmRoot = rootProject.file("host/compukter-vm")
val compukterFfiRoot = compukterVmRoot.resolve("ffi")
val compukterFfiTargetRoot = rootProject.file(".toolchain/build/cargo/compukter-ffi")
val compukterFfiLibrary = compukterFfiTargetRoot.resolve("release/${System.mapLibraryName("compukter_ffi")}")
val compukterVmManifest = compukterVmRoot.resolve("Cargo.toml")
val compukterVmLock = compukterVmRoot.resolve("Cargo.lock")

registerCompukterVmReleaseTasks(compukterVmRoot)

val cleanWorkspace =
    tasks.register("cleanWorkspace") {
        description = "Deletes repo-local build and target outputs while preserving .toolchain."
        group = "build"

        doLast {
            workspaceCleanTargets(rootProject.projectDir.toPath()).forEach { target ->
                if (target.toFile().exists()) {
                    delete(target.toFile())
                }
            }
        }
    }

tasks.named("clean") {
    dependsOn(cleanWorkspace)
}

val testCompukterVmRust =
    tasks.register<Exec>("testCompukterVmRust") {
        description = "Runs Compukter-VM submodule Rust tests."
        group = "verification"
        val vmManifest = compukterVmRoot.resolve("Cargo.toml")
        workingDir(compukterVmRoot)
        inputs.file(vmManifest)
        inputs.file(compukterVmRoot.resolve("Cargo.lock"))
        inputs.dir(compukterVmRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("tests"))
        inputs.property("compukterVmBuildJobs", compukterVmBuildJobs)
        doFirst {
            check(vmManifest.isFile) {
                "Compukter-VM submodule is not initialized; run: git submodule update --init --recursive"
            }
        }
        commandLine("cargo", "test", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterVmTargetRoot.absolutePath)
    }

val testCompukterFfiRust =
    tasks.register<Exec>("testCompukterFfiRust") {
        description = "Runs Compukter FFM adapter Rust tests."
        group = "verification"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.dir(compukterFfiRoot.resolve("tests"))
        inputs.dir(compukterVmRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("tests"))
        commandLine("cargo", "test", "-p", "compukter-ffi", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterFfiTargetRoot.absolutePath)
    }

val testCompukterFfiRustRelease =
    tasks.register<Exec>("testCompukterFfiRustRelease") {
        description = "Runs optimized Compukter FFM adapter Rust tests."
        group = "verification"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.dir(compukterFfiRoot.resolve("tests"))
        inputs.dir(compukterVmRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("tests"))
        commandLine("cargo", "test", "-p", "compukter-ffi", "--release", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterFfiTargetRoot.absolutePath)
    }

val fmtCompukterFfiRust =
    tasks.register<Exec>("fmtCompukterFfiRust") {
        description = "Checks Rust formatting for the Compukter FFM adapter."
        group = "verification"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        commandLine("cargo", "fmt", "--package", "compukter-ffi", "--", "--check")
    }

val clippyCompukterFfiRust =
    tasks.register<Exec>("clippyCompukterFfiRust") {
        description = "Runs warning-free Clippy checks for the Compukter FFM adapter."
        group = "verification"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.dir(compukterFfiRoot.resolve("tests"))
        inputs.dir(compukterVmRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("tests"))
        commandLine("cargo", "clippy", "-p", "compukter-ffi", "--locked", "--offline", "--all-targets", "--", "-D", "warnings")
        environment("CARGO_TARGET_DIR", compukterFfiTargetRoot.absolutePath)
    }

val cargoBuildCompukterFfi =
    tasks.register<Exec>("cargoBuildCompukterFfi") {
        description = "Builds the release Compukter FFM platform library."
        group = "build"
        workingDir(compukterVmRoot)
        inputs.file(compukterVmManifest)
        inputs.file(compukterVmLock)
        inputs.file(compukterFfiRoot.resolve("Cargo.toml"))
        inputs.dir(compukterFfiRoot.resolve("src"))
        inputs.dir(compukterVmRoot.resolve("src"))
        outputs.file(compukterFfiLibrary)
        commandLine("cargo", "build", "-p", "compukter-ffi", "--release", "--locked", "--offline", "-j", compukterVmBuildJobs)
        environment("CARGO_TARGET_DIR", compukterFfiTargetRoot.absolutePath)
    }

val verifyKotlinVmConformance =
    tasks.register("verifyKotlinVmConformance") {
        description = "Runs every registered Kotlin-to-Compukter-VM execution-conformance scenario."
        group = "verification"
    }

val compilerArtifactVmConformanceHarness =
    rootProject.file("modules/compiler-artifact/src/test/rust/executable-conformance/Cargo.toml")
val compilerArtifactVmConformanceLock =
    rootProject.file("modules/compiler-artifact/src/test/rust/executable-conformance/Cargo.lock")
val compilerArtifactVmConformanceSource =
    rootProject.file("modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs")

fun registerKotlinVmConformance(
    taskName: String,
    taskDescription: String,
    artifactTask: String,
    artifact: Provider<RegularFile>,
    cargoTargetDirectory: String,
    artifactEnvironmentVariable: String,
    conformanceScenario: String,
) {
    val conformanceTask =
        tasks.register<Exec>(taskName) {
            description = taskDescription
            group = "verification"
            dependsOn(artifactTask)
            inputs.file(compilerArtifactVmConformanceHarness)
            inputs.file(compilerArtifactVmConformanceLock)
            inputs.file(compilerArtifactVmConformanceSource)
            inputs.file(artifact)
            doFirst {
                check(compilerArtifactVmConformanceHarness.isFile) {
                    "compiler artifact Rust conformance harness is missing"
                }
                check(artifact.get().asFile.isFile) {
                    "$taskName requires generated artifact ${artifact.get().asFile}"
                }
            }
            commandLine(
                "cargo",
                "test",
                "--locked",
                "--offline",
                "--manifest-path",
                compilerArtifactVmConformanceHarness.absolutePath,
                "--test",
                "kotlin_writer",
                "--",
                conformanceScenario,
            )
            environment("CARGO_TARGET_DIR", rootProject.file(cargoTargetDirectory).absolutePath)
            environment(artifactEnvironmentVariable, artifact.get().asFile.absolutePath)
        }
    verifyKotlinVmConformance.configure {
        dependsOn(conformanceTask)
    }
}

registerKotlinVmConformance(
    taskName = "testCompilerArtifactVmConformance",
    taskDescription = "Verifies Kotlin executable Artifact v1 output with the pinned Compukter VM.",
    artifactTask = ":compiler-artifact:test",
    artifact = project(":compiler-artifact").layout.buildDirectory.file("generated/conformance/executable-instructions.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-artifact-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_EXECUTABLE_ARTIFACT",
    conformanceScenario = "executable",
)
registerKotlinVmConformance(
    taskName = "testKotlinSubsetVmConformance",
    taskDescription = "Verifies K2-lowered Kotlin subset output with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateKotlinSubsetConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-subset.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_SUBSET_ARTIFACT",
    conformanceScenario = "subset",
)
registerKotlinVmConformance(
    taskName = "testKotlinSuspendCallVmConformance",
    taskDescription = "Executes a K2-produced suspend project call with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateSuspendCallConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/suspend-call.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-suspend-call-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_SUSPEND_CALL_ARTIFACT",
    conformanceScenario = "suspend-call",
)
registerKotlinVmConformance(
    taskName = "testKotlinTasksVmConformance",
    taskDescription = "Executes cooperative K2 Guest tasks with independent host requests.",
    artifactTask = ":compiler-k2:generateTasksConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/tasks.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-tasks-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_TASKS_ARTIFACT",
    conformanceScenario = "tasks",
)
registerKotlinVmConformance(
    taskName = "testKotlinChannelVmConformance",
    taskDescription = "Executes a K2-produced VM-owned bounded channel handoff.",
    artifactTask = ":compiler-k2:generateChannelConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/channel.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-channel-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_CHANNEL_ARTIFACT",
    conformanceScenario = "channel",
)
registerKotlinVmConformance(
    taskName = "testKotlinWhenVmConformance",
    taskDescription = "Executes bounded K2 when branches with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateWhenConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/when.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-when-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_WHEN_ARTIFACT",
    conformanceScenario = "when",
)
registerKotlinVmConformance(
    taskName = "testKotlinArgvVmConformance",
    taskDescription = "Executes K2 Array<String> entry arguments with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateArgvConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/argv.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-argv-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_ARGV_ARTIFACT",
    conformanceScenario = "argv",
)
registerKotlinVmConformance(
    taskName = "testKotlinPlatformScalarVmConformance",
    taskDescription = "Executes a bounded K2 platform-scalar precondition with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generatePlatformScalarConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/platform-scalar.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-platform-scalar-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_PLATFORM_SCALAR_ARTIFACT",
    conformanceScenario = "platform-scalar",
)
registerKotlinVmConformance(
    taskName = "testKotlinIntLoopsVmConformance",
    taskDescription = "Executes allocation-free K2 Int loops with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateIntLoopsConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-int-loops.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-int-loops-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_INT_LOOPS_ARTIFACT",
    conformanceScenario = "int-loops",
)
registerKotlinVmConformance(
    taskName = "testKotlinIntArrayVmConformance",
    taskDescription = "Executes specialized K2 IntArray operations with the pinned Compukter VM.",
    artifactTask = ":compiler-k2:generateIntArrayConformanceArtifact",
    artifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/kotlin-int-array.cpkt"),
    cargoTargetDirectory = ".toolchain/build/cargo/compiler-k2-int-array-conformance",
    artifactEnvironmentVariable = "COMPUKTER_KOTLIN_INT_ARRAY_ARTIFACT",
    conformanceScenario = "int-array",
)

val buildScriptsTest = gradle.includedBuild("build-scripts").task(":test")

val verifyActiveMinecraftBaseline =
    tasks.register("verifyActiveMinecraftBaseline") {
        group = "verification"
        description = "Rejects stale Minecraft and JDK baseline configuration."
        val activeFiles =
            fileTree(rootDir) {
                include(
                    "README.md",
                    "AGENTS.md",
                    "*.gradle.kts",
                    "*.properties",
                    "docs/**/*.md",
                    "gradle/*.toml",
                    "build-scripts/**/*.gradle.kts",
                    "build-scripts/**/*.kt",
                    "build-scripts/**/*.properties",
                    "config/**/*.properties",
                    "modules/**/*.gradle.kts",
                    "modules/**/*.json",
                    "modules/**/*.kt",
                    "modules/**/*.properties",
                    "modules/**/*.toml",
                )
                exclude(
                    "**/build/**",
                    "**/.gradle/**",
                    "docs/superpowers/specs/**",
                    "docs/superpowers/plans/**",
                )
            }
        val forbiddenTokens =
            listOf(
                "v1" + "_21_1",
                "1.21." + "1",
                "Parch" + "ment",
                "Java " + "17",
                "JDK " + "17",
                "JVM " + "17",
                "architectury-" + "neoforge",
                "Remap" + "JarTask",
            )

        inputs.files(activeFiles)
        doLast {
            check(rootProject.file("modules/v26_1/v26_1-common").isDirectory) {
                "active common module modules/v26_1/v26_1-common is missing"
            }
            check(rootProject.file("modules/v26_1/v26_1-neoforge").isDirectory) {
                "active NeoForge module modules/v26_1/v26_1-neoforge is missing"
            }
            val matches =
                activeFiles.files
                    .asSequence()
                    .filter(File::isFile)
                    .filter { file -> forbiddenTokens.any(file.readText()::contains) }
                    .map { it.relativeTo(rootDir).path }
                    .sorted()
                    .toList()
            check(matches.isEmpty()) {
                "stale Minecraft/JDK baseline references: ${matches.joinToString()}"
            }
        }
    }

val portableJava21Projects =
    listOf(
        ":native-runtime-api",
        ":platform-bundle",
        ":platform-k2",
        ":compiler-artifact",
        ":worker-client",
        ":tooling-runtime",
        ":compiler-client",
        ":compiler-runtime",
        ":compiler-k2-engine",
        ":compiler-k2",
        ":guest-platform",
        ":ide-core",
        ":ide-kotlin-formatter",
        ":ide-analysis-client",
        ":ide-analysis-k2",
        ":ide-client",
        ":core",
    )

val verifyPortableJava21Bytecode =
    tasks.register("verifyPortableJava21Bytecode") {
        group = "verification"
        description = "Checks that portable production classes remain loadable on Java 21."
        val archives =
            portableJava21Projects.map { path ->
                project(path).tasks.named<Jar>("jar").flatMap(Jar::getArchiveFile)
            }
        dependsOn(portableJava21Projects.map { "$it:jar" })
        inputs.files(archives)
        doLast {
            val maximumMajorVersion = 65
            archives.forEach { archiveProvider ->
                val archive = archiveProvider.get().asFile
                java.util.zip.ZipFile(archive).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .forEach { entry ->
                            java.io.DataInputStream(zip.getInputStream(entry)).use { input ->
                                check(input.readInt() == 0xCAFEBABE.toInt()) {
                                    "invalid class file ${entry.name} in ${archive.name}"
                                }
                                input.readUnsignedShort()
                                val majorVersion = input.readUnsignedShort()
                                check(majorVersion <= maximumMajorVersion) {
                                    "portable class ${entry.name} in ${archive.name} targets class version " +
                                        "$majorVersion, expected <= $maximumMajorVersion"
                                }
                            }
                        }
                }
            }
        }
    }

val verifyLicensePolicy =
    tasks.register("verifyLicensePolicy") {
        description = "Rejects stale GPL identity and inconsistent Apache-2.0 metadata."
        group = "verification"
        val eligibleFiles =
            fileTree(rootDir) {
                include(
                    "*.gradle.kts",
                    "*.properties",
                    "README.md",
                    "AGENTS.md",
                    ".github/**/*.md",
                    "build-scripts/**/*.gradle.kts",
                    "build-scripts/**/*.kt",
                    "build-scripts/**/*.properties",
                    "config/**/*.properties",
                    "modules/**/*.gradle.kts",
                    "modules/**/*.kt",
                    "modules/**/*.rs",
                    "system/**/*.kt",
                    "host/**/*.rs",
                    "host/**/Cargo.toml",
                    "host/**/README.md",
                )
                exclude(
                    "**/build/**",
                    "**/target/**",
                    "**/.gradle/**",
                    "**/.gradle-sandbox/**",
                    "**/run/**",
                    "**/.agents/**",
                    "**/META-INF/licenses/**",
                    "tools/fonts/**",
                )
            }
        val canonicalLicense = rootProject.file("licenses/project/Apache-2.0.txt")
        val rootLicense = rootProject.file("LICENSE.md")
        val modProperties = rootProject.file("config/mod.properties")
        val ffiManifest = compukterFfiRoot.resolve("Cargo.toml")
        val ffiLock = compukterVmLock
        val vmManifest = compukterVmManifest
        val componentInventory = rootProject.file("licenses/distribution-components.tsv")
        val packagedRustDependencyTree =
            providers.exec {
                workingDir(compukterVmRoot)
                commandLine(
                    "cargo",
                    "tree",
                    "--locked",
                    "--offline",
                    "-p",
                    "compukter-ffi",
                    "--edges",
                    "normal,build",
                    "--prefix",
                    "none",
                    "--format",
                    "{p}",
                )
            }.standardOutput.asText

        inputs.files(eligibleFiles)
        inputs.files(rootLicense, canonicalLicense, modProperties, ffiManifest, ffiLock, vmManifest, componentInventory)
        doLast {
            val forbidden = listOf("GNU General " + "Public License", "GPL-" + "3.0", "GPL" + "v3")
            val stale =
                eligibleFiles.files
                    .asSequence()
                    .filter(File::isFile)
                    .filter { file -> forbidden.any(file.readText()::contains) }
                    .map { it.relativeTo(rootDir).path }
                    .sorted()
                    .toList()
            check(stale.isEmpty()) { "stale GPL identity in active files: ${stale.joinToString()}" }
            check(canonicalLicense.isFile) { "canonical Apache-2.0 license is missing" }
            check(rootLicense.readBytes().contentEquals(canonicalLicense.readBytes())) {
                "LICENSE.md differs from the canonical Apache-2.0 text"
            }
            check("common_mod_license=Apache-2.0" in modProperties.readText()) {
                "mod metadata must use common_mod_license=Apache-2.0"
            }
            listOf(ffiManifest, vmManifest).forEach { manifest ->
                check("license = \"Apache-2.0\"" in manifest.readText()) {
                    "${manifest.relativeTo(rootDir)} must declare license = \"Apache-2.0\""
                }
            }
            val expectedRust =
                componentInventory
                    .readLines()
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { it.split('\t') }
                    .filter { it[0] == "rust-native" }
                    .map { (_, component, version, _) -> component to version }
                    .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
            val actualRust =
                packagedRustDependencyTree
                    .get()
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map { line -> line.substringBefore(" (*)").substringBefore(" (") }
                    .map { identity -> identity.substringBeforeLast(' ') to identity.substringAfterLast(' ').removePrefix("v") }
                    .filterNot { (component, _) -> component == "compukter-ffi" || component == "compukter-vm" }
                    .distinct()
                    .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
                    .toList()
            check(actualRust == expectedRust) {
                "native Rust dependency inventory mismatch: expected $expectedRust, found $actualRust"
            }
        }
    }

tasks.register("verifyLocalFast") {
    description = "Runs the curated fast local JVM and build-script verification slice."
    group = "verification"
    dependsOn(buildScriptsTest)
    dependsOn(verifyActiveMinecraftBaseline)
    dependsOn(verifyPortableJava21Bytecode)
    dependsOn(verifyLicensePolicy)
    dependsOn(":core:test")
    dependsOn(":ide-client:check")
    dependsOn(":ide-analysis-client:check")
    dependsOn(":ide-analysis-k2:check")
    dependsOn(":native-runtime-api:test")
    dependsOn(":native-runtime-ffm:test")
    dependsOn(":playground:test")
    dependsOn(":v26_1-common:test")
    dependsOn(":v26_1-neoforge:test")
}

tasks.named("check") {
    dependsOn(verifyLicensePolicy)
}

val verifyAllModuleChecks =
    tasks.register("verifyAllModuleChecks") {
        description = "Runs the check lifecycle of every Gradle subproject."
        group = "verification"
        dependsOn(subprojects.map { "${it.path}:check" })
    }

tasks.register("verifyLocalFull") {
    description = "Fully verifies the current checkout and its locally packaged production artifact."
    group = "verification"
    dependsOn("verifyLocalFast")
    dependsOn(verifyAllModuleChecks)
    dependsOn(verifyKotlinVmConformance)
    dependsOn(testCompukterVmRust)
    dependsOn(testCompukterFfiRust)
    dependsOn(testCompukterFfiRustRelease)
    dependsOn(fmtCompukterFfiRust)
    dependsOn(clippyCompukterFfiRust)
    dependsOn(cargoBuildCompukterFfi)
    dependsOn("checkCompukterVmRelease")
    dependsOn(":v26_1-neoforge:runGameTestServer")
}
