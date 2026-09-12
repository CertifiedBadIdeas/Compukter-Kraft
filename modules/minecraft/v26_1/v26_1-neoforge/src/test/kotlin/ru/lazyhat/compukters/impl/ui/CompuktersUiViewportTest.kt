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

package ru.lazyhat.compukters.impl.ui

import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import org.joml.Matrix3x2fStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompuktersUiViewportTest {
    @Test
    fun `full HD admits a crisp scale three viewport`() {
        val viewport = CompuktersUiViewport.admit(1_920, 1_080, 4)

        assertEquals(3, viewport.physicalScale)
        assertEquals(4, viewport.minecraftGuiScale)
        assertEquals(640, viewport.width)
        assertEquals(360, viewport.height)
        assertEquals(0.75f, viewport.renderScale)
        assertTrue(viewport.supported)
    }

    @Test
    fun `QHD admits scale four and remainder height remains usable`() {
        val qhd = CompuktersUiViewport.admit(2_560, 1_440, 4)
        val tall = CompuktersUiViewport.admit(1_920, 1_200, 4)

        assertEquals(4, qhd.physicalScale)
        assertEquals(640, qhd.width)
        assertEquals(360, qhd.height)
        assertEquals(3, tall.physicalScale)
        assertEquals(640, tall.width)
        assertEquals(400, tall.height)
    }

    @Test
    fun `physical admission is independent from Minecraft GUI scale`() {
        val smallMinecraftUi = CompuktersUiViewport.admit(1_920, 1_080, 1)
        val largeMinecraftUi = CompuktersUiViewport.admit(1_920, 1_080, 4)

        assertEquals(smallMinecraftUi.physicalScale, largeMinecraftUi.physicalScale)
        assertEquals(smallMinecraftUi.width, largeMinecraftUi.width)
        assertEquals(smallMinecraftUi.height, largeMinecraftUi.height)
        assertEquals(3.0f, smallMinecraftUi.renderScale)
        assertEquals(0.75f, largeMinecraftUi.renderScale)
    }

    @Test
    fun `undersized and invalid dimensions produce bounded unsupported viewport`() {
        val undersized = CompuktersUiViewport.admit(639, 359, 4)
        val invalid = CompuktersUiViewport.admit(0, -1, 0)

        assertEquals(1, undersized.physicalScale)
        assertEquals(639, undersized.width)
        assertEquals(359, undersized.height)
        assertFalse(undersized.supported)
        assertEquals(1, invalid.physicalScale)
        assertEquals(1, invalid.minecraftGuiScale)
        assertEquals(0, invalid.width)
        assertEquals(0, invalid.height)
        assertFalse(invalid.supported)
    }

    @Test
    fun `pointer coordinates and deltas use the exact inverse render scale`() {
        val viewport = CompuktersUiViewport.admit(1_920, 1_080, 4)

        assertEquals(320.0, viewport.toVirtualX(240.0))
        assertEquals(160.0, viewport.toVirtualY(120.0))
        assertEquals(12.0, viewport.toVirtualDelta(9.0))
        assertEquals(240.0, viewport.toMinecraftX(320.0))
        assertEquals(120.0, viewport.toMinecraftY(160.0))
    }

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
