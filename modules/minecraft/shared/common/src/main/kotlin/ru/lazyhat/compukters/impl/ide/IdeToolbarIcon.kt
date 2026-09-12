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

enum class IdeIconKind {
    Format,
    Resolve,
    Build,
    Cancel,
    Verify,
    Deploy,
    Run,
    NewFile,
    NewDirectory,
    Rename,
    Delete,
}

data class IdeIconDraw(
    val kind: IdeIconKind,
    val bounds: IdeRect,
    val color: Int,
    val zIndex: Int,
)

data class IdeToolbarIconSprite(
    val bounds: IdeRect,
    val sourceX: Int,
)

object IdeToolbarIconAtlas {
    const val DRAW_SIZE = 16
    const val CELL_SIZE = 32
    const val WIDTH = CELL_SIZE * 11
    const val HEIGHT = CELL_SIZE

    fun sprite(icon: IdeIconDraw): IdeToolbarIconSprite {
        val size = minOf(DRAW_SIZE, icon.bounds.width, icon.bounds.height)
        val left = icon.bounds.left + (icon.bounds.width - size) / 2
        val top = icon.bounds.top + (icon.bounds.height - size) / 2
        return IdeToolbarIconSprite(
            bounds = IdeRect(left, top, left + size, top + size),
            sourceX = icon.kind.ordinal * CELL_SIZE,
        )
    }
}
