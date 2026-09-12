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

package ru.lazyhat.compukters.ide.compiler.profile

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.worker.value.ImmutableBytes

internal fun platformBundle(
    terminalVersion: String = "2.1.0",
    terminalSource: String = "public external fun println(value: Any?)",
): PlatformBundle {
    val builtins = platformModule("compukters:builtins", "1.0.0")
    val core = platformModule("stdlib:core", "1.0.0", dependencies = arrayOf(builtins.id))
    val ranges = platformModule("stdlib:ranges", "1.1.0", dependencies = arrayOf(builtins.id, core.id))
    val terminal =
        platformModule(
            "std:terminal",
            terminalVersion,
            terminalSource,
            arrayOf(builtins.id, ranges.id),
        )
    val sensors = platformModule("create:sensors", "1.0.0", dependencies = arrayOf(builtins.id, core.id))
    val filesystem = platformModule("std:filesystem", "1.0.0", dependencies = arrayOf(builtins.id, core.id))
    return PlatformBundleCodec.assemble(
        "2.4",
        PlatformBundleCodec.SUPPORTED_PLATFORM_ABI,
        builtins,
        listOf(filesystem, terminal, sensors, ranges, core),
    )
}

internal fun platformCatalog(bundle: PlatformBundle = platformBundle()): PlatformCatalog = PlatformCatalog.of(bundle)

internal fun platformToolchain(bundle: PlatformBundle = platformBundle()): ToolchainLockIdentity =
    ToolchainLockIdentity(
        compilerVersion = "2.4.10",
        languageVersion = bundle.identity.languageVersion,
        codegenAbi = 1u,
        artifactAbi = 1u,
        artifactWriterVersion = 1u,
        payloadHash = Hash256.of(ByteArray(32) { 3 }),
        platformAbi = Hash256.of(bundle.identity.contentHash.toByteArray()),
    )

private fun platformModule(
    id: String,
    version: String,
    body: String = "public class Marker",
    dependencies: Array<PlatformModuleId> = emptyArray(),
): PlatformModule {
    val (namespace, name) = id.split(':', limit = 2)
    val moduleId = PlatformModuleId(namespace, name)
    val path = "${moduleId.namespace}/${moduleId.name}.kt"
    val source = "package ${moduleId.namespace}\n$body".encodeToByteArray()
    return PlatformModule(
        id = moduleId,
        version = version,
        dependencies = dependencies.toList(),
        metadata = ImmutableBytes.of(id.encodeToByteArray()),
        libraryFragment = null,
        sources = listOf(PlatformSource(path, ImmutableBytes.of(source))),
        declarations =
            listOf(
                PlatformDeclaration(
                    symbol = "$id/marker",
                    signature = body,
                    module = moduleId,
                    sourcePath = path,
                    startUtf16 = 0,
                    endUtf16 = source.decodeToString().length,
                    trustedExternal = " external " in " $body ",
                ),
            ),
        completionDeclarations = emptyList(),
    )
}
