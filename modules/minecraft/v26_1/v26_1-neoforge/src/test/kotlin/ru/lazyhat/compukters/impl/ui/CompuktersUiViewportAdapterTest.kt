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

import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import org.joml.Matrix3x2fStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CompuktersUiViewportAdapterTest {
    @Test
    fun `mapped mouse event preserves button identity and modifiers`() {
        val viewport = CompuktersUiViewport.admit(1_920, 1_080, 4)
        val button = MouseButtonInfo(1, 5)
        val event = MouseButtonEvent(240.0, 120.0, button)

        val mapped = viewport.map(event)

        assertEquals(320.0, mapped.x())
        assertEquals(160.0, mapped.y())
        assertSame(button, mapped.buttonInfo())
        assertEquals(1, mapped.button())
        assertEquals(5, mapped.modifiers())
    }

    @Test
    fun `root transform scales inside its boundary and always restores the pose`() {
        val viewport = CompuktersUiViewport.admit(1_920, 1_080, 4)
        val pose = Matrix3x2fStack(4)

        viewport.withTransform(pose) {
            assertEquals(0.75f, pose.m00())
            assertEquals(0.75f, pose.m11())
        }
        assertEquals(1.0f, pose.m00())
        assertEquals(1.0f, pose.m11())

        assertFailsWith<IllegalStateException> {
            viewport.withTransform(pose) {
                throw IllegalStateException("test")
            }
        }
        assertEquals(1.0f, pose.m00())
        assertEquals(1.0f, pose.m11())
    }
}
