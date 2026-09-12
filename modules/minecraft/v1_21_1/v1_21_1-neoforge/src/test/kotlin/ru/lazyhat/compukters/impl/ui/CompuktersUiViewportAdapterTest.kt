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

package ru.lazyhat.compukters.impl.ui

import com.mojang.blaze3d.vertex.PoseStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompuktersUiViewportAdapterTest {
    @Test
    fun `root transform scales inside its boundary and always restores the pose`() {
        val viewport = CompuktersUiViewport.admit(1_920, 1_080, 4)
        val pose = PoseStack()

        viewport.withTransform(pose) {
            assertEquals(0.75f, pose.last().pose().m00())
            assertEquals(0.75f, pose.last().pose().m11())
        }
        assertEquals(1.0f, pose.last().pose().m00())
        assertEquals(1.0f, pose.last().pose().m11())

        assertFailsWith<IllegalStateException> {
            viewport.withTransform(pose) {
                throw IllegalStateException("test")
            }
        }
        assertEquals(1.0f, pose.last().pose().m00())
        assertEquals(1.0f, pose.last().pose().m11())
    }
}
