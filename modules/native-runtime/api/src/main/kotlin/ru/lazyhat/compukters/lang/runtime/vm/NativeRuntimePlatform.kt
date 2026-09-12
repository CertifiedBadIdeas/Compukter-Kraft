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

import java.util.Locale

internal data class NativeRuntimePlatform(
    val os: String,
    val architecture: String,
    val filename: String,
) {
    val resourcePath: String = "/META-INF/natives/$os/$architecture/$filename"

    companion object {
        fun resolve(
            osName: String,
            osArch: String,
        ): NativePlatformResolution {
            val os =
                when {
                    osName.normalized().startsWith("linux") -> NativeOperatingSystem.LINUX
                    osName.normalized().startsWith("windows") -> NativeOperatingSystem.WINDOWS
                    osName.normalized().startsWith("mac") -> NativeOperatingSystem.MACOS
                    else -> null
                }
            val architecture =
                when (osArch.normalized()) {
                    "amd64", "x86_64" -> "x86_64"
                    "arm64", "aarch64" -> "aarch64"
                    else -> null
                }
            if (os == null || architecture == null) {
                return NativePlatformResolution.Unsupported(
                    osName = osName.runtimeDiagnostic("unknown operating system"),
                    osArch = osArch.runtimeDiagnostic("unknown architecture"),
                )
            }
            return NativePlatformResolution.Supported(
                NativeRuntimePlatform(
                    os = os.id,
                    architecture = architecture,
                    filename = os.filename,
                ),
            )
        }

        private fun String.normalized(): String = trim().lowercase(Locale.ROOT)
    }
}

internal sealed interface NativePlatformResolution {
    data class Supported(
        val platform: NativeRuntimePlatform,
    ) : NativePlatformResolution

    data class Unsupported(
        val osName: String,
        val osArch: String,
    ) : NativePlatformResolution
}

private enum class NativeOperatingSystem(
    val id: String,
    val filename: String,
) {
    LINUX("linux", "libcompukter_ffi.so"),
    WINDOWS("windows", "compukter_ffi.dll"),
    MACOS("macos", "libcompukter_ffi.dylib"),
}

internal const val MAXIMUM_RUNTIME_DIAGNOSTIC_CODE_UNITS = 256

internal fun String?.runtimeDiagnostic(fallback: String): String {
    val raw = this ?: fallback
    val lineEnd = raw.indexOfAny(charArrayOf('\r', '\n')).let { if (it < 0) raw.length else it }
    return raw.substring(0, lineEnd).ifEmpty { fallback }.take(MAXIMUM_RUNTIME_DIAGNOSTIC_CODE_UNITS)
}
