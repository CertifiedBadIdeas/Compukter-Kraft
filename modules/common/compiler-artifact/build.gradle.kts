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
    implementation(libs.kotlin.stdlib)
    testImplementation(kotlin("test"))
}

val executableConformanceArtifact = layout.buildDirectory.file("generated/conformance/executable-instructions.cpkt")

tasks.test {
    val rustFixtures = rootProject.layout.projectDirectory.dir("host/compukter-vm/tests/fixtures")
    inputs.dir(rustFixtures)
    outputs.file(executableConformanceArtifact)
    systemProperty("compukter.vm.fixtures", rustFixtures.asFile.absolutePath)
    systemProperty("compukter.vm.executableArtifact", executableConformanceArtifact.get().asFile.absolutePath)
}

val assertCompilerArtifactIsolation = tasks.register("assertCompilerArtifactIsolation") {
    group = "verification"
    description = "Fails when compiler implementation artifacts enter compiler-artifact production classpaths."

    doLast {
        val forbidden =
            listOf("compileClasspath", "runtimeClasspath")
                .flatMap { name -> configurations.getByName(name).resolvedConfiguration.resolvedArtifacts }
                .map { it.moduleVersion.id.group to it.moduleVersion.id.name }
                .filter { (group, name) ->
                    group == "org.jetbrains.kotlin" &&
                        (name.startsWith("kotlin-compiler") || name.startsWith("kotlin-scripting-compiler"))
                }
        check(forbidden.isEmpty()) { "compiler-artifact contains compiler implementation dependencies: $forbidden" }
    }
}

tasks.check {
    dependsOn(assertCompilerArtifactIsolation)
}
