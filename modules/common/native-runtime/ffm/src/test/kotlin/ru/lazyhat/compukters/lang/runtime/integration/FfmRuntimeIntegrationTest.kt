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

package ru.lazyhat.compukters.lang.runtime.integration

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukters.lang.runtime.vm.FfmRuntimeBackend
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import java.nio.file.Path
import kotlin.test.Test

class FfmRuntimeIntegrationTest {
    @Test
    fun `terminal artifact runs through Kotlin FFM and Rust VM`() =
        runBlocking {
            VmRuntime.install(FfmRuntimeBackend)
            VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukter.ffi.library")))
            ShellProgram.run(Path.of(requiredProperty("compukters.shell.artifact")))
        }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing test system property $name" }
}
