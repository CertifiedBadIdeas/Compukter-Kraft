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

val sharedLayer =
    when {
        name.endsWith("-common") -> "common"
        name.endsWith("-neoforge") -> "neoforge"
        else -> error("minecraft-shared-sources requires a common or NeoForge leaf: $path")
    }
val libs = libsCatalog()
val supportedMinecraftVersions =
    setOf(
        libs.findVersion("minecraft-v1211").get().toString(),
        libs.findVersion("minecraft-v261").get().toString(),
    )
val activeMinecraftVersion =
    providers.gradleProperty("compuktersActiveMinecraftVersion").get().also { activeVersion ->
        check(activeVersion in supportedMinecraftVersions) {
            "unsupported compuktersActiveMinecraftVersion '$activeVersion'; expected one of ${supportedMinecraftVersions.sorted()}"
        }
    }
val usesCanonicalSharedSources = buildContext().minecraftVersion == activeMinecraftVersion
val minecraftProjectDirectory = rootProject.layout.projectDirectory.dir("modules/minecraft")
val sharedProjectDirectory = minecraftProjectDirectory.dir("shared/$sharedLayer")

fun attachSharedSourceSet(sourceSet: SourceSet) {
    val sourceSetName = sourceSet.name
    val canonicalRoot = sharedProjectDirectory.dir("src/$sourceSetName")

    if (usesCanonicalSharedSources) {
        sourceSet.apply {
            kotlin.srcDir(canonicalRoot.dir("kotlin"))
            resources.srcDir(canonicalRoot.dir("resources"))
        }
        return
    }

    val generatedRoot = layout.buildDirectory.dir("generated/minecraft-shared/$sharedLayer/$sourceSetName")
    val taskNameSegment = sourceSetName.replaceFirstChar(Char::uppercase)
    val syncKotlin =
        tasks.register<Sync>("sync${taskNameSegment}SharedMinecraftKotlin") {
            description = "Mirrors canonical $sharedLayer $sourceSetName Kotlin for the inactive Minecraft target."
            from(canonicalRoot.dir("kotlin"))
            into(generatedRoot.map { it.dir("kotlin") })
        }
    val syncResources =
        tasks.register<Sync>("sync${taskNameSegment}SharedMinecraftResources") {
            description = "Mirrors canonical $sharedLayer $sourceSetName resources for the inactive Minecraft target."
            from(canonicalRoot.dir("resources"))
            into(generatedRoot.map { it.dir("resources") })
        }

    sourceSet.apply {
        kotlin.srcDir(syncKotlin)
        resources.srcDir(syncResources)
    }
}

sourceSets.configureEach {
    if (name == "main" || name == "test" || name == "gameTest") {
        attachSharedSourceSet(this)
    }
}

val verifyMinecraftSourceOwnership =
    tasks.register("verifyMinecraftSourceOwnership") {
        group = "verification"
        description = "Rejects cross-version source roots and classes emitted in Minecraft-owned packages."
        dependsOn(tasks.named(sourceSets.main.get().classesTaskName))
        doLast {
            val ownVersionRoot = projectDir.parentFile.canonicalFile.toPath()
            val versionRoots =
                minecraftProjectDirectory.asFile.listFiles().orEmpty()
                    .filter { it.isDirectory && it.name.startsWith("v") }
                    .map { it.canonicalFile.toPath() }
            val foreignSourceRoots =
                sourceSets
                    .flatMap { it.allSource.srcDirs }
                    .map { it.canonicalFile.toPath() }
                    .filter { sourceRoot ->
                        versionRoots.any { versionRoot -> sourceRoot.startsWith(versionRoot) && versionRoot != ownVersionRoot }
                    }
            check(foreignSourceRoots.isEmpty()) {
                "cross-version source roots configured for $path: ${foreignSourceRoots.sorted()}"
            }

            val minecraftPackageClasses =
                sourceSets.main.get().output.classesDirs.asFileTree.matching {
                    include("net/minecraft/**")
                }.files
            check(minecraftPackageClasses.isEmpty()) {
                "project-owned classes must not be emitted beneath net.minecraft: ${minecraftPackageClasses.sorted()}"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyMinecraftSourceOwnership)
}
