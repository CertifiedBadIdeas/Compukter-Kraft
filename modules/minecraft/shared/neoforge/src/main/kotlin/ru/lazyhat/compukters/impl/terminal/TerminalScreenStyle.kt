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

import net.minecraft.network.chat.Component

internal object TerminalScreenStyle {
    const val DIM_COLOR: Int = -535818224
    const val PANEL_BORDER_COLOR: Int = -10855846
    const val PANEL_COLOR: Int = -15329770
    const val TITLE_COLOR: Int = -1513240
    const val RESOURCE_COLOR: Int = -4671304
    val UNSUPPORTED_MESSAGE: Component = Component.literal("Compukters UI requires at least 640x360 pixels")

    fun animationMillis(): Long = System.nanoTime() / 1_000_000L
}
