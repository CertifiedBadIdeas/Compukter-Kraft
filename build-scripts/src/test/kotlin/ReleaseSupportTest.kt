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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReleaseSupportTest {
    @Test
    fun stableVersionDerivesTagAndNextDevelopmentVersion() {
        val version = ReleaseVersion.parse("0.1.0")

        assertEquals("v0.1.0", version.tag)
        assertEquals("0.2.0", version.nextDevelopmentVersion)
    }

    @Test
    fun releaseVersionRejectsLabelsAndMalformedValues() {
        listOf("0.1.0-Beta1", "0.1", "v0.1.0", "1.0.0.0").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.parse(value) }
        }
    }

    @Test
    fun universalReleaseRequiresExactVersionBundlesTagAndCleanRepositories() {
        val valid =
            UniversalReleaseState(
                version = "0.2.0",
                runtimeBundlesConfigured = true,
                headTags = setOf("v0.2.0"),
                worktreeStatus = "",
                submoduleStatus = " 0519552 host/compukter-vm (runtime-v5.1)",
            )

        validateUniversalReleaseState(valid)

        listOf(
            valid.copy(version = "0.2.1"),
            valid.copy(runtimeBundlesConfigured = false),
            valid.copy(headTags = emptySet()),
            valid.copy(worktreeStatus = " M gradle.properties"),
            valid.copy(submoduleStatus = "+0519552 host/compukter-vm (runtime-v5.1)"),
            valid.copy(submoduleStatus = "-0519552 host/compukter-vm"),
            valid.copy(submoduleStatus = "U0519552 host/compukter-vm"),
        ).forEach { state ->
            assertThrows(IllegalArgumentException::class.java) { validateUniversalReleaseState(state) }
        }
    }

    @Test
    fun tagCreationRequiresCleanUntaggedHead() {
        val valid =
            TagReleaseState(
                version = "0.1.0",
                branch = "dev",
                existingTags = emptySet(),
                worktreeStatus = "",
                submoduleStatus = " 0519552 host/compukter-vm (runtime-v5.1)",
            )

        validateTagReleaseState(valid)

        listOf(
            valid.copy(branch = "fix/0.1.x"),
            valid.copy(existingTags = setOf("v0.1.0")),
            valid.copy(worktreeStatus = "?? release.jar"),
            valid.copy(submoduleStatus = "+0519552 host/compukter-vm (runtime-v5.1)"),
        ).forEach { state ->
            assertThrows(IllegalArgumentException::class.java) { validateTagReleaseState(state) }
        }
    }

    @Test
    fun mainlineReleaseTagsTheCurrentVersionWithoutBumpingIt() {
        val events = mutableListOf<String>()
        val state =
            TagReleaseState(
                version = "0.2.0",
                branch = "dev",
                existingTags = setOf("v0.1.0"),
                worktreeStatus = "",
                submoduleStatus = " 0519552 host/compukter-vm (runtime-v5.1)",
            )

        performMainlineRelease(
            state = state,
            createTag = { events += "tag:$it" },
        )

        assertEquals(listOf("tag:v0.2.0"), events)
    }

    @Test
    fun mainlineReleaseValidatesEverythingBeforeMutatingGitOrVersionFiles() {
        val events = mutableListOf<String>()
        val dirty =
            TagReleaseState(
                version = "0.2.0",
                branch = "dev",
                existingTags = emptySet(),
                worktreeStatus = " M gradle.properties",
                submoduleStatus = " 0519552 host/compukter-vm (runtime-v5.1)",
            )

        assertThrows(IllegalArgumentException::class.java) {
            performMainlineRelease(
                state = dirty,
                createTag = { events += "tag:$it" },
            )
        }

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun postReleaseBumpRequiresTheCurrentReleaseTagAndCleanRepositories() {
        val valid =
            BumpAfterReleaseState(
                version = "0.1.0",
                headTags = setOf("v0.1.0"),
                worktreeStatus = "",
                submoduleStatus = " 0519552 host/compukter-vm (runtime-v5.1)",
            )

        assertEquals("0.2.0", validateBumpAfterReleaseState(valid))
        assertThrows(IllegalArgumentException::class.java) {
            validateBumpAfterReleaseState(valid.copy(headTags = emptySet()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateBumpAfterReleaseState(valid.copy(worktreeStatus = " M README.md"))
        }
    }

    @Test
    fun nativeResourceContractDistinguishesDevelopmentAndUniversalArchives() {
        val linux = "META-INF/natives/linux/x86_64/libcompukter_ffi.so"
        val windows = "META-INF/natives/windows/x86_64/compukter_ffi.dll"

        assertEquals(listOf(linux), expectedNativeResources(false, linux, RuntimeTransport.FFI))
        assertEquals(listOf(linux, windows), expectedNativeResources(true, linux, RuntimeTransport.FFI))
        validateNativeResources(listOf(windows, linux), expectedNativeResources(true, linux, RuntimeTransport.FFI))
        assertThrows(IllegalArgumentException::class.java) {
            validateNativeResources(listOf(linux), expectedNativeResources(true, linux, RuntimeTransport.FFI))
        }
        assertEquals(
            listOf(
                "META-INF/natives/linux/x86_64/libcompukter_jni.so",
                "META-INF/natives/windows/x86_64/compukter_jni.dll",
            ),
            expectedNativeResources(true, "ignored", RuntimeTransport.JNI),
        )
    }

    @Test
    fun onlyTheExplicitReleaseAssemblyTaskSelectsUniversalRuntimeMode() {
        assertEquals(true, requestsUniversalReleaseBuild(listOf(":v26_1-neoforge:buildReleaseUniversalJar")))
        assertEquals(true, requestsUniversalReleaseBuild(listOf("buildReleaseUniversalJar")))
        assertEquals(false, requestsUniversalReleaseBuild(listOf(":v26_1-neoforge:buildProductionUniversalJar")))
        assertEquals(false, requestsUniversalReleaseBuild(listOf("release")))
    }
}
