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

@file:Suppress("PropertyName")

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.tukaani.xz.XZInputStream
import java.net.URLClassLoader
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    idea
    alias(libs.plugins.v261)
    alias(libs.plugins.neoforgeConvention)
    alias(libs.plugins.metadataConvention)
}

val gameTest by sourceSets.creating
val redstoneConformanceArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/redstone.cpkt")
val soundConformanceArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/conformance/sound.cpkt")

kotlin.target.compilations.named(gameTest.name) {
    associateWith(kotlin.target.compilations.getByName("main"))
}

tasks.named<ProcessResources>(gameTest.processResourcesTaskName) {
    dependsOn(":compiler-k2:generateRedstoneConformanceArtifact")
    dependsOn(":compiler-k2:generateSoundConformanceArtifact")
    from(redstoneConformanceArtifact) {
        into("fixtures")
    }
    from(soundConformanceArtifact) {
        into("fixtures")
    }
    from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/filesystem-write.cpkt")) {
        into("fixtures")
    }
    from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/filesystem-write-alternate.cpkt")) {
        into("fixtures")
    }
    from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/filesystem-compilation-source.cpkt")) {
        into("fixtures")
    }
    from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/filesystem-read.cpkt")) {
        into("fixtures")
    }
    from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/process-terminal-child.cpkt")) {
        into("fixtures")
    }
    from(rootProject.layout.projectDirectory.file("host/compukter-vm/tests/fixtures/process-install-rom-executable.cpkt")) {
        into("fixtures")
    }
}

configurations[gameTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[gameTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
gameTest.compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
gameTest.runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output

tasks.named("check") {
    dependsOn(gameTest.classesTaskName)
}

val verifyGameTestRunIsolation =
    tasks.register("verifyGameTestRunIsolation") {
        group = "verification"
        description = "Checks that GameTest classes are visible only to the GameTest run."
        doLast {
            val gameTestFiles = gameTest.output.files.map(File::getCanonicalFile).toSet()

            fun effectiveModFiles(runName: String): Set<File> {
                val run = loom.runs.named(runName).get()
                val mods = if (run.mods.isEmpty()) loom.mods else run.mods
                return mods.flatMap { it.modFiles.files }.map(File::getCanonicalFile).toSet()
            }

            listOf("client", "client2", "client3", "server").forEach { runName ->
                val leaked = effectiveModFiles(runName).intersect(gameTestFiles)
                check(leaked.isEmpty()) { "GameTest output leaked into $runName: $leaked" }
            }
            check(effectiveModFiles("gameTestServer").intersect(gameTestFiles).isNotEmpty()) {
                "GameTest output is missing from gameTestServer"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyGameTestRunIsolation)
}

tasks.configureEach {
    if (name == "runGameTestServer") {
        dependsOn(gameTest.classesTaskName)
    }
}

loom {
    // Generic client / client2 / server runs are declared in the
    // `loom-runs-convention` precompiled script plugin (build-scripts).
    // Only neoforge-specific runs live here.
    runs {
        register("gameTestServer") {
            server()
            forgeTemplate("gameTestServer")
            runDir("run/gameTestServer")
            property("neoforge.enabledGameTestNamespaces", "compukters")
            ideConfigGenerated(true)
            vmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
            mods {
                maybeCreate("main").apply {
                    sourceSet("main")
                    sourceSet("main", projects.v261Common.path)
                    sourceSet(gameTest.name)
                }
            }
        }
    }

    mods {
        maybeCreate("main").apply {
            sourceSet("main", project(projects.v261Common.path))
        }
    }
}

dependencies {
    common(project(path = projects.v261Common.path)) { isTransitive = false }
    shadowBundle(project(path = projects.v261Common.path, configuration = "transformProductionNeoForge"))
    testImplementation(project(path = projects.v261Common.path))
    implementation(projects.nativeRuntimeFfm)
    shadowBundle(project(path = projects.nativeRuntimeFfm.path)) { isTransitive = false }
    implementation(projects.platformBundle)
    shadowBundle(project(path = projects.platformBundle.path)) { isTransitive = false }

    add(gameTest.implementationConfigurationName, sourceSets.main.get().output)
    add(gameTest.implementationConfigurationName, project(path = projects.v261Common.path))
}

tasks.test {
    filter.excludeTestsMatching("ru.lazyhat.compukters.impl.ide.performance.IdeVisibleLatencyPerformanceTest")
}

val visibleIdeLatencyPerformanceTest =
    tasks.register<Test>("visibleIdeLatencyPerformanceTest") {
        description = "Runs machine-sensitive first-visible-frame IDE latency SLO checks."
        group = "verification"
        useJUnitPlatform()
        dependsOn(tasks.named(sourceSets.test.get().classesTaskName), ":v26_1-common:processResources")
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        maxHeapSize = "512m"
        filter {
            includeTestsMatching("ru.lazyhat.compukters.impl.ide.performance.IdeVisibleLatencyPerformanceTest")
            isFailOnNoMatchingTests = true
        }
        mustRunAfter(tasks.test)
    }

data class TerminalFontBuildSpec(
    val id: String,
    val taskStem: String,
    val displayName: String,
    val sourceDescription: String,
    val bdfPath: String,
    val cellHeight: Int,
    val ascent: Int,
    val replacementCodePoint: Int,
    val selectedRanges: List<String>,
)

fun registerTerminalFont(spec: TerminalFontBuildSpec) {
    val bdf = rootProject.layout.projectDirectory.file(spec.bdfPath)
    val fontJson = layout.projectDirectory.file("src/main/resources/assets/compukters/font/terminal/${spec.id}.json")
    val atlas = layout.projectDirectory.file("src/main/resources/assets/compukters/textures/font/terminal/${spec.id}.png")
    val manifest =
        layout.projectDirectory.file("src/main/resources/assets/compukters/font/terminal/${spec.id}-codepoints.txt")
    val coverage =
        layout.projectDirectory.file(
            "src/main/kotlin/ru/lazyhat/compukters/impl/terminal/${spec.taskStem}FontCoverage.kt",
        )
    val coverageName = "${spec.id.uppercase()}_SUPPORTED_CODE_POINTS"
    val generate =
        tasks.register<GenerateTerminalBitmapFont>("generate${spec.taskStem}TerminalFont") {
            description = "Regenerates committed ${spec.displayName} terminal font resources from the pinned BDF."
            group = "build setup"
            bdfFile.set(bdf)
            displayName.set(spec.displayName)
            resourceName.set(spec.id)
            coveragePropertyName.set(coverageName)
            sourceDescription.set(spec.sourceDescription)
            cellWidth.set(6)
            cellHeight.set(spec.cellHeight)
            ascent.set(spec.ascent)
            descent.set(spec.cellHeight - spec.ascent)
            replacementCodePoint.set(spec.replacementCodePoint)
            selectedRanges.set(spec.selectedRanges)
            fontJsonFile.set(fontJson)
            atlasPngFile.set(atlas)
            manifestFile.set(manifest)
            coverageKotlinFile.set(coverage)
        }
    val verify =
        tasks.register<VerifyTerminalBitmapFont>("verify${spec.taskStem}TerminalFont") {
            description = "Rejects committed ${spec.displayName} terminal font resources that drifted from its BDF."
            group = "verification"
            regenerationTaskName.set(generate.name)
            bdfFile.set(bdf)
            displayName.set(spec.displayName)
            resourceName.set(spec.id)
            coveragePropertyName.set(coverageName)
            sourceDescription.set(spec.sourceDescription)
            cellWidth.set(6)
            cellHeight.set(spec.cellHeight)
            ascent.set(spec.ascent)
            descent.set(spec.cellHeight - spec.ascent)
            replacementCodePoint.set(spec.replacementCodePoint)
            selectedRanges.set(spec.selectedRanges)
            fontJsonFile.set(fontJson)
            atlasPngFile.set(atlas)
            manifestFile.set(manifest)
            coverageKotlinFile.set(coverage)
            mustRunAfter(generate)
        }
    tasks.named("check") { dependsOn(verify) }
}

registerTerminalFont(
    TerminalFontBuildSpec(
        id = "cozette",
        taskStem = "Cozette",
        displayName = "Cozette",
        sourceDescription = "pinned Cozette v.1.30.0",
        bdfPath = "tools/fonts/cozette/v.1.30.0/cozette.bdf",
        cellHeight = 13,
        ascent = 10,
        replacementCodePoint = 0xFFFD,
        selectedRanges =
            listOf("32..126", "160..255", "1024..1279", "8592..8703", "9472..9599", "9600..9631", "65533..65533"),
    ),
)
registerTerminalFont(
    TerminalFontBuildSpec(
        id = "dina",
        taskStem = "Dina",
        displayName = "Dina",
        sourceDescription = "pinned Dina v2.92 Regular 6pt",
        bdfPath = "tools/fonts/dina/v2.92/Dina_r400-6.bdf",
        cellHeight = 10,
        ascent = 8,
        replacementCodePoint = '?'.code,
        selectedRanges = listOf("32..126", "160..255"),
    ),
)
registerTerminalFont(
    TerminalFontBuildSpec(
        id = "proggy_tiny",
        taskStem = "ProggyTiny",
        displayName = "ProggyTiny",
        sourceDescription = "pinned ProggyTiny commit 139ec08a",
        bdfPath = "tools/fonts/proggy/139ec08a/ProggyTiny.bdf",
        cellHeight = 10,
        ascent = 8,
        replacementCodePoint = '?'.code,
        selectedRanges = listOf("32..126", "160..255"),
    ),
)

val nativeOs =
    when {
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("linux") -> "linux"
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("windows") -> "windows"
        System.getProperty("os.name").trim().lowercase(Locale.ROOT).startsWith("mac") -> "macos"
        else -> error("unsupported native build operating system: ${System.getProperty("os.name")}")
    }
val nativeArch =
    when (System.getProperty("os.arch").trim().lowercase(Locale.ROOT)) {
        "amd64", "x86_64" -> "x86_64"
        "arm64", "aarch64" -> "aarch64"
        else -> error("unsupported native build architecture: ${System.getProperty("os.arch")}")
    }
val nativeFilename =
    when (nativeOs) {
        "linux" -> "libcompukter_ffi.so"
        "windows" -> "compukter_ffi.dll"
        "macos" -> "libcompukter_ffi.dylib"
        else -> error("unreachable native build operating system: $nativeOs")
    }
val nativeResourcePath = "META-INF/natives/$nativeOs/$nativeArch/$nativeFilename"
val releaseRuntimeMode =
    providers.gradleProperty("compukterRuntimeBundleDir").isPresent ||
        requestsUniversalReleaseBuild(gradle.startParameter.taskNames)
val expectedPackagedNativeResources = expectedNativeResources(releaseRuntimeMode, nativeResourcePath)
val productionJar = tasks.named<ShadowJar>("shadowJar")
val verifyPackagedCompukterFfi =
    tasks.register("verifyPackagedCompukterFfi") {
        description = "Checks the contents of the production NeoForge jar."
        group = "verification"
        dependsOn(productionJar)
        inputs.file(productionJar.flatMap { it.archiveFile })
        inputs.property("expectedNativeResources", expectedPackagedNativeResources)
        doLast {
            val archive = productionJar.get().archiveFile.get().asFile
            val entries =
                ZipFile(archive).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filterNot { it.isDirectory }
                        .map { it.name }
                        .toList()
                }
            val nativeEntries = entries.filter { it.startsWith("META-INF/natives/") }
            validateNativeResources(nativeEntries, expectedPackagedNativeResources)
            check(entries.count { it == "META-INF/neoforge.mods.toml" } == 1) {
                "expected exactly one META-INF/neoforge.mods.toml in ${archive.name}"
            }
            val toolingResources = entries.filter { it.startsWith("tooling/workers/") }.sorted()
            val toolingManifestResource = "tooling/workers/k2-tooling-workers.bundle"
            val expectedToolingResources = listOf(ArtifactSizeReport.TOOLING_RESOURCE, toolingManifestResource).sorted()
            check(toolingResources == expectedToolingResources) {
                "expected exactly $expectedToolingResources in ${archive.name}, found $toolingResources"
            }
            check(
                entries.none {
                    it == "compiler/worker/compiler-k2-worker.zip" ||
                        it == "analysis/worker/ide-analysis-k2-worker.zip"
                },
            ) {
                "legacy worker archives leaked into ${archive.name}"
            }
            listOf(
                "META-INF/licenses/Compukters-Apache-2.0.txt",
                "META-INF/licenses/Compukters-Textures-CC-BY-4.0.txt",
                "META-INF/licenses/Compukters-Textures-PROVENANCE.txt",
                "META-INF/licenses/Material-Symbols-Apache-2.0.txt",
                "META-INF/licenses/Material-Symbols-PROVENANCE.txt",
                "META-INF/licenses/jvm/antlr4-runtime-4.11.1-BSD-3-Clause.txt",
                "META-INF/licenses/jvm/checker-qual-3.21.2-MIT.txt",
                "META-INF/licenses/jvm/xz-java-1.10-0BSD.txt",
                "META-INF/NOTICE.txt",
                "META-INF/THIRD-PARTY-NOTICES.md",
            ).forEach { required ->
                check(entries.count { it == required } == 1) {
                    "expected exactly one $required in ${archive.name}"
                }
            }
            val nestedToolingEntries = linkedSetOf<String>()
            var toolingManifestBytes: ByteArray? = null
            var externalToolingManifestBytes: ByteArray? = null
            ZipFile(archive).use { zip ->
                externalToolingManifestBytes =
                    zip.getInputStream(checkNotNull(zip.getEntry(toolingManifestResource))).use { it.readBytes() }
                val worker = checkNotNull(zip.getEntry(ArtifactSizeReport.TOOLING_RESOURCE)) {
                    "shared tooling bundle is missing from ${archive.name}"
                }
                ZipInputStream(XZInputStream(zip.getInputStream(worker))).use { nested ->
                    while (true) {
                        val entry = nested.nextEntry ?: break
                        if (!entry.isDirectory) {
                            check(nestedToolingEntries.add(entry.name)) {
                                "duplicate shared tooling entry: ${entry.name}"
                            }
                            if (entry.name == "tooling.bundle") toolingManifestBytes = nested.readBytes()
                        }
                        nested.closeEntry()
                    }
                }
            }
            check(checkNotNull(toolingManifestBytes).contentEquals(checkNotNull(externalToolingManifestBytes))) {
                "external tooling manifest does not match the solid carrier"
            }
            listOf(
                "META-INF/licenses/Compukters-Apache-2.0.txt",
                "META-INF/NOTICE.txt",
                "META-INF/THIRD-PARTY-NOTICES.md",
            ).forEach { required ->
                check(nestedToolingEntries.count { it == required } == 1) {
                    "expected exactly one $required in packaged shared tooling bundle"
                }
            }
            listOf("tooling.bundle", "manifests/compiler.payload", "manifests/analysis.payload").forEach { required ->
                check(nestedToolingEntries.count { it == required } == 1) {
                    "$required is missing or duplicated in packaged shared tooling bundle"
                }
            }
            val toolingManifest = checkNotNull(toolingManifestBytes).decodeToString().lineSequence().toList()
            check(toolingManifest.firstOrNull() == "format=1") { "shared tooling manifest format is invalid" }
            check(toolingManifest.count { it.startsWith("profile=compiler\t") } == 1) {
                "shared tooling manifest must contain exactly one compiler profile"
            }
            check(toolingManifest.count { it.startsWith("profile=analysis\t") } == 1) {
                "shared tooling manifest must contain exactly one analysis profile"
            }
            val manifestRuntimeFiles =
                toolingManifest
                    .filter { it.startsWith("file=") }
                    .map { it.removePrefix("file=").substringBefore('\t') }
                    .sorted()
            val nestedRuntimeFiles =
                nestedToolingEntries.filter { path ->
                    path.endsWith(".jar") &&
                        (path.startsWith("common/lib/") ||
                            path.startsWith("compiler/lib/") ||
                            path.startsWith("analysis/lib/"))
                }.sorted()
            check(manifestRuntimeFiles == nestedRuntimeFiles) {
                "shared tooling manifest runtime inventory does not match its archive"
            }
            val inventory = rootProject.file("licenses/distribution-components.tsv")
            check(inventory.isFile) { "distribution component inventory is missing: $inventory" }
            val expectedNestedLibraries =
                inventory
                    .readLines()
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { it.split('\t') }
                    .filter { it[0] == "jvm-outer" }
                    .map { (_, component, version, _) -> "$component-$version.jar" }
                    .sorted()
            val actualNestedLibraries =
                entries
                    .filter { it.startsWith("META-INF/jars/") && it.endsWith(".jar") }
                    .map { it.removePrefix("META-INF/jars/") }
                    .sorted()
            check(actualNestedLibraries == expectedNestedLibraries) {
                "nested JVM library inventory mismatch: expected $expectedNestedLibraries, found $actualNestedLibraries"
            }
            val expectedToolingLibraries =
                inventory
                    .readLines()
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { it.split('\t') }
                    .filter { it[0] == "jvm-worker" || it[0] == "jvm-analysis-worker" }
                    .map { (_, component, version, _) -> "$component-$version.jar" }
                    .distinct()
                    .sorted()
            val toolingProjectPrefixes =
                listOf(
                    "compiler-artifact-",
                    "compiler-client-",
                    "compiler-k2-",
                    "guest-platform-",
                    "ide-analysis-client-",
                    "ide-analysis-k2-",
                    "ide-core-",
                    "platform-bundle-",
                    "platform-k2-",
                    "worker-client-",
                )
            val actualToolingLibraries =
                nestedRuntimeFiles
                    .map { it.substringAfterLast('/') }
                    .filterNot { name -> toolingProjectPrefixes.any(name::startsWith) }
                    .sorted()
            check(actualToolingLibraries == expectedToolingLibraries) {
                "shared tooling JVM inventory mismatch: expected $expectedToolingLibraries, found $actualToolingLibraries"
            }
            check(
                actualToolingLibraries.none { name ->
                    "embeddable" in name || "scripting-compiler" in name || "scripting-compiler-impl" in name
                },
            ) {
                "embeddable or scripting compiler distribution leaked into shared tooling"
            }
            check(entries.none { it.startsWith("dev/architectury/") }) {
                "Architectury runtime classes leaked into ${archive.name}"
            }
            val forbiddenIdeClassPrefixes =
                listOf(
                    "com/intellij/",
                    "dev/architectury/",
                    "org/jetbrains/kotlin/analysis/",
                    "org/jetbrains/kotlin/fir/",
                    "org/jetbrains/kotlin/idea/",
                    "org/jetbrains/kotlin/psi/",
                    "ru/lazyhat/compukters/ide/analysis/k2/",
                )
            check(entries.none { entry -> forbiddenIdeClassPrefixes.any(entry::startsWith) || entry.contains("kotlin/compiler") }) {
                "forbidden IDE/platform runtime classes leaked into ${archive.name}"
            }
            ZipFile(archive).use { outer ->
                entries
                    .filter { it.startsWith("META-INF/jars/") && it.endsWith(".jar") }
                    .forEach { nestedName ->
                        val nestedEntry = checkNotNull(outer.getEntry(nestedName))
                        ZipInputStream(outer.getInputStream(nestedEntry)).use { nested ->
                            while (true) {
                                val entry = nested.nextEntry ?: break
                                check(forbiddenIdeClassPrefixes.none(entry.name::startsWith) && !entry.name.contains("kotlin/compiler")) {
                                    "forbidden IDE/platform class ${entry.name} leaked through $nestedName"
                                }
                                nested.closeEntry()
                            }
                        }
                    }
            }
            listOf(
                "ru/lazyhat/compukters/ide/client/target/IdeTargetPort.class",
                "ru/lazyhat/compukters/ide/project/ProjectCatalog.class",
                "ru/lazyhat/compukters/ide/analysis/controller/AnalysisClient.class",
                "ru/lazyhat/compukters/platform/bundle/PackagedPlatformBundleLoader.class",
                "ru/lazyhat/compukters/worker/payload/PackagedWorkerPayload.class",
            ).forEach { required ->
                check(entries.count { it == required } == 1) {
                    "expected exactly one $required in ${archive.name}"
                }
            }
            val forbiddenIdeLibraries =
                listOf(
                    "analysis-api",
                    "architectury-",
                    "intellij-",
                    "kotlin-compiler",
                    "kotlin-fir",
                    "kotlin-psi",
                    "low-level-api-fir",
                    "symbol-light-classes",
                )
            check(
                entries.none { entry ->
                    entry.startsWith("META-INF/jars/") &&
                        forbiddenIdeLibraries.any(entry.substringAfterLast('/')::startsWith)
                },
            ) {
                "IDE implementation/platform dependencies leaked into ${archive.name}"
            }
            check(entries.none { it.contains("ComputerBlockGameTest") }) {
                "GameTest classes leaked into ${archive.name}"
            }
            check(entries.none { it.startsWith("fixtures/") }) {
                "GameTest artifacts leaked into ${archive.name}"
            }
            check("ru/lazyhat/compukters/minecraft/computer/ComputerBlock.class" in entries) {
                "common computer classes are missing from ${archive.name}"
            }
            check("ru/lazyhat/compukters/impl/computer/NeoForgeComputerBlockEntity.class" in entries) {
                "NeoForge computer classes are missing from ${archive.name}"
            }
            check(
                listOf("boot", "shell", "kotlinc", "edit", "vmbench")
                    .all { program -> "system/programs/$program" in entries },
            ) {
                "packaged extensionless system programs are missing from ${archive.name}"
            }
            check(entries.none { it.startsWith("system/programs/") && it.endsWith(".cpkt") }) {
                "extension-bearing system program leaked into ${archive.name}"
            }
            check("assets/compukters/items/compukter.json" in entries) {
                "26.1 item model is missing from ${archive.name}"
            }
            listOf(
                "assets/compukters/font/terminal/cozette.json",
                "assets/compukters/font/terminal/cozette-codepoints.txt",
                "assets/compukters/textures/font/terminal/cozette.png",
                "META-INF/licenses/Cozette-MIT.txt",
                "META-INF/licenses/Cozette-PROVENANCE.txt",
                "assets/compukters/font/terminal/dina.json",
                "assets/compukters/font/terminal/dina-codepoints.txt",
                "assets/compukters/textures/font/terminal/dina.png",
                "META-INF/licenses/Dina-LICENSE.txt",
                "META-INF/licenses/Dina-PROVENANCE.txt",
                "assets/compukters/font/terminal/proggy_tiny.json",
                "assets/compukters/font/terminal/proggy_tiny-codepoints.txt",
                "assets/compukters/textures/font/terminal/proggy_tiny.png",
                "META-INF/licenses/Proggy-MIT.txt",
                "META-INF/licenses/Proggy-PROVENANCE.txt",
                "assets/compukters/textures/gui/ide_toolbar.png",
            ).forEach { required ->
                check(required in entries) { "$required is missing from ${archive.name}" }
            }
            check("assets/compukters/textures/gui/term_font.png" !in entries) {
                "legacy terminal font atlas leaked into ${archive.name}"
            }
            check(entries.none { it.contains("Spleen", ignoreCase = true) }) {
                "legacy Spleen font attribution leaked into ${archive.name}"
            }
            check("assets/compukters/models/item/compukter.json" !in entries) {
                "legacy item model leaked into ${archive.name}"
            }
            check("pack.mcmeta" !in entries) {
                "legacy pack.mcmeta leaked into ${archive.name}"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyPackagedCompukterFfi)
}

tasks.named("buildProductionUniversalJar") {
    dependsOn(verifyPackagedCompukterFfi)
}

val reportProductionArtifactSize =
    tasks.register("reportProductionArtifactSize") {
        description = "Reports the exact classified size of the production NeoForge jar."
        group = "verification"
        dependsOn(productionJar, verifyPackagedCompukterFfi)
        val archive = productionJar.flatMap { it.archiveFile }
        val report = layout.buildDirectory.file("reports/artifact-size/production-artifact-size.tsv")
        inputs.file(archive)
        outputs.file(report)
        doLast {
            val result = ArtifactSizeReport.write(archive.get().asFile.toPath(), report.get().asFile.toPath())
            println(result.render())
        }
    }

tasks.named("buildProductionUniversalJar") {
    dependsOn(reportProductionArtifactSize)
}

fun captureReleaseGit(vararg arguments: String): String {
    val process =
        ProcessBuilder("git", *arguments)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().readText().trimEnd()
    check(process.waitFor() == 0) { "git ${arguments.toList()} failed: $output" }
    return output
}

val verifyUniversalReleaseState =
    tasks.register("verifyUniversalReleaseState") {
        description = "Requires the exact clean tagged state used to assemble a universal release."
        group = "verification"
        doLast {
            validateUniversalReleaseState(
                UniversalReleaseState(
                    version = rootProject.version.toString(),
                    runtimeBundlesConfigured = releaseRuntimeMode,
                    headTags =
                        captureReleaseGit("tag", "--points-at", "HEAD")
                            .lineSequence()
                            .filter(String::isNotBlank)
                            .toSet(),
                    worktreeStatus = captureReleaseGit("status", "--porcelain"),
                    submoduleStatus = captureReleaseGit("submodule", "status", "--recursive"),
                ),
            )
        }
    }

tasks.register("buildReleaseUniversalJar") {
    description = "Builds and verifies the clean tagged NeoForge release with Linux and Windows Runtime natives."
    group = "build"
    dependsOn(
        verifyUniversalReleaseState,
        verifyPackagedCompukterFfi,
        ":native-runtime-ffm:packagedNativeIntegrationTest",
    )
}

val verifyNeoForgeRuntimeDependencies =
    tasks.register("verifyNeoForgeRuntimeDependencies") {
        description = "Rejects Architectury mod runtime and in-process K2/IntelliJ dependencies."
        group = "verification"
        val runtimeClasspath = configurations.named("runtimeClasspath")
        inputs.files(runtimeClasspath)
        doLast {
            val forbidden =
                runtimeClasspath
                    .get()
                    .incoming
                    .resolutionResult
                    .allComponents
                    .mapNotNull { component ->
                        when (val id = component.id) {
                            is ModuleComponentIdentifier -> "${id.group}:${id.module}"
                            is ProjectComponentIdentifier -> id.projectPath
                            else -> null
                        }
                    }.filter { component ->
                        val normalized = component.lowercase(Locale.ROOT)
                        (normalized.startsWith("dev.architectury:") &&
                            !normalized.contains("architectury-transformer")) ||
                            listOf(
                                ":ide-analysis-k2",
                                "analysis-api",
                                "intellij",
                                "kotlin-compiler",
                                "kotlin-fir",
                                "kotlin-psi",
                                "low-level-api-fir",
                                "symbol-light-classes",
                            ).any(normalized::contains)
                    }.sorted()
            check(forbidden.isEmpty()) {
                "forbidden NeoForge runtime dependencies: ${forbidden.joinToString()}"
            }
        }
    }

val verifyIdeWorkerClassIsolation =
    tasks.register("verifyIdeWorkerClassIsolation") {
        description = "Checks that K2 worker entry points are not loadable from the Minecraft application classpath."
        group = "verification"
        dependsOn(tasks.classes)
        val applicationClasspath = configurations.named("runtimeClasspath")
        inputs.files(applicationClasspath, sourceSets.main.get().output)
        doLast {
            val urls =
                (applicationClasspath.get().files + sourceSets.main.get().output.files)
                    .map { it.toURI().toURL() }
                    .toTypedArray()
            URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
                listOf(
                    "ru.lazyhat.compukters.compiler.worker.server.CompilerWorkerMainKt",
                    "ru.lazyhat.compukters.ide.analysis.k2.server.AnalysisWorkerMainKt",
                ).forEach { mainClass ->
                    val loadable = runCatching { Class.forName(mainClass, false, loader) }.isSuccess
                    check(!loadable) { "$mainClass is loadable from the Minecraft application classpath" }
                }
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyNeoForgeRuntimeDependencies)
    dependsOn(verifyIdeWorkerClassIsolation)
}
