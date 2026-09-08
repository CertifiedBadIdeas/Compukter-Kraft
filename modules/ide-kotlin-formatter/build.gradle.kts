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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.File
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlinConvention)
    id("com.gradleup.shadow")
}

val relocatedFormatterRuntime = configurations.create("relocatedFormatterRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "org.jetbrains")
}

val ordinaryCompilerRuntime = configurations.create("ordinaryCompilerRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(libs.ktlint.rule.engine)
    implementation(libs.ktlint.ruleset.standard)
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlin.stdlib)
    implementation(libs.slf4j.api)
    testImplementation(kotlin("test"))

    add(relocatedFormatterRuntime.name, libs.ktlint.rule.engine)
    add(relocatedFormatterRuntime.name, libs.ktlint.ruleset.standard)
    add(relocatedFormatterRuntime.name, libs.slf4j.api)
    add(ordinaryCompilerRuntime.name, libs.kotlin.compiler)
}

val relocatedFormatterJar = tasks.register<ShadowJar>("relocatedFormatterJar") {
    description = "Builds ktlint against the ordinary Kotlin compiler namespace."
    group = "build"
    archiveClassifier = "relocated-runtime"
    configurations = listOf(relocatedFormatterRuntime)
    from(sourceSets.main.map { it.output })
    relocate("org.jetbrains.kotlin.com.intellij", "com.intellij")
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val relocatedFormatterRuntimeElements = configurations.create("relocatedFormatterRuntimeElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    description = "Compiler-free ktlint runtime relocated to the ordinary IntelliJ namespace."
}

artifacts.add(relocatedFormatterRuntimeElements.name, relocatedFormatterJar)

val verifyRelocatedFormatterRuntime = tasks.register("verifyRelocatedFormatterRuntime") {
    description = "Checks the relocated formatter artifact and its exact private dependency inventory."
    group = "verification"
    dependsOn(relocatedFormatterJar)
    inputs.file(relocatedFormatterJar.flatMap { it.archiveFile })
    inputs.files(relocatedFormatterRuntime)
    doLast {
        val expectedDependencies =
            listOf(
                "ec4j-core-1.1.1.jar",
                "kotlin-logging-jvm-7.0.13.jar",
                "ktlint-cli-ruleset-core-1.8.0.jar",
                "ktlint-logger-1.8.0.jar",
                "ktlint-rule-engine-1.8.0.jar",
                "ktlint-rule-engine-core-1.8.0.jar",
                "ktlint-ruleset-standard-1.8.0.jar",
                "poko-annotations-jvm-0.20.1.jar",
                "slf4j-api-2.0.18.jar",
            )
        val actualDependencies = relocatedFormatterRuntime.files.map(File::getName).sorted()
        check(actualDependencies == expectedDependencies) {
            "relocated formatter dependency inventory mismatch: expected $expectedDependencies, found $actualDependencies"
        }

        ZipFile(relocatedFormatterJar.get().archiveFile.get().asFile).use { jar ->
            val entries = jar.entries().asSequence().filterNot { it.isDirectory }.toList()
            val names = entries.map { it.name }
            check(names.size == names.toSet().size) { "relocated formatter contains duplicate entries" }
            check(names.none { it.startsWith("org/jetbrains/kotlin/com/intellij/") }) {
                "relocated IntelliJ classes leaked into formatter runtime"
            }
            check(
                names.none {
                    it.startsWith("org/jetbrains/kotlin/cli/") ||
                        it.startsWith("org/jetbrains/kotlin/psi/") ||
                        it.startsWith("com/intellij/")
                },
            ) {
                "Kotlin compiler or IntelliJ implementation classes leaked into formatter runtime"
            }
            entries.filter { it.name.endsWith(".class") }.forEach { entry ->
                val bytes = jar.getInputStream(entry).use { it.readAllBytes() }
                check(!bytes.toString(Charsets.ISO_8859_1).contains("org/jetbrains/kotlin/com/intellij")) {
                    "unrelocated IntelliJ reference remains in ${entry.name}"
                }
            }
        }
    }
}

tasks.test {
    dependsOn(relocatedFormatterJar)
    doFirst {
        val classpath = listOf(relocatedFormatterJar.get().archiveFile.get().asFile) + ordinaryCompilerRuntime.files
        systemProperty("compukters.test.relocatedFormatterClasspath", classpath.joinToString(File.pathSeparator))
    }
}

tasks.check {
    dependsOn(verifyRelocatedFormatterRuntime)
}
