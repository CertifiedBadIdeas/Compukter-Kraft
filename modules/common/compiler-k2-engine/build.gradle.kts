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
    api(projects.platformK2)
    implementation(projects.compilerArtifact)
    implementation(projects.compilerClient)
    implementation(libs.kotlin.compiler)
    testImplementation(kotlin("test"))
}

val assertCompilerEngineBoundary = tasks.register("assertCompilerEngineBoundary") {
    doLast {
        val allowedProjects = setOf(":compiler-artifact", ":compiler-client", ":platform-bundle", ":platform-k2", ":worker-client")
        val projects =
            configurations.runtimeClasspath
                .get()
                .incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                .map { it.projectPath }
                .toSet() - project.path
        check(projects.all { it in allowedProjects }) { "compiler engine has forbidden project dependencies: ${projects - allowedProjects}" }
    }
}

tasks.check {
    dependsOn(assertCompilerEngineBoundary)
}

tasks.test {
    dependsOn(tasks.jar)
    doFirst {
        systemProperty("compukters.engine.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    }
}
