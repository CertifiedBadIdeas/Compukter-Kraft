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
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformCatalogTest {
    @Test
    fun `catalog resolves deterministic transitive closure and marks only manifest roots direct`() {
        val catalog = PlatformCatalog.of(bundle())

        val selection = catalog.resolve(mapOf(ModuleId.parse("std:terminal") to ApiMajor(2)))

        assertEquals(listOf("stdlib:core", "stdlib:ranges", "std:terminal"), selection.modules.map { it.identity.id.value })
        assertFalse(selection.modules[0].direct)
        assertFalse(selection.modules[1].direct)
        assertTrue(selection.modules[2].direct)
        assertEquals(setOf(ModuleId.parse("std:terminal")), selection.directModules)
    }

    @Test
    fun `catalog promotes a transitive dependency when manifest declares it`() {
        val selection =
            PlatformCatalog.of(bundle()).resolve(
                mapOf(
                    ModuleId.parse("std:terminal") to ApiMajor(2),
                    ModuleId.parse("stdlib:ranges") to ApiMajor(1),
                ),
            )

        assertTrue(selection.modules.single { it.identity.id == ModuleId.parse("stdlib:ranges") }.direct)
    }

    @Test
    fun `catalog resolves a shared diamond dependency exactly once`() {
        val selection =
            PlatformCatalog.of(bundle()).resolve(
                mapOf(
                    ModuleId.parse("std:terminal") to ApiMajor(2),
                    ModuleId.parse("std:filesystem") to ApiMajor(1),
                ),
            )

        assertEquals(1, selection.modules.count { it.identity.id == ModuleId.parse("stdlib:core") })
        assertEquals(
            setOf(ModuleId.parse("std:terminal"), ModuleId.parse("std:filesystem")),
            selection.directModules,
        )
    }

    @Test
    fun `target availability excludes undeclared extras and rejects missing transitive dependencies`() {
        val bundle = bundle()
        val all = PlatformCatalog.of(bundle)
        val terminal = all.require(ModuleId.parse("std:terminal")).identity
        val ranges = all.require(ModuleId.parse("stdlib:ranges")).identity

        assertFailsWith<IllegalArgumentException> {
            PlatformCatalog.forTarget(bundle, listOf(terminal, ranges))
        }

        val core = all.require(ModuleId.parse("stdlib:core")).identity
        val target = PlatformCatalog.forTarget(bundle, listOf(terminal, ranges, core))
        assertFailsWith<IllegalArgumentException> {
            target.resolve(mapOf(ModuleId.parse("std:filesystem") to ApiMajor(1)))
        }
    }

    @Test
    fun `catalog verifies advertised major version and content hash`() {
        val bundle = bundle()
        val local = PlatformCatalog.of(bundle)
        val terminal = local.require(ModuleId.parse("std:terminal")).identity

        assertFailsWith<IllegalArgumentException> {
            PlatformCatalog.forTarget(bundle, listOf(terminal.copy(major = ApiMajor(1))))
        }
        assertFailsWith<IllegalArgumentException> {
            PlatformCatalog.forTarget(bundle, listOf(terminal.copy(version = "2.1.0")))
        }
        assertFailsWith<IllegalArgumentException> {
            PlatformCatalog.forTarget(bundle, listOf(terminal.copy(contentHash = hash(9))))
        }
    }

    private fun bundle(): PlatformBundle {
        val builtins = module("compukters", "builtins", "1.0.0")
        val core = module("stdlib", "core", "1.0.0", builtins.id)
        val ranges = module("stdlib", "ranges", "1.2.0", builtins.id, core.id)
        val terminal = module("std", "terminal", "2.0.0", builtins.id, ranges.id)
        val filesystem = module("std", "filesystem", "1.0.0", builtins.id, core.id)
        return PlatformBundleCodec.assemble(
            "2.4",
            PlatformBundleCodec.SUPPORTED_PLATFORM_ABI,
            builtins,
            listOf(filesystem, terminal, ranges, core),
        )
    }

    private fun module(
        namespace: String,
        name: String,
        version: String,
        vararg dependencies: PlatformModuleId,
    ): PlatformModule {
        val id = PlatformModuleId(namespace, name)
        val path = "$namespace/$name.kt"
        val source = "package $namespace\npublic class ${name.replaceFirstChar(Char::uppercase)}".encodeToByteArray()
        return PlatformModule(
            id = id,
            version = version,
            dependencies = dependencies.toList(),
            metadata = ImmutableBytes.of(byteArrayOf(name.length.toByte())),
            libraryFragment = null,
            sources = listOf(PlatformSource(path, ImmutableBytes.of(source))),
            declarations =
                listOf(
                    PlatformDeclaration(
                        symbol = "$namespace/$name",
                        signature = name,
                        module = id,
                        sourcePath = path,
                        startUtf16 = 0,
                        endUtf16 = source.decodeToString().length,
                        trustedExternal = false,
                    ),
                ),
            completionDeclarations = emptyList(),
        )
    }

    private fun hash(byte: Int) = Hash256.of(ByteArray(32) { byte.toByte() })
}
