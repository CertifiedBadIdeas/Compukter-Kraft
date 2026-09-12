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

package ru.lazyhat.compukters.compiler.artifact.model

private const val MAX_LOCAL_ID: UInt = 0x7fff_ffffu

private fun checkedLocalId(
    kind: String,
    value: UInt,
): UInt {
    require(value <= MAX_LOCAL_ID) { "$kind must fit the 31-bit local identity range" }
    return value
}

@JvmInline
value class ModuleId private constructor(
    val value: UInt,
) {
    companion object {
        fun of(value: UInt) = ModuleId(checkedLocalId("ModuleId", value))
    }
}

@JvmInline
value class TypeId private constructor(
    val value: UInt,
) {
    companion object {
        fun of(value: UInt) = TypeId(checkedLocalId("TypeId", value))
    }
}

@JvmInline
value class FunctionId private constructor(
    val value: UInt,
) {
    companion object {
        fun of(value: UInt) = FunctionId(checkedLocalId("FunctionId", value))
    }
}

@JvmInline
value class FieldId private constructor(
    val value: UInt,
) {
    companion object {
        fun of(value: UInt) = FieldId(checkedLocalId("FieldId", value))
    }
}

@JvmInline
value class BlockId private constructor(
    val value: UInt,
) {
    companion object {
        fun of(value: UInt) = BlockId(checkedLocalId("BlockId", value))
    }
}

@JvmInline
value class StringId private constructor(
    val value: UInt,
) {
    companion object {
        fun of(value: UInt) = StringId(checkedLocalId("StringId", value))
    }
}

@JvmInline
value class Utf16LiteralId private constructor(
    val value: UInt,
) {
    companion object {
        fun of(value: UInt) = Utf16LiteralId(checkedLocalId("Utf16LiteralId", value))
    }
}

@JvmInline
value class ConstantId private constructor(
    val value: UInt,
) {
    companion object {
        fun of(value: UInt) = ConstantId(checkedLocalId("ConstantId", value))
    }
}

@JvmInline
value class CapabilityId private constructor(
    val value: UInt,
) {
    companion object {
        fun of(value: UInt) = CapabilityId(checkedLocalId("CapabilityId", value))
    }
}

@JvmInline
value class ImportId private constructor(
    val value: UInt,
) {
    companion object {
        fun of(value: UInt) = ImportId(checkedLocalId("ImportId", value))
    }
}

@JvmInline
value class ExportId private constructor(
    val value: UInt,
) {
    companion object {
        fun of(value: UInt) = ExportId(checkedLocalId("ExportId", value))
    }
}

@JvmInline
value class DebugEntryId private constructor(
    val value: UInt,
) {
    companion object {
        fun of(value: UInt) = DebugEntryId(checkedLocalId("DebugEntryId", value))
    }
}

@JvmInline
value class RegisterId private constructor(
    val value: UShort,
) {
    companion object {
        fun of(value: UInt): RegisterId {
            require(value < UShort.MAX_VALUE.toUInt()) {
                "RegisterId must not collide with the absent-register sentinel"
            }
            return RegisterId(value.toUShort())
        }
    }
}

sealed interface TypeRef {
    data class Local(
        val id: TypeId,
    ) : TypeRef

    data class Imported(
        val id: ImportId,
    ) : TypeRef
}

sealed interface FunctionRef {
    data class Local(
        val id: FunctionId,
    ) : FunctionRef

    data class Imported(
        val id: ImportId,
    ) : FunctionRef
}

sealed interface FieldRef {
    data class Local(
        val id: FieldId,
    ) : FieldRef

    data class Imported(
        val id: ImportId,
    ) : FieldRef
}

sealed interface Destination {
    data class Register(
        val id: RegisterId,
    ) : Destination

    data object Unit : Destination
}
