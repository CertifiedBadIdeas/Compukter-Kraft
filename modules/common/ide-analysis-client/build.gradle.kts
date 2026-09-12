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

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class AssertAnalysisClientRuntime : DefaultTask() {
    @get:Input
    abstract val allowedRuntimeModules: SetProperty<String>

    @get:Input
    abstract val runtimeModules: ListProperty<String>

    @get:Input
    abstract val forbiddenRuntimeModuleFragments: SetProperty<String>

    @TaskAction
    fun verify() {
        val resolved = runtimeModules.get()
        val forbidden = resolved.filter { module -> forbiddenRuntimeModuleFragments.get().any(module::contains) }
        check(forbidden.isEmpty()) { "ide-analysis-client runtimeClasspath contains K2/platform dependencies: $forbidden" }
        val disallowed = resolved.filterNot(allowedRuntimeModules.get()::contains)
        check(disallowed.isEmpty()) { "ide-analysis-client runtimeClasspath contains disallowed dependencies: $disallowed" }
    }
}

plugins {
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    api(projects.ideCore)
    api(projects.compilerClient)
    implementation(projects.workerClient)
    implementation(libs.kotlin.stdlib)
    testImplementation(kotlin("test"))
}

val allowedAnalysisClientRuntimeModules =
    setOf(
        ":compiler-client",
        ":ide-core",
        ":platform-bundle",
        ":worker-client",
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains:annotations",
        "org.tomlj:tomlj",
        "org.antlr:antlr4-runtime",
        "org.checkerframework:checker-qual",
    )
val resolvedRuntimeModules =
    configurations.named("runtimeClasspath").map { runtimeClasspath ->
        runtimeClasspath.incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                when (val id = component.id) {
                    is ModuleComponentIdentifier -> "${id.group}:${id.module}"
                    is ProjectComponentIdentifier -> id.projectPath.takeUnless { it == project.path }
                    else -> null
                }
            }.sorted()
    }

val assertAnalysisClientRuntime = tasks.register<AssertAnalysisClientRuntime>("assertAnalysisClientRuntime") {
    group = "verification"
    description = "Fails when the reusable analysis client resolves K2 or platform implementation dependencies."
    allowedRuntimeModules.set(allowedAnalysisClientRuntimeModules)
    forbiddenRuntimeModuleFragments.set(
        setOf(
            "analysis-api",
            "intellij",
            "kotlin-compiler",
            "kotlin-fir",
            "kotlin-psi",
            "low-level-api-fir",
            "symbol-light-classes",
            "kotlinx-coroutines",
            "minecraft",
            "neoforge",
            "architectury",
        ),
    )
    runtimeModules.set(resolvedRuntimeModules)
}

tasks.check {
    dependsOn(assertAnalysisClientRuntime)
}
