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

internal inline fun <T> CompuktersUiViewport.withTransform(
    pose: PoseStack,
    block: () -> T,
): T {
    pose.pushPose()
    return try {
        pose.scale(renderScale, renderScale, 1f)
        block()
    } finally {
        pose.popPose()
    }
}
