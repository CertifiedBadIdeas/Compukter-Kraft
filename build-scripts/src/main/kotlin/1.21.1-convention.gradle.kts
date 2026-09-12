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
    id("kotlin-convention")
    id("dev.architectury.loom")
    id("architectury-plugin")
}

val libVersion = "v1211"
val libs = the<VersionCatalogsExtension>().named("libs")
val minecraftVersion = libs.findVersion("minecraft-$libVersion").get().toString()

setBuildContext(
    versionKey = libVersion,
    minecraftVersion = minecraftVersion,
)

version = computeModVersion()

architectury {
    minecraft = minecraftVersion
}

dependencies {
    minecraft(libs.findLibrary("minecraft-$libVersion").get())
    mappings(
        loom.layered {
            officialMojangMappings()
            parchment(libs.findLibrary("parchment-$libVersion").get())
        },
    )
    testImplementation(kotlin("test"))
    testImplementation(libs.findLibrary("kotlinx-coroutines-test").get())
}
