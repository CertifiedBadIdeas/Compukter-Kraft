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

package ru.lazyhat.compukters.ide.analysis.k2.standalone

import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.k2.query.K2QueryFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectSourceIndexTest {
    @Test
    fun `snapshot indexes current declarations and replaces one file atomically`() {
        val firstSource =
            """
            @file:JvmName("FirstFacade")
            package sample

            class Outer { object Nested }
            typealias Alias = Outer
            fun function() = 1
            val property = 2
            """.trimIndent()
        val secondSource = "package other\nobject Stable"

        K2QueryFixture.source("first.kt" to firstSource, "second.kt" to secondSource).use { fixture ->
            val first = fixture.snapshot.files.getValue(VirtualSourcePath.kotlin("first.kt"))
            val second = fixture.snapshot.files.getValue(VirtualSourcePath.kotlin("second.kt"))
            val index = MutableProjectSourceIndex(listOf(first, second))
            val initial = index.snapshot()

            assertSame(
                first,
                initial.classesById
                    .getValue(ClassId.topLevel(FqName("sample.Outer")))
                    .single()
                    .containingKtFile,
            )
            assertEquals(
                "Nested",
                initial.classesById
                    .getValue(ClassId.fromString("sample/Outer.Nested"))
                    .single()
                    .name,
            )
            assertEquals(
                "Alias",
                initial.typeAliasesById
                    .getValue(ClassId.topLevel(FqName("sample.Alias")))
                    .single()
                    .name,
            )
            assertEquals(
                "function",
                initial.functionsById
                    .getValue(CallableId(FqName("sample"), Name.identifier("function")))
                    .single()
                    .name,
            )
            assertEquals(
                "property",
                initial.propertiesById
                    .getValue(CallableId(FqName("sample"), Name.identifier("property")))
                    .single()
                    .name,
            )
            assertEquals(setOf(Name.identifier("Outer"), Name.identifier("Alias")), initial.classifierNamesByPackage[FqName("sample")])
            assertEquals(setOf(Name.identifier("function"), Name.identifier("property")), initial.callableNamesByPackage[FqName("sample")])
            assertSame(first, initial.filesByFacade.getValue(first.javaFileFacadeFqName).single())
            assertTrue(FqName("sample") in initial.packages)
            assertTrue(FqName("other") in initial.packages)

            val rebuildsBefore = index.rebuildCount
            fixture.update("first.kt" to "package changed\nobject Replacement")
            index.replace(first)
            val updated = index.snapshot()

            assertEquals(rebuildsBefore + 1, index.rebuildCount)
            assertTrue(ClassId.topLevel(FqName("sample.Outer")) !in updated.classesById)
            assertTrue(ClassId.topLevel(FqName("sample.Alias")) !in updated.typeAliasesById)
            assertTrue(CallableId(FqName("sample"), Name.identifier("function")) !in updated.functionsById)
            assertEquals(
                "Replacement",
                updated.classesById
                    .getValue(ClassId.topLevel(FqName("changed.Replacement")))
                    .single()
                    .name,
            )
            assertSame(
                second,
                updated.classesById
                    .getValue(ClassId.topLevel(FqName("other.Stable")))
                    .single()
                    .containingKtFile,
            )
            assertTrue(FqName("sample") !in updated.packages)
            assertTrue(FqName("changed") in updated.packages)
        }
    }
}
