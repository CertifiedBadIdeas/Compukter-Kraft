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

package ru.lazyhat.compukters.ide.project

import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog

data class ProjectResolution(
    val toolchain: ToolchainLockIdentity,
    val catalog: PlatformCatalog,
) {
    init {
        require(toolchain.languageVersion == catalog.bundle.identity.languageVersion) {
            "toolchain language version does not match platform bundle"
        }
        val toolchainPlatformAbi = toolchain.platformAbi.toByteArray()
        val bundlePlatformAbi =
            catalog.bundle.identity.contentHash
                .toByteArray()
        require(toolchainPlatformAbi.contentEquals(bundlePlatformAbi)) {
            "toolchain platform ABI does not match platform bundle"
        }
    }
}
