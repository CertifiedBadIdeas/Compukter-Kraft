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

import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.FieldRef
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef

internal data class EncodedInstruction(
    val bytes: ByteArray,
    val fixedCost: UInt,
)

internal fun encodeInstruction(
    instruction: Instruction,
    maximumBytes: Int,
): EncodedInstruction {
    val operands = BinarySink(maximumBytes)
    val opcode: UInt
    var form = 0u
    val cost = instructionFixedCost(instruction)

    when (instruction) {
        is Instruction.Move -> {
            opcode = 0x01u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.source)
        }

        is Instruction.Const -> {
            opcode = 0x02u
            operands.writeRegister(instruction.destination)
            operands.writeUleb128(instruction.constant.value)
        }

        is Instruction.Null -> {
            opcode = 0x03u
            operands.writeRegister(instruction.destination)
        }

        is Instruction.Convert -> {
            opcode = 0x04u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.source)
        }

        is Instruction.AddI32 -> {
            opcode = 0x10u
            form = 1u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.SubtractI32 -> {
            opcode = 0x11u
            form = 1u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.MultiplyI32 -> {
            opcode = 0x12u
            form = 1u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.DivideI32 -> {
            opcode = 0x13u
            form = 1u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.RemainderI32 -> {
            opcode = 0x14u
            form = 1u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.BitAndI32 -> {
            opcode = 0x16u
            form = 1u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.BitOrI32 -> {
            opcode = 0x17u
            form = 1u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.BitXorI32 -> {
            opcode = 0x18u
            form = 1u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.ShiftLeftI32 -> {
            opcode = 0x19u
            form = 1u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.ShiftUnsignedI32 -> {
            opcode = 0x1bu
            form = 1u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.Equal -> {
            opcode = 0x20u
            form = instruction.type.artifactForm
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.RefEqual -> {
            opcode = 0x26u
            form = 7u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.RefNotEqual -> {
            opcode = 0x27u
            form = 7u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.Less -> {
            opcode = 0x22u
            form = instruction.type.artifactForm
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.LessOrEqual -> {
            opcode = 0x23u
            form = instruction.type.artifactForm
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.Greater -> {
            opcode = 0x24u
            form = instruction.type.artifactForm
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.GreaterOrEqual -> {
            opcode = 0x25u
            form = instruction.type.artifactForm
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.NewObject -> {
            opcode = 0x30u
            operands.writeRegister(instruction.destination)
            operands.writeUleb128(encodeTypeRef(instruction.type))
        }

        is Instruction.NewArray -> {
            opcode = 0x31u
            operands.writeRegister(instruction.destination)
            operands.writeUleb128(encodeTypeRef(instruction.type))
            operands.writeRegister(instruction.length)
        }

        is Instruction.ArrayLength -> {
            opcode = 0x32u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.array)
        }

        is Instruction.ArrayLoad -> {
            opcode = 0x33u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.array)
            operands.writeRegister(instruction.index)
        }

        is Instruction.ArrayStore -> {
            opcode = 0x34u
            operands.writeRegister(instruction.array)
            operands.writeRegister(instruction.index)
            operands.writeRegister(instruction.value)
        }

        is Instruction.FieldGet -> {
            opcode = 0x35u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.receiver)
            operands.writeUleb128(encodeFieldRef(instruction.field))
        }

        is Instruction.FieldSet -> {
            opcode = 0x36u
            operands.writeRegister(instruction.receiver)
            operands.writeUleb128(encodeFieldRef(instruction.field))
            operands.writeRegister(instruction.value)
        }

        is Instruction.StaticGet -> {
            opcode = 0x37u
            operands.writeRegister(instruction.destination)
            operands.writeUleb128(encodeFieldRef(instruction.field))
        }

        is Instruction.StaticSet -> {
            opcode = 0x38u
            operands.writeUleb128(encodeFieldRef(instruction.field))
            operands.writeRegister(instruction.value)
        }

        is Instruction.IsType -> {
            opcode = 0x39u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.value)
            operands.writeUleb128(encodeTypeRef(instruction.type))
        }

        is Instruction.CheckedCast -> {
            opcode = 0x3au
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.value)
            operands.writeUleb128(encodeTypeRef(instruction.type))
        }

        is Instruction.Call -> {
            opcode = 0x40u
            operands.writeDestination(instruction.destination)
            operands.writeUleb128(encodeFunctionRef(instruction.function))
            operands.writeArguments(instruction.arguments)
        }

        is Instruction.CallSuspend -> {
            opcode = 0xe5u
            operands.writeDestination(instruction.destination)
            operands.writeUleb128(encodeFunctionRef(instruction.function))
            operands.writeArguments(instruction.arguments)
            operands.writeUleb128(instruction.resumeBlock.value)
        }

        is Instruction.StringConcat -> {
            opcode = 0x65u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.left)
            operands.writeRegister(instruction.right)
        }

        is Instruction.StringValueOf -> {
            opcode = 0x68u
            form = instruction.type.artifactForm
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.source)
        }

        is Instruction.StringLength -> {
            opcode = 0x60u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.string)
        }

        is Instruction.StringGet -> {
            opcode = 0x61u
            operands.writeBinaryRegisters(instruction.destination, instruction.string, instruction.index)
        }

        is Instruction.StringEquals -> {
            opcode = 0x62u
            operands.writeBinaryRegisters(instruction.destination, instruction.left, instruction.right)
        }

        is Instruction.StringSubstring -> {
            opcode = 0x66u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.string)
            operands.writeRegister(instruction.start)
            operands.writeRegister(instruction.end)
        }

        is Instruction.StringFromCharArray -> {
            opcode = 0x67u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.array)
            operands.writeRegister(instruction.start)
            operands.writeRegister(instruction.end)
        }

        is Instruction.CapabilityCallSync -> {
            opcode = 0x51u
            operands.writeDestination(instruction.destination)
            operands.writeUleb128(instruction.capability.value)
            operands.writeUleb128(instruction.operation)
            operands.writeArguments(instruction.arguments)
        }

        is Instruction.TaskSpawn -> {
            opcode = 0x50u
            operands.writeRegister(instruction.destination)
            operands.writeUleb128(encodeFunctionRef(instruction.function))
            operands.writeArguments(instruction.arguments)
        }

        is Instruction.TaskJoin -> {
            opcode = 0xe8u
            operands.writeDestination(instruction.destination)
            operands.writeRegister(instruction.task)
            operands.writeUleb128(instruction.resumeBlock.value)
        }

        is Instruction.ChannelCreate -> {
            opcode = 0x52u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.capacity)
        }

        is Instruction.ChannelSend -> {
            opcode = 0xeau
            operands.writeRegister(instruction.channel)
            operands.writeRegister(instruction.value)
            operands.writeUleb128(instruction.resumeBlock.value)
        }

        is Instruction.ChannelReceive -> {
            opcode = 0xebu
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.channel)
            operands.writeUleb128(instruction.resumeBlock.value)
        }

        is Instruction.CapabilityCallAsync -> {
            opcode = 0xe9u
            operands.writeDestination(instruction.destination)
            operands.writeUleb128(instruction.capability.value)
            operands.writeUleb128(instruction.operation)
            operands.writeArguments(instruction.arguments)
            operands.writeUleb128(instruction.resumeBlock.value)
        }

        is Instruction.Jump -> {
            opcode = 0xe0u
            operands.writeUleb128(instruction.target.value)
        }

        is Instruction.Branch -> {
            opcode = 0xe1u
            operands.writeRegister(instruction.condition)
            operands.writeUleb128(instruction.trueTarget.value)
            operands.writeUleb128(instruction.falseTarget.value)
        }

        is Instruction.Return -> {
            opcode = 0xe3u
            operands.writeDestination(instruction.value)
        }

        is Instruction.Throw -> {
            opcode = 0xe4u
            operands.writeRegister(instruction.exception)
        }

        Instruction.Unreachable -> {
            opcode = 0xffu
        }
    }

    val operandBytes = operands.toByteArray()
    val length = 4L + operandBytes.size
    if (length > UShort.MAX_VALUE.toLong()) {
        throw ArtifactEncodingException(ArtifactWriteErrorCode.OVERFLOW, "instruction frame exceeds u16")
    }
    val frame = BinarySink(maximumBytes)
    frame.writeU8(opcode)
    frame.writeU8(form)
    frame.writeU16(length.toUInt())
    frame.writeBytes(operandBytes)
    return EncodedInstruction(frame.toByteArray(), cost)
}

private fun BinarySink.writeRegister(register: RegisterId) {
    writeU16(register.value.toUInt())
}

private fun BinarySink.writeDestination(destination: Destination) {
    when (destination) {
        is Destination.Register -> writeRegister(destination.id)
        Destination.Unit -> writeU16(UShort.MAX_VALUE.toUInt())
    }
}

private fun BinarySink.writeBinaryRegisters(
    destination: RegisterId,
    left: RegisterId,
    right: RegisterId,
) {
    writeRegister(destination)
    writeRegister(left)
    writeRegister(right)
}

private fun BinarySink.writeArguments(arguments: List<RegisterId>) {
    writeUleb128(arguments.size.toUInt())
    arguments.forEach(::writeRegister)
}

private fun variableCost(
    base: UInt,
    argumentCount: Int,
): UInt {
    val cost = base.toULong() + argumentCount.toULong()
    if (cost > UInt.MAX_VALUE.toULong()) {
        throw ArtifactEncodingException(ArtifactWriteErrorCode.OVERFLOW, "instruction fixed cost exceeds u32")
    }
    return cost.toUInt()
}

internal fun instructionFixedCost(instruction: Instruction): UInt =
    when (instruction) {
        is Instruction.Move,
        is Instruction.Const,
        is Instruction.Null,
        is Instruction.AddI32,
        is Instruction.SubtractI32,
        is Instruction.BitAndI32,
        is Instruction.BitOrI32,
        is Instruction.BitXorI32,
        is Instruction.ShiftLeftI32,
        is Instruction.ShiftUnsignedI32,
        is Instruction.Equal,
        is Instruction.RefEqual,
        is Instruction.RefNotEqual,
        is Instruction.Less,
        is Instruction.LessOrEqual,
        is Instruction.Greater,
        is Instruction.GreaterOrEqual,
        is Instruction.StringLength,
        is Instruction.StringGet,
        is Instruction.StringEquals,
        is Instruction.StringSubstring,
        is Instruction.StringFromCharArray,
        is Instruction.StringConcat,
        is Instruction.StringValueOf,
        is Instruction.Jump,
        is Instruction.Branch,
        is Instruction.Return,
        Instruction.Unreachable,
        -> 1u

        is Instruction.MultiplyI32 -> 2u

        is Instruction.DivideI32,
        is Instruction.RemainderI32,
        -> 4u

        is Instruction.ArrayLoad,
        is Instruction.ArrayStore,
        is Instruction.Convert,
        is Instruction.ArrayLength,
        is Instruction.FieldGet,
        is Instruction.FieldSet,
        is Instruction.StaticGet,
        is Instruction.StaticSet,
        is Instruction.IsType,
        is Instruction.CheckedCast,
        is Instruction.Throw,
        -> 2u

        is Instruction.NewObject,
        is Instruction.NewArray,
        -> 4u

        is Instruction.Call -> variableCost(4u, instruction.arguments.size)

        is Instruction.CallSuspend -> variableCost(5u, instruction.arguments.size)

        is Instruction.TaskSpawn -> variableCost(6u, instruction.arguments.size)

        is Instruction.TaskJoin -> 4u

        is Instruction.ChannelCreate -> 3u

        is Instruction.ChannelSend,
        is Instruction.ChannelReceive,
        -> 4u

        is Instruction.CapabilityCallSync -> variableCost(5u, instruction.arguments.size)

        is Instruction.CapabilityCallAsync -> variableCost(6u, instruction.arguments.size)
    }

internal fun encodeTypeRef(reference: TypeRef): UInt =
    when (reference) {
        is TypeRef.Local -> reference.id.value
        is TypeRef.Imported -> reference.id.value or 0x8000_0000u
    }

internal fun encodeFunctionRef(reference: FunctionRef): UInt =
    when (reference) {
        is FunctionRef.Local -> reference.id.value
        is FunctionRef.Imported -> reference.id.value or 0x8000_0000u
    }

internal fun encodeFieldRef(reference: FieldRef): UInt =
    when (reference) {
        is FieldRef.Local -> reference.id.value
        is FieldRef.Imported -> reference.id.value or 0x8000_0000u
    }
