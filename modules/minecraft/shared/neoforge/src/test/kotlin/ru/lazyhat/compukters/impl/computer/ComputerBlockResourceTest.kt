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

package ru.lazyhat.compukters.impl.computer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ComputerBlockResourceTest {
    @Test
    fun `blue workbench textures are restored byte for byte`() {
        EXPECTED_TEXTURE_HASHES.forEach { (name, expectedHash) ->
            val bytes = resourceBytes("/assets/compukters/textures/block/compukter/$name.png")
            val image = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))

            assertEquals(16, image.width, name)
            assertEquals(16, image.height, name)
            assertEquals(expectedHash, bytes.sha256(), name)
        }
    }

    @Test
    fun `model assigns distinct front back and side textures`() {
        val model = resourceJson("/assets/compukters/models/block/compukter.json")
        val textures = model.getAsJsonObject("textures")
        assertEquals("compukters:block/compukter/front", textures["front"].asString)
        assertEquals("compukters:block/compukter/back", textures["back"].asString)
        assertEquals("compukters:block/compukter/side", textures["side"].asString)

        val faces = model.getAsJsonArray("elements")[0].asJsonObject.getAsJsonObject("faces")
        assertEquals("#front", faces.textureFor("north"))
        assertEquals("#back", faces.textureFor("south"))
        listOf("east", "west", "up", "down").forEach { face ->
            assertEquals("#side", faces.textureFor(face), face)
        }
    }

    @Test
    fun `blockstate rotates the front for every horizontal facing`() {
        val variants =
            resourceJson("/assets/compukters/blockstates/compukter.json")
                .getAsJsonObject("variants")

        mapOf("north" to 0, "east" to 90, "south" to 180, "west" to 270).forEach { (facing, rotation) ->
            val variant = variants.getAsJsonObject("facing=$facing")
            assertEquals("compukters:block/compukter", variant["model"].asString, facing)
            assertEquals(rotation, variant["y"].asInt, facing)
        }
    }

    @Test
    fun `creative tab has a localized title`() {
        val translations = resourceJson("/assets/compukters/lang/en_us.json")

        assertEquals("Compukters", translations["itemGroup.compukters"].asString)
    }

    private fun resourceBytes(path: String): ByteArray = assertNotNull(javaClass.getResourceAsStream(path), path).use { it.readAllBytes() }

    private fun resourceJson(path: String): JsonObject =
        resourceBytes(path).inputStream().reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }

    private fun ByteArray.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun JsonObject.textureFor(face: String): String = getAsJsonObject(face)["texture"].asString

    companion object {
        private val EXPECTED_TEXTURE_HASHES =
            mapOf(
                "front" to "5c3f69e98125aef9809647c73ae59d1fb25e8891c94cd0e366881519b37785a0",
                "side" to "0881e37bb6164825e7355b5b1304cb2837762fa7b9c45340bbf3b927369b7c49",
                "back" to "8d4d20e411022ead50de036fcf15f59cc1661e10fdcd889a5d487cc5279e6fc2",
            )
    }
}
