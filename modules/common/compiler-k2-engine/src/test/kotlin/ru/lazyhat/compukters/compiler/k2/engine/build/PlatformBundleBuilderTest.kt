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

package ru.lazyhat.compukters.compiler.k2.engine.build

import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.TrustedIntrinsicRegistry
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlatformBundleBuilderTest {
    @Test
    fun `bundle construction is deterministic and preserves exact sources`() {
        val root = fixture()
        val builder = PlatformBundleBuilder(TrustedIntrinsicRegistry.empty(), emptySet())

        val first = builder.build(root, root.resolve("modules.toml"))
        val second = builder.build(root, root.resolve("modules.toml"))
        val firstBytes = PlatformBundleCodec.encode(first)
        val secondBytes = PlatformBundleCodec.encode(second)

        assertContentEquals(firstBytes, secondBytes)
        assertEquals(
            "package kotlin\n",
            first.builtins.sources
                .single()
                .content
                .toByteArray()
                .decodeToString(),
        )
        assertEquals(
            "package sample\n",
            first.modules
                .single()
                .sources
                .single()
                .content
                .toByteArray()
                .decodeToString(),
        )
    }

    @Test
    fun `construction rejects a catalog without mandatory builtins`() {
        val root = createTempDirectory("platform-no-builtins")
        root.resolve("modules.toml").writeText(
            """
            [[module]]
            id = "test:library"
            version = "1.0.0"
            dependencies = []
            sources = ["library/**/*.kt"]
            """.trimIndent(),
        )
        root.resolve("library/Library.kt").apply {
            parent.createDirectories()
            writeText("package sample\n")
        }

        val failure = assertFailsWith<IllegalArgumentException> { PlatformBundleBuilder().build(root, root.resolve("modules.toml")) }
        assertTrue(failure.message.orEmpty().contains("kotlin:builtins"))
    }

    private fun fixture() =
        createTempDirectory("platform-builder").also { root ->
            root.resolve("modules.toml").writeText(
                """
                [[module]]
                id = "kotlin:builtins"
                version = "1.0.0"
                dependencies = []
                sources = ["builtins/**/*.kt"]

                [[module]]
                id = "test:library"
                version = "1.0.0"
                dependencies = ["kotlin:builtins"]
                sources = ["library/**/*.kt"]
                """.trimIndent(),
            )
            root.resolve("builtins/Builtins.kt").apply {
                parent.createDirectories()
                writeText("package kotlin\n")
            }
            root.resolve("library/Library.kt").apply {
                parent.createDirectories()
                writeText("package sample\n")
            }
        }
}
