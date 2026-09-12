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

package ru.lazyhat.compukters.ide.analysis

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.editor.EditorRange

sealed interface DeclarationOrigin {
    data object Project : DeclarationOrigin

    data class Platform(
        val identity: AnalysisModuleIdentity,
    ) : DeclarationOrigin
}

sealed interface DeclarationLocation {
    val origin: DeclarationOrigin

    data class Source(
        override val origin: DeclarationOrigin,
        val path: VirtualSourcePath,
        val range: EditorRange,
    ) : DeclarationLocation {
        init {
            VirtualSourcePath.kotlin(path.value)
            require(range.length > 0) { "declaration source range must not be empty" }
        }
    }

    data class SourceUnavailable(
        override val origin: DeclarationOrigin,
    ) : DeclarationLocation {
        init {
            require(origin is DeclarationOrigin.Platform) { "only a platform declaration may have unavailable source" }
        }
    }
}
