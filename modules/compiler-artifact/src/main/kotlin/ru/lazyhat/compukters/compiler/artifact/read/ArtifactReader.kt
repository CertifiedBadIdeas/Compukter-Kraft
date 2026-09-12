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

@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.lazyhat.compukters.compiler.artifact.read

import ru.lazyhat.compukters.compiler.artifact.model.*
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Strict inverse of the canonical CPKT v3 writer. */
object ArtifactReader {
    fun read(bytes: ByteArray): Artifact {
        require(bytes.size >= 96) { "artifact is truncated" }
        val header = Cursor(bytes, 0, 64)
        require(header.bytes(4).contentEquals(MAGIC)) { "invalid artifact magic" }
        require(header.u16() == 3u && header.u16() == 0u) { "unsupported artifact format" }
        val runtimeAbi = AbiVersion(header.u16().toUShort(), header.u16().toUShort())
        require(header.u16() == 64u && header.u16() == 32u) { "unsupported artifact layout" }
        val sectionCount = header.u32().checkedInt("section count")
        val features = decodeFeatures(header.u32())
        require(header.u64() == 64uL) { "invalid section directory offset" }
        val payloadEnd = header.u64().checkedInt("payload end")
        val entryModule = ModuleId.of(header.u32())
        val entryFunction = FunctionId.of(header.u32())
        val entryArguments =
            when (header.u8()) {
                0u -> EntryArguments.NONE
                1u -> EntryArguments.STRING_ARRAY
                else -> error("invalid entry argument kind")
            }
        require(header.bytes(15).all { it == 0.toByte() }) { "non-zero artifact header reserved bytes" }
        require(bytes.size == payloadEnd + 32) { "artifact length or trailing bytes disagree with header" }
        require(
            MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(bytes.copyOfRange(0, payloadEnd))
                .contentEquals(bytes.copyOfRange(payloadEnd, bytes.size)),
        ) {
            "artifact digest mismatch"
        }
        val directoryEnd = 64L + sectionCount.toLong() * 32L
        require(directoryEnd <= payloadEnd) { "section directory is outside artifact" }
        val directory = Cursor(bytes, 64, directoryEnd.toInt())
        val sections =
            List(sectionCount) {
                val kind = directory.u16().toInt()
                val flags = directory.u16()
                val scope = directory.u32().checkedInt("section scope")
                val offset = directory.u64().checkedInt("section offset")
                val length = directory.u64().checkedInt("section length")
                val count = directory.u32().checkedInt("section record count")
                require(directory.u32() == 0u) { "non-zero section reserved field" }
                require(flags == 3u || (flags == 0u && kind == DEBUG)) { "invalid section flags" }
                require(offset >= align8(directoryEnd.toInt()) && offset.toLong() + length <= payloadEnd) { "section is outside artifact" }
                Section(kind, scope, count, bytes.copyOfRange(offset, offset + length))
            }
        require(sections.map { it.scope to it.kind }.toSet().size == sections.size) { "duplicate artifact section" }
        val manifest = decodeManifest(sections.singleSection(MANIFEST, 0).payload)
        val moduleRecords = indexed(sections.singleSection(MODULES, 0)).map(::decodeModuleRecord)
        val capabilities = indexed(sections.singleSection(CAPABILITIES, 0)).map(::decodeCapability)
        val modules = moduleRecords.indices.map { index -> decodeModule(moduleRecords[index], sections, index + 1) }
        return Artifact(runtimeAbi, features, manifest, EntryPoint(entryModule, entryFunction, entryArguments), modules, capabilities)
    }
}

private data class Section(
    val kind: Int,
    val scope: Int,
    val count: Int,
    val payload: ByteArray,
)

private data class ModuleRecord(
    val name: StringId,
    val kind: ModuleKind,
)

private data class FunctionRoots(
    val function: FunctionId,
    val roots: SafepointRoots,
)

private fun decodeManifest(bytes: ByteArray): Manifest {
    val c = Cursor(bytes)
    val heap = c.u32()
    val stack = c.u32()
    val coroutines = c.u32()
    val depth = c.u32()
    val requests = c.u32()
    val events = c.u32()
    val block = c.u32()
    val slice = c.u32()
    c.u32()
    c.u32()
    val manifest = Manifest(heap, stack, coroutines, depth, requests, events, block, slice, c.bytes(32), c.bytes(32))
    require(c.u64() == 0uL && c.done()) { "invalid manifest record" }
    return manifest
}

private fun decodeModuleRecord(bytes: ByteArray): ModuleRecord {
    val c = Cursor(bytes)
    val record =
        ModuleRecord(
            StringId.of(c.u32()),
            when (c.u32()) {
                1u -> ModuleKind.APPLICATION
                2u -> ModuleKind.LIBRARY
                else -> error("invalid module kind")
            },
        )
    c.bytes(32)
    repeat(4) { c.u32() }
    require(c.u32() == 0u && c.done()) { "invalid module record" }
    return record
}

private fun decodeCapability(bytes: ByteArray): Capability {
    val c = Cursor(bytes)
    val result =
        Capability(
            StringId.of(c.u32()),
            StringId.of(c.u32()),
            AbiVersion(c.u16().toUShort(), c.u16().toUShort()),
            when (c.u32()) {
                1u -> true
                2u -> false
                else -> error("invalid capability requirement")
            },
            c.u32(),
        )
    require(c.u32() == 0u && c.done()) { "invalid capability record" }
    return result
}

private fun decodeModule(
    record: ModuleRecord,
    sections: List<Section>,
    scope: Int,
): Module {
    fun records(kind: Int): List<ByteArray> = indexed(sections.singleSection(kind, scope))
    val code = records(CODE).map(::decodeCode)
    val blockRecords = records(BLOCKS)
    require(blockRecords.size == code.size) { "BLOCKS and CODE counts differ" }
    val decodedFunctions = records(FUNCTIONS).map(::decodeFunction)
    val rootsByFunction = records(SAFEPOINT_ROOTS).map(::decodeSafepointRoots).groupBy { it.function }
    require(rootsByFunction.keys.all { it.value.toInt() in decodedFunctions.indices }) { "root map owner is outside function table" }
    return Module(
        name = record.name,
        kind = record.kind,
        strings = records(STRINGS).map { MetadataText.of(strictUtf8(it)) },
        utf16Literals = records(UTF16_LITERALS).map(::decodeUtf16),
        types = records(TYPES).map(::decodeType),
        constants = records(CONSTANTS).map(::decodeConstant),
        imports = records(IMPORTS).map(::decodeImport),
        exports = records(EXPORTS).map(::decodeExport),
        fields = records(FIELDS).map(::decodeField),
        functions =
            decodedFunctions.mapIndexed { index, function ->
                function.copy(
                    safepointRoots =
                        rootsByFunction[FunctionId.of(index.toUInt())]
                            .orEmpty()
                            .map(FunctionRoots::roots),
                )
            },
        blocks = blockRecords.indices.map { decodeBlock(blockRecords[it], code[it]) },
        exceptions = records(EXCEPTIONS).map(::decodeException),
        debug =
            sections
                .singleOrNull { it.kind == DEBUG && it.scope == scope }
                ?.let(::indexed)
                .orEmpty()
                .map(::decodeDebug),
    )
}

private fun decodeType(bytes: ByteArray): NominalType {
    val c = Cursor(bytes)
    val tag = c.u8()
    val flags = c.u8()
    val arity = c.u16().toUShort()
    val name = StringId.of(c.u32())
    val result =
        when (tag) {
            0u, 1u -> {
                val superType = c.u32().optionalTypeRef()
                val interfaces = c.u32().checkedInt("interface count")
                val fieldStart = c.u32()
                val fieldCount = c.u32()
                val methodStart = c.u32()
                val methodCount = c.u32()
                val parents = List(interfaces) { c.u32().typeRef() }
                if (tag ==
                    0u
                ) {
                    NominalType.Class(
                        name,
                        flags and 1u != 0u,
                        flags and 2u != 0u,
                        arity,
                        superType,
                        parents,
                        fieldStart,
                        fieldCount,
                        methodStart,
                        methodCount,
                        if (c.done()) null else FunctionId.of(c.u32()),
                    )
                } else {
                    NominalType.Interface(name, flags and 1u != 0u, arity, superType, parents, methodStart, methodCount)
                }
            }

            2u -> {
                require(flags == 0u && arity == 0.toUShort())
                NominalType.Array(name, c.valueType())
            }

            3u -> {
                require(flags == 0u && arity == 0.toUShort())
                val count = c.u16().toInt()
                val suspending = c.u16() == 1u
                val resultType = c.valueType()
                NominalType.Function(name, suspending, resultType, List(count) { c.valueType() })
            }

            else -> {
                error("invalid nominal type tag")
            }
        }
    require(c.done()) { "trailing nominal type bytes" }
    return result
}

private fun decodeConstant(bytes: ByteArray): Constant {
    val c = Cursor(bytes)
    val value =
        when (c.u8()) {
            0u -> Constant.I32(c.u32().toInt())
            1u -> Constant.I64(c.u64().toLong())
            2u -> Constant.F32(c.u32())
            3u -> Constant.F64(c.u64())
            4u -> Constant.Bool(c.u8().also { require(it <= 1u) } == 1u)
            5u -> Constant.Char(c.u16().toUShort())
            6u -> Constant.StringLiteral(Utf16LiteralId.of(c.u32()))
            7u -> Constant.Null
            else -> error("invalid constant tag")
        }
    require(c.done()) { "trailing constant bytes" }
    return value
}

private fun decodeImport(bytes: ByteArray): Import {
    val c = Cursor(bytes)
    val kind = enumAt<SymbolKind>(c.u8())
    require(c.u8() == 0u && c.u16() == 0u)
    val result = Import(kind, ModuleId.of(c.u32()), StringId.of(c.u32()), c.u32().typeRef(), c.bytes(32))
    require(c.done())
    return result
}

private fun decodeExport(bytes: ByteArray): Export {
    val c = Cursor(bytes)
    val kind = enumAt<SymbolKind>(c.u8())
    val visibility =
        if (c.u8() ==
            1u
        ) {
            ExportVisibility.PUBLIC_LIBRARY
        } else {
            ExportVisibility.BUNDLE
        }
    ; require(c.u16() == 0u)
    val result = Export(kind, visibility, StringId.of(c.u32()), c.u32(), c.u32().typeRef())
    require(c.done())
    return result
}

private fun decodeField(bytes: ByteArray): Field {
    val c = Cursor(bytes)
    val owner = c.u32().typeRef()
    val name = StringId.of(c.u32())
    val type = c.valueType()
    val flags = c.u32()
    require(c.u32() == 0u && c.done())
    return Field(owner, name, type, flags and 1u != 0u, flags and 2u != 0u)
}

private fun decodeFunction(bytes: ByteArray): ru.lazyhat.compukters.compiler.artifact.model.Function {
    val c = Cursor(bytes)
    val owner = c.u32().optionalTypeRef()
    val name = StringId.of(c.u32())
    val signature = c.u32().typeRef()
    val mask = c.u32()
    val flags =
        setOfNotNull(
            FunctionFlag.SUSPENDING.takeIf { mask and 1u != 0u },
            FunctionFlag.STATIC.takeIf { mask and 2u != 0u },
            FunctionFlag.VIRTUAL.takeIf {
                mask and
                    4u !=
                    0u
            },
            FunctionFlag.ABSTRACT.takeIf { mask and 8u != 0u },
        )
    val values = c.u16().toInt()
    val parameters = c.u16()
    val firstBlock = BlockId.of(c.u32())
    val blockCount = c.u32()
    val firstException = c.u32()
    val exceptionCount = c.u32()
    val result =
        ru.lazyhat.compukters.compiler.artifact.model.Function(
            owner,
            name,
            signature,
            flags,
            List(values) {
                val semanticType = c.valueType()
                val componentCount = c.u16().toInt()
                require(c.u16() == 0u)
                FunctionValue(
                    semanticType,
                    PhysicalShape(
                        List(componentCount) {
                            when (c.u8()) {
                                0u -> PhysicalAtom.I32
                                1u -> PhysicalAtom.I64
                                2u -> PhysicalAtom.F32
                                3u -> PhysicalAtom.F64
                                4u -> PhysicalAtom.REF32
                                else -> error("invalid physical atom tag")
                            }
                        },
                    ),
                )
            },
            parameters,
            firstBlock,
            blockCount,
            firstException,
            exceptionCount,
        )
    require(c.done())
    return result
}

private fun decodeSafepointRoots(bytes: ByteArray): FunctionRoots {
    val c = Cursor(bytes)
    val function = FunctionId.of(c.u32())
    val block = BlockId.of(c.u32())
    val boundary = c.u32()
    val count = c.u16().toInt()
    require(c.u16() == 0u)
    val roots =
        List(count) {
            ValueComponent(RegisterId.of(c.u16()), c.u16().toUShort())
        }
    require(c.done()) { "trailing safepoint root bytes" }
    return FunctionRoots(function, SafepointRoots(block, boundary, roots))
}

private fun decodeBlock(
    bytes: ByteArray,
    code: List<Instruction>,
): Block {
    val c = Cursor(bytes)
    val owner = FunctionId.of(c.u32())
    c.u32()
    require(c.u32().toInt() == code.size)
    c.u32()
    val loop =
        c.u32() == 1u
    require(c.u32() == 0u && c.done())
    return Block(owner, loop, code)
}

private fun decodeException(bytes: ByteArray): ExceptionEntry {
    val c = Cursor(bytes)
    val result =
        ExceptionEntry(
            FunctionId.of(c.u32()),
            BlockId.of(c.u32()),
            c.u32(),
            c.u32().optionalTypeRef(),
            BlockId.of(c.u32()),
            RegisterId.of(c.u16()),
        )
    require(c.u16() == 0u && c.done())
    return result
}

private fun decodeDebug(bytes: ByteArray): DebugEntry {
    val c = Cursor(bytes)
    val function = FunctionId.of(c.u32())
    val block = BlockId.of(c.u32())
    val instruction = c.u32()
    val start = c.u32()
    val end = c.u32()
    val parent =
        c.u32().let {
            if (it ==
                UInt.MAX_VALUE
            ) {
                null
            } else {
                DebugEntryId.of(it)
            }
        }
    ; val length = c.u32().checkedInt("debug path")
    val path = MetadataText.of(strictUtf8(c.bytes(length)))
    require(c.done())
    return DebugEntry(function, block, instruction, start, end, parent, path)
}

private fun decodeCode(bytes: ByteArray): List<Instruction> {
    val c = Cursor(bytes)
    val result = mutableListOf<Instruction>()
    while (!c.done()) {
        val start = c.position
        val opcode = c.u8()
        val form = c.u8()
        val length = c.u16().toInt()
        require(length >= 4 && start + length <= c.limit) { "invalid instruction frame" }
        val frame = Cursor(bytes, c.position, start + length)
        c.position = start + length

        fun r() = RegisterId.of(frame.u16())

        fun d() = frame.u16().let { if (it == UShort.MAX_VALUE.toUInt()) Destination.Unit else Destination.Register(RegisterId.of(it)) }

        fun tri(factory: (RegisterId, RegisterId, RegisterId) -> Instruction): Instruction = factory(r(), r(), r())

        fun args(): List<RegisterId> = List(frame.uleb().checkedInt("argument count")) { r() }
        val instruction: Instruction =
            when (opcode) {
                0x01u -> {
                    Instruction.Move(r(), r())
                }

                0x02u -> {
                    Instruction.Const(r(), ConstantId.of(frame.uleb()))
                }

                0x03u -> {
                    Instruction.Null(r())
                }

                0x04u -> {
                    Instruction.Convert(r(), r())
                }

                0x10u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.AddI32(a, b, c)
                    }
                }

                0x11u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.SubtractI32(a, b, c)
                    }
                }

                0x12u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.MultiplyI32(a, b, c)
                    }
                }

                0x13u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.DivideI32(a, b, c)
                    }
                }

                0x14u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.RemainderI32(a, b, c)
                    }
                }

                0x16u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.BitAndI32(a, b, c)
                    }
                }

                0x17u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.BitOrI32(a, b, c)
                    }
                }

                0x18u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.BitXorI32(a, b, c)
                    }
                }

                0x19u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.ShiftLeftI32(a, b, c)
                    }
                }

                0x1bu -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.ShiftUnsignedI32(a, b, c)
                    }
                }

                0x20u -> {
                    Instruction.Equal(scalar(form), r(), r(), r())
                }

                0x26u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.RefEqual(a, b, c)
                    }
                }

                0x27u -> {
                    tri { a, b, c -> Instruction.RefNotEqual(a, b, c) }
                }

                0x22u -> {
                    Instruction.Less(ordered(form), r(), r(), r())
                }

                0x23u -> {
                    Instruction.LessOrEqual(ordered(form), r(), r(), r())
                }

                0x24u -> {
                    Instruction.Greater(ordered(form), r(), r(), r())
                }

                0x25u -> {
                    Instruction.GreaterOrEqual(ordered(form), r(), r(), r())
                }

                0x30u -> {
                    Instruction.NewObject(r(), frame.uleb().typeRef())
                }

                0x31u -> {
                    Instruction.NewArray(r(), frame.uleb().typeRef(), r())
                }

                0x32u -> {
                    Instruction.ArrayLength(r(), r())
                }

                0x33u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.ArrayLoad(a, b, c)
                    }
                }

                0x34u -> {
                    tri { a, b, c -> Instruction.ArrayStore(a, b, c) }
                }

                0x35u -> {
                    Instruction.FieldGet(r(), r(), frame.uleb().fieldRef())
                }

                0x36u -> {
                    Instruction.FieldSet(r(), frame.uleb().fieldRef(), r())
                }

                0x37u -> {
                    Instruction.StaticGet(r(), frame.uleb().fieldRef())
                }

                0x38u -> {
                    Instruction.StaticSet(frame.uleb().fieldRef(), r())
                }

                0x39u -> {
                    Instruction.IsType(r(), r(), frame.uleb().typeRef())
                }

                0x3au -> {
                    Instruction.CheckedCast(r(), r(), frame.uleb().typeRef())
                }

                0x40u -> {
                    Instruction.Call(d(), frame.uleb().functionRef(), args())
                }

                0xe5u -> {
                    Instruction.CallSuspend(d(), frame.uleb().functionRef(), args(), BlockId.of(frame.uleb()))
                }

                0x65u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.StringConcat(a, b, c)
                    }
                }

                0x68u -> {
                    val type =
                        when (form) {
                            1u -> StringValueType.I32
                            5u -> StringValueType.BOOL
                            6u -> StringValueType.CHAR
                            else -> error("unsupported string conversion form $form")
                        }
                    Instruction.StringValueOf(type, r(), r())
                }

                0x60u -> {
                    Instruction.StringLength(r(), r())
                }

                0x61u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.StringGet(a, b, c)
                    }
                }

                0x62u -> {
                    tri {
                        a,
                        b,
                        c,
                        ->
                        Instruction.StringEquals(a, b, c)
                    }
                }

                0x66u -> {
                    Instruction.StringSubstring(r(), r(), r(), r())
                }

                0x67u -> {
                    Instruction.StringFromCharArray(r(), r(), r(), r())
                }

                0x51u -> {
                    Instruction.CapabilityCallSync(d(), CapabilityId.of(frame.uleb()), frame.uleb(), args())
                }

                0x50u -> {
                    Instruction.TaskSpawn(r(), frame.uleb().functionRef(), args())
                }

                0xe8u -> {
                    Instruction.TaskJoin(d(), r(), BlockId.of(frame.uleb()))
                }

                0xe9u -> {
                    Instruction.CapabilityCallAsync(d(), CapabilityId.of(frame.uleb()), frame.uleb(), args(), BlockId.of(frame.uleb()))
                }

                0xe0u -> {
                    Instruction.Jump(BlockId.of(frame.uleb()))
                }

                0xe1u -> {
                    Instruction.Branch(r(), BlockId.of(frame.uleb()), BlockId.of(frame.uleb()))
                }

                0xe3u -> {
                    Instruction.Return(d())
                }

                0xe4u -> {
                    Instruction.Throw(r())
                }

                0xffu -> {
                    Instruction.Unreachable
                }

                else -> {
                    error("unsupported instruction opcode $opcode")
                }
            }
        require(frame.done()) { "trailing instruction operands" }
        result += instruction
    }
    return result
}

private fun indexed(section: Section): List<ByteArray> {
    val c = Cursor(section.payload)
    val count = c.u32().checkedInt("indexed count")
    require(count == section.count && c.u32() == 0u)
    val recordBytes = c.u64().checkedInt("indexed bytes")
    val offsets =
        List(count + 1) { c.u32().checkedInt("indexed offset") }
    ; require(offsets.first() == 0 && offsets.last() == recordBytes && offsets.zipWithNext().all { it.first <= it.second })
    c.position =
        align8(c.position)
    val base = c.position
    require(base + recordBytes == c.limit)
    return List(count) { section.payload.copyOfRange(base + offsets[it], base + offsets[it + 1]) }
}

private class Cursor(
    private val data: ByteArray,
    start: Int = 0,
    val limit: Int = data.size,
) {
    var position = start

    init {
        require(start in 0..limit && limit <= data.size)
    }

    fun done() = position == limit

    fun u8(): UInt {
        require(position < limit) { "truncated artifact" }
        return (data[position++].toInt() and 0xff).toUInt()
    }

    fun u16(): UInt = integer(2).toUInt()

    fun u32(): UInt = integer(4).toUInt()

    fun u64(): ULong = integer(8)

    fun bytes(length: Int): ByteArray {
        require(length >= 0 && position.toLong() + length <= limit)
        return data
            .copyOfRange(
                position,
                position + length,
            ).also { position += length }
    }

    fun uleb(): UInt {
        var result = 0u
        var shift = 0
        repeat(5) {
            val byte = u8()
            result = result or ((byte and 0x7fu) shl shift)
            if (byte and
                0x80u ==
                0u
            ) {
                return result
            }
            shift += 7
        }
        error("invalid ULEB128")
    }

    fun valueType(): ValueType {
        val kind = u8()
        val nullable = u8()
        require(u16() == 0u)
        val reference = u32()
        return when (kind) {
            0u -> {
                ValueType.Unit
            }

            1u -> {
                ValueType.I32
            }

            2u -> {
                ValueType.I64
            }

            3u -> {
                ValueType.F32
            }

            4u -> {
                ValueType.F64
            }

            5u -> {
                ValueType.Bool
            }

            6u -> {
                ValueType.Char
            }

            7u -> {
                ValueType.Ref(nullable == 1u, reference.typeRef())
            }

            else -> {
                error("invalid value type")
            }
        }
    }

    private fun integer(size: Int): ULong {
        require(position + size <= limit) { "truncated artifact" }
        var result = 0uL
        repeat(size) {
            result =
                result or ((data[position++].toInt() and 0xff).toULong() shl (it * 8))
        }
        return result
    }
}

private fun decodeUtf16(bytes: ByteArray): Utf16Literal {
    require(bytes.size % 2 == 0)
    return Utf16Literal.of(
        *IntArray(bytes.size / 2) {
            (
                bytes[
                    it *
                        2,
                ].toInt() and
                    0xff
            ) or
                ((bytes[it * 2 + 1].toInt() and 0xff) shl 8)
        },
    )
}

private fun UInt.typeRef(): TypeRef =
    if (this and 0x8000_0000u ==
        0u
    ) {
        TypeRef.Local(TypeId.of(this))
    } else {
        TypeRef.Imported(ImportId.of(this and 0x7fff_ffffu))
    }

private fun UInt.optionalTypeRef(): TypeRef? = if (this == UInt.MAX_VALUE) null else typeRef()

private fun UInt.functionRef(): FunctionRef =
    if (this and 0x8000_0000u ==
        0u
    ) {
        FunctionRef.Local(FunctionId.of(this))
    } else {
        FunctionRef.Imported(ImportId.of(this and 0x7fff_ffffu))
    }

private fun UInt.fieldRef(): FieldRef =
    if (this and 0x8000_0000u ==
        0u
    ) {
        FieldRef.Local(FieldId.of(this))
    } else {
        FieldRef.Imported(ImportId.of(this and 0x7fff_ffffu))
    }

private fun scalar(form: UInt) = ScalarValueType.entries.single { it.ordinal + 1 == form.toInt() }

private fun ordered(form: UInt) = OrderedScalarValueType.entries.single { it.name == scalar(form).name }

private inline fun <reified T : Enum<T>> enumAt(value: UInt): T =
    enumValues<T>().getOrElse(value.toInt()) {
        error("invalid ${T::class.simpleName}")
    }

private fun decodeFeatures(mask: UInt): Set<SemanticFeature> {
    val known =
        SemanticFeature.entries.fold(0u) { value, feature ->
            value or
                (1u shl feature.ordinal)
        }
    ; require(mask and known.inv() == 0u)
    return SemanticFeature.entries.filterTo(linkedSetOf()) {
        mask and
            (1u shl it.ordinal) !=
            0u
    }
}

private fun List<Section>.singleSection(
    kind: Int,
    scope: Int,
): Section = singleOrNull { it.kind == kind && it.scope == scope } ?: error("missing or duplicate artifact section $scope:$kind")

private fun strictUtf8(bytes: ByteArray): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(
            CodingErrorAction.REPORT,
        ).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString()

private fun ULong.checkedInt(label: String): Int = toLong().also { require(it in 0..Int.MAX_VALUE) { "$label exceeds Int" } }.toInt()

private fun UInt.checkedInt(label: String): Int = toLong().also { require(it <= Int.MAX_VALUE) { "$label exceeds Int" } }.toInt()

private fun align8(value: Int): Int = ((value.toLong() + 7L) and -8L).also { require(it <= Int.MAX_VALUE) }.toInt()

private val MAGIC = byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte())
private const val MANIFEST = 0x0001
private const val MODULES = 0x0002
private const val CAPABILITIES = 0x0003
private const val STRINGS = 0x0100
private const val TYPES = 0x0101
private const val CONSTANTS = 0x0102
private const val IMPORTS = 0x0103
private const val EXPORTS = 0x0104
private const val FIELDS = 0x0105
private const val FUNCTIONS = 0x0106
private const val BLOCKS = 0x0107
private const val CODE = 0x0108
private const val EXCEPTIONS = 0x0109
private const val UTF16_LITERALS = 0x010a
private const val SAFEPOINT_ROOTS = 0x010b
private const val DEBUG = 0x0110
