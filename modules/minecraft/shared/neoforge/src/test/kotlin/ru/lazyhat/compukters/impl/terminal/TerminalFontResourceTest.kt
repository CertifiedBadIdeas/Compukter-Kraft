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

package ru.lazyhat.compukters.impl.terminal

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.io.path.inputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalFontResourceTest {
    @Test
    fun `committed resources match every terminal font contract`() {
        EXPECTED_FONTS.forEach(::assertFont)
    }

    @Test
    fun `pinned upstream BDF has the reviewed digest`() {
        PINNED_INPUTS.forEach { (relativePath, expectedDigest) ->
            val source = repositoryRoot().resolve(relativePath)
            val digest = source.inputStream().use { MessageDigest.getInstance("SHA-256").digest(it.readAllBytes()) }
            assertEquals(expectedDigest, digest.joinToString(separator = "") { "%02x".format(it) }, relativePath)
        }
    }

    private fun assertFont(expected: ExpectedFont) {
        val json = runtimeResource("/assets/compukters/font/terminal/${expected.id}.json").reader().use { it.readText() }
        val provider =
            GSON
                .fromJson(json, JsonObject::class.java)
                .asJsonObject
                .getAsJsonArray("providers")
                .single()
                .asJsonObject
        val characterRows = provider.getAsJsonArray("chars").map { it.asString }
        val codePoints = characterRows.flatMap { it.codePoints().boxed().toList() }.filterNot { it == 0 }.toSet()
        val image = runtimeResource("/assets/compukters/textures/font/terminal/${expected.id}.png").use(ImageIO::read)

        assertEquals("bitmap", provider.get("type").asString)
        assertEquals("compukters:font/terminal/${expected.id}.png", provider.get("file").asString)
        assertEquals(expected.height, provider.get("height").asInt)
        assertEquals(expected.ascent, provider.get("ascent").asInt)
        assertEquals(characterRows.first().codePointCount(0, characterRows.first().length) * expected.width, image.width)
        assertEquals(characterRows.size * expected.height, image.height)
        assertTrue(expected.requiredCodePoints.all(codePoints::contains))
        assertTrue('"'.code in codePoints)
        assertTrue('\\'.code in codePoints)
        assertFalse(json.contains("minecraft:uniform"))
        assertFalse(json.contains("minecraft:default"))
    }

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            ?: error("Could not locate repository root")

    private fun runtimeResource(path: String) =
        requireNotNull(TerminalFontResourceTest::class.java.getResourceAsStream(path)) {
            "Missing runtime resource $path"
        }

    private data class ExpectedFont(
        val id: String,
        val width: Int,
        val height: Int,
        val ascent: Int,
        val requiredCodePoints: Set<Int>,
    )

    private companion object {
        val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val EXPECTED_FONTS =
            listOf(
                ExpectedFont(
                    "cozette",
                    6,
                    13,
                    10,
                    listOf('A', 'z', '0', 'Ё', 'ё', 'Ж', 'я', '←', '↑', '→', '↓', '─', '│', '┼', '█')
                        .map(Char::code)
                        .plus(0xFFFD)
                        .toSet(),
                ),
                ExpectedFont("dina", 6, 10, 8, setOf('A'.code, 'z'.code, '0'.code, '?'.code)),
                ExpectedFont("proggy_tiny", 6, 10, 8, setOf('A'.code, 'z'.code, '0'.code, '?'.code)),
            )
        val PINNED_INPUTS =
            mapOf(
                "tools/fonts/cozette/v.1.30.0/cozette.bdf" to
                    "8d740166af3a14053773ac7a8846bf288c8818a44f73ab28d4725d91b03a5639",
                "tools/fonts/dina/v2.92/Dina_r400-6.bdf" to
                    "0efe660581b38b8025a46401d2c919c7e654fc21c81979e8d31e714c414deba1",
                "tools/fonts/proggy/139ec08a/ProggyTiny.pcf.gz" to
                    "a8beed341cfa79272b80c48d3237c417ff7b155468b95a634a70ba918d6d503a",
            )
    }
}
