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

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporaryBudgetTest {
    @Test
    fun `request result is copied before exact request directory cleanup`() {
        val root = createTempDirectory("compukters-temporary-budget-")
        val outside = root.resolve("keep.txt").also { it.writeText("keep") }
        val budget = TemporaryBudget(root, WorkerLimits(temporaryFiles = 2, temporaryBytes = 8))

        val copied =
            budget.useRequestDirectory { request ->
                request.resolve("one.bin").writeBytes(byteArrayOf(1, 2, 3))
                request.resolve("two.bin").writeBytes(byteArrayOf(4, 5))
                Files.readAllBytes(request.resolve("one.bin"))
            }

        assertEquals(listOf<Byte>(1, 2, 3), copied.toList())
        assertTrue(Files.exists(outside))
        assertEquals(listOf(outside), Files.list(root).use { it.toList() })
        root.toFile().deleteRecursively()
    }

    @Test
    fun `file count byte overflow and symlinks are rejected and cleaned`() {
        val root = createTempDirectory("compukters-temporary-overflow-")
        val budget = TemporaryBudget(root, WorkerLimits(temporaryFiles = 1, temporaryBytes = 3))

        assertFailsWith<TemporaryBudgetException> {
            budget.useRequestDirectory { request -> request.resolve("large.bin").writeBytes(ByteArray(4)) }
        }
        assertTrue(Files.list(root).use { it.findAny().isEmpty })

        assertFailsWith<TemporaryBudgetException> {
            budget.useRequestDirectory { request ->
                request.resolve("first").writeText("a")
                request.resolve("second").writeText("b")
            }
        }
        assertTrue(Files.list(root).use { it.findAny().isEmpty })

        val outside = root.resolve("outside").also { it.writeText("safe") }
        assertFailsWith<TemporaryBudgetException> {
            budget.useRequestDirectory { request -> Files.createSymbolicLink(request.resolve("link"), outside) }
        }
        assertTrue(Files.exists(outside))
        assertFalse(Files.isSymbolicLink(outside))
        root.toFile().deleteRecursively()
    }

    @Test
    fun `temporary file count includes directories below the request root`() {
        val root = createTempDirectory("compukters-temporary-directories-")
        val budget = TemporaryBudget(root, WorkerLimits(temporaryFiles = 0))

        assertFailsWith<TemporaryBudgetException> {
            budget.useRequestDirectory { request -> request.resolve("source").createDirectories() }
        }

        assertTrue(Files.list(root).use { it.findAny().isEmpty })
        root.toFile().deleteRecursively()
    }
}
