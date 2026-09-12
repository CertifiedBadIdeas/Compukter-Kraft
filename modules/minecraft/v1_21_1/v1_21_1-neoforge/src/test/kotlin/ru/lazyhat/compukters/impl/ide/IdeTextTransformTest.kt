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

package ru.lazyhat.compukters.impl.ide

import com.mojang.blaze3d.vertex.PoseStack
import org.joml.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals

class IdeTextTransformTest {
    @Test
    fun `clockwise text transform advances downward and restores the GUI pose`() {
        val pose = PoseStack()

        val (origin, advance) =
            withIdeTextTransform(pose, IdeTextRotation.Clockwise90, 20, 30) {
                transformed(pose, 0f, 0f) to transformed(pose, 10f, 0f)
            }

        assertVector(20f, 30f, origin)
        assertVector(20f, 40f, advance)
        assertVector(3f, 4f, transformed(pose, 3f, 4f))
    }

    @Test
    fun `ordinary text leaves the GUI pose unchanged`() {
        val pose = PoseStack()

        val point =
            withIdeTextTransform(pose, IdeTextRotation.None, 20, 30) {
                transformed(pose, 3f, 4f)
            }

        assertVector(3f, 4f, point)
        assertVector(3f, 4f, transformed(pose, 3f, 4f))
    }

    private fun transformed(
        pose: PoseStack,
        x: Float,
        y: Float,
    ): Vector3f = pose.last().pose().transformPosition(x, y, 0f, Vector3f())

    private fun assertVector(
        expectedX: Float,
        expectedY: Float,
        actual: Vector3f,
    ) {
        assertEquals(expectedX, actual.x, 0.001f)
        assertEquals(expectedY, actual.y, 0.001f)
    }
}
