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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeRuntimePlatformTest {
    @Test
    fun `platform aliases resolve to stable resource paths`() {
        val cases =
            listOf(
                Triple("Linux", "amd64", "/META-INF/natives/linux/x86_64/libcompukter_ffi.so"),
                Triple(" linux ", "x86_64", "/META-INF/natives/linux/x86_64/libcompukter_ffi.so"),
                Triple("Windows 11", "AMD64", "/META-INF/natives/windows/x86_64/compukter_ffi.dll"),
                Triple("Mac OS X", "arm64", "/META-INF/natives/macos/aarch64/libcompukter_ffi.dylib"),
                Triple("macOS", "aarch64", "/META-INF/natives/macos/aarch64/libcompukter_ffi.dylib"),
            )

        cases.forEach { (os, arch, expected) ->
            val resolution = assertIs<NativePlatformResolution.Supported>(NativeRuntimePlatform.resolve(os, arch))
            assertEquals(expected, resolution.platform.resourcePath)
        }
    }

    @Test
    fun `unsupported operating system retains bounded single line inputs`() {
        val resolution =
            assertIs<NativePlatformResolution.Unsupported>(
                NativeRuntimePlatform.resolve("Plan9\nignored", "mips".repeat(100)),
            )

        assertTrue(resolution.osName.length <= MAXIMUM_DIAGNOSTIC_CODE_UNITS)
        assertTrue(resolution.osArch.length <= MAXIMUM_DIAGNOSTIC_CODE_UNITS)
        assertFalse(resolution.osName.contains('\n'))
    }

    @Test
    fun `unsupported architecture does not partially resolve a platform`() {
        val resolution =
            assertIs<NativePlatformResolution.Unsupported>(
                NativeRuntimePlatform.resolve("Linux", "riscv64"),
            )

        assertEquals("Linux", resolution.osName)
        assertEquals("riscv64", resolution.osArch)
    }

    @Test
    fun `load exception exposes the typed failure`() {
        val failure = VmRuntimeLoadFailure.MissingResource("/META-INF/natives/linux/x86_64/libcompukter_ffi.so")

        val exception = VmRuntimeLoadException(failure)

        assertEquals(failure, exception.failure)
        assertTrue(exception.message.orEmpty().contains("MissingResource"))
    }

    private companion object {
        const val MAXIMUM_DIAGNOSTIC_CODE_UNITS = 256
    }
}
