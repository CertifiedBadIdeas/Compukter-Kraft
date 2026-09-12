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

package ru.lazyhat.compukters.impl.ide

import org.joml.Matrix3x2fStack
import org.joml.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals

class IdeTextTransformTest {
    @Test
    fun `clockwise text transform advances downward and restores the GUI pose`() {
        val pose = Matrix3x2fStack(2)

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
        val pose = Matrix3x2fStack(2)

        val point =
            withIdeTextTransform(pose, IdeTextRotation.None, 20, 30) {
                transformed(pose, 3f, 4f)
            }

        assertVector(3f, 4f, point)
        assertVector(3f, 4f, transformed(pose, 3f, 4f))
    }

    private fun transformed(
        pose: Matrix3x2fStack,
        x: Float,
        y: Float,
    ): Vector2f = pose.transformPosition(x, y, Vector2f())

    private fun assertVector(
        expectedX: Float,
        expectedY: Float,
        actual: Vector2f,
    ) {
        assertEquals(expectedX, actual.x, 0.001f)
        assertEquals(expectedY, actual.y, 0.001f)
    }
}
