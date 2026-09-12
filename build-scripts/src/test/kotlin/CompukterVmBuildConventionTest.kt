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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class CompukterVmBuildConventionTest {
    @Test
    fun neutralVmHasHostRustVerificationWithoutNativePackaging() {
        val rootBuildScript = rootBuildSource().readText()

        assertTrue(rootBuildScript.contains("testCompukterVmRust"))
        assertTrue(rootBuildScript.contains("host/compukter-vm"))
        assertTrue(rootBuildScript.contains("Compukter-VM submodule"))
        assertTrue(rootBuildScript.contains("inputs.dir(compukterVmRoot.resolve(\"src\"))"))
        assertTrue(rootBuildScript.contains("inputs.dir(compukterVmRoot.resolve(\"tests\"))"))
        assertTrue(rootBuildScript.contains("dependsOn(testCompukterVmRust)"))
        assertFalse(rootBuildScript.contains("libcompukter_vm"))
        assertFalse(rootBuildScript.contains("buildCompukterVmNativeLibrary"))
    }

    @Test
    fun ffiBuildUsesTheVmWorkspaceAsItsSingleRustSource() {
        val rootBuildScript = rootBuildSource().readText()

        assertTrue(rootBuildScript.contains("val compukterVmRoot = rootProject.file(\"host/compukter-vm\")"))
        assertTrue(rootBuildScript.contains("val compukterFfiRoot = compukterVmRoot.resolve(\"ffi\")"))
        assertTrue(rootBuildScript.contains("inputs.file(compukterVmRoot.resolve(\"Cargo.lock\"))"))
        assertTrue(rootBuildScript.contains("\"-p\", \"compukter-ffi\""))
        assertFalse(rootBuildScript.contains("host/compukter-ffi"))
    }

    @Test
    fun releaseRuntimeBundlesDownloadByDefaultAndRetainAnOfflineOverride() {
        val rootBuildScript = rootBuildSource().readText()
        val ffmBuildScript = repoRoot().resolve("modules/common/native-runtime/ffm/build.gradle.kts").readText()
        val jniBuildScript = repoRoot().resolve("modules/common/native-runtime/jni/build.gradle.kts").readText()
        val support = repoRoot().resolve("build-scripts/src/main/kotlin/RuntimeBundleSupport.kt").readText()

        assertTrue(rootBuildScript.contains("tasks.register(\"downloadCompukterRuntimeBundles\")"))
        assertTrue(rootBuildScript.contains("RuntimeBundleDownloadSupport.download"))
        listOf(ffmBuildScript, jniBuildScript).forEach { nativeBuildScript ->
            assertTrue(nativeBuildScript.contains("compukterRuntimeBundleDir"))
            assertTrue(nativeBuildScript.contains("rootProject.tasks.named(\"downloadCompukterRuntimeBundles\")"))
            assertTrue(nativeBuildScript.contains("preparePackagedReleaseRuntime"))
            assertTrue(nativeBuildScript.contains("RuntimeBundleSupport.validateAndStage"))
        }
        assertTrue(ffmBuildScript.contains("RuntimeTransport.FFI"))
        assertTrue(jniBuildScript.contains("RuntimeTransport.JNI"))
        assertTrue(support.contains("HttpClient"))
        assertTrue(support.contains("Redirect.NORMAL"))
    }

    private fun rootBuildSource(): Path {
        return repoRoot().resolve("build.gradle.kts")
    }

    private fun repoRoot(): Path {
        val candidates =
            listOf(
                Path.of(System.getProperty("user.dir"), "..").normalize(),
                Path.of(System.getProperty("user.dir")),
            )
        return candidates.firstOrNull { Files.exists(it.resolve("build.gradle.kts")) }
            ?: error("Could not locate repository root from ${System.getProperty("user.dir")}")
    }
}
