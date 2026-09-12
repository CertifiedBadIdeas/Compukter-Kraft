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

package ru.lazyhat.compukters.ide.analysis.k2.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisMeasurementFixturesTest {
    @Test
    fun `single-file fixture contains exactly five hundred Kotlin lines`() {
        val fixture = AnalysisMeasurementFixtures.singleFile()

        assertEquals(1, fixture.sources.size)
        assertEquals(500, fixture.totalLines)
        assertTrue("fun candidate()" in fixture.sources.single().text)
        assertTrue("fun completionProbe() { can }" in fixture.sources.single().text)
    }

    @Test
    fun `multi-file fixture contains five related files within five thousand lines`() {
        val fixture = AnalysisMeasurementFixtures.fiveFiles()

        assertEquals(5, fixture.sources.size)
        assertEquals(4_500, fixture.totalLines)
        assertTrue(fixture.sources.zipWithNext().all { (left, right) -> left.path.value < right.path.value })
        fixture.sources.drop(1).forEachIndexed { index, source ->
            assertTrue("file${index}Seed()" in source.text)
        }
    }

    @Test
    fun `maximum file fixture reaches file limit within line budget`() {
        val fixture = AnalysisMeasurementFixtures.maximumFiles()

        assertEquals(512, fixture.sources.size)
        assertTrue(fixture.totalLines in 4_900..5_000)
        assertEquals(
            512,
            fixture.sources
                .map { it.path }
                .toSet()
                .size,
        )
        assertTrue(
            fixture.sources
                .last()
                .text
                .contains("object ActiveObject"),
        )
        assertEquals("file0S", fixture.completionPrefix)
    }
}
