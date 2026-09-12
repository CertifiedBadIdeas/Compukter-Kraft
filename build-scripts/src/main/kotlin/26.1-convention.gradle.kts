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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("kotlin-convention")
    id("dev.architectury.loom-no-remap")
    id("architectury-plugin")
}

val libVersion = "v261"

val libs = the<VersionCatalogsExtension>().named("libs")
val minecraftVersion = libs.findVersion("minecraft-$libVersion").get().toString()
val minecraftLibrary = libs.findLibrary("minecraft-$libVersion").get()

setBuildContext(
    versionKey = libVersion,
    minecraftVersion = minecraftVersion,
)

// Common (Architectury) module: archive version = "<mc>-<modVersion>".
// Loader-specific conventions override this with "<mc>-<loader>-<modVersion>".
version = computeModVersion()

architectury {
    minecraft = minecraftVersion
}

dependencies {
    minecraft(minecraftLibrary)

    testImplementation(kotlin("test"))
    testImplementation(libs.findLibrary("kotlinx-coroutines-test").get())
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
