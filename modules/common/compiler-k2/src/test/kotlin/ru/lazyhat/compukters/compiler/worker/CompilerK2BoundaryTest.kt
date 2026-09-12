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

package ru.lazyhat.compukters.compiler.worker

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import kotlin.test.Test
import kotlin.test.assertEquals

class CompilerK2BoundaryTest {
    @Test
    fun `worker can read client protocol source bytes`() {
        assertEquals("val answer = 42", BinaryValue.of("val answer = 42".encodeToByteArray()).toByteArray().decodeToString())
    }

    @Test
    fun `worker owns the pinned K2 compiler`() {
        val compiler = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        val version = Class.forName("org.jetbrains.kotlin.config.KotlinCompilerVersion").getMethod("getVersion").invoke(null)

        assertEquals("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", compiler.name)
        assertEquals("2.4.10", version)
    }
}
