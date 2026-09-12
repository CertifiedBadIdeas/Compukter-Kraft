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

package ru.lazyhat.compukters.lang.runtime.vm

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class VmRuntimeTest {
    @Test
    fun `runtime requires an explicitly installed backend`() {
        val registry = registry()

        val failure = assertFailsWith<IllegalStateException> { registry.requireLoader() }

        assertEquals("native runtime backend is not installed", failure.message)
    }

    @Test
    fun `installing the same backend is idempotent`() {
        val backend = FakeBackend("ffm")
        var loaderCreations = 0
        val registry = registry { loaderCreations++ }

        registry.install(backend)
        val installed = registry.requireLoader()
        registry.install(backend)

        assertSame(installed, registry.requireLoader())
        assertEquals(1, loaderCreations)
    }

    @Test
    fun `installing a conflicting backend is rejected`() {
        val registry = registry()
        registry.install(FakeBackend("ffm"))

        val failure = assertFailsWith<IllegalStateException> { registry.install(FakeBackend("jni")) }

        assertEquals("native runtime backend ffm is already installed; cannot install jni", failure.message)
    }

    private fun registry(onCreate: () -> Unit = {}): NativeRuntimeRegistry =
        NativeRuntimeRegistry {
            onCreate()
            NativeRuntimeLoader(
                osName = { "unsupported" },
                osArch = { "unsupported" },
                resource = { null },
                createTempDirectory = { Path.of("unused") },
                nativeLoad = { error("unused") },
            )
        }

    private class FakeBackend(
        override val id: String,
    ) : NativeRuntimeBackend {
        override fun open(library: Path): LowLevelVmBridge = error("unused")
    }
}
