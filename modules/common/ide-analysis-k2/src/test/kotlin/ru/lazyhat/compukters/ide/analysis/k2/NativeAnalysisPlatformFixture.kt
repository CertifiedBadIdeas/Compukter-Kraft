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

package ru.lazyhat.compukters.ide.analysis.k2

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisModule
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisPlatform
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.nio.file.Files
import java.nio.file.Path

internal fun testPlatform(): PlatformBundle =
    PlatformBundleCodec.decode(
        Files.readAllBytes(Path.of(requireNotNull(System.getProperty("compukters.test.platformBundle")))),
    )

internal fun testPlatformAbi(): Hash256 = Hash256.of(testPlatform().identity.contentHash.toByteArray())

internal fun testAdmittedPlatform(
    selectAllModules: Boolean = false,
    attachedSources: Boolean = false,
): AdmittedAnalysisPlatform {
    val platform = testPlatform()
    val modules =
        if (selectAllModules) {
            (listOf(platform.builtins) + platform.modules).sortedBy { it.id }.map { module ->
                AdmittedAnalysisModule(
                    AnalysisModuleIdentity(
                        module.id.toString(),
                        Hash256.of(PlatformBundleCodec.moduleContentHash(module).toByteArray()),
                    ),
                )
            }
        } else {
            emptyList()
        }
    val sourceRoot =
        if (attachedSources) {
            Path
                .of(requireNotNull(System.getProperty("compukters.test.guestApi")))
                .toAbsolutePath()
                .normalize()
                .toString()
        } else {
            null
        }
    return AdmittedAnalysisPlatform(testPlatformAbi(), modules, sourceRoot)
}
