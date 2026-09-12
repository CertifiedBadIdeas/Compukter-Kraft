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

package ru.lazyhat.compukters.ide.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectManifestCodecTest {
    @Test
    fun `inline and expanded module tables have one semantic form`() {
        val inline =
            ProjectManifestCodec.decode(
                """
                format = 1
                name = "hello"

                [modules]
                std = { terminal = 2 }
                """.trimIndent(),
            )
        val expanded =
            ProjectManifestCodec.decode(
                """
                format = 1
                name = "hello"

                [modules.std]
                terminal = 2
                """.trimIndent(),
            )

        assertEquals(inline, expanded)
        assertEquals(ApiMajor(2), inline.modules.getValue(ModuleId("std", "terminal")))
    }

    @Test
    fun `quoted keys and input order encode canonically`() {
        val manifest =
            ProjectManifestCodec.decode(
                """
                name = "hello \"world\""
                format = 1

                [modules]
                "std" = { "terminal" = 2, filesystem = 1 }
                create = { kinetics = 3 }
                """.trimIndent(),
            )

        assertEquals(
            """
            format = 1
            name = "hello \"world\""

            [modules]
            create = { kinetics = 3 }
            std = { filesystem = 1, terminal = 2 }

            """.trimIndent(),
            ProjectManifestCodec.encode(manifest),
        )
        assertEquals(manifest, ProjectManifestCodec.decode(ProjectManifestCodec.encode(manifest)))
    }

    @Test
    fun `empty module table is valid and deterministic`() {
        val manifest = ProjectManifestCodec.decode("format = 1\nname = \"empty\"\n")

        assertEquals(emptyMap(), manifest.modules)
        assertEquals("format = 1\nname = \"empty\"\n\n[modules]\n", ProjectManifestCodec.encode(manifest))
    }

    @Test
    fun `manifest rejects syntax duplicates unknown fields and unsupported formats`() {
        invalid("format = 1\nname = \"x\"\nname = \"y\"")
        invalid("format = 1\nname = \"x\"\nunknown = true")
        invalid("format = 2\nname = \"x\"")
        invalid("format = \"1\"\nname = \"x\"")
        invalid("name = \"x\"")
        invalid("format = 1")
        invalid("format = 1\nname = \"x\"\nmodules = 1")
        invalid("format = 1\nname = \"x\"\n[modules]\nstd = { terminal = { major = 2 } }")
        invalid("format = 1\nname = \"unterminated")
    }

    @Test
    fun `manifest rejects invalid project names`() {
        listOf("", ".", "..", "a/b", "a\\b", "bad\u0000name", "\ud800").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { ProjectManifest.of(name, emptyMap()) }
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectManifest.of("a".repeat(65), emptyMap(), ProjectLimits(projectNameCodePoints = 64))
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectManifest.of(
                "é".repeat(65),
                emptyMap(),
                ProjectLimits(projectNameCodePoints = 128, projectNameUtf8Bytes = 128),
            )
        }
    }

    @Test
    fun `manifest rejects invalid module identities and majors`() {
        listOf(
            "Std" to "terminal",
            "std." to "terminal",
            "std" to "Terminal",
            "std" to "a".repeat(65),
        ).forEach { (provider, module) ->
            assertFailsWith<IllegalArgumentException>("$provider:$module") { ModuleId(provider, module) }
        }
        assertFailsWith<IllegalArgumentException> { ApiMajor(0) }
        assertFailsWith<IllegalArgumentException> { ApiMajor(65536) }

        invalid("format = 1\nname = \"x\"\n[modules]\nstd = { terminal = 0 }")
        invalid("format = 1\nname = \"x\"\n[modules]\nstd = { terminal = -1 }")
        invalid("format = 1\nname = \"x\"\n[modules]\nstd = { terminal = 1.0 }")
        invalid("format = 1\nname = \"x\"\n[modules]\nstd = { terminal = \"2\" }")
        invalid("format = 1\nname = \"x\"\n[modules]\nstd = { terminal = 65536 }")
    }

    @Test
    fun `manifest enforces byte and module-count limits`() {
        assertFailsWith<ManifestException> {
            ProjectManifestCodec.decode(
                "format = 1\nname = \"hello\"",
                ProjectLimits(manifestBytes = 8),
            )
        }
        assertFailsWith<ManifestException> {
            ProjectManifestCodec.decode(
                "format = 1\nname = \"hello\"\n[modules]\nstd = { one = 1, two = 2 }",
                ProjectLimits(modules = 1),
            )
        }
    }

    private fun invalid(source: String) {
        assertFailsWith<ManifestException>(source) { ProjectManifestCodec.decode(source) }
    }
}
