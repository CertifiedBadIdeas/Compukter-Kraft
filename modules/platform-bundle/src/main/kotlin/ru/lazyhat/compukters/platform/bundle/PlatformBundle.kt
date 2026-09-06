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

package ru.lazyhat.compukters.platform.bundle

import ru.lazyhat.compukters.worker.value.ImmutableBytes
import ru.lazyhat.compukters.worker.value.Sha256

data class PlatformIdentity(
    val languageVersion: String,
    val platformAbi: Int,
    val contentHash: Sha256,
)

data class PlatformModuleId(
    val namespace: String,
    val name: String,
) : Comparable<PlatformModuleId> {
    init {
        require(namespace.matches(COMPONENT_PATTERN)) { "invalid platform module namespace: $namespace" }
        require(name.matches(COMPONENT_PATTERN)) { "invalid platform module name: $name" }
    }

    override fun compareTo(other: PlatformModuleId): Int = compareValuesBy(this, other, PlatformModuleId::namespace, PlatformModuleId::name)

    override fun toString(): String = "$namespace:$name"

    private companion object {
        val COMPONENT_PATTERN = Regex("[a-z][a-z0-9-]{0,63}")
    }
}

data class PlatformSource(
    val path: String,
    val content: ImmutableBytes,
)

data class PlatformDeclaration(
    val symbol: String,
    val signature: String,
    val module: PlatformModuleId,
    val sourcePath: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val trustedExternal: Boolean,
    val defaultArguments: List<PlatformDefaultArgument?> = emptyList(),
)

sealed interface PlatformDefaultArgument {
    data class EnumEntry(
        val symbol: String,
    ) : PlatformDefaultArgument
}

enum class PlatformCompletionKind {
    CLASS,
    INTERFACE,
    FUNCTION,
    PROPERTY,
    OBJECT,
    TYPE_ALIAS,
}

data class PlatformCompletionDeclaration(
    val symbol: String,
    val shortName: String,
    val signature: String,
    val kind: PlatformCompletionKind,
    val module: PlatformModuleId,
    val sourcePath: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val defaultImport: Boolean,
)

enum class PlatformScalarRepresentation {
    INT,
    BOOLEAN,
    CHAR,
}

data class PlatformScalarType(
    val symbol: String,
    val representation: PlatformScalarRepresentation,
    val underlyingProperty: String,
    val sourcePath: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val minimumInt: Int? = null,
    val maximumInt: Int? = null,
)

sealed interface PlatformScalarValue {
    data class IntValue(
        val value: Int,
    ) : PlatformScalarValue

    data class BooleanValue(
        val value: Boolean,
    ) : PlatformScalarValue

    data class CharValue(
        val value: Char,
    ) : PlatformScalarValue
}

data class PlatformScalarConstant(
    val symbol: String,
    val typeSymbol: String,
    val value: PlatformScalarValue,
)

data class PlatformModule(
    val id: PlatformModuleId,
    val version: String,
    val dependencies: List<PlatformModuleId>,
    val metadata: ImmutableBytes,
    val libraryFragment: ImmutableBytes?,
    val sources: List<PlatformSource>,
    val declarations: List<PlatformDeclaration>,
    val completionDeclarations: List<PlatformCompletionDeclaration>,
    val scalarTypes: List<PlatformScalarType> = emptyList(),
    val scalarConstants: List<PlatformScalarConstant> = emptyList(),
)

data class PlatformBundle(
    val identity: PlatformIdentity,
    val builtins: PlatformModule,
    val modules: List<PlatformModule>,
)
