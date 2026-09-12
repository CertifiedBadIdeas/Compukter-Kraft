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

package ru.lazyhat.compukters.platform.k2

import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompuktersSessionTest {
    @Test
    fun `session selects builtins and resolved library declarations without foreign classpaths`() {
        val bundle = bundle()
        val session = CompuktersSessionConfigurator.create(bundle, setOf(PlatformModuleId("std", "terminal")))

        assertEquals("kotlin/Int", session.resolve("kotlin/Int")?.symbol)
        assertEquals("kotlin.io/println", session.resolve("kotlin.io/println")?.symbol)
        assertNull(session.resolve("kotlin.io/readFile"))
        assertNull(session.resolve("java.lang/String"))
        assertTrue(session.binaryRoots.isEmpty())
    }

    @Test
    fun `guest source rejects foreign APIs JvmInline and external declarations`() {
        val diagnostics =
            CompuktersPlatformCheckers.checkGuestSource(
                """
                import java.lang.String
                @JvmInline value class Port(val value: Int)
                external fun escape(): Int
                """.trimIndent(),
            )

        assertEquals(
            setOf(
                CompuktersPlatformDiagnosticCode.FOREIGN_PLATFORM_REFERENCE,
                CompuktersPlatformDiagnosticCode.JVM_INLINE,
                CompuktersPlatformDiagnosticCode.GUEST_EXTERNAL_DECLARATION,
            ),
            diagnostics.mapTo(mutableSetOf()) { it.code },
        )
    }

    @Test
    fun `checker ignores forbidden-looking text in comments and strings`() {
        val diagnostics =
            CompuktersPlatformCheckers.checkGuestSource(
                """
                // external fun ignored(): java.lang.String
                val message = "@JvmInline kotlin.jvm.Hidden"
                """.trimIndent(),
            )

        assertTrue(diagnostics.isEmpty())
    }

    private fun bundle() =
        PlatformBundleCodec.assemble(
            "2.4",
            PlatformBundleCodec.SUPPORTED_PLATFORM_ABI,
            module(
                PlatformModuleId("compukters", "builtins"),
                "kotlin/Int",
                "package kotlin\npublic class Int",
            ),
            listOf(
                module(
                    PlatformModuleId("std", "filesystem"),
                    "kotlin.io/readFile",
                    "package kotlin.io\npublic external fun readFile(path: String): String",
                ),
                module(
                    PlatformModuleId("std", "terminal"),
                    "kotlin.io/println",
                    "package kotlin.io\npublic external fun println(value: Any?)",
                ),
            ),
        )

    private fun module(
        id: PlatformModuleId,
        symbol: String,
        text: String,
    ): PlatformModule {
        val path = "${id.namespace}/${id.name}.kt"
        return PlatformModule(
            id = id,
            version = "1.0.0",
            dependencies = emptyList(),
            metadata = ImmutableBytes.of(symbol.encodeToByteArray()),
            libraryFragment = null,
            sources = listOf(PlatformSource(path, ImmutableBytes.of(text.encodeToByteArray()))),
            declarations =
                listOf(
                    PlatformDeclaration(
                        symbol = symbol,
                        signature = text.substringAfterLast("public "),
                        module = id,
                        sourcePath = path,
                        startUtf16 = 0,
                        endUtf16 = text.length,
                        trustedExternal = " external " in " $text ",
                    ),
                ),
            completionDeclarations = emptyList(),
        )
    }
}
