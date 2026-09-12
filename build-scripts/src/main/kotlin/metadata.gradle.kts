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
}

// project.version is already set by the loader-specific convention plugin
// (e.g. neoforge-convention -> computeModArchiveVersion). Reuse it here so the
// mod_version placeholder in mods.toml / fabric.mod.json matches the jar version.
val modVersion = project.version.toString()

val modProperties =
    readVersionedModProperties()
        .toMutableMap()
        .apply {
            this["mod_version"] = modVersion
        }.toMap()

base.archivesName = modProperties.getValue("mod_id").replace(" ", "")

val generateModMetadata =
    tasks.register("generateModMetadata", ProcessResources::class) {
        val replaceProperties = modProperties.toMap()
        val template = rootProject.layout.projectDirectory.file("config/neoforge.mods.toml")
        val intoDir = file("build/generated/resources")

        inputs.properties(replaceProperties)
        inputs.file(template)

        outputs.dir(intoDir)

        from(template) {
            expand(replaceProperties)
            into("META-INF")
        }
        into(intoDir)
    }

tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    dependsOn(generateModMetadata)
}

sourceSets.main {
    resources.srcDir(generateModMetadata.get().outputs.files)
}
