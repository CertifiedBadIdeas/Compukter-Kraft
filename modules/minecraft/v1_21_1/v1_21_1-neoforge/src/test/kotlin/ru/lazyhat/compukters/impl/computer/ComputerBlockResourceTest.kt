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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ComputerBlockResourceTest {
    @Test
    fun `blockstate uses the legacy variant model format`() {
        val variants = resourceJson("/assets/compukters/blockstates/compukter.json").getAsJsonObject("variants")

        mapOf("north" to 0, "east" to 90, "south" to 180, "west" to 270).forEach { (facing, rotation) ->
            val variant = variants.getAsJsonObject("facing=$facing")
            assertEquals("compukters:block/compukter", variant["model"].asString, facing)
            assertEquals(rotation, variant["y"].asInt, facing)
        }
    }

    @Test
    fun `item model uses the legacy parent format`() {
        val model = resourceJson("/assets/compukters/models/item/compukter.json")

        assertEquals("compukters:block/compukter", model["parent"].asString)
    }

    private fun resourceJson(path: String): JsonObject =
        assertNotNull(javaClass.getResourceAsStream(path), path).reader(Charsets.UTF_8).use {
            JsonParser.parseReader(it).asJsonObject
        }
}
