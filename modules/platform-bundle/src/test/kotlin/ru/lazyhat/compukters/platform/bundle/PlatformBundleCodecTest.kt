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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlatformBundleCodecTest {
    @Test
    fun `enum default arguments round trip and affect module identity`() {
        val base = terminal()
        val declaration =
            base.declarations.first().copy(
                defaultArguments = listOf(PlatformDefaultArgument.EnumEntry("sample.Power.WEAK")),
            )
        val changed = base.copy(declarations = listOf(declaration) + base.declarations.drop(1))
        val bundle = PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, builtins(), listOf(ranges(), changed))
        val decoded = PlatformBundleCodec.decode(PlatformBundleCodec.encode(bundle))

        assertEquals(
            listOf(PlatformDefaultArgument.EnumEntry("sample.Power.WEAK")),
            decoded.modules
                .single { it.id == changed.id }
                .declarations
                .single { it.symbol == declaration.symbol }
                .defaultArguments,
        )
        assertNotEquals(PlatformBundleCodec.moduleContentHash(base), PlatformBundleCodec.moduleContentHash(changed))
    }

    @Test
    fun `completion declarations round trip canonically and affect module identity`() {
        val base = terminal()
        val println =
            PlatformCompletionDeclaration(
                symbol = "kotlin.io.println",
                shortName = "println",
                signature = "fun(Int):Unit",
                kind = PlatformCompletionKind.FUNCTION,
                module = base.id,
                sourcePath = base.sources.first().path,
                startUtf16 = 0,
                endUtf16 = 7,
                defaultImport = true,
            )
        val readln = println.copy(symbol = "kotlin.io.readln", shortName = "readln", signature = "fun():String")
        val ordered = base.copy(completionDeclarations = listOf(readln, println))
        val reversed = ordered.copy(completionDeclarations = ordered.completionDeclarations.reversed())

        val first = PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, builtins(), listOf(ranges(), ordered))
        val second = PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, builtins(), listOf(ranges(), reversed))
        val decoded = PlatformBundleCodec.decode(PlatformBundleCodec.encode(first))

        assertEquals(first, second)
        assertEquals(listOf(println, readln), decoded.modules.single { it.id == ordered.id }.completionDeclarations)
        assertNotEquals(PlatformBundleCodec.moduleContentHash(base), PlatformBundleCodec.moduleContentHash(ordered))
    }

    @Test
    fun `encoding canonicalizes module dependency source and declaration order`() {
        val ordered = fixture(reverse = false)
        val reversed = fixture(reverse = true)

        assertContentEquals(PlatformBundleCodec.encode(ordered), PlatformBundleCodec.encode(reversed))
        assertEquals(ordered, reversed)
        assertEquals(ordered, PlatformBundleCodec.decode(PlatformBundleCodec.encode(ordered)))
    }

    @Test
    fun `bundle values defensively copy binary inputs and outputs`() {
        val metadata = byteArrayOf(1, 2, 3)
        val source = "package kotlin\npublic class Any".encodeToByteArray()
        val module =
            module(
                id = BUILTINS,
                metadata = metadata,
                sourcePath = "builtins/kotlin/Any.kt",
                sourceContent = source,
                symbol = "kotlin/Any",
            )

        val bundle = PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, module, emptyList())
        metadata[0] = 99
        source[0] = 99
        val returnedMetadata = bundle.builtins.metadata.toByteArray()
        val returnedSource =
            bundle.builtins.sources
                .single()
                .content
                .toByteArray()
        returnedMetadata[1] = 99
        returnedSource[1] = 99

        assertContentEquals(byteArrayOf(1, 2, 3), bundle.builtins.metadata.toByteArray())
        assertContentEquals(
            "package kotlin\npublic class Any".encodeToByteArray(),
            bundle.builtins.sources
                .single()
                .content
                .toByteArray(),
        )
    }

    @Test
    fun `stored content hash covers every semantic byte`() {
        val encoded = PlatformBundleCodec.encode(fixture())
        val changed = encoded.copyOf()
        val sourceByte = (40..changed.lastIndex).first { changed[it] == 'p'.code.toByte() }
        changed[sourceByte] = (changed[sourceByte].toInt() xor 1).toByte()

        val failure = assertFailsWith<IllegalArgumentException> { PlatformBundleCodec.decode(changed) }

        assertTrue(failure.message.orEmpty().contains("content hash"))
    }

    @Test
    fun `decoder rejects trailing bytes unsupported versions and malformed utf8`() {
        val encoded = PlatformBundleCodec.encode(fixture())
        assertFailsWith<IllegalArgumentException> { PlatformBundleCodec.decode(encoded + 0) }

        val unsupportedFormat = encoded.copyOf().also { it[4] = 99 }
        assertFailsWith<IllegalArgumentException> { PlatformBundleCodec.decode(unsupportedFormat) }

        val languageOffset = 4 + 4 + 32 + 4
        val unsupportedAbi =
            encoded.copyOf().also { bytes ->
                val abiOffset = languageOffset + "2.4".encodeToByteArray().size
                bytes[abiOffset] = 2
            }
        assertFailsWith<IllegalArgumentException> { PlatformBundleCodec.decode(unsupportedAbi) }

        val malformedUtf8 = encoded.copyOf().also { it[languageOffset] = 0x80.toByte() }
        assertFailsWith<IllegalArgumentException> { PlatformBundleCodec.decode(malformedUtf8) }
    }

    @Test
    fun `decoder rejects oversized counts before allocation`() {
        val encoded =
            PlatformBundleCodec.encode(
                PlatformBundleCodec.assemble(
                    "2.4",
                    PlatformBundleCodec.SUPPORTED_PLATFORM_ABI,
                    builtins(),
                    emptyList(),
                ),
            )
        val oversizedModuleCount =
            encoded.copyOf().also { bytes ->
                val count = 4097
                repeat(4) { index -> bytes[bytes.size - 4 + index] = (count ushr (index * 8)).toByte() }
            }

        val failure = assertFailsWith<IllegalArgumentException> { PlatformBundleCodec.decode(oversizedModuleCount) }

        assertTrue(failure.message.orEmpty().contains("count exceeds limit"))
    }

    @Test
    fun `assembly rejects duplicate module ids and source paths`() {
        val builtins = builtins()
        val terminal = terminal()
        val duplicateId = terminal.copy(version = "2.0.0")
        assertFailsWith<IllegalArgumentException> {
            PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, builtins, listOf(terminal, duplicateId))
        }

        val duplicateSource =
            module(
                id = PlatformModuleId("std", "filesystem"),
                sourcePath = terminal.sources.first().path,
                symbol = "kotlin.io/readText",
            )
        assertFailsWith<IllegalArgumentException> {
            PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, builtins, listOf(terminal, duplicateSource))
        }
    }

    @Test
    fun `assembly rejects declaration ownership and source range mismatches`() {
        val sourcePath = "libraries/std-terminal/kotlin/io/Console.kt"
        val wrongOwner =
            terminal().copy(
                declarations = terminal().declarations.map { it.copy(module = PlatformModuleId("std", "filesystem")) },
            )
        assertFailsWith<IllegalArgumentException> {
            PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, builtins(), listOf(wrongOwner))
        }

        val badRange =
            terminal().copy(
                declarations = terminal().declarations.map { it.copy(sourcePath = sourcePath, endUtf16 = Int.MAX_VALUE) },
            )
        assertFailsWith<IllegalArgumentException> {
            PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, builtins(), listOf(badRange))
        }
    }

    @Test
    fun `different semantic bundles receive different identities`() {
        val first = fixture()
        val second =
            fixture().let { bundle ->
                PlatformBundleCodec.assemble(
                    bundle.identity.languageVersion,
                    bundle.identity.platformAbi,
                    bundle.builtins,
                    bundle.modules.map { module ->
                        if (module.id == PlatformModuleId("std", "terminal")) module.copy(version = "2.0.0") else module
                    },
                )
            }

        assertNotEquals(first.identity.contentHash, second.identity.contentHash)
    }

    @Test
    fun `scalar types and constants round trip canonically and affect module identity`() {
        val base = terminal()
        val scalarType =
            PlatformScalarType(
                symbol = "example.Port",
                representation = PlatformScalarRepresentation.INT,
                underlyingProperty = "value",
                sourcePath = base.sources.first().path,
                startUtf16 = 0,
                endUtf16 = 4,
                minimumInt = 0,
                maximumInt = 15,
            )
        val minimum = PlatformScalarConstant("example.Port.MIN", "example.Port", PlatformScalarValue.IntValue(0))
        val maximum = PlatformScalarConstant("example.Port.MAX", "example.Port", PlatformScalarValue.IntValue(15))
        val ordered = base.copy(scalarTypes = listOf(scalarType), scalarConstants = listOf(minimum, maximum))
        val reversed = ordered.copy(scalarConstants = ordered.scalarConstants.reversed())

        val first = PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, builtins(), listOf(ranges(), ordered))
        val second = PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, builtins(), listOf(ranges(), reversed))
        val decoded = PlatformBundleCodec.decode(PlatformBundleCodec.encode(first))

        assertEquals(first, second)
        assertEquals(listOf(maximum, minimum), decoded.modules.single { it.id == ordered.id }.scalarConstants)
        assertNotEquals(PlatformBundleCodec.moduleContentHash(base), PlatformBundleCodec.moduleContentHash(ordered))
    }

    @Test
    fun `assembly rejects duplicate or mismatched scalar descriptors`() {
        val base = terminal()
        val scalarType =
            PlatformScalarType("example.Port", PlatformScalarRepresentation.INT, "value", base.sources.first().path, 0, 4)
        val duplicate = base.copy(scalarTypes = listOf(scalarType, scalarType))
        val mismatched =
            base.copy(
                scalarTypes = listOf(scalarType),
                scalarConstants =
                    listOf(
                        PlatformScalarConstant(
                            "example.Port.INVALID",
                            "example.Missing",
                            PlatformScalarValue.IntValue(16),
                        ),
                    ),
            )

        assertFailsWith<IllegalArgumentException> {
            PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, builtins(), listOf(duplicate))
        }
        assertFailsWith<IllegalArgumentException> {
            PlatformBundleCodec.assemble("2.4", PlatformBundleCodec.SUPPORTED_PLATFORM_ABI, builtins(), listOf(mismatched))
        }
    }

    private fun fixture(reverse: Boolean = false): PlatformBundle {
        val modules = listOf(filesystem(), terminal(), ranges())
        return PlatformBundleCodec.assemble(
            languageVersion = "2.4",
            platformAbi = PlatformBundleCodec.SUPPORTED_PLATFORM_ABI,
            builtins = builtins(),
            modules = if (reverse) modules.reversed().map(::reverseContents) else modules,
        )
    }

    private fun reverseContents(module: PlatformModule): PlatformModule =
        module.copy(
            dependencies = module.dependencies.reversed(),
            sources = module.sources.reversed(),
            declarations = module.declarations.reversed(),
            completionDeclarations = module.completionDeclarations.reversed(),
        )

    private fun builtins(): PlatformModule =
        module(
            id = BUILTINS,
            sourcePath = "builtins/kotlin/Any.kt",
            sourceContent = "package kotlin\npublic open class Any".encodeToByteArray(),
            symbol = "kotlin/Any",
        )

    private fun ranges(): PlatformModule =
        module(
            id = RANGES,
            dependencies = listOf(BUILTINS),
            sourcePath = "libraries/stdlib-ranges/kotlin/ranges/IntRange.kt",
            symbol = "kotlin.ranges/IntRange",
        )

    private fun terminal(): PlatformModule {
        val module =
            module(
                id = PlatformModuleId("std", "terminal"),
                dependencies = listOf(BUILTINS, RANGES),
                sourcePath = "libraries/std-terminal/kotlin/io/Console.kt",
                symbol = "kotlin.io/println(kotlin.Any?)",
                external = true,
            )
        val extraPath = "libraries/std-terminal/kotlin/io/Input.kt"
        val extraSource = "package kotlin.io\npublic external fun readln(): String".encodeToByteArray()
        return module.copy(
            sources = module.sources + PlatformSource(extraPath, ImmutableBytes.of(extraSource)),
            declarations =
                module.declarations +
                    PlatformDeclaration(
                        symbol = "kotlin.io/readln()",
                        signature = "readln()",
                        module = module.id,
                        sourcePath = extraPath,
                        startUtf16 = 0,
                        endUtf16 = extraSource.decodeToString().length,
                        trustedExternal = true,
                    ),
        )
    }

    private fun filesystem(): PlatformModule =
        module(
            id = PlatformModuleId("std", "filesystem"),
            dependencies = listOf(BUILTINS),
            sourcePath = "libraries/std-filesystem/kotlin/io/Files.kt",
            symbol = "kotlin.io/readText(kotlin.String)",
            external = true,
        )

    private fun module(
        id: PlatformModuleId,
        dependencies: List<PlatformModuleId> = emptyList(),
        metadata: ByteArray = byteArrayOf(1, 2, 3),
        sourcePath: String,
        sourceContent: ByteArray = "package example\npublic external fun sample(): Unit".encodeToByteArray(),
        symbol: String,
        external: Boolean = false,
    ): PlatformModule =
        PlatformModule(
            id = id,
            version = "1.0.0",
            dependencies = dependencies,
            metadata = ImmutableBytes.of(metadata),
            libraryFragment = null,
            sources = listOf(PlatformSource(sourcePath, ImmutableBytes.of(sourceContent))),
            declarations =
                listOf(
                    PlatformDeclaration(
                        symbol = symbol,
                        signature = symbol.substringAfter('/'),
                        module = id,
                        sourcePath = sourcePath,
                        startUtf16 = 0,
                        endUtf16 = sourceContent.decodeToString().length,
                        trustedExternal = external,
                    ),
                ),
            completionDeclarations = emptyList(),
        )

    private companion object {
        val BUILTINS = PlatformModuleId("compukters", "builtins")
        val RANGES = PlatformModuleId("stdlib", "ranges")
    }
}
