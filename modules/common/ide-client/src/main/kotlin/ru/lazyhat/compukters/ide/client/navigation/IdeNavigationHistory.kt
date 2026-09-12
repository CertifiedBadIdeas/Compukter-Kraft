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

package ru.lazyhat.compukters.ide.client.navigation

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.project.fs.ProjectPath

sealed interface IdeNavigationSource {
    data class Project(
        val path: ProjectPath,
    ) : IdeNavigationSource

    data class Attached(
        val module: AnalysisModuleIdentity,
        val path: VirtualSourcePath,
    ) : IdeNavigationSource {
        init {
            VirtualSourcePath.kotlin(path.value)
        }
    }
}

data class IdeNavigationPosition(
    val source: IdeNavigationSource,
    val caretUtf16: Int,
    val firstVisibleLine: Int,
    val firstVisibleColumn: Int,
) {
    init {
        require(caretUtf16 >= 0) { "navigation caret must not be negative" }
        require(firstVisibleLine >= 0) { "navigation line must not be negative" }
        require(firstVisibleColumn >= 0) { "navigation column must not be negative" }
    }
}

class IdeNavigationHistory(
    private val maximumPositions: Int,
) {
    private val back = ArrayDeque<IdeNavigationPosition>()
    private val forward = ArrayDeque<IdeNavigationPosition>()

    init {
        require(maximumPositions > 0) { "navigation history limit must be positive" }
    }

    fun record(
        from: IdeNavigationPosition,
        to: IdeNavigationPosition,
    ) {
        if (from == to) return
        back.addLast(from)
        while (back.size > maximumPositions) back.removeFirst()
        forward.clear()
    }

    fun clear() {
        back.clear()
        forward.clear()
    }

    fun back(current: IdeNavigationPosition): IdeNavigationPosition? {
        val target = peekBack() ?: return null
        commitBack(current, target)
        return target
    }

    fun forward(current: IdeNavigationPosition): IdeNavigationPosition? {
        val target = peekForward() ?: return null
        commitForward(current, target)
        return target
    }

    fun peekBack(): IdeNavigationPosition? = back.lastOrNull()

    fun peekForward(): IdeNavigationPosition? = forward.lastOrNull()

    fun commitBack(
        current: IdeNavigationPosition,
        target: IdeNavigationPosition,
    ) {
        check(back.lastOrNull() == target) { "back navigation target changed" }
        back.removeLast()
        forward.addLast(current)
        trim(forward)
    }

    fun commitForward(
        current: IdeNavigationPosition,
        target: IdeNavigationPosition,
    ) {
        check(forward.lastOrNull() == target) { "forward navigation target changed" }
        forward.removeLast()
        back.addLast(current)
        trim(back)
    }

    private fun trim(values: ArrayDeque<IdeNavigationPosition>) {
        while (values.size > maximumPositions) values.removeFirst()
    }
}
