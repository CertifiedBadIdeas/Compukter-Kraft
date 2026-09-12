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

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val label: String? = null,
    val labelNum: Int? = null,
) {
    val base get() = "$major.$minor.$patch"

    override fun toString(): String = if (label != null) "$base-$label.$labelNum" else base
}

val versionRegex = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(Beta|RC)(\d+))?$""")

fun parseVersion(v: String): SemVer {
    val match =
        versionRegex.matchEntire(v)
            ?: error("Invalid version format: '$v'. Expected 'X.Y.Z' or 'X.Y.Z-BetaN' or 'X.Y.Z-RCN'.")
    return SemVer(
        major = match.groupValues[1].toInt(),
        minor = match.groupValues[2].toInt(),
        patch = match.groupValues[3].toInt(),
        label = match.groupValues[4].ifEmpty { null },
        labelNum = match.groupValues[5].ifEmpty { null }?.toInt(),
    )
}

fun git(
    projectDir: File,
    vararg args: String,
) {
    val process =
        ProcessBuilder("git", *args)
            .directory(projectDir)
            .inheritIO()
            .start()
    require(process.waitFor() == 0) { "git ${args.toList()} failed" }
}

fun gitCapture(
    projectDir: File,
    vararg args: String,
): String {
    val process =
        ProcessBuilder("git", *args)
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()
    val output =
        process.inputStream
            .bufferedReader()
            .readText()
            .trimEnd()
    require(process.waitFor() == 0) { "git ${args.toList()} failed: $output" }
    return output
}

/** Highest stable release tag (vX.Y.Z without -BetaN/-RCN) sorted by semver. */
fun latestStableReleaseTag(projectDir: File): String {
    val stableTagRegex = Regex("""^v\d+\.\d+\.\d+$""")
    val tag =
        gitCapture(projectDir, "tag", "--list", "v*", "--sort=-v:refname")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .firstOrNull(stableTagRegex::matches)
            ?: error("No stable release tag found (vX.Y.Z). Run :release first.")
    return tag
}

fun currentBranch(projectDir: File): String = gitCapture(projectDir, "rev-parse", "--abbrev-ref", "HEAD")

fun fixBranchName(v: SemVer): String = "fix/${v.major}.${v.minor}.x"

fun doVersionBump(
    project: Project,
    nextVersion: String,
) {
    val propsFile = project.file("gradle.properties")
    val original = propsFile.readText()
    // Read the current version from the FILE, not from project.version. The in-memory
    // project.version is captured at Gradle configuration time and goes stale whenever a
    // task changes HEAD before bumping (e.g. ':startFixBranch' checks out the release tag
    // and gradle.properties on disk now reflects the tag's older version). Using the
    // in-memory value would produce a no-op String.replace, leaving 'git add' empty and
    // 'git commit' to fail with "nothing to commit".
    val versionLineRegex = Regex("""(?m)^version\s*=\s*(\S+)\s*$""")
    val match =
        versionLineRegex.find(original)
            ?: error("Could not find 'version = ...' line in ${propsFile.relativeTo(project.rootDir)}")
    val currentVersion = match.groupValues[1]
    if (currentVersion == nextVersion) {
        println("Version already at $nextVersion — nothing to bump.")
        return
    }
    val updated = original.replaceRange(match.range, "version = $nextVersion")
    propsFile.writeText(updated)
    git(project.projectDir, "add", "gradle.properties")
    git(project.projectDir, "commit", "-m", "chore: bump version to $nextVersion")
    println("Version bumped: $currentVersion -> $nextVersion")
}

val v = parseVersion(project.version.toString())

tasks.register("currentVersion") {
    group = "release"
    description = "Print the current project version"
    doLast { println("Project version: ${project.version}") }
}

val tagRelease =
    tasks.register("tagRelease") {
        group = "release"
        description = "Validate and tag the current stable version without changing the worktree"
        doLast {
            val currentVersion = project.version.toString()
            val releaseVersion = ReleaseVersion.parse(currentVersion)
            validateTagReleaseState(
                TagReleaseState(
                    version = currentVersion,
                    branch = currentBranch(projectDir),
                    existingTags =
                        gitCapture(projectDir, "tag", "--list")
                            .lineSequence()
                            .filter(String::isNotBlank)
                            .toSet(),
                    worktreeStatus = gitCapture(projectDir, "status", "--porcelain"),
                    submoduleStatus = gitCapture(projectDir, "submodule", "status", "--recursive"),
                ),
            )
            git(projectDir, "tag", "-a", releaseVersion.tag, "-m", "Compukters ${releaseVersion.value}")
            println("Tagged ${releaseVersion.tag}; gradle.properties was not changed.")
            println("Run the release assembly gate before publishing the tag.")
        }
    }

tasks.register("release") {
    group = "release"
    description = "Tag the current stable version without changing the project version"
    doLast {
        val currentVersion = project.version.toString()
        performMainlineRelease(
            state =
                TagReleaseState(
                    version = currentVersion,
                    branch = currentBranch(projectDir),
                    existingTags =
                        gitCapture(projectDir, "tag", "--list")
                            .lineSequence()
                            .filter(String::isNotBlank)
                            .toSet(),
                    worktreeStatus = gitCapture(projectDir, "status", "--porcelain"),
                    submoduleStatus = gitCapture(projectDir, "submodule", "status", "--recursive"),
                ),
            createTag = { tag ->
                git(projectDir, "tag", "-a", tag, "-m", "Compukters $currentVersion")
                println("Tagged $tag")
            },
        )
        println("Release commit tagged; gradle.properties was not changed.")
        println("Run 'buildReleaseUniversalJar' from the repository root before publishing the tag.")
    }
}

tasks.register("bumpAfterRelease") {
    group = "release"
    description = "After publication, bump a tagged stable release to the next development minor"
    doLast {
        val currentVersion = project.version.toString()
        val nextVersion =
            validateBumpAfterReleaseState(
                BumpAfterReleaseState(
                    version = currentVersion,
                    headTags =
                        gitCapture(projectDir, "tag", "--points-at", "HEAD")
                            .lineSequence()
                            .filter(String::isNotBlank)
                            .toSet(),
                    worktreeStatus = gitCapture(projectDir, "status", "--porcelain"),
                    submoduleStatus = gitCapture(projectDir, "submodule", "status", "--recursive"),
                ),
            )
        doVersionBump(project, nextVersion)
    }
}

tasks.register("startFixBranch") {
    group = "release"
    description = "Create fix/X.Y.x branch from the latest stable release tag and bump patch"
    doLast {
        val latestTag = latestStableReleaseTag(projectDir) // e.g. "v0.2.0"
        val baseline = parseVersion(latestTag.removePrefix("v"))
        val branch = fixBranchName(baseline)

        val branchExists = gitCapture(projectDir, "branch", "--list", branch).isNotBlank()
        require(!branchExists) {
            "Branch '$branch' already exists. Check it out and run ':bumpPatch' / ':releaseFix' there."
        }
        val statusOutput = gitCapture(projectDir, "status", "--porcelain")
        require(statusOutput.isEmpty()) {
            "Working tree has uncommitted changes — commit/stash them before starting a fix branch:\n$statusOutput"
        }

        // Branch off the tag, switch onto it, then bump patch on the new branch.
        val originalBranch = currentBranch(projectDir)
        git(projectDir, "checkout", "-b", branch, latestTag)
        val nextVersion = "${baseline.major}.${baseline.minor}.${baseline.patch + 1}"
        try {
            doVersionBump(project, nextVersion)
        } catch (t: Throwable) {
            // Roll back: drop the half-created fix branch and return to the original branch
            // so the user is not stranded on a detached / partially-set-up branch.
            runCatching { git(projectDir, "checkout", originalBranch) }
            runCatching { git(projectDir, "branch", "-D", branch) }
            throw t
        }
        println("Started fix branch '$branch' at $nextVersion (baseline: $latestTag)")
    }
}

tasks.register("releaseFix") {
    group = "release"
    description = "Tag current fix version on the active fix/X.Y.x branch without bumping"
    doLast {
        val currentVersion = project.version.toString()
        val sv = parseVersion(currentVersion)
        require(sv.label == null) {
            "':releaseFix' refuses labeled versions ($currentVersion). Use ':promote' first."
        }
        require(sv.patch > 0) {
            "':releaseFix' expected patch > 0, got $currentVersion. " +
                "Use ':release' on main, or ':startFixBranch' to start a fix line."
        }
        val branch = currentBranch(projectDir)
        val expectedBranch = fixBranchName(sv)
        require(branch == expectedBranch) {
            "':releaseFix' must run on '$expectedBranch' (current: '$branch'). " +
                "Use ':startFixBranch' first or check out the right branch."
        }
        git(projectDir, "tag", "v$currentVersion")
        println("Tagged v$currentVersion on $branch")
        println("Run 'git push --tags' to publish.")
    }
}

tasks.register("bumpPatch") {
    group = "release"
    description = "Bump patch, clear label (${project.version} -> ${v.major}.${v.minor}.${v.patch + 1})"
    doLast {
        val v = parseVersion(project.version.toString())
        doVersionBump(project, "${v.major}.${v.minor}.${v.patch + 1}")
    }
}

tasks.register("bumpMinor") {
    group = "release"
    description = "Bump minor, reset patch and label (${project.version} -> ${v.major}.${v.minor + 1}.0)"
    doLast {
        val v = parseVersion(project.version.toString())
        doVersionBump(project, "${v.major}.${v.minor + 1}.0")
    }
}

tasks.register("bumpMajor") {
    group = "release"
    description = "Bump major, reset all (${project.version} -> ${v.major + 1}.0.0)"
    doLast {
        val v = parseVersion(project.version.toString())
        doVersionBump(project, "${v.major + 1}.0.0")
    }
}

tasks.register("bumpBeta") {
    val next =
        when (v.label) {
            null -> "${v.base}-Beta1"
            "Beta" -> "${v.base}-Beta${v.labelNum!! + 1}"
            else -> null
        }
    group = "release"
    description =
        if (next !=
            null
        ) {
            "Add/increment Beta (${project.version} -> $next)"
        } else {
            "Add/increment Beta (unavailable: current label is '${v.label}')"
        }
    doLast {
        val v = parseVersion(project.version.toString())
        val next =
            when (v.label) {
                null -> "${v.base}-Beta1"
                "Beta" -> "${v.base}-Beta${v.labelNum!! + 1}"
                else -> error("Cannot bump Beta: current label is '${v.label}'. Use promote first.")
            }
        doVersionBump(project, next)
    }
}

tasks.register("bumpRC") {
    val next =
        when (v.label) {
            null, "Beta" -> "${v.base}-RC1"
            "RC" -> "${v.base}-RC${v.labelNum!! + 1}"
            else -> null
        }
    group = "release"
    description =
        if (next !=
            null
        ) {
            "Add/increment RC (${project.version} -> $next)"
        } else {
            "Add/increment RC (unavailable: unexpected label '${v.label}')"
        }
    doLast {
        val v = parseVersion(project.version.toString())
        val next =
            when (v.label) {
                null, "Beta" -> "${v.base}-RC1"
                "RC" -> "${v.base}-RC${v.labelNum!! + 1}"
                else -> error("Cannot bump RC: unexpected label '${v.label}'.")
            }
        doVersionBump(project, next)
    }
}

tasks.register("promote") {
    group = "release"
    description = if (v.label != null) "Remove label (${project.version} -> ${v.base})" else "Remove label (no label to remove)"
    doLast {
        val v = parseVersion(project.version.toString())
        require(v.label != null) { "Version '${project.version}' has no label to remove." }
        doVersionBump(project, v.base)
    }
}
