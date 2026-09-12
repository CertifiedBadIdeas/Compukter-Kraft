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

import ru.lazyhat.compukters.ide.editor.EditorRange
import java.util.Collections

enum class CompletionKind {
    Class,
    Interface,
    TypeParameter,
    Function,
    ExtensionFunction,
    Property,
    LocalVariable,
    Parameter,
    Object,
    EnumEntry,
    Package,
    Keyword,
    TypeAlias,
}

data class CompletionTextEdit(
    val range: EditorRange,
    val text: String,
) {
    init {
        strictUtf8Size(text)
    }
}

data class CompletionSymbol(
    val fqName: String,
    val importFqName: String?,
) {
    init {
        require(fqName.isNotEmpty()) { "completion symbol name must not be empty" }
        strictUtf8Size(fqName)
        importFqName?.let { importName ->
            require(importName.isNotEmpty()) { "completion import name must not be empty" }
            strictUtf8Size(importName)
        }
    }
}

@ConsistentCopyVisibility
data class CompletionItem private constructor(
    val label: String,
    val insertText: String,
    val kind: CompletionKind,
    val detail: String? = null,
    val origin: DeclarationOrigin? = null,
    val symbol: CompletionSymbol? = null,
    val additionalEdits: List<CompletionTextEdit> = emptyList(),
) {
    constructor(
        label: String,
        insertText: String,
        kind: CompletionKind,
        detail: String? = null,
        origin: DeclarationOrigin? = null,
        symbol: CompletionSymbol? = null,
        additionalEdits: Collection<CompletionTextEdit> = emptyList(),
    ) : this(label, insertText, kind, detail, origin, symbol, Collections.unmodifiableList(additionalEdits.toList()))

    init {
        require(label.isNotEmpty()) { "completion label must not be empty" }
        require(insertText.isNotEmpty()) { "completion insert text must not be empty" }
        strictUtf8Size(label)
        strictUtf8Size(insertText)
        detail?.let(::strictUtf8Size)
    }
}
