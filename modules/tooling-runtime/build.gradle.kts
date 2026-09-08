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

import java.nio.file.Files
import java.util.zip.ZipFile
import org.gradle.api.tasks.bundling.ZipEntryCompression

plugins {
    application
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(libs.aircompressor)
    implementation(projects.workerClient)
    implementation(libs.kotlin.stdlib)
    implementation(libs.zstd.jni)
    testImplementation(kotlin("test"))
}

val compilerWorkerPayloadInput = configurations.create("compilerWorkerPayloadInput") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val analysisWorkerPayloadInput = configurations.create("analysisWorkerPayloadInput") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(compilerWorkerPayloadInput.name, project(path = ":compiler-k2", configuration = "compilerWorkerPayloadContent"))
    add(analysisWorkerPayloadInput.name, project(path = ":ide-analysis-k2", configuration = "analysisWorkerPayloadContent"))
}

application {
    mainClass = "ru.lazyhat.compukters.tooling.bundle.ToolingBundleMainKt"
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val toolingBundleDirectory = layout.buildDirectory.dir("tooling-bundle/content")

val prepareToolingRuntimeBundle = tasks.register<JavaExec>("prepareToolingRuntimeBundle") {
    group = "build"
    description = "Assembles the shared compiler and analysis K2 runtime tree."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    inputs.files(compilerWorkerPayloadInput, analysisWorkerPayloadInput)
    outputs.dir(toolingBundleDirectory)
    doFirst {
        delete(toolingBundleDirectory)
        args(
            "assemble",
            compilerWorkerPayloadInput.singleFile.absolutePath,
            analysisWorkerPayloadInput.singleFile.absolutePath,
            toolingBundleDirectory.get().asFile.absolutePath,
        )
    }
}

val canonicalToolingRuntimeBundle = tasks.register<Zip>("canonicalToolingRuntimeBundle") {
    group = "distribution"
    description = "Packages the shared K2 tooling runtime as a canonical stored-entry ZIP."
    dependsOn(prepareToolingRuntimeBundle)
    from(toolingBundleDirectory)
    archiveFileName = "k2-tooling-workers.zip"
    destinationDirectory = layout.buildDirectory.dir("tooling-bundle/carrier")
    entryCompression = ZipEntryCompression.STORED
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val toolingRuntimeBundleFile = layout.buildDirectory.file("distributions/k2-tooling-workers.zip.zst")
val toolingRuntimeManifestFile = layout.buildDirectory.file("distributions/k2-tooling-workers.bundle")
val toolingRuntimeManifest = tasks.register("toolingRuntimeManifest") {
    group = "distribution"
    description = "Publishes the external identity manifest for tooling cache admission."
    dependsOn(prepareToolingRuntimeBundle)
    val source = toolingBundleDirectory.map { it.file("tooling.bundle") }
    inputs.file(source)
    outputs.file(toolingRuntimeManifestFile)
    doLast {
        source.get().asFile.copyTo(toolingRuntimeManifestFile.get().asFile, overwrite = true)
    }
}
val toolingRuntimeBundle = tasks.register<JavaExec>("toolingRuntimeBundle") {
    group = "distribution"
    description = "Compresses the canonical shared K2 tooling ZIP as one Zstandard frame."
    dependsOn(tasks.classes, canonicalToolingRuntimeBundle)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    inputs.file(canonicalToolingRuntimeBundle.flatMap { it.archiveFile })
    outputs.file(toolingRuntimeBundleFile)
    doFirst {
        args(
            "compress",
            canonicalToolingRuntimeBundle.get().archiveFile.get().asFile.absolutePath,
            toolingRuntimeBundleFile.get().asFile.absolutePath,
        )
    }
}

val verifyToolingRuntimeBundle = tasks.register<JavaExec>("verifyToolingRuntimeBundle") {
    group = "verification"
    description = "Reassembles, publishes, and verifies the shared K2 tooling runtime."
    dependsOn(tasks.classes, toolingRuntimeBundle, toolingRuntimeManifest)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    inputs.files(compilerWorkerPayloadInput, analysisWorkerPayloadInput)
    inputs.file(toolingRuntimeBundleFile)
    inputs.file(toolingRuntimeManifestFile)
    val scratch = layout.buildDirectory.dir("tooling-bundle/verification")
    outputs.upToDateWhen { false }
    doFirst {
        args(
            "verify",
            compilerWorkerPayloadInput.singleFile.absolutePath,
            analysisWorkerPayloadInput.singleFile.absolutePath,
            toolingRuntimeManifestFile.get().asFile.absolutePath,
            toolingRuntimeBundleFile.get().asFile.absolutePath,
            scratch.get().asFile.absolutePath,
        )
    }
}

val verifyToolingRuntimeLicenses =
    tasks.register("verifyToolingRuntimeLicenses") {
        group = "verification"
        description = "Checks shared tooling licenses and its exact external JVM inventory."
        dependsOn(toolingRuntimeBundle, ":ide-kotlin-formatter:verifyRelocatedFormatterRuntime")
        inputs.dir(toolingBundleDirectory)
        inputs.file(toolingRuntimeBundleFile)
        inputs.file(rootProject.layout.projectDirectory.file("licenses/distribution-components.tsv"))
        doLast {
            val archive = toolingRuntimeBundleFile.get().asFile
            val content = toolingBundleDirectory.get().asFile.toPath()
            val entries =
                Files.walk(content).use { paths ->
                    paths
                        .filter(Files::isRegularFile)
                        .map { content.relativize(it).joinToString("/") }
                        .toList()
                }
            listOf(
                "tooling.bundle",
                "manifests/compiler.payload",
                "manifests/analysis.payload",
                "META-INF/licenses/Compukters-Apache-2.0.txt",
                "META-INF/licenses/jvm/ktlint-1.8.0-MIT.txt",
                "META-INF/licenses/jvm/slf4j-2.0.18-MIT.txt",
                "META-INF/NOTICE.txt",
                "META-INF/THIRD-PARTY-NOTICES.md",
            ).forEach { required ->
                check(entries.count { it == required } == 1) {
                    "expected exactly one $required in ${archive.name}"
                }
            }
            val expectedExternal =
                rootProject
                    .file("licenses/distribution-components.tsv")
                    .readLines()
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { it.split('\t') }
                    .filter { it[0] == "jvm-worker" || it[0] == "jvm-analysis-worker" }
                    .map { (_, component, version, _) -> "$component-$version.jar" }
                    .distinct()
                    .sorted()
            val projectPrefixes =
                listOf(
                    "compiler-artifact-",
                    "compiler-client-",
                    "compiler-k2-",
                    "guest-platform-",
                    "ide-analysis-client-",
                    "ide-analysis-k2-",
                    "ide-core-",
                    "platform-bundle-",
                    "platform-k2-",
                    "worker-client-",
                )
            val actualExternal =
                entries
                    .filter { path ->
                        path.endsWith(".jar") &&
                            (path.startsWith("common/lib/") ||
                                path.startsWith("compiler/lib/") ||
                                path.startsWith("analysis/lib/"))
                    }.map { it.substringAfterLast('/') }
                    .filterNot { name -> projectPrefixes.any(name::startsWith) }
                    .sorted()
            check(actualExternal == expectedExternal) {
                "shared tooling library inventory mismatch: expected $expectedExternal, found $actualExternal"
            }
            check(actualExternal.none { "embeddable" in it || "scripting-compiler" in it }) {
                "embeddable or scripting compiler distribution leaked into ${archive.name}"
            }
            val actualFormatter =
                entries
                    .single { it.startsWith("analysis/lib/ide-analysis-k2-") && it.endsWith(".jar") }
                    .let(content::resolve)
                    .let { analysisJar ->
                        ZipFile(analysisJar.toFile()).use { analysis ->
                            analysis
                                .entries()
                                .asSequence()
                                .filter {
                                    it.name.startsWith("META-INF/compukters/kotlin-formatter/") && it.name.endsWith(".jar")
                                }.map { it.name.substringAfterLast('/') }
                                .toList()
                        }
                    }.sorted()
            check(
                actualFormatter.size == 1 &&
                    actualFormatter.single().startsWith("ide-kotlin-formatter-") &&
                    actualFormatter.single().endsWith("-relocated-runtime.jar"),
            ) {
                "expected one relocated embedded formatter runtime, found $actualFormatter"
            }
        }
    }

tasks.check {
    dependsOn(verifyToolingRuntimeBundle, verifyToolingRuntimeLicenses)
}
