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

package ru.lazyhat.compukters.compiler.k2.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EngineBoundaryTest {
    @Test
    fun `engine owns the IR compilation bridge`() {
        assertEquals("ru.lazyhat.compukters.compiler.k2.engine.CompilationBridge", CompilationBridge::class.qualifiedName)
    }

    @Test
    fun `engine runtime cannot see worker IDE or Minecraft implementations`() {
        listOf(
            "ru.lazyhat.compukters.compiler.runtime.ServerCompilerService",
            "ru.lazyhat.compukters.compiler.worker.server.CompilerWorkerServer",
            "ru.lazyhat.compukters.ide.client.IdeClient",
            "net.minecraft.client.Minecraft",
            "net.neoforged.neoforge.common.NeoForge",
        ).forEach { name ->
            assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
        }
    }
}
