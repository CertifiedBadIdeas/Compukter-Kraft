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

package ru.lazyhat.compukters.ide.analysis.k2.formatter

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class IsolatedKotlinFormatterTest {
    @Test
    fun `formatter compiler runtime is isolated from the K2 worker classloader`() {
        val classpath =
            requireNotNull(System.getProperty("compukters.test.kotlinFormatterClasspath"))
                .split(File.pathSeparator)
                .map(Path::of)
        IsolatedKotlinFormatter.open(classpath).use { formatter ->
            assertNotSame(IsolatedKotlinFormatter::class.java.classLoader, formatter.implementationClassLoader())
            assertEquals(
                "fun main() {\n    println(1)\n}\n",
                formatter.format("main.kt", "fun main(){println(1)}"),
            )
        }
    }
}
