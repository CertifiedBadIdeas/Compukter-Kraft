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

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeToolbarIconAtlasTest {
    @Test
    fun `every toolbar icon has a distinct atlas cell inside its logical control`() {
        val control = IdeRect(10, 20, 32, 36)
        val sprites = IdeIconKind.entries.map { kind -> IdeToolbarIconAtlas.sprite(IdeIconDraw(kind, control, -1, 30)) }

        assertEquals(IdeIconKind.entries.size, sprites.map { it.sourceX }.distinct().size)
        sprites.forEach { sprite ->
            assertTrue(sprite.sourceX >= 0)
            assertTrue(sprite.sourceX + IdeToolbarIconAtlas.CELL_SIZE <= IdeToolbarIconAtlas.WIDTH)
            assertTrue(sprite.bounds.left >= control.left)
            assertTrue(sprite.bounds.top >= control.top)
            assertTrue(sprite.bounds.right <= control.right)
            assertTrue(sprite.bounds.bottom <= control.bottom)
        }
    }

    @Test
    fun `packaged atlas contains visible pixels in every icon cell`() {
        val atlas =
            checkNotNull(javaClass.getResourceAsStream(RESOURCE_PATH)) { "$RESOURCE_PATH must be packaged" }
                .use(ImageIO::read)

        assertEquals(IdeToolbarIconAtlas.WIDTH, atlas.width)
        assertEquals(IdeToolbarIconAtlas.HEIGHT, atlas.height)
        assertTrue(atlas.colorModel.hasAlpha())
        IdeIconKind.entries.forEach { kind ->
            assertTrue(atlas.hasVisiblePixel(kind.ordinal * IdeToolbarIconAtlas.CELL_SIZE), "$kind must not be empty")
        }
    }

    private fun BufferedImage.hasVisiblePixel(sourceX: Int): Boolean =
        (sourceX until sourceX + IdeToolbarIconAtlas.CELL_SIZE).any { x ->
            (0 until IdeToolbarIconAtlas.HEIGHT).any { y -> getRGB(x, y).ushr(24) != 0 }
        }

    private companion object {
        const val RESOURCE_PATH = "/assets/compukters/textures/gui/ide_toolbar.png"
    }
}
