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

import net.minecraft.client.gui.screens.Screen

internal interface ChildScreenParent {
    fun suspendForChild(): Screen

    fun resumeFromChild(): Boolean

    fun abandonChild()
}

internal object IdeOpeningHandoff {
    fun <S, P> open(
        createSession: () -> S,
        attachTarget: (S) -> Unit,
        suspendParent: () -> P,
        installScreen: (S, P) -> Unit,
        closeSession: (S) -> Unit,
        resumeParent: () -> Unit,
    ) {
        val session = createSession()
        var parentSuspensionStarted = false
        try {
            attachTarget(session)
            parentSuspensionStarted = true
            val parent = suspendParent()
            installScreen(session, parent)
        } catch (failure: Throwable) {
            runCatching { closeSession(session) }.exceptionOrNull()?.let(failure::addSuppressed)
            if (parentSuspensionStarted) {
                runCatching(resumeParent).exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }
}
