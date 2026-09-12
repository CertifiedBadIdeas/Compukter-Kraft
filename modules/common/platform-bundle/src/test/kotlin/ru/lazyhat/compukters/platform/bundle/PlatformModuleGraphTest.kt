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

package ru.lazyhat.compukters.platform.bundle

import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlatformModuleGraphTest {
    @Test
    fun `resolution returns deterministic dependency first closure and direct roots`() {
        val core = id("stdlib", "core")
        val ranges = id("stdlib", "ranges")
        val text = id("stdlib", "text")
        val terminal = id("std", "terminal")
        val graph =
            graph(
                module(terminal, text, ranges),
                module(text, core),
                module(ranges, core),
                module(core, BUILTINS),
            )

        val resolved = graph.resolve(setOf(terminal))

        assertEquals(setOf(terminal), resolved.directRoots)
        assertEquals(listOf(core, ranges, text, terminal), resolved.modules.map(PlatformModule::id))
        assertTrue(resolved.isDirect(terminal))
        assertTrue(!resolved.isDirect(core))
    }

    @Test
    fun `resolution promotes a transitive module when it is also a direct root`() {
        val core = id("stdlib", "core")
        val terminal = id("std", "terminal")
        val resolved = graph(module(terminal, core), module(core, BUILTINS)).resolve(setOf(terminal, core))

        assertEquals(setOf(core, terminal), resolved.directRoots)
        assertEquals(listOf(core, terminal), resolved.modules.map(PlatformModule::id))
    }

    @Test
    fun `resolution rejects an unknown direct root`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                graph().resolve(setOf(id("std", "missing")))
            }

        assertTrue(failure.message.orEmpty().contains("unknown module"))
    }

    @Test
    fun `graph rejects unknown dependencies before resolution`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                graph(module(id("std", "terminal"), id("stdlib", "missing")))
            }

        assertTrue(failure.message.orEmpty().contains("unknown dependency"))
    }

    @Test
    fun `graph rejects dependency cycles with the complete cycle`() {
        val first = id("stdlib", "first")
        val second = id("stdlib", "second")
        val third = id("stdlib", "third")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                graph(module(first, second), module(second, third), module(third, first))
            }

        assertTrue(failure.message.orEmpty().contains("stdlib:first -> stdlib:second -> stdlib:third -> stdlib:first"))
    }

    private fun graph(vararg modules: PlatformModule): PlatformModuleGraph =
        PlatformModuleGraph(
            PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, module(BUILTINS), modules.toList()),
        )

    private fun module(
        id: PlatformModuleId,
        vararg dependencies: PlatformModuleId,
    ): PlatformModule {
        val sourcePath = "${id.namespace}/${id.name}.kt"
        val source = "package ${id.namespace}\npublic class ${id.name.replaceFirstChar(Char::uppercase)}".encodeToByteArray()
        return PlatformModule(
            id = id,
            version = "1.0.0",
            dependencies = dependencies.toList(),
            metadata = ImmutableBytes.of(byteArrayOf(1)),
            libraryFragment = null,
            sources = listOf(PlatformSource(sourcePath, ImmutableBytes.of(source))),
            declarations =
                listOf(
                    PlatformDeclaration(
                        symbol = "${id.namespace}/${id.name}",
                        signature = id.name,
                        module = id,
                        sourcePath = sourcePath,
                        startUtf16 = 0,
                        endUtf16 = source.decodeToString().length,
                        trustedExternal = false,
                    ),
                ),
            completionDeclarations = emptyList(),
        )
    }

    private fun id(
        namespace: String,
        name: String,
    ) = PlatformModuleId(namespace, name)

    private companion object {
        val BUILTINS = PlatformModuleId("compukters", "builtins")
    }
}
