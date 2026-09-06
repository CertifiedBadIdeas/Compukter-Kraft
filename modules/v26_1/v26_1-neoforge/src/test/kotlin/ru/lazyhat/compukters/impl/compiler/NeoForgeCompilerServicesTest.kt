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

package ru.lazyhat.compukters.impl.compiler

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfileResolver
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.ProfileResolution
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfileIdentity
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.LockedModule
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ProjectLock
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class NeoForgeCompilerServicesTest {
    @Test
    fun `server publishes the exact packaged toolchain and limits as its target profile`() {
        val platform = platform()
        val identity =
            WorkerIdentity(
                "2.4.0",
                "2.4",
                3u,
                4u,
                hash(5),
                Hash256.of(platform.identity.contentHash.toByteArray()),
            )
        val limits = WorkerLimits(sourceFiles = 7, artifactBytes = 8)

        val profile = serverTargetProfile(identity, platform, limits)
        val catalog = PlatformCatalog.of(platform)

        assertEquals(identity.compilerVersion, profile.toolchain.compilerVersion)
        assertEquals(identity.languageVersion, profile.toolchain.languageVersion)
        assertEquals(identity.codegenAbi, profile.toolchain.codegenAbi)
        assertEquals(2u, profile.toolchain.artifactAbi)
        assertEquals(identity.artifactWriterVersion, profile.toolchain.artifactWriterVersion)
        assertEquals(identity.payloadHash, profile.toolchain.payloadHash)
        assertEquals(identity.platformAbi, profile.toolchain.platformAbi)
        assertEquals(limits, profile.limits)
        assertEquals(catalog.entries.map { it.identity }, profile.modules)
        assertEquals(
            TargetCompileProfileIdentity.of(profile),
            TargetCompileProfileIdentity.of(serverTargetProfile(identity, platform, limits)),
        )

        val selection = catalog.resolve(mapOf(ModuleId.parse("compukter:redstone") to ApiMajor(2)))
        val lock = ProjectLock.of(profile.toolchain, selection.modules.map { LockedModule(it.identity, it.direct) })
        assertIs<ProfileResolution.Resolved>(CompileProfileResolver(profile.toolchain, catalog, limits).resolveTarget(lock, profile))
    }

    @Test
    fun `canonical world root owns one shared service until stop`() {
        val root = createTempDirectory("compukters-compiler-services-").toRealPath()
        val secondRoot = root.resolve("second").createDirectories().toRealPath()
        val opened = mutableListOf<Path>()
        val registry = CompilerServiceRegistry { path -> FakeService(path).also { opened.add(path) } }
        try {
            val first = registry.service(root)
            assertSame(first, registry.service(root.resolve(".")))
            val second = registry.service(secondRoot)
            assertNotSame(first, second)
            assertEquals(listOf(root, secondRoot), opened)

            registry.stop(root)
            registry.stop(root)
            assertEquals(1, first.closes)
            val reopened = registry.service(root)
            assertNotSame(first, reopened)
            assertEquals(root, reopened.root)
        } finally {
            registry.stop(root)
            registry.stop(secondRoot)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `service paths keep cache payload and temporary storage separate`() {
        val root = createTempDirectory("compukters-compiler-paths-").toRealPath()
        try {
            val paths = CompilerServicePaths.at(root)
            assertEquals(root.resolve("compukters/compiler-cache").normalize(), paths.cacheRoot)
            assertEquals(root.resolve("compukters/compiler-worker").normalize(), paths.payloadRoot)
            assertEquals(root.resolve("compukters/compiler-temp").normalize(), paths.temporaryRoot)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class FakeService(
        val root: Path,
    ) : AutoCloseable {
        var closes = 0

        override fun close() {
            closes++
        }
    }

    private fun hash(value: Int) = Hash256.of(ByteArray(32) { value.toByte() })

    private fun platform(): PlatformBundle {
        val builtins = module("kotlin", "builtins")
        val core = module("stdlib", "core", listOf(builtins.id))
        val redstone = module("compukter", "redstone", listOf(core.id), "2.0.0")
        return PlatformBundleCodec.assemble(
            "2.4",
            PlatformBundleCodec.SUPPORTED_PLATFORM_ABI,
            builtins,
            listOf(redstone, core),
        )
    }

    private fun module(
        namespace: String,
        name: String,
        dependencies: List<PlatformModuleId> = emptyList(),
        version: String = "1.0.0",
    ): PlatformModule =
        PlatformModule(
            PlatformModuleId(namespace, name),
            version,
            dependencies,
            ImmutableBytes.of("$namespace:$name".encodeToByteArray()),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
        )
}
