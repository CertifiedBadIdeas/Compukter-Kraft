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

data class AnalysisSnapshotIdentity(
    val source: SourceSnapshotId,
    val profile: AnalysisProfileIdentity,
)

enum class CompletionTrigger {
    Automatic,
    Manual,
}

sealed interface AnalysisQuery {
    val identity: AnalysisSnapshotIdentity

    data class Presentation(
        override val identity: AnalysisSnapshotIdentity,
        val path: VirtualSourcePath,
    ) : AnalysisQuery {
        init {
            VirtualSourcePath.kotlin(path.value)
        }
    }

    data class Completion(
        override val identity: AnalysisSnapshotIdentity,
        val path: VirtualSourcePath,
        val offsetUtf16: Int,
        val trigger: CompletionTrigger,
    ) : AnalysisQuery {
        init {
            validateCursor(path, offsetUtf16)
        }
    }

    data class ExpressionInfo(
        override val identity: AnalysisSnapshotIdentity,
        val path: VirtualSourcePath,
        val offsetUtf16: Int,
    ) : AnalysisQuery {
        init {
            validateCursor(path, offsetUtf16)
        }
    }

    data class ParameterInfo(
        override val identity: AnalysisSnapshotIdentity,
        val path: VirtualSourcePath,
        val offsetUtf16: Int,
    ) : AnalysisQuery {
        init {
            validateCursor(path, offsetUtf16)
        }
    }

    data class Declaration(
        override val identity: AnalysisSnapshotIdentity,
        val path: VirtualSourcePath,
        val offsetUtf16: Int,
    ) : AnalysisQuery {
        init {
            validateCursor(path, offsetUtf16)
        }
    }

    data class References(
        override val identity: AnalysisSnapshotIdentity,
        val path: VirtualSourcePath,
        val offsetUtf16: Int,
    ) : AnalysisQuery {
        init {
            validateCursor(path, offsetUtf16)
        }
    }

    data class Format(
        override val identity: AnalysisSnapshotIdentity,
        val path: VirtualSourcePath,
        val source: String,
        val caretOffsetUtf16: Int,
    ) : AnalysisQuery {
        init {
            validateCursor(path, caretOffsetUtf16)
            require(caretOffsetUtf16 <= source.length) { "format caret offset exceeds its source" }
            requireUtf16Boundary(source, caretOffsetUtf16, "format caret offset")
            strictUtf8Size(source)
        }
    }
}

private fun validateCursor(
    path: VirtualSourcePath,
    offsetUtf16: Int,
) {
    VirtualSourcePath.kotlin(path.value)
    require(offsetUtf16 >= 0) { "analysis cursor offset must be non-negative" }
}

internal fun requireUtf16Boundary(
    text: String,
    offsetUtf16: Int,
    label: String,
) {
    require(
        offsetUtf16 <= 0 ||
            offsetUtf16 >= text.length ||
            !Character.isHighSurrogate(text[offsetUtf16 - 1]) ||
            !Character.isLowSurrogate(text[offsetUtf16]),
    ) { "$label splits a surrogate pair" }
}
