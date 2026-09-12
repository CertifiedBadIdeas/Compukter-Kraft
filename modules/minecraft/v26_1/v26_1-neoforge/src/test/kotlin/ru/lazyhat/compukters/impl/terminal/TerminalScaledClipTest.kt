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

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.client.gui.navigation.ScreenRectangle
import org.joml.Matrix3x2f
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertTrue

class TerminalScaledClipTest {
    @Test
    fun `whole grid glyph clip contains fractional transformed grid edges`() {
        val geometry = TerminalRenderGeometry(640, 360, TerminalFontProfile.COZETTE)

        listOf(0.75f, 0.25f).forEach { scale ->
            val clip = geometry.glyphClip
            val transformed =
                ScreenRectangle(clip.left, clip.top, clip.width, clip.height)
                    .transformAxisAligned(Matrix3x2f().scale(scale))

            assertTrue(transformed.right() >= ceil(geometry.grid.right * scale).toInt())
            assertTrue(transformed.bottom() >= ceil(geometry.grid.bottom * scale).toInt())
        }
    }
}
