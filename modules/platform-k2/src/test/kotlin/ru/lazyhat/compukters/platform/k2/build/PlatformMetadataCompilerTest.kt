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

package ru.lazyhat.compukters.platform.k2.build

import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import ru.lazyhat.compukters.platform.bundle.PlatformCompletionKind
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformScalarConstant
import ru.lazyhat.compukters.platform.bundle.PlatformScalarRepresentation
import ru.lazyhat.compukters.platform.bundle.PlatformScalarType
import ru.lazyhat.compukters.platform.bundle.PlatformScalarValue
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformMetadataCompilerTest {
    private val compiler = PlatformMetadataCompiler()
    private val module = PlatformModuleId("test", "library")

    @Test
    fun `metadata and export indexes are deterministic`() {
        val first = source("z.kt", "package sample\npublic external fun zebra(value: Int): String")
        val second = source("a.kt", "package sample\npublic class Alpha")

        val forward = compiler.compile(module, listOf(first, second))
        val reverse = compiler.compile(module, listOf(second, first))

        assertContentEquals(forward.metadata.toByteArray(), reverse.metadata.toByteArray())
        assertEquals(listOf("sample.Alpha", "sample.zebra"), forward.exportedSymbols)
        assertEquals(forward.declarations, reverse.declarations)
    }

    @Test
    fun `declarations preserve exact source ranges and external state`() {
        val text = "package sample\n// 😀 keeps offsets UTF-16\npublic external fun ping(value: Int): String\n"
        val result = compiler.compile(module, listOf(source("api.kt", text)))
        val declaration = result.declarations.single()

        assertEquals("sample.ping", declaration.symbol)
        assertEquals("fun(Int):String", declaration.signature)
        assertEquals("api.kt", declaration.sourcePath)
        assertEquals(text.indexOf("public external fun ping"), declaration.startUtf16)
        assertEquals(text.indexOf("String") + "String".length, declaration.endUtf16)
        assertTrue(declaration.trustedExternal)
        assertEquals("public external fun ping(value: Int): String", text.substring(declaration.startUtf16, declaration.endUtf16))
    }

    @Test
    fun `completion declarations contain only public top-level importable API`() {
        val text =
            """
            package sample
            class Visible { class Nested; fun member() = Unit }
            interface Contract
            object Api
            typealias Alias = String
            val property: Int = 1
            external fun nativeCall(value: Int): String
            fun overloaded(value: Int): String = "int"
            fun overloaded(value: String): String = value
            private class Hidden
            internal object InternalBinding
            """.trimIndent()

        val result = compiler.compile(module, listOf(source("api.kt", text)))
        val symbols = result.completionDeclarations.map { it.symbol }

        assertContains(symbols, "sample.Visible")
        assertContains(symbols, "sample.Contract")
        assertContains(symbols, "sample.Api")
        assertContains(symbols, "sample.Alias")
        assertContains(symbols, "sample.property")
        assertContains(symbols, "sample.nativeCall")
        assertEquals(2, symbols.count { it == "sample.overloaded" })
        assertFalse(symbols.any { it == "sample.Hidden" || it == "sample.InternalBinding" })
        assertFalse(symbols.any { it.contains("Nested") || it.contains("member") })
        assertEquals(PlatformCompletionKind.CLASS, result.completionDeclarations.single { it.symbol == "sample.Visible" }.kind)
        assertEquals(PlatformCompletionKind.INTERFACE, result.completionDeclarations.single { it.symbol == "sample.Contract" }.kind)
        assertEquals(PlatformCompletionKind.OBJECT, result.completionDeclarations.single { it.symbol == "sample.Api" }.kind)
        assertEquals(PlatformCompletionKind.TYPE_ALIAS, result.completionDeclarations.single { it.symbol == "sample.Alias" }.kind)
        assertTrue(result.completionDeclarations.none { it.defaultImport })
    }

    @Test
    fun `completion declarations recognize platform default imports`() {
        val io = PlatformModuleId("std", "terminal")
        val result = compiler.compile(io, listOf(source("Console.kt", "package kotlin.io\nexternal fun println(value: Int)")))

        assertTrue(result.completionDeclarations.single().defaultImport)
    }

    @Test
    fun `duplicate canonical declarations are rejected`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                compiler.compile(
                    module,
                    listOf(
                        source("one.kt", "package sample\nfun duplicate(value: Int): Int = value"),
                        source("two.kt", "package sample\nfun duplicate(value: Int): Int = value"),
                    ),
                )
            }

        assertTrue(failure.message.orEmpty().contains("duplicate platform declaration sample.duplicate fun(Int):Int"))
    }

    @Test
    fun `metadata decode rejects a source signature mismatch`() {
        val compiled = compiler.compile(module, listOf(source("api.kt", "package sample\nexternal fun ping(): Int")))
        val decoded = PlatformMetadataCodec.decode(compiled.metadata)
        val mismatched = decoded.copy(declarations = decoded.declarations.map { it.copy(signature = "fun():String") })

        assertFailsWith<IllegalArgumentException> {
            PlatformMetadataCodec.validateAgainstSources(mismatched, listOf(source("api.kt", "package sample\nexternal fun ping(): Int")))
        }
    }

    @Test
    fun `explicit primary constructors and member properties are indexed`() {
        val result =
            compiler.compile(
                module,
                listOf(
                    source(
                        "Token.kt",
                        "package sample\nclass Token public constructor(val value: Int) { val doubled: Int get() = value + value }",
                    ),
                ),
            )

        assertEquals(
            listOf(
                "sample.Token" to "class(Int)",
                "sample.Token.<get-doubled>" to "fun():Int",
                "sample.Token.<init>" to "constructor(Int)",
                "sample.Token.doubled" to "val():Int",
                "sample.Token.value" to "val():Int",
            ),
            result.declarations.map { it.symbol to it.signature },
        )
    }

    @Test
    fun `value class scalar types and literal companion constants are derived from canonical sources`() {
        val text =
            """
            package sample
            value class Port(val value: Int) {
                init {
                    require(value in 0..15)
                }
                companion object {
                    val MIN: Port = Port(0)
                    val MAX: Port = Port(15)
                }
            }
            """.trimIndent()
        val result = compiler.compile(module, listOf(source("Port.kt", text)))

        assertEquals(
            listOf(
                PlatformScalarType(
                    symbol = "sample.Port",
                    representation = PlatformScalarRepresentation.INT,
                    underlyingProperty = "value",
                    sourcePath = "Port.kt",
                    startUtf16 = text.indexOf("value class Port"),
                    endUtf16 = text.length,
                    minimumInt = 0,
                    maximumInt = 15,
                ),
            ),
            result.scalarTypes,
        )
        assertEquals(
            listOf(
                PlatformScalarConstant("sample.Port.MAX", "sample.Port", PlatformScalarValue.IntValue(15)),
                PlatformScalarConstant("sample.Port.MIN", "sample.Port", PlatformScalarValue.IntValue(0)),
            ),
            result.scalarConstants,
        )
    }

    @Test
    fun `mutable and computed value class constants are not scalar metadata`() {
        val text =
            """
            package sample
            value class Port(val value: Int) {
                companion object {
                    var mutable: Port = Port(1)
                    val computed: Port get() = Port(2)
                }
            }
            """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            compiler.compile(module, listOf(source("Port.kt", text)))
        }
    }

    @Test
    fun `resolved FIR metadata is deterministic and contains no source text`() {
        val sources = listOf(source("Builtins.kt", "package kotlin\nopen class Any\nclass Int private constructor()"))
        val parsed = compiler.compile(module, sources)
        val first =
            CompuktersFirBuildEnvironment.create().use { environment ->
                val output = environment.compile(module, sources, emptyList())
                compiler.attachResolvedMetadata(parsed, output)
            }
        val second =
            CompuktersFirBuildEnvironment.create().use { environment ->
                val output = environment.compile(module, sources, emptyList())
                compiler.attachResolvedMetadata(parsed, output)
            }

        assertContentEquals(first.metadata.toByteArray(), second.metadata.toByteArray())
        val decoded = PlatformMetadataCodec.decode(first.metadata)
        assertTrue(decoded.moduleHeader.isNotEmpty())
        assertTrue(decoded.fragments.isNotEmpty())
        assertFalse(
            first.metadata
                .toByteArray()
                .decodeToString()
                .contains("open class Any"),
        )
    }

    @Test
    fun `resolved metadata supplies platform classes without replaying platform sources`() {
        val sources = listOf(source("Builtins.kt", "package kotlin\nopen class Any\nclass Int private constructor()"))
        val compiled =
            CompuktersFirBuildEnvironment.create().use { environment ->
                compiler.attachResolvedMetadata(compiler.compile(module, sources), environment.compile(module, sources, emptyList()))
            }
        val platformModule =
            PlatformModule(
                module,
                "1.0.0",
                emptyList(),
                compiled.metadata,
                null,
                sources,
                compiled.declarations,
                compiled.completionDeclarations,
            )

        CompuktersFirBuildEnvironment.create().use { environment ->
            val guest = environment.compileGuest(PlatformModuleId("guest", "application"), emptyList(), listOf(platformModule))
            val symbol =
                guest.frontendOutput.session.symbolProvider.getClassLikeSymbolByClassId(
                    ClassId.topLevel(FqName("kotlin.Int")),
                )

            assertTrue(symbol != null)
        }
    }

    private fun source(
        path: String,
        content: String,
    ): PlatformSource = PlatformSource(path, ImmutableBytes.of(content.encodeToByteArray()))
}
