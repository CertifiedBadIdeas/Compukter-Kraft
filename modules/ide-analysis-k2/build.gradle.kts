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

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlinConvention)
}

repositories {
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
        name = "JetBrainsIntellijDependencies"
        content {
            includeGroup("org.jetbrains.kotlin")
            includeGroup("com.intellij.platform")
        }
    }
    maven("https://www.jetbrains.com/intellij-repository/releases") {
        name = "JetBrainsIntellijReleases"
        content {
            includeModule("com.jetbrains.intellij.platform", "util-diff")
        }
    }
}

dependencies {
    implementation(projects.ideAnalysisClient) { isTransitive = false }
    implementation(projects.ideCore) { isTransitive = false }
    implementation(projects.platformBundle) { isTransitive = false }
    implementation(projects.platformK2) { isTransitive = false }
    implementation(projects.compilerClient) { isTransitive = false }
    implementation(projects.workerClient) { isTransitive = false }
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.compiler) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.caffeine.kotlin)
    implementation(libs.intellij.coroutines.core)
    implementation(libs.intellij.util.diff) { isTransitive = false }
    implementation(libs.kotlin.analysis.api) { isTransitive = false }
    implementation(libs.kotlin.analysis.api.impl.base) { isTransitive = false }
    implementation(libs.kotlin.analysis.api.k2) { isTransitive = false }
    implementation(libs.kotlin.analysis.platform) { isTransitive = false }
    implementation(libs.kotlin.analysis.low.level.fir) { isTransitive = false }
    implementation(libs.kotlin.symbol.light.classes) { isTransitive = false }
    implementation(libs.kotlin.analysis.api.standalone) {
        // JetBrains publishes each `for-ide` JAR as a shaded aggregate while
        // its generated POM still names unpublished source modules.
        isTransitive = false
    }
    testImplementation(kotlin("test"))
}

val kotlinFormatterRuntime = configurations.create("kotlinFormatterRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(kotlinFormatterRuntime.name, project(":ide-kotlin-formatter"))
}

val guestPlatformSources = configurations.create("guestPlatformSources") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(guestPlatformSources.name, project(path = ":guest-platform")) {
        isTransitive = false
    }
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    dependsOn(kotlinFormatterRuntime)
    from(kotlinFormatterRuntime) {
        into("META-INF/compukters/kotlin-formatter")
    }
}

val analysisWorkerPayloadDirectory = layout.buildDirectory.dir("worker-payload/content")
val analysisWorkerMainClass = "ru.lazyhat.compukters.ide.analysis.k2.server.AnalysisWorkerMainKt"
val pinnedKotlinVersion = libs.versions.kotlin.asProvider().get()

val prepareAnalysisWorkerPayload = tasks.register<Sync>("prepareAnalysisWorkerPayload") {
    dependsOn(tasks.jar)
    into(analysisWorkerPayloadDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(tasks.jar) {
        into("lib")
    }
    from(configurations.runtimeClasspath) {
        into("lib")
    }
    from(rootProject.layout.projectDirectory.file("licenses/project/Apache-2.0.txt")) {
        into("META-INF/licenses")
        rename { "Compukters-Apache-2.0.txt" }
    }
    from(rootProject.layout.projectDirectory.file("NOTICE")) {
        into("META-INF")
        rename { "NOTICE.txt" }
    }
    from(rootProject.layout.projectDirectory.file("THIRD-PARTY-NOTICES.md")) {
        into("META-INF")
    }
    from(rootProject.layout.projectDirectory.dir("licenses/kotlin/v2.4.10")) {
        into("META-INF/licenses/kotlin/v2.4.10")
    }
    from(rootProject.layout.projectDirectory.file("licenses/jvm/checker-qual-3.19.0-MIT.txt")) {
        into("META-INF/licenses/jvm")
    }
    from(rootProject.layout.projectDirectory.file("licenses/jvm/ktlint-1.8.0-MIT.txt")) {
        into("META-INF/licenses/jvm")
    }
    from(rootProject.layout.projectDirectory.file("licenses/jvm/slf4j-2.0.18-MIT.txt")) {
        into("META-INF/licenses/jvm")
    }
    from(rootProject.layout.projectDirectory.file("licenses/distribution-components.tsv")) {
        into("META-INF/licenses")
    }
    doLast {
        val root = analysisWorkerPayloadDirectory.get().asFile.toPath()
        val files =
            Files.list(root.resolve("lib")).use { paths ->
                paths.sorted().map { path ->
                    val bytes = Files.readAllBytes(path)
                    Triple("lib/${path.fileName}", bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes))
                }.toList()
            }
        val identities = sortedMapOf("compiler" to pinnedKotlinVersion, "language" to "2.4")
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray()
            digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
            digest.update(bytes)
        }
        digest.update("Compukters worker payload v1\u0000".toByteArray())
        digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
        field("analysis")
        identities.forEach { (name, value) -> field(name); field(value) }
        field(analysisWorkerMainClass)
        files.forEach { (path, size, hash) ->
            field(path)
            digest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array())
            digest.update(hash)
        }
        val payloadHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val manifest =
            buildString {
                appendLine("format=1")
                appendLine("kind=analysis")
                identities.forEach { (name, value) -> appendLine("identity.$name=$value") }
                appendLine("mainClass=$analysisWorkerMainClass")
                appendLine("payloadSha256=$payloadHash")
                files.forEach { (path, size, hash) ->
                    append("file=").append(path).append('\t').append(size).append('\t')
                    appendLine(hash.joinToString("") { "%02x".format(it.toInt() and 0xff) })
                }
            }
        Files.writeString(root.resolve("worker.payload"), manifest)
    }
}

val analysisWorkerPayloadContent = configurations.create("analysisWorkerPayloadContent") {
    isCanBeConsumed = true
    isCanBeResolved = false
    description = "Prepared analysis worker payload directory for tooling bundle assembly."
}
artifacts.add(analysisWorkerPayloadContent.name, analysisWorkerPayloadDirectory) {
    builtBy(prepareAnalysisWorkerPayload)
}

val analysisWorkerPayload = tasks.register<Zip>("analysisWorkerPayload") {
    dependsOn(prepareAnalysisWorkerPayload)
    from(analysisWorkerPayloadDirectory)
    archiveFileName = "ide-analysis-k2-worker.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyAnalysisWorkerLicenses = tasks.register("verifyAnalysisWorkerLicenses") {
    description = "Checks licenses and the exact library inventory in the packaged K2 analysis worker."
    group = "verification"
    dependsOn(analysisWorkerPayload)
    inputs.file(analysisWorkerPayload.flatMap { it.archiveFile })
    inputs.file(rootProject.layout.projectDirectory.file("licenses/distribution-components.tsv"))
    doLast {
        val archive = analysisWorkerPayload.get().archiveFile.get().asFile
        val entries =
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            }
        listOf(
            "META-INF/licenses/Compukters-Apache-2.0.txt",
            "META-INF/licenses/jvm/checker-qual-3.19.0-MIT.txt",
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
                .filter { it[0] == "jvm-analysis-worker" }
                .map { (_, component, version, _) -> "$component-$version.jar" }
                .sorted()
        val actualExternal =
            entries
                .filter { it.startsWith("lib/") && it.endsWith(".jar") }
                .map { it.removePrefix("lib/") }
                .filterNot { name ->
                    listOf(
                        "guest-platform-",
                        "ide-analysis-k2-",
                        "ide-analysis-client-",
                        "ide-core-",
                        "platform-bundle-",
                        "platform-k2-",
                        "compiler-client-",
                        "worker-client-",
                    )
                        .any(name::startsWith)
                }
                .sorted()
        check(actualExternal == expectedExternal) {
            "analysis worker library inventory mismatch: expected $expectedExternal, found $actualExternal"
        }
        check(actualExternal.none { "embeddable" in it }) {
            "formatter compiler leaked onto the analysis worker launch classpath"
        }

        val expectedFormatter =
            rootProject
                .file("licenses/distribution-components.tsv")
                .readLines()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { it.split('\t') }
                .filter { it[0] == "jvm-analysis-formatter" }
                .map { (_, component, version, _) -> "$component-$version.jar" }
                .sorted()
        val actualFormatter =
            ZipFile(archive).use { worker ->
                val analysisJar =
                    worker.entries().asSequence().single {
                        it.name.startsWith("lib/ide-analysis-k2-") && it.name.endsWith(".jar")
                    }
                ZipInputStream(worker.getInputStream(analysisJar)).use { nested ->
                    buildList {
                        while (true) {
                            val entry = nested.nextEntry ?: break
                            if (entry.name.startsWith("META-INF/compukters/kotlin-formatter/") && entry.name.endsWith(".jar")) {
                                add(entry.name.substringAfterLast('/'))
                            }
                        }
                    }
                }
            }.filterNot { it.startsWith("ide-kotlin-formatter-") }.sorted()
        check(actualFormatter == expectedFormatter) {
            "embedded formatter inventory mismatch: expected $expectedFormatter, found $actualFormatter"
        }
    }
}

tasks.test {
    val guestApiJar = project(":guest-platform").tasks.named<Jar>("jar")
    val platformBundle = project(":guest-platform").tasks.named("assemblePlatformBundle")
    dependsOn(guestApiJar, platformBundle)
    doFirst {
        systemProperty("compukters.test.kotlinFormatterClasspath", kotlinFormatterRuntime.files.joinToString(File.pathSeparator))
        systemProperty("compukters.test.guestApi", guestApiJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty(
            "compukters.test.platformBundle",
            project(":guest-platform").layout.buildDirectory.file("platform/compukters-platform.cpb").get().asFile.absolutePath,
        )
    }
    filter.excludeTestsMatching("ru.lazyhat.compukters.ide.analysis.k2.integration.*")
}

val forkedWorkerTest = tasks.register<Test>("forkedWorkerTest") {
    description = "Runs forked K2 analysis worker integration tests."
    group = "verification"
    dependsOn(":tooling-runtime:prepareToolingRuntimeBundle", ":guest-platform:jar", ":guest-platform:assemblePlatformBundle")
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("ru.lazyhat.compukters.ide.analysis.k2.integration.*")
        isFailOnNoMatchingTests = false
    }
    doFirst {
        systemProperty("compukters.analysis.testClasspath", classpath.files.joinToString(File.pathSeparator))
        systemProperty("compukters.analysis.java", javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }.get().executablePath.asFile.absolutePath)
        systemProperty(
            "compukters.analysis.payload",
            project(":tooling-runtime").layout.buildDirectory.dir("tooling-bundle/content").get().asFile.absolutePath,
        )
        systemProperty(
            "compukters.test.platformBundle",
            project(":guest-platform").layout.buildDirectory.file("platform/compukters-platform.cpb").get().asFile.absolutePath,
        )
        systemProperty(
            "compukters.test.guestApi",
            project(":guest-platform").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
}

val incrementalAnalysisPerformanceTest = tasks.register<Test>("incrementalAnalysisPerformanceTest") {
    description = "Runs machine-sensitive incremental IDE analysis SLO checks."
    group = "verification"
    dependsOn(":tooling-runtime:prepareToolingRuntimeBundle", ":guest-platform:jar", ":guest-platform:assemblePlatformBundle")
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxHeapSize = "512m"
    testLogging.showStandardStreams = true
    filter {
        includeTestsMatching("ru.lazyhat.compukters.ide.analysis.k2.integration.AnalysisWorkerMeasurementTest")
        includeTestsMatching("ru.lazyhat.compukters.ide.analysis.k2.integration.SourceIndexMeasurementTest")
        isFailOnNoMatchingTests = true
    }
    doFirst {
        systemProperty("compukters.analysis.performance", "true")
        systemProperty("compukters.analysis.testClasspath", classpath.files.joinToString(File.pathSeparator))
        systemProperty("compukters.analysis.java", javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }.get().executablePath.asFile.absolutePath)
        systemProperty(
            "compukters.analysis.payload",
            project(":tooling-runtime").layout.buildDirectory.dir("tooling-bundle/content").get().asFile.absolutePath,
        )
        systemProperty(
            "compukters.test.platformBundle",
            project(":guest-platform").layout.buildDirectory.file("platform/compukters-platform.cpb").get().asFile.absolutePath,
        )
        systemProperty(
            "compukters.test.guestApi",
            project(":guest-platform").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
}

// Both suites initialize the heavyweight standalone K2/IntelliJ environment.
// Keep their Gradle test workers disjoint in time: concurrent execution can
// terminate the ordinary test worker while Gradle is still finalizing its
// binary results.
forkedWorkerTest.configure {
    mustRunAfter(tasks.test)
}

incrementalAnalysisPerformanceTest.configure {
    mustRunAfter(tasks.test)
}

tasks.named("check") {
    dependsOn(verifyAnalysisWorkerLicenses)
    dependsOn(forkedWorkerTest)
}
