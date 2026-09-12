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
 */

package ru.lazyhat.compukters.impl.computer

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ComputerItemResourceTest {
    @Test
    fun `item definition points to the shared block model`() {
        val item =
            assertNotNull(javaClass.getResourceAsStream("/assets/compukters/items/compukter.json"))
                .reader(Charsets.UTF_8)
                .use { JsonParser.parseReader(it).asJsonObject }
        val model = item.getAsJsonObject("model")

        assertEquals("minecraft:model", model["type"].asString)
        assertEquals("compukters:block/compukter", model["model"].asString)
    }
}
