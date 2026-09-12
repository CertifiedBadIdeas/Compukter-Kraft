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
import org.joml.Matrix3x2fStack

internal fun CompuktersUiViewport.map(event: MouseButtonEvent): MouseButtonEvent =
    MouseButtonEvent(
        toVirtualX(event.x()),
        toVirtualY(event.y()),
        event.buttonInfo(),
    )

internal inline fun <T> CompuktersUiViewport.withTransform(
    pose: Matrix3x2fStack,
    block: () -> T,
): T {
    pose.pushMatrix()
    return try {
        pose.scale(renderScale)
        block()
    } finally {
        pose.popMatrix()
    }
}
