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

package ru.lazyhat.compukters.ide.analysis.k2.query

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeclarationQueryTest {
    @Test
    fun `navigation resolves every link styled declaration kind`() {
        val source =
            """
            class Box(val property: Int) {
                fun member(parameter: Int) {
                    val local = parameter
                    println(local)
                }
            }
            fun top() = Box(1)
            fun use() {
                top()
                Box(2).property
                Box(3).member(4)
            }
            """.trimIndent()
        val usages =
            listOf(
                "class" to source.lastIndexOf("Box(2)") + 1,
                "top-level function" to source.lastIndexOf("top()") + 1,
                "member function" to source.lastIndexOf("member(4)") + 1,
                "property" to source.lastIndexOf("property") + 1,
                "local variable" to source.lastIndexOf("local") + 1,
                "parameter" to source.indexOf("parameter", source.indexOf("val local")) + 1,
            )
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            usages.forEach { (kind, offset) ->
                val result =
                    fixture.execute(
                        AnalysisQuery.Declaration(
                            fixture.identity,
                            VirtualSourcePath.kotlin("main.kt"),
                            offset,
                        ),
                    ) as AnalysisResult.Declaration

                assertEquals(1, result.locations.size, kind)
                assertIs<DeclarationOrigin.Project>(result.locations.single().origin, kind)
            }
        }
    }

    @Test
    fun `navigation resolves local top level member and extension declarations`() {
        val declarations =
            """
            package sample

            fun top() = Unit
            class Box { fun member() = Unit }
            fun String.extension() = Unit
            """.trimIndent()
        val usage =
            """
            package sample

            fun use(box: Box) {
                val local = 1
                println(local)
                top()
                box.member()
                "value".extension()
            }
            """.trimIndent()
        K2QueryFixture.source("declarations.kt" to declarations, "usage.kt" to usage).use { fixture ->
            val expectations =
                listOf(
                    "local" to sourceLocation("usage.kt", usage.indexOf("local ="), "local"),
                    "top()" to sourceLocation("declarations.kt", declarations.indexOf("top"), "top"),
                    "member()" to sourceLocation("declarations.kt", declarations.indexOf("member"), "member"),
                    "extension()" to sourceLocation("declarations.kt", declarations.indexOf("extension"), "extension"),
                )

            expectations.forEach { (usageText, expected) ->
                val offset = usage.lastIndexOf(usageText) + 1
                val result =
                    fixture.execute(
                        AnalysisQuery.Declaration(fixture.identity, VirtualSourcePath.kotlin("usage.kt"), offset),
                    ) as AnalysisResult.Declaration

                assertEquals(listOf(expected), result.locations, usageText)
            }
        }
    }

    @Test
    fun `navigation keeps UTF-16 declaration ranges`() {
        val source = "fun привет() = Unit\nval 😀 = ::привет"
        K2QueryFixture.source("unicode.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.Declaration(
                        fixture.identity,
                        VirtualSourcePath.kotlin("unicode.kt"),
                        source.lastIndexOf("привет") + 1,
                    ),
                ) as AnalysisResult.Declaration

            assertEquals(
                listOf(sourceLocation("unicode.kt", source.indexOf("привет"), "привет")),
                result.locations,
            )
        }
    }

    @Test
    fun `navigation returns every declaration candidate for an ambiguous overload reference`() {
        val source =
            """
            fun choose(value: Int) = value
            fun choose(value: String) = value
            val unresolved = ::choose
            """.trimIndent()
        K2QueryFixture.source("overloads.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.Declaration(
                        fixture.identity,
                        VirtualSourcePath.kotlin("overloads.kt"),
                        source.lastIndexOf("choose") + 1,
                    ),
                ) as AnalysisResult.Declaration

            assertEquals(
                listOf(
                    sourceLocation("overloads.kt", source.indexOf("choose"), "choose"),
                    sourceLocation("overloads.kt", source.indexOf("choose", source.indexOf("choose") + 1), "choose"),
                ),
                result.locations,
            )
        }
    }

    @Test
    fun `navigation reports binary only Guest API source as unavailable`() {
        val source = "import compukter.terminal.Terminal\nfun main() = Terminal.write(\"ok\")"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.Declaration(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.indexOf("write") + 1,
                    ),
                ) as AnalysisResult.Declaration

            val unavailable = assertIs<DeclarationLocation.SourceUnavailable>(result.locations.single())
            val bundle = assertIs<DeclarationOrigin.Platform>(unavailable.origin)
            assertEquals("std:terminal", bundle.identity.name)
        }
    }

    @Test
    fun `navigation maps an attached Guest API declaration to its source`() {
        val source = "import compukter.terminal.Terminal\nfun main() = Terminal.write(\"ok\")"
        val sourcePath = "compukters-platform/sources/libraries/std-terminal/compukter/terminal/Terminal.kt"
        val guestApi = Path.of(requireNotNull(System.getProperty("compukters.test.guestApi")))
        val guestSource =
            ZipFile(guestApi.toFile()).use { archive ->
                archive.getInputStream(requireNotNull(archive.getEntry(sourcePath))).reader().readText()
            }
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.Declaration(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.indexOf("write") + 1,
                    ),
                ) as AnalysisResult.Declaration

            assertEquals(
                listOf(
                    DeclarationLocation.Source(
                        DeclarationOrigin.Platform(
                            fixture.snapshot.moduleIdentities.values
                                .single { it.name == "std:terminal" },
                        ),
                        VirtualSourcePath.kotlin(sourcePath),
                        EditorRange(
                            guestSource.indexOf("write(payload"),
                            guestSource.indexOf("write(payload") + "write".length,
                        ),
                    ),
                ),
                result.locations,
            )
        }
    }

    @Test
    fun `navigation maps an overloaded platform println to its exact source`() {
        val source = "fun main() { println(7) }"
        val sourcePath = "compukters-platform/sources/libraries/std-terminal/kotlin/io/Console.kt"
        val guestApi = Path.of(requireNotNull(System.getProperty("compukters.test.guestApi")))
        val guestSource =
            ZipFile(guestApi.toFile()).use { archive ->
                archive.getInputStream(requireNotNull(archive.getEntry(sourcePath))).reader().readText()
            }
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.Declaration(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.indexOf("println") + 1,
                    ),
                ) as AnalysisResult.Declaration
            val declarationStart = guestSource.indexOf("println(value: Int)")

            assertEquals(
                listOf(
                    DeclarationLocation.Source(
                        DeclarationOrigin.Platform(
                            fixture.snapshot.moduleIdentities.values
                                .single { it.name == "std:terminal" },
                        ),
                        VirtualSourcePath.kotlin(sourcePath),
                        EditorRange(declarationStart, declarationStart + "println".length),
                    ),
                ),
                result.locations,
            )
        }
    }

    @Test
    fun `navigation maps int array factory to its platform source`() {
        val source = "fun main() { val values = intArrayOf(7, 11) }"
        val sourcePath = "compukters-platform/sources/builtins/kotlin/Arrays.kt"
        val guestApi = Path.of(requireNotNull(System.getProperty("compukters.test.guestApi")))
        val guestSource =
            ZipFile(guestApi.toFile()).use { archive ->
                archive.getInputStream(requireNotNull(archive.getEntry(sourcePath))).reader().readText()
            }
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.Declaration(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.indexOf("intArrayOf") + 1,
                    ),
                ) as AnalysisResult.Declaration
            val declarationStart = guestSource.indexOf("intArrayOf(vararg")

            assertEquals(
                listOf(
                    DeclarationLocation.Source(
                        DeclarationOrigin.Platform(
                            fixture.snapshot.moduleIdentities.values
                                .single { it.name == "kotlin:builtins" },
                        ),
                        VirtualSourcePath.kotlin(sourcePath),
                        EditorRange(declarationStart, declarationStart + "intArrayOf".length),
                    ),
                ),
                result.locations,
            )
        }
    }

    private fun sourceLocation(
        path: String,
        start: Int,
        name: String,
    ) = DeclarationLocation.Source(
        DeclarationOrigin.Project,
        VirtualSourcePath.kotlin(path),
        EditorRange(start, start + name.length),
    )
}
