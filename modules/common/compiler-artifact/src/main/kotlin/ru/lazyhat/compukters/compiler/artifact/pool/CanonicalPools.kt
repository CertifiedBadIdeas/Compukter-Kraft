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

package ru.lazyhat.compukters.compiler.artifact.pool

import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.Utf16Literal
import ru.lazyhat.compukters.compiler.artifact.model.Utf16LiteralId

class MetadataKey internal constructor(
    internal val owner: Any,
    internal val index: Int,
)

class Utf16LiteralKey internal constructor(
    internal val owner: Any,
    internal val index: Int,
)

class MetadataPoolBuilder {
    private val owner = Any()
    private val values = mutableListOf<MetadataText>()

    fun intern(value: MetadataText): MetadataKey {
        values += value
        return MetadataKey(owner, values.lastIndex)
    }

    fun freeze(): FrozenMetadataPool {
        val records = values.distinct().sorted()
        val ids = records.withIndex().associate { (index, value) -> value to StringId.of(index.toUInt()) }
        return FrozenMetadataPool(
            owner = owner,
            records = records,
            idsByInsertion = values.map(ids::getValue),
        )
    }
}

class FrozenMetadataPool internal constructor(
    private val owner: Any,
    records: List<MetadataText>,
    private val idsByInsertion: List<StringId>,
) {
    val records: List<MetadataText> = records.toList()

    fun idOf(key: MetadataKey): StringId {
        require(key.owner === owner) { "metadata key belongs to another pool builder" }
        return idsByInsertion[key.index]
    }
}

class Utf16LiteralPoolBuilder {
    private val owner = Any()
    private val values = mutableListOf<Utf16Literal>()

    fun intern(value: Utf16Literal): Utf16LiteralKey {
        values += value
        return Utf16LiteralKey(owner, values.lastIndex)
    }

    fun freeze(): FrozenUtf16LiteralPool {
        val records = values.distinct().sorted()
        val ids = records.withIndex().associate { (index, value) -> value to Utf16LiteralId.of(index.toUInt()) }
        return FrozenUtf16LiteralPool(
            owner = owner,
            records = records,
            idsByInsertion = values.map(ids::getValue),
        )
    }
}

class FrozenUtf16LiteralPool internal constructor(
    private val owner: Any,
    records: List<Utf16Literal>,
    private val idsByInsertion: List<Utf16LiteralId>,
) {
    val records: List<Utf16Literal> = records.toList()

    fun idOf(key: Utf16LiteralKey): Utf16LiteralId {
        require(key.owner === owner) { "UTF-16 literal key belongs to another pool builder" }
        return idsByInsertion[key.index]
    }
}

class ConstantKey internal constructor(
    internal val owner: Any,
    internal val index: Int,
)

class ConstantPoolBuilder {
    private val owner = Any()
    private val values = mutableListOf<Constant>()

    fun intern(value: Constant): ConstantKey {
        values += value
        return ConstantKey(owner, values.lastIndex)
    }

    fun freeze(): FrozenConstantPool {
        val records =
            values.distinct().sortedWith { left, right ->
                val tagComparison = left.tag.compareTo(right.tag)
                if (tagComparison != 0) tagComparison else compareUnsignedLists(constantPayload(left), constantPayload(right))
            }
        val ids = records.withIndex().associate { (index, value) -> value to ConstantId.of(index.toUInt()) }
        return FrozenConstantPool(owner, records, values.map(ids::getValue))
    }
}

class FrozenConstantPool internal constructor(
    private val owner: Any,
    records: List<Constant>,
    private val idsByInsertion: List<ConstantId>,
) {
    val records: List<Constant> = records.toList()

    fun idOf(key: ConstantKey): ConstantId {
        require(key.owner === owner) { "constant key belongs to another pool builder" }
        return idsByInsertion[key.index]
    }
}

private fun constantPayload(value: Constant): List<Int> =
    when (value) {
        is Constant.I32 -> littleEndian(value.value.toUInt().toULong(), 4)
        is Constant.I64 -> littleEndian(value.value.toULong(), 8)
        is Constant.F32 -> littleEndian(value.bits.toULong(), 4)
        is Constant.F64 -> littleEndian(value.bits, 8)
        is Constant.Bool -> listOf(if (value.value) 1 else 0)
        is Constant.Char -> littleEndian(value.codeUnit.toULong(), 2)
        is Constant.StringLiteral -> littleEndian(value.literal.value.toULong(), 4)
        Constant.Null -> emptyList()
    }

private fun littleEndian(
    value: ULong,
    bytes: Int,
): List<Int> = List(bytes) { index -> ((value shr (index * 8)) and 0xffu).toInt() }

private fun compareUnsignedLists(
    left: List<Int>,
    right: List<Int>,
): Int {
    for (index in 0 until minOf(left.size, right.size)) {
        val comparison = left[index].compareTo(right[index])
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}
