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
project(":native-runtime-api").projectDir = modulesDir.resolve("native-runtime/api")
include("native-runtime-ffm")
project(":native-runtime-ffm").projectDir = modulesDir.resolve("native-runtime/ffm")
include("native-runtime-jni")
project(":native-runtime-jni").projectDir = modulesDir.resolve("native-runtime/jni")
include("platform-bundle", modulesDir)
include("platform-k2", modulesDir)
include("compiler-artifact", modulesDir)
include("worker-client", modulesDir)
include("tooling-runtime", modulesDir)
include("compiler-client", modulesDir)
include("compiler-runtime", modulesDir)
include("compiler-k2-engine", modulesDir)
include("compiler-k2", modulesDir)
include("guest-platform", modulesDir)
include("ide-core", modulesDir)
include("ide-kotlin-formatter", modulesDir)
include("ide-analysis-client", modulesDir)
include("ide-analysis-k2", modulesDir)
include("ide-client", modulesDir)
include("playground", modulesDir)
include("core", modulesDir)

val v26_1Dir = modulesDir.resolve("v26_1")
include("v26_1-common", v26_1Dir)
include("v26_1-neoforge", v26_1Dir)

rootProject.name = "Compukters"
