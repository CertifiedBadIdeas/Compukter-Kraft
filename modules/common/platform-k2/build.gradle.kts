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

repositories {
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
        name = "JetBrainsIntellijDependencies"
        content {
            includeGroup("org.jetbrains.kotlin")
            includeGroup("com.intellij.platform")
        }
    }
}

dependencies {
    api(projects.platformBundle)
    implementation(libs.kotlin.compiler)
    compileOnly(libs.kotlin.analysis.api) { isTransitive = false }
    compileOnly(libs.kotlin.analysis.low.level.fir) { isTransitive = false }
    testImplementation(libs.kotlin.analysis.api) { isTransitive = false }
    testImplementation(libs.kotlin.analysis.low.level.fir) { isTransitive = false }
    testImplementation(kotlin("test"))
}
