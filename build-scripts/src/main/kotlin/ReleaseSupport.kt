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

data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    val value: String = "$major.$minor.$patch"
    val tag: String = "v$value"
    val nextDevelopmentVersion: String = "$major.${minor + 1}.0"

    companion object {
        private val stableVersion = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")

        fun parse(value: String): ReleaseVersion {
            val match = requireNotNull(stableVersion.matchEntire(value)) {
                "release version must be canonical X.Y.Z, got '$value'"
            }
            return ReleaseVersion(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }
    }
}

data class UniversalReleaseState(
    val version: String,
    val runtimeBundlesConfigured: Boolean,
    val headTags: Set<String>,
    val worktreeStatus: String,
    val submoduleStatus: String,
)

data class TagReleaseState(
    val version: String,
    val branch: String,
    val existingTags: Set<String>,
    val worktreeStatus: String,
    val submoduleStatus: String,
)

data class BumpAfterReleaseState(
    val version: String,
    val headTags: Set<String>,
    val worktreeStatus: String,
    val submoduleStatus: String,
)

fun validateUniversalReleaseState(state: UniversalReleaseState) {
    val version = ReleaseVersion.parse(state.version)
    require(state.runtimeBundlesConfigured) { "universal release requires compukterRuntimeBundleDir" }
    require(version.tag in state.headTags) { "HEAD must have exact release tag ${version.tag}" }
    require(state.worktreeStatus.isBlank()) { "release worktree must be clean:\n${state.worktreeStatus}" }
    requireCleanSubmodules(state.submoduleStatus)
}

fun validateTagReleaseState(state: TagReleaseState) {
    val version = ReleaseVersion.parse(state.version)
    require(!state.branch.startsWith("fix/")) { "main-line release cannot be tagged from ${state.branch}" }
    require(version.tag !in state.existingTags) { "release tag ${version.tag} already exists" }
    require(state.worktreeStatus.isBlank()) { "release worktree must be clean:\n${state.worktreeStatus}" }
    requireCleanSubmodules(state.submoduleStatus)
}

fun performMainlineRelease(
    state: TagReleaseState,
    createTag: (String) -> Unit,
) {
    validateTagReleaseState(state)
    val version = ReleaseVersion.parse(state.version)
    createTag(version.tag)
}

fun validateBumpAfterReleaseState(state: BumpAfterReleaseState): String {
    val version = ReleaseVersion.parse(state.version)
    require(version.tag in state.headTags) { "HEAD must have exact release tag ${version.tag}" }
    require(state.worktreeStatus.isBlank()) { "release worktree must be clean:\n${state.worktreeStatus}" }
    requireCleanSubmodules(state.submoduleStatus)
    return version.nextDevelopmentVersion
}

fun expectedNativeResources(
    releaseMode: Boolean,
    developmentResource: String,
    transport: RuntimeTransport,
): List<String> =
    if (releaseMode) {
        when (transport) {
            RuntimeTransport.FFI ->
                listOf(
                    "META-INF/natives/linux/x86_64/libcompukter_ffi.so",
                    "META-INF/natives/windows/x86_64/compukter_ffi.dll",
                )
            RuntimeTransport.JNI ->
                listOf(
                    "META-INF/natives/linux/x86_64/libcompukter_jni.so",
                    "META-INF/natives/windows/x86_64/compukter_jni.dll",
                )
        }
    } else {
        listOf(developmentResource)
    }

fun requestsUniversalReleaseBuild(taskNames: List<String>): Boolean =
    taskNames.any {
        it.substringAfterLast(':') == "buildReleaseUniversalJar" ||
            it.substringAfterLast(':') == "buildReleaseArtifacts"
    }

fun validateNativeResources(
    actual: List<String>,
    expected: List<String>,
) {
    require(actual.sorted() == expected.sorted()) {
        "expected native resources ${expected.sorted()}, found ${actual.sorted()}"
    }
}

private fun requireCleanSubmodules(status: String) {
    val lines = status.lineSequence().filter(String::isNotBlank).toList()
    require(lines.isNotEmpty()) { "release requires a pinned submodule" }
    require(lines.all { it.startsWith(' ') }) { "release submodules must be clean:\n$status" }
}
