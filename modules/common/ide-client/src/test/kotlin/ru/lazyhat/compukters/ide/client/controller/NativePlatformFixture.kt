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

package ru.lazyhat.compukters.ide.client.controller

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.project.ProjectResolution
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.worker.value.ImmutableBytes

private val TEST_PLATFORM_BUNDLE =
    PlatformBundleCodec.assemble(
        languageVersion = "2.4",
        platformAbi = PlatformBundleCodec.SUPPORTED_PLATFORM_ABI,
        builtins =
            PlatformModule(
                id = PlatformModuleId("compukters", "builtins"),
                version = "1.0.0",
                dependencies = emptyList(),
                metadata = ImmutableBytes.of(byteArrayOf()),
                libraryFragment = null,
                sources = emptyList(),
                declarations = emptyList(),
                completionDeclarations = emptyList(),
            ),
        modules =
            listOf(
                PlatformModule(
                    id = PlatformModuleId("compukter", "redstone"),
                    version = "1.0.0",
                    dependencies = emptyList(),
                    metadata = ImmutableBytes.of("redstone".encodeToByteArray()),
                    libraryFragment = null,
                    sources = emptyList(),
                    declarations = emptyList(),
                    completionDeclarations = emptyList(),
                ),
            ),
    )

internal val TEST_PLATFORM_CATALOG = PlatformCatalog.of(TEST_PLATFORM_BUNDLE)

internal val TEST_TOOLCHAIN =
    ToolchainLockIdentity(
        compilerVersion = "2.4.10",
        languageVersion = TEST_PLATFORM_BUNDLE.identity.languageVersion,
        codegenAbi = 1u,
        artifactAbi = 1u,
        artifactWriterVersion = 1u,
        payloadHash = Hash256.of(ByteArray(32) { 1 }),
        platformAbi = Hash256.of(TEST_PLATFORM_BUNDLE.identity.contentHash.toByteArray()),
    )

internal val TEST_PROJECT_RESOLUTION = ProjectResolution(TEST_TOOLCHAIN, TEST_PLATFORM_CATALOG)
