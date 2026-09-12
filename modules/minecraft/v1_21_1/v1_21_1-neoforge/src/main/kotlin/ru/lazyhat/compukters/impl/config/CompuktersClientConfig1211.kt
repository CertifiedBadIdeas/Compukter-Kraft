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

@file:Suppress("ktlint:standard:filename")

package ru.lazyhat.compukters.impl.config

import net.neoforged.neoforge.common.ModConfigSpec
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import java.util.function.Predicate

object CompuktersClientConfig {
    private val builder = ModConfigSpec.Builder()

    @Suppress("UNCHECKED_CAST")
    private val terminalFontValidator =
        Predicate<Any?> { value -> value is String && TerminalFontProfile.ALL.any { it.id == value } } as Predicate<Any>

    private val terminalFontId =
        builder
            .comment("Font used by the local terminal screen")
            .define("terminal.font", TerminalFontProfile.DEFAULT.id, terminalFontValidator)

    val SPEC: ModConfigSpec = builder.build()

    fun selectedFont(): TerminalFontProfile = TerminalFontProfile.fromId(terminalFontId.get())
}
