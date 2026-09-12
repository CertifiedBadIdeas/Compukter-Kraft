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
    application
    alias(libs.plugins.kotlinConvention)
}

// The standalone application keeps using FFM until the Java 21 JNI transport
// is available later in issue #615.
kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    implementation(projects.compilerClient)
    implementation(projects.ideCore)
    implementation(projects.nativeRuntimeApi)
    implementation(projects.nativeRuntimeFfm)
    implementation(projects.platformBundle)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "ru.lazyhat.compukters.playground.PlaygroundMainKt"
}

val workerPayload = project(":tooling-runtime").layout.buildDirectory.dir("tooling-bundle/content")
val compukterFfiLibrary =
    rootProject.file(".toolchain/build/cargo/compukter-ffi/release/${System.mapLibraryName("compukter_ffi")}")
val repositoryRoot = rootProject.layout.projectDirectory

tasks.test {
    filter.excludeTestsMatching("ru.lazyhat.compukters.playground.integration.*")
}

tasks.named<JavaExec>("run") {
    dependsOn(":tooling-runtime:prepareToolingRuntimeBundle", rootProject.tasks.named("cargoBuildCompukterFfi"))
    workingDir(rootProject.projectDir)
    standardInput = System.`in`
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
    doFirst {
        systemProperty("compukters.worker.payload", workerPayload.get().asFile.absolutePath)
        systemProperty("compukters.ffi.library", compukterFfiLibrary.absolutePath)
    }
}

val endToEndTest =
    tasks.register<Test>("endToEndTest") {
        description = "Compiles and executes the standalone Kotlin example through the real worker, FFM, and Rust VM."
        group = "verification"
        dependsOn(":tooling-runtime:prepareToolingRuntimeBundle", rootProject.tasks.named("cargoBuildCompukterFfi"))
        useJUnitPlatform()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("ru.lazyhat.compukters.playground.integration.*")
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
        inputs.dir(workerPayload)
        inputs.file(compukterFfiLibrary)
        inputs.dir(repositoryRoot.dir("examples/hello"))
        doFirst {
            systemProperty("compukters.worker.payload", workerPayload.get().asFile.absolutePath)
            systemProperty("compukters.ffi.library", compukterFfiLibrary.absolutePath)
            systemProperty("compukters.project.root", repositoryRoot.asFile.absolutePath)
        }
    }

tasks.check {
    dependsOn(tasks.named("assertNoK2CompilerRuntime"))
    dependsOn(endToEndTest)
}
