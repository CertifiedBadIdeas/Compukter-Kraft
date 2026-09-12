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

sealed interface ValueType {
    data object Unit : ValueType

    data object I32 : ValueType

    data object I64 : ValueType

    data object F32 : ValueType

    data object F64 : ValueType

    data object Bool : ValueType

    data object Char : ValueType

    data class Ref(
        val nullable: Boolean,
        val type: TypeRef,
    ) : ValueType
}

enum class PhysicalAtom(
    val byteSize: UInt,
    val alignment: UInt,
    internal val artifactTag: UInt,
) {
    I32(4u, 4u, 0u),
    I64(8u, 8u, 1u),
    F32(4u, 4u, 2u),
    F64(8u, 8u, 3u),
    REF32(4u, 4u, 4u),
}

data class PhysicalShape(
    val components: List<PhysicalAtom>,
) {
    val alignment: UInt
    val byteSize: UInt
    val componentOffsets: List<UInt>

    init {
        require(components.isNotEmpty()) { "physical shape must contain at least one component" }
        alignment = components.maxOf(PhysicalAtom::alignment)

        var offset = 0u
        componentOffsets =
            components.map { component ->
                offset = alignPhysicalOffset(offset, component.alignment)
                val componentOffset = offset
                offset =
                    (offset.toULong() + component.byteSize.toULong())
                        .also { require(it <= UInt.MAX_VALUE.toULong()) { "physical shape size overflow" } }
                        .toUInt()
                componentOffset
            }
        byteSize = alignPhysicalOffset(offset, alignment)
    }
}

data class FunctionValue(
    val semanticType: ValueType,
    val physicalShape: PhysicalShape,
) {
    companion object {
        fun scalar(semanticType: ValueType): FunctionValue =
            FunctionValue(
                semanticType = semanticType,
                physicalShape =
                    PhysicalShape(
                        listOf(
                            when (semanticType) {
                                ValueType.I32, ValueType.Bool, ValueType.Char -> PhysicalAtom.I32
                                ValueType.I64 -> PhysicalAtom.I64
                                ValueType.F32 -> PhysicalAtom.F32
                                ValueType.F64 -> PhysicalAtom.F64
                                is ValueType.Ref -> PhysicalAtom.REF32
                                ValueType.Unit -> error("Unit has no physical value shape")
                            },
                        ),
                    ),
            )
    }
}

data class ValueComponent(
    val value: RegisterId,
    val component: UShort,
)

data class SafepointRoots(
    val block: BlockId,
    val instructionBoundary: UInt,
    val references: List<ValueComponent>,
)

private fun alignPhysicalOffset(
    value: UInt,
    alignment: UInt,
): UInt {
    require(alignment != 0u && alignment.countOneBits() == 1) { "physical alignment must be a power of two" }
    val aligned = (value.toULong() + alignment.toULong() - 1uL) and alignment.toULong().minus(1uL).inv()
    require(aligned <= UInt.MAX_VALUE.toULong()) { "physical shape alignment overflow" }
    return aligned.toUInt()
}

/** Scalar kinds accepted by Artifact v1 comparison forms. */
enum class ScalarValueType(
    internal val artifactForm: UInt,
    internal val valueType: ValueType,
) {
    I32(1u, ValueType.I32),
    I64(2u, ValueType.I64),
    F32(3u, ValueType.F32),
    F64(4u, ValueType.F64),
    BOOL(5u, ValueType.Bool),
    CHAR(6u, ValueType.Char),
}

/** Scalar kinds accepted by Artifact v1 ordered-comparison forms. */
enum class OrderedScalarValueType(
    internal val artifactForm: UInt,
    internal val valueType: ValueType,
) {
    I32(1u, ValueType.I32),
    I64(2u, ValueType.I64),
    F32(3u, ValueType.F32),
    F64(4u, ValueType.F64),
    CHAR(6u, ValueType.Char),
}

/** Scalar kinds accepted by Artifact v1 string-value conversion forms. */
enum class StringValueType(
    internal val artifactForm: UInt,
    internal val valueType: ValueType,
) {
    I32(1u, ValueType.I32),
    BOOL(5u, ValueType.Bool),
    CHAR(6u, ValueType.Char),
}

sealed interface NominalType {
    val name: StringId

    data class Class(
        override val name: StringId,
        val abstract: Boolean = false,
        val final: Boolean = false,
        val genericArity: UShort = 0u,
        val superType: TypeRef? = null,
        val interfaces: List<TypeRef> = emptyList(),
        val fieldStart: UInt = 0u,
        val fieldCount: UInt = 0u,
        val methodStart: UInt = 0u,
        val methodCount: UInt = 0u,
        val initializer: FunctionId? = null,
    ) : NominalType

    data class Interface(
        override val name: StringId,
        val sealed: Boolean = false,
        val genericArity: UShort = 0u,
        val superType: TypeRef? = null,
        val interfaces: List<TypeRef> = emptyList(),
        val methodStart: UInt = 0u,
        val methodCount: UInt = 0u,
    ) : NominalType

    data class Array(
        override val name: StringId,
        val element: ValueType,
    ) : NominalType

    data class Function(
        override val name: StringId,
        val suspending: Boolean,
        val result: ValueType,
        val parameters: List<ValueType>,
    ) : NominalType
}

sealed interface Constant {
    val tag: Int

    data class I32(
        val value: Int,
    ) : Constant {
        override val tag: Int = 0
    }

    data class I64(
        val value: Long,
    ) : Constant {
        override val tag: Int = 1
    }

    data class F32(
        val bits: UInt,
    ) : Constant {
        override val tag: Int = 2
    }

    data class F64(
        val bits: ULong,
    ) : Constant {
        override val tag: Int = 3
    }

    data class Bool(
        val value: Boolean,
    ) : Constant {
        override val tag: Int = 4
    }

    data class Char(
        val codeUnit: UShort,
    ) : Constant {
        override val tag: Int = 5
    }

    data class StringLiteral(
        val literal: Utf16LiteralId,
    ) : Constant {
        override val tag: Int = 6
    }

    data object Null : Constant {
        override val tag: Int = 7
    }
}
