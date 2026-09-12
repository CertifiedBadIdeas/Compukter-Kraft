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

@file:OptIn(InternalRefreshVersionsApi::class)
@file:Suppress("ktlint:standard:property-naming", "PropertyName")

import de.fayard.refreshVersions.core.internal.InternalRefreshVersionsApi

check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_25)) {
    "Compukters requires Gradle to run on JDK 25 or newer; current JVM is ${System.getProperty("java.version")}. " +
        "Set JAVA_HOME to a JDK 25 installation and retry."
}

pluginManagement {
    repositories {
        mavenCentral()
        maven("https://maven.architectury.dev/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases/")
        gradlePluginPortal()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

plugins {
    id("de.fayard.refreshVersions") version "0.60.6"
}

includeBuild("build-scripts")
val modulesDir = rootDir.resolve("modules")
val commonModulesDir = modulesDir.resolve("common")
val minecraftModulesDir = modulesDir.resolve("minecraft")

fun include(
    path: String,
    dir: File,
) {
    include(path)
    project(":$path").apply {
        projectDir = dir.resolve(path)
    }
}

include("native-runtime-api")
project(":native-runtime-api").projectDir = commonModulesDir.resolve("native-runtime/api")
include("native-runtime-ffm")
project(":native-runtime-ffm").projectDir = commonModulesDir.resolve("native-runtime/ffm")
include("native-runtime-jni")
project(":native-runtime-jni").projectDir = commonModulesDir.resolve("native-runtime/jni")
include("platform-bundle", commonModulesDir)
include("platform-k2", commonModulesDir)
include("compiler-artifact", commonModulesDir)
include("worker-client", commonModulesDir)
include("tooling-runtime", commonModulesDir)
include("compiler-client", commonModulesDir)
include("compiler-runtime", commonModulesDir)
include("compiler-k2-engine", commonModulesDir)
include("compiler-k2", commonModulesDir)
include("guest-platform", commonModulesDir)
include("ide-core", commonModulesDir)
include("ide-kotlin-formatter", commonModulesDir)
include("ide-analysis-client", commonModulesDir)
include("ide-analysis-k2", commonModulesDir)
include("ide-client", commonModulesDir)
include("playground", commonModulesDir)
include("core", commonModulesDir)

val v26_1Dir = minecraftModulesDir.resolve("v26_1")
include("v26_1-common", v26_1Dir)
include("v26_1-neoforge", v26_1Dir)

val v1_21_1Dir = minecraftModulesDir.resolve("v1_21_1")
include("v1_21_1-common", v1_21_1Dir)
include("v1_21_1-neoforge", v1_21_1Dir)

rootProject.name = "Compukters"
