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

package ru.lazyhat.compukters.impl.config

import net.neoforged.neoforge.common.ModConfigSpec
import ru.lazyhat.compukters.impl.ide.IdeLayoutSettings
import ru.lazyhat.compukters.impl.ide.IdeLayoutStore
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import java.util.function.Predicate

object CompuktersClientConfig {
    private val builder = ModConfigSpec.Builder()

    @Suppress("UNCHECKED_CAST")
    private val terminalFontValidator =
        Predicate<Any?> { value ->
            value is String && TerminalFontProfile.ALL.any { it.id == value }
        } as Predicate<Any>

    internal val terminalFontId =
        builder
            .comment("Font used by the local terminal screen")
            .define("terminal.font", TerminalFontProfile.DEFAULT.id, terminalFontValidator)

    internal val ideTreeWidth =
        builder
            .comment("Preferred IDE project tree width in GUI pixels")
            .defineInRange(
                "ide.tree_width",
                IdeLayoutSettings.DEFAULT_TREE_WIDTH,
                IdeLayoutSettings.MINIMUM_TREE_WIDTH,
                IdeLayoutSettings.MAXIMUM_TREE_WIDTH,
            )
    internal val ideDiagnosticsHeight =
        builder
            .comment("Preferred IDE diagnostics panel height in GUI pixels")
            .defineInRange(
                "ide.diagnostics_height",
                IdeLayoutSettings.DEFAULT_DIAGNOSTICS_HEIGHT,
                IdeLayoutSettings.MINIMUM_DIAGNOSTICS_HEIGHT,
                IdeLayoutSettings.MAXIMUM_DIAGNOSTICS_HEIGHT,
            )
    internal val ideDiagnosticsExpanded =
        builder
            .comment("Whether the IDE diagnostics panel is expanded")
            .define("ide.diagnostics_expanded", true)

    val SPEC: ModConfigSpec = builder.build()

    fun selectedFont(): TerminalFontProfile = TerminalFontProfile.fromId(terminalFontId.get())

    fun selectFont(profile: TerminalFontProfile) {
        terminalFontId.set(profile.id)
        terminalFontId.save()
    }

    internal fun admitIdeLayout(
        treeWidth: Int,
        diagnosticsHeight: Int,
        diagnosticsExpanded: Boolean,
    ): IdeLayoutSettings = IdeLayoutSettings.admit(treeWidth, diagnosticsHeight, diagnosticsExpanded)

    internal object IdeLayout : IdeLayoutStore {
        override fun load(): IdeLayoutSettings =
            admitIdeLayout(
                runCatching(ideTreeWidth::get).getOrDefault(IdeLayoutSettings.DEFAULT_TREE_WIDTH),
                runCatching(ideDiagnosticsHeight::get).getOrDefault(IdeLayoutSettings.DEFAULT_DIAGNOSTICS_HEIGHT),
                runCatching(ideDiagnosticsExpanded::get).getOrDefault(true),
            )

        override fun save(settings: IdeLayoutSettings) {
            ideTreeWidth.set(settings.treeWidth)
            ideDiagnosticsHeight.set(settings.diagnosticsHeight)
            ideDiagnosticsExpanded.set(settings.diagnosticsExpanded)
            ideDiagnosticsExpanded.save()
        }
    }
}
