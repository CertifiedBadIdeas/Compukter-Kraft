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
    alias(libs.plugins.v261)
    alias(libs.plugins.commonConvention)
}

architectury {
    common("neoforge")
}

val bootArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/system/boot.cpkt")
val shellArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/system/shell.cpkt")
val kotlincArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/system/kotlinc.cpkt")
val editArtifact = project(":compiler-k2").layout.buildDirectory.file("generated/system/edit.cpkt")
val toolingRuntimeBundle = project(":tooling-runtime").layout.buildDirectory.file("distributions/k2-tooling-workers.zip.zst")
val toolingRuntimeManifest = project(":tooling-runtime").layout.buildDirectory.file("distributions/k2-tooling-workers.bundle")

tasks.processResources {
    dependsOn(
        ":compiler-k2:generateBootArtifact",
        ":compiler-k2:generateShellArtifact",
        ":compiler-k2:generateKotlincArtifact",
        ":compiler-k2:generateEditArtifact",
        ":tooling-runtime:toolingRuntimeBundle",
        ":tooling-runtime:toolingRuntimeManifest",
    )
    from(bootArtifact) {
        into("system/programs")
        rename { "boot" }
    }
    from(shellArtifact) {
        into("system/programs")
        rename { "shell" }
    }
    from(kotlincArtifact) {
        into("system/programs")
        rename { "kotlinc" }
    }
    from(editArtifact) {
        into("system/programs")
        rename { "edit" }
    }
    from(toolingRuntimeBundle) {
        into("tooling/workers")
        rename { "k2-tooling-workers.zip.zst" }
    }
    from(toolingRuntimeManifest) {
        into("tooling/workers")
        rename { "k2-tooling-workers.bundle" }
    }
    from(rootProject.layout.projectDirectory.file("licenses/project/Apache-2.0.txt")) {
        into("META-INF/licenses")
        rename { "Compukters-Apache-2.0.txt" }
    }
    from(rootProject.layout.projectDirectory.file("NOTICE")) {
        into("META-INF")
        rename { "NOTICE.txt" }
    }
    from(rootProject.layout.projectDirectory.file("THIRD-PARTY-NOTICES.md")) {
        into("META-INF")
    }
    from(rootProject.layout.projectDirectory.dir("licenses/kotlin/v2.4.10")) {
        into("META-INF/licenses/kotlin/v2.4.10")
    }
    from(rootProject.layout.projectDirectory.file("licenses/rust/generic-array-0.14.7-LICENSE.txt")) {
        into("META-INF/licenses/rust")
    }
    from(rootProject.layout.projectDirectory.file("licenses/jvm/antlr4-runtime-4.11.1-BSD-3-Clause.txt")) {
        into("META-INF/licenses/jvm")
    }
    from(rootProject.layout.projectDirectory.file("licenses/jvm/checker-qual-3.21.2-MIT.txt")) {
        into("META-INF/licenses/jvm")
    }
    from(rootProject.layout.projectDirectory.file("licenses/distribution-components.tsv")) {
        into("META-INF/licenses")
    }
}
