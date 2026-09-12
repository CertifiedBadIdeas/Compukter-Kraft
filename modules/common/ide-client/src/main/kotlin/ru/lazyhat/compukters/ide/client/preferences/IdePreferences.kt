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

package ru.lazyhat.compukters.ide.client.preferences

import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.util.Collections

@ConsistentCopyVisibility
data class IdeProjectEditorState private constructor(
    val file: ProjectPath?,
    val caretUtf16: Int,
    val firstVisibleLine: Int,
    val firstVisibleColumn: Int,
) {
    companion object {
        fun admit(
            file: String?,
            caretUtf16: Int,
            firstVisibleLine: Int,
            firstVisibleColumn: Int,
        ): IdeProjectEditorState =
            IdeProjectEditorState(
                file = file?.let { runCatching { ProjectPath.file(it) }.getOrNull() },
                caretUtf16 = caretUtf16.coerceAtLeast(0),
                firstVisibleLine = firstVisibleLine.coerceAtLeast(0),
                firstVisibleColumn = firstVisibleColumn.coerceAtLeast(0),
            )
    }
}

class IdePreferences private constructor(
    val lastProjectDirectory: String?,
    projectStates: Map<String, IdeProjectEditorState>,
    val treeWidth: Int,
    val diagnosticsHeight: Int,
    val diagnosticsExpanded: Boolean,
) {
    val projectStates: Map<String, IdeProjectEditorState> = Collections.unmodifiableMap(LinkedHashMap(projectStates))

    private val activeState: IdeProjectEditorState?
        get() = lastProjectDirectory?.let(projectStates::get)

    val lastFile: ProjectPath?
        get() = activeState?.file

    val caretUtf16: Int
        get() = activeState?.caretUtf16 ?: 0

    val firstVisibleLine: Int
        get() = activeState?.firstVisibleLine ?: 0

    val firstVisibleColumn: Int
        get() = activeState?.firstVisibleColumn ?: 0

    fun projectState(directoryName: String): IdeProjectEditorState? = projectStates[directoryName]

    fun remember(
        projectDirectory: String,
        file: String?,
        caretUtf16: Int,
        firstVisibleLine: Int,
        firstVisibleColumn: Int,
    ): IdePreferences {
        if (!isDirectCanonicalName(projectDirectory)) return this
        val remembered =
            linkedMapOf(projectDirectory to IdeProjectEditorState.admit(file, caretUtf16, firstVisibleLine, firstVisibleColumn))
        projectStates.forEach { (directoryName, state) ->
            if (directoryName != projectDirectory && remembered.size < MAX_PROJECT_STATES) {
                remembered[directoryName] = state
            }
        }
        return admit(
            lastProjectDirectory = projectDirectory,
            projectStates = remembered,
            treeWidth = treeWidth,
            diagnosticsHeight = diagnosticsHeight,
            diagnosticsExpanded = diagnosticsExpanded,
        )
    }

    companion object {
        const val MIN_PANEL_SIZE = 32
        const val MAX_PANEL_SIZE = 16 * 1024
        const val MAX_PROJECT_STATES = 64

        fun admit(
            projectDirectory: String?,
            file: String?,
            caretUtf16: Int,
            firstVisibleLine: Int,
            firstVisibleColumn: Int,
            treeWidth: Int,
            diagnosticsHeight: Int,
            diagnosticsExpanded: Boolean,
        ): IdePreferences {
            val project = projectDirectory?.takeIf(::isDirectCanonicalName)
            val states =
                project
                    ?.let {
                        linkedMapOf(it to IdeProjectEditorState.admit(file, caretUtf16, firstVisibleLine, firstVisibleColumn))
                    }.orEmpty()
            return admit(project, states, treeWidth, diagnosticsHeight, diagnosticsExpanded)
        }

        fun admit(
            lastProjectDirectory: String?,
            projectStates: Map<String, IdeProjectEditorState>,
            treeWidth: Int,
            diagnosticsHeight: Int,
            diagnosticsExpanded: Boolean,
        ): IdePreferences {
            val admitted = linkedMapOf<String, IdeProjectEditorState>()
            projectStates.forEach { (directoryName, state) ->
                if (admitted.size < MAX_PROJECT_STATES && isDirectCanonicalName(directoryName)) {
                    admitted.putIfAbsent(directoryName, state)
                }
            }
            return IdePreferences(
                lastProjectDirectory = lastProjectDirectory?.takeIf { it in admitted },
                projectStates = admitted,
                treeWidth = treeWidth.coerceIn(MIN_PANEL_SIZE, MAX_PANEL_SIZE),
                diagnosticsHeight = diagnosticsHeight.coerceIn(MIN_PANEL_SIZE, MAX_PANEL_SIZE),
                diagnosticsExpanded = diagnosticsExpanded,
            )
        }

        fun empty(
            treeWidth: Int,
            diagnosticsHeight: Int,
            diagnosticsExpanded: Boolean,
        ): IdePreferences = admit(null, emptyMap(), treeWidth, diagnosticsHeight, diagnosticsExpanded)

        private fun isDirectCanonicalName(value: String): Boolean = '/' !in value && runCatching { ProjectPath.file(value) }.isSuccess
    }
}

interface IdePreferencesStore {
    fun load(): IdePreferences?

    fun save(preferences: IdePreferences)
}
