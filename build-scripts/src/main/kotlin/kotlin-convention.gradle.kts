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

import org.jmailen.gradle.kotlinter.tasks.ConfigurableKtLintTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jmailen.kotlinter")
}

repositories {
    mavenCentral {
        name = "Central"
    }
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
    }
    maven("https://maven.neoforged.net/releases/") {
        name = "NeoForged"
    }
}

group = readAllModProperties().getValue("common_mod_group_id")
version = rootProject.version

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<ConfigurableKtLintTask>().configureEach {
    exclude { it.file.path.contains("build/generated") }
}
