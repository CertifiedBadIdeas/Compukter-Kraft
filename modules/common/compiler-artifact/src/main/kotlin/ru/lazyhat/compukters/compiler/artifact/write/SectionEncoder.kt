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

package ru.lazyhat.compukters.compiler.artifact.write

import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.DebugEntry
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.ExportVisibility
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val STRINGS = 0x0100
internal const val TYPES = 0x0101
internal const val CONSTANTS = 0x0102
internal const val IMPORTS = 0x0103
internal const val EXPORTS = 0x0104
internal const val FIELDS = 0x0105
internal const val FUNCTIONS = 0x0106
internal const val BLOCKS = 0x0107
internal const val CODE = 0x0108
internal const val EXCEPTIONS = 0x0109
internal const val UTF16_LITERALS = 0x010a
internal const val SAFEPOINT_ROOTS = 0x010b
internal const val DEBUG = 0x0110

internal data class EncodedSection(
    val kind: Int,
    val payload: ByteArray,
    val count: UInt,
)

internal class EncodedModuleSections(
    val semantic: List<EncodedSection>,
    val debug: EncodedSection?,
    semanticHash: ByteArray,
) {
    val semanticHash: ByteArray = semanticHash.copyOf()

    fun required(kind: Int): EncodedSection = semantic.single { it.kind == kind }
}

internal fun encodeModuleSections(
    module: Module,
    limits: ArtifactWriteLimits,
): EncodedModuleSections {
    val maximum = limits.artifactBytes
    val codeRecords =
        module.blocks.map { block ->
            val sink = BinarySink(limits.codeBytes)
            block.instructions.forEach { sink.writeBytes(encodeInstruction(it, limits.codeBytes).bytes) }
            sink.toByteArray()
        }
    val semantic =
        listOf(
            EncodedSection(STRINGS, encodeIndexed(module.strings.map { it.toByteArray() }, maximum), module.strings.size.toUInt()),
            EncodedSection(TYPES, encodeIndexed(module.types.map { encodeType(it, maximum) }, maximum), module.types.size.toUInt()),
            EncodedSection(
                CONSTANTS,
                encodeIndexed(module.constants.map { encodeConstant(it, maximum) }, maximum),
                module.constants.size.toUInt(),
            ),
            EncodedSection(IMPORTS, encodeIndexed(module.imports.map { encodeImport(it, maximum) }, maximum), module.imports.size.toUInt()),
            EncodedSection(EXPORTS, encodeIndexed(module.exports.map { encodeExport(it, maximum) }, maximum), module.exports.size.toUInt()),
            EncodedSection(FIELDS, encodeIndexed(module.fields.map { encodeField(it, maximum) }, maximum), module.fields.size.toUInt()),
            EncodedSection(
                FUNCTIONS,
                encodeIndexed(module.functions.map { encodeFunction(it, maximum) }, maximum),
                module.functions.size.toUInt(),
            ),
            EncodedSection(
                BLOCKS,
                encodeIndexed(
                    module.blocks.mapIndexed { index, block ->
                        val cost = block.instructions.sumOf { encodeInstruction(it, limits.codeBytes).fixedCost.toLong() }.toUInt()
                        val sink = BinarySink(maximum)
                        sink.writeU32(block.owner.value)
                        sink.writeU32(index.toUInt())
                        sink.writeU32(block.instructions.size.toUInt())
                        sink.writeU32(cost)
                        sink.writeU32(if (block.loopHeaderSafepoint) 1u else 0u)
                        sink.writeU32(0u)
                        sink.toByteArray()
                    },
                    maximum,
                ),
                module.blocks.size.toUInt(),
            ),
            EncodedSection(CODE, encodeIndexed(codeRecords, maximum), codeRecords.size.toUInt()),
            EncodedSection(
                EXCEPTIONS,
                encodeIndexed(
                    module.exceptions.map { exception ->
                        BinarySink(maximum)
                            .apply {
                                writeU32(exception.owner.value)
                                writeU32(exception.firstProtectedBlock.value)
                                writeU32(exception.protectedBlockCount)
                                writeU32(exception.catchType?.let(::encodeTypeRef) ?: UInt.MAX_VALUE)
                                writeU32(exception.handlerBlock.value)
                                writeU16(exception.exceptionRegister.value.toUInt())
                                writeU16(0u)
                            }.toByteArray()
                    },
                    maximum,
                ),
                module.exceptions.size.toUInt(),
            ),
            EncodedSection(
                UTF16_LITERALS,
                encodeIndexed(module.utf16Literals.map { it.toLittleEndianByteArray() }, maximum),
                module.utf16Literals.size.toUInt(),
            ),
            EncodedSection(
                SAFEPOINT_ROOTS,
                encodeIndexed(
                    module.functions.flatMapIndexed { functionIndex, function ->
                        function.safepointRoots.map { roots ->
                            encodeSafepointRoots(FunctionId.of(functionIndex.toUInt()), roots, maximum)
                        }
                    },
                    maximum,
                ),
                module.functions.sumOf { it.safepointRoots.size }.toUInt(),
            ),
        )
    val debug =
        module.debug.takeIf(List<DebugEntry>::isNotEmpty)?.let { records ->
            EncodedSection(DEBUG, encodeIndexed(records.map { encodeDebug(it, maximum) }, maximum), records.size.toUInt())
        }
    return EncodedModuleSections(semantic, debug, semanticHash(semantic))
}

private fun encodeType(
    type: NominalType,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            when (type) {
                is NominalType.Class -> {
                    writeU8(0u)
                    writeU8((if (type.abstract) 1u else 0u) or (if (type.final) 2u else 0u))
                    writeU16(type.genericArity.toUInt())
                    writeU32(type.name.value)
                    writeClassLike(type.superType, type.interfaces, type.fieldStart, type.fieldCount, type.methodStart, type.methodCount)
                    type.initializer?.let { writeU32(it.value) }
                }

                is NominalType.Interface -> {
                    writeU8(1u)
                    writeU8(if (type.sealed) 1u else 0u)
                    writeU16(type.genericArity.toUInt())
                    writeU32(type.name.value)
                    writeClassLike(type.superType, type.interfaces, 0u, 0u, type.methodStart, type.methodCount)
                }

                is NominalType.Array -> {
                    writeU8(2u)
                    writeU8(0u)
                    writeU16(0u)
                    writeU32(type.name.value)
                    writeValueType(type.element)
                }

                is NominalType.Function -> {
                    writeU8(3u)
                    writeU8(0u)
                    writeU16(0u)
                    writeU32(type.name.value)
                    writeU16(type.parameters.size.toUInt())
                    writeU16(if (type.suspending) 1u else 0u)
                    writeValueType(type.result)
                    type.parameters.forEach(::writeValueType)
                }
            }
        }.toByteArray()

private fun BinarySink.writeClassLike(
    superType: TypeRef?,
    interfaces: List<TypeRef>,
    fieldStart: UInt,
    fieldCount: UInt,
    methodStart: UInt,
    methodCount: UInt,
) {
    writeU32(superType?.let(::encodeTypeRef) ?: UInt.MAX_VALUE)
    writeU32(interfaces.size.toUInt())
    writeU32(fieldStart)
    writeU32(fieldCount)
    writeU32(methodStart)
    writeU32(methodCount)
    interfaces.forEach { writeU32(encodeTypeRef(it)) }
}

private fun BinarySink.writeValueType(type: ValueType) {
    val kind =
        when (type) {
            ValueType.Unit -> 0u
            ValueType.I32 -> 1u
            ValueType.I64 -> 2u
            ValueType.F32 -> 3u
            ValueType.F64 -> 4u
            ValueType.Bool -> 5u
            ValueType.Char -> 6u
            is ValueType.Ref -> 7u
        }
    writeU8(kind)
    writeU8(if (type is ValueType.Ref && type.nullable) 1u else 0u)
    writeU16(0u)
    writeU32(if (type is ValueType.Ref) encodeTypeRef(type.type) else UInt.MAX_VALUE)
}

private fun encodeConstant(
    constant: Constant,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU8(constant.tag.toUInt())
            when (constant) {
                is Constant.I32 -> writeU32(constant.value.toUInt())
                is Constant.I64 -> writeU64(constant.value.toULong())
                is Constant.F32 -> writeU32(constant.bits)
                is Constant.F64 -> writeU64(constant.bits)
                is Constant.Bool -> writeU8(if (constant.value) 1u else 0u)
                is Constant.Char -> writeU16(constant.codeUnit.toUInt())
                is Constant.StringLiteral -> writeU32(constant.literal.value)
                Constant.Null -> Unit
            }
        }.toByteArray()

private fun encodeImport(
    value: ru.lazyhat.compukters.compiler.artifact.model.Import,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU8(value.kind.ordinal.toUInt())
            writeU8(0u)
            writeU16(0u)
            writeU32(value.targetModule.value)
            writeU32(value.targetName.value)
            writeU32(encodeTypeRef(value.expectedSignature))
            require(value.targetModuleHash.size == 32) { "target module hash must contain 32 bytes" }
            writeBytes(value.targetModuleHash)
        }.toByteArray()

private fun encodeExport(
    value: ru.lazyhat.compukters.compiler.artifact.model.Export,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU8(value.kind.ordinal.toUInt())
            writeU8(if (value.visibility == ExportVisibility.PUBLIC_LIBRARY) 1u else 0u)
            writeU16(0u)
            writeU32(value.name.value)
            writeU32(value.localSymbol)
            writeU32(encodeTypeRef(value.signature))
        }.toByteArray()

private fun encodeField(
    value: ru.lazyhat.compukters.compiler.artifact.model.Field,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU32(encodeTypeRef(value.owner))
            writeU32(value.name.value)
            writeValueType(value.type)
            writeU32((if (value.mutable) 1u else 0u) or (if (value.static) 2u else 0u))
            writeU32(0u)
        }.toByteArray()

private fun encodeFunction(
    value: ru.lazyhat.compukters.compiler.artifact.model.Function,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU32(value.owner?.let(::encodeTypeRef) ?: UInt.MAX_VALUE)
            writeU32(value.name.value)
            writeU32(encodeTypeRef(value.signature))
            writeU32(
                (if (FunctionFlag.SUSPENDING in value.flags) 1u else 0u) or
                    (if (FunctionFlag.STATIC in value.flags) 2u else 0u) or
                    (if (FunctionFlag.VIRTUAL in value.flags) 4u else 0u) or
                    (if (FunctionFlag.ABSTRACT in value.flags) 8u else 0u),
            )
            writeU16(value.values.size.toUInt())
            writeU16(value.parameterCount)
            writeU32(value.firstBlock.value)
            writeU32(value.blockCount)
            writeU32(value.firstException)
            writeU32(value.exceptionCount)
            value.values.forEach { functionValue ->
                writeValueType(functionValue.semanticType)
                writeU16(
                    functionValue.physicalShape.components.size
                        .toUInt(),
                )
                writeU16(0u)
                functionValue.physicalShape.components.forEach { writeU8(it.artifactTag) }
            }
        }.toByteArray()

private fun encodeSafepointRoots(
    function: FunctionId,
    roots: ru.lazyhat.compukters.compiler.artifact.model.SafepointRoots,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU32(function.value)
            writeU32(roots.block.value)
            writeU32(roots.instructionBoundary)
            writeU16(roots.references.size.toUInt())
            writeU16(0u)
            roots.references.forEach { reference ->
                writeU16(reference.value.value.toUInt())
                writeU16(reference.component.toUInt())
            }
        }.toByteArray()

private fun encodeDebug(
    value: DebugEntry,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU32(value.function.value)
            writeU32(value.block.value)
            writeU32(value.instruction)
            writeU32(value.startUtf16)
            writeU32(value.endUtf16)
            writeU32(value.inlineParent?.value ?: UInt.MAX_VALUE)
            val path = value.sourcePath.toByteArray()
            writeU32(path.size.toUInt())
            writeBytes(path)
        }.toByteArray()

private fun semanticHash(sections: List<EncodedSection>): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("Compukter module v2\u0000".toByteArray(StandardCharsets.US_ASCII))
    sections.sortedBy(EncodedSection::kind).forEach { section ->
        digest.update(BinarySink(2).apply { writeU16(section.kind.toUInt()) }.toByteArray())
        digest.update(BinarySink(8).apply { writeU64(section.payload.size.toULong()) }.toByteArray())
        digest.update(section.payload)
    }
    return digest.digest()
}
