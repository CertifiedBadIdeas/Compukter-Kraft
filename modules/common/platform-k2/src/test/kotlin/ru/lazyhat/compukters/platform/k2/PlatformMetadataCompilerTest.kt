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

import ru.lazyhat.compukters.platform.bundle.PlatformDefaultArgument
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.build.PlatformMetadataCodec
import ru.lazyhat.compukters.platform.k2.build.PlatformMetadataCompiler
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformMetadataCompilerTest {
    @Test
    fun `enum defaults retain their qualified entry and hidden receiver position`() {
        val metadata =
            PlatformMetadataCompiler().compile(
                PlatformModuleId("sample", "defaults"),
                listOf(
                    PlatformSource(
                        "Defaults.kt",
                        ImmutableBytes.of(
                            """
                            package sample

                            object Api {
                                enum class Power { WEAK, DIRECT }
                            }

                            value class Side(val index: Int) {
                                fun set(level: Int, power: Api.Power = Api.Power.WEAK) = level
                            }
                            """.trimIndent().encodeToByteArray(),
                        ),
                    ),
                ),
            )

        val set = metadata.declarations.single { it.symbol == "sample.Side.set" }
        assertEquals(
            listOf(null, null, PlatformDefaultArgument.EnumEntry("sample.Api.Power.WEAK")),
            set.defaultArguments,
        )
    }

    @Test
    fun `int defaults retain decimal hexadecimal and signed values`() {
        val metadata =
            PlatformMetadataCompiler().compile(
                PlatformModuleId("sample", "defaults"),
                listOf(
                    PlatformSource(
                        "Defaults.kt",
                        ImmutableBytes.of(
                            """
                            package sample

                            object Api {
                                fun configure(decimal: Int = 100, hexadecimal: Int = 0x18, minimum: Int = -2147483648) = decimal
                            }
                            """.trimIndent().encodeToByteArray(),
                        ),
                    ),
                ),
            )

        val configure = metadata.declarations.single { it.symbol == "sample.Api.configure" }
        val defaults =
            listOf(
                PlatformDefaultArgument.IntValue(100),
                PlatformDefaultArgument.IntValue(24),
                PlatformDefaultArgument.IntValue(Int.MIN_VALUE),
            )
        assertEquals(defaults, configure.defaultArguments)
        assertEquals(
            defaults,
            PlatformMetadataCodec
                .decode(metadata.metadata)
                .declarations
                .single { it.symbol == "sample.Api.configure" }
                .defaultArguments,
        )
    }

    @Test
    fun `unsupported platform default expressions remain rejected`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PlatformMetadataCompiler().compile(
                    PlatformModuleId("sample", "defaults"),
                    listOf(
                        PlatformSource(
                            "Defaults.kt",
                            ImmutableBytes.of(
                                """
                                package sample

                                fun configuredDefault(): Int = 100
                                fun configure(value: Int = configuredDefault()) = value
                                """.trimIndent().encodeToByteArray(),
                            ),
                        ),
                    ),
                )
            }

        assertTrue(failure.message.orEmpty().contains("qualified enum entry or Int literal"))
    }

    @Test
    fun `public nominal types constructor properties and enum entries are linkable`() {
        val metadata =
            PlatformMetadataCompiler().compile(
                PlatformModuleId("sample", "results"),
                listOf(
                    PlatformSource(
                        "Results.kt",
                        ImmutableBytes.of(
                            """
                            package sample

                            sealed interface Result {
                                data class Ok(val code: Int) : Result
                            }

                            enum class Reason {
                                MISSING,
                            }

                            private data class Hidden(val code: Int)

                            value class Signal(val level: Int)
                            """.trimIndent().encodeToByteArray(),
                        ),
                    ),
                ),
            )

        assertTrue(
            metadata.libraryDeclarations.any {
                it.symbol == "sample.Result" && it.kind.name == "TYPE"
            },
        )
        assertTrue(
            metadata.libraryDeclarations.any {
                it.symbol == "sample.Result.Ok.code" && it.kind.name == "FIELD"
            },
        )
        assertTrue(
            metadata.libraryDeclarations.any {
                it.symbol == "sample.Reason.MISSING" && it.kind.name == "FIELD"
            },
        )
        assertFalse(metadata.libraryDeclarations.any { it.symbol.startsWith("sample.Hidden") })
        assertFalse(metadata.libraryDeclarations.any { it.symbol.startsWith("sample.Signal") })
    }
}
