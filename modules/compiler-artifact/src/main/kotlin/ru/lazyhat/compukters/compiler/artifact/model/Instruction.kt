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

import java.util.Collections

sealed interface Instruction {
    data class Move(
        val destination: RegisterId,
        val source: RegisterId,
    ) : Instruction

    data class Const(
        val destination: RegisterId,
        val constant: ConstantId,
    ) : Instruction

    data class Null(
        val destination: RegisterId,
    ) : Instruction

    data class Convert(
        val destination: RegisterId,
        val source: RegisterId,
    ) : Instruction

    data class AddI32(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class SubtractI32(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class MultiplyI32(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class DivideI32(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class RemainderI32(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class BitAndI32(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class BitOrI32(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class BitXorI32(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class ShiftLeftI32(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class ShiftUnsignedI32(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class Equal(
        val type: ScalarValueType,
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class RefEqual(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class RefNotEqual(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class Less(
        val type: OrderedScalarValueType,
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class LessOrEqual(
        val type: OrderedScalarValueType,
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class Greater(
        val type: OrderedScalarValueType,
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class GreaterOrEqual(
        val type: OrderedScalarValueType,
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class NewObject(
        val destination: RegisterId,
        val type: TypeRef,
    ) : Instruction

    data class NewArray(
        val destination: RegisterId,
        val type: TypeRef,
        val length: RegisterId,
    ) : Instruction

    data class ArrayLength(
        val destination: RegisterId,
        val array: RegisterId,
    ) : Instruction

    data class ArrayLoad(
        val destination: RegisterId,
        val array: RegisterId,
        val index: RegisterId,
    ) : Instruction

    data class ArrayStore(
        val array: RegisterId,
        val index: RegisterId,
        val value: RegisterId,
    ) : Instruction

    data class FieldGet(
        val destination: RegisterId,
        val receiver: RegisterId,
        val field: FieldRef,
    ) : Instruction

    data class FieldSet(
        val receiver: RegisterId,
        val field: FieldRef,
        val value: RegisterId,
    ) : Instruction

    data class StaticGet(
        val destination: RegisterId,
        val field: FieldRef,
    ) : Instruction

    data class StaticSet(
        val field: FieldRef,
        val value: RegisterId,
    ) : Instruction

    data class IsType(
        val destination: RegisterId,
        val value: RegisterId,
        val type: TypeRef,
    ) : Instruction

    data class CheckedCast(
        val destination: RegisterId,
        val value: RegisterId,
        val type: TypeRef,
    ) : Instruction

    class Call(
        val destination: Destination,
        val function: FunctionRef,
        arguments: List<RegisterId>,
    ) : Instruction {
        val arguments: List<RegisterId> = Collections.unmodifiableList(ArrayList(arguments))

        override fun equals(other: Any?): Boolean =
            other is Call && destination == other.destination && function == other.function && arguments == other.arguments

        override fun hashCode(): Int = 31 * (31 * destination.hashCode() + function.hashCode()) + arguments.hashCode()

        override fun toString(): String = "Call(destination=$destination, function=$function, arguments=$arguments)"
    }

    class CallSuspend(
        val destination: Destination,
        val function: FunctionRef,
        arguments: List<RegisterId>,
        val resumeBlock: BlockId,
    ) : Instruction {
        val arguments: List<RegisterId> = Collections.unmodifiableList(ArrayList(arguments))

        override fun equals(other: Any?): Boolean =
            other is CallSuspend &&
                destination == other.destination &&
                function == other.function &&
                arguments == other.arguments &&
                resumeBlock == other.resumeBlock

        override fun hashCode(): Int =
            31 * (31 * (31 * destination.hashCode() + function.hashCode()) + arguments.hashCode()) + resumeBlock.hashCode()

        override fun toString(): String =
            "CallSuspend(destination=$destination, function=$function, arguments=$arguments, resumeBlock=$resumeBlock)"
    }

    data class StringConcat(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class StringValueOf(
        val type: StringValueType,
        val destination: RegisterId,
        val source: RegisterId,
    ) : Instruction

    data class StringLength(
        val destination: RegisterId,
        val string: RegisterId,
    ) : Instruction

    data class StringGet(
        val destination: RegisterId,
        val string: RegisterId,
        val index: RegisterId,
    ) : Instruction

    data class StringEquals(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    data class StringSubstring(
        val destination: RegisterId,
        val string: RegisterId,
        val start: RegisterId,
        val end: RegisterId,
    ) : Instruction

    data class StringFromCharArray(
        val destination: RegisterId,
        val array: RegisterId,
        val start: RegisterId,
        val end: RegisterId,
    ) : Instruction

    class CapabilityCallSync(
        val destination: Destination,
        val capability: CapabilityId,
        val operation: UInt,
        arguments: List<RegisterId>,
    ) : Instruction {
        val arguments: List<RegisterId> = Collections.unmodifiableList(ArrayList(arguments))

        override fun equals(other: Any?): Boolean =
            other is CapabilityCallSync &&
                destination == other.destination &&
                capability == other.capability &&
                operation == other.operation &&
                arguments == other.arguments

        override fun hashCode(): Int =
            31 * (31 * (31 * destination.hashCode() + capability.hashCode()) + operation.hashCode()) + arguments.hashCode()

        override fun toString(): String =
            "CapabilityCallSync(destination=$destination, capability=$capability, operation=$operation, arguments=$arguments)"
    }

    class TaskSpawn(
        val destination: RegisterId,
        val function: FunctionRef,
        arguments: List<RegisterId>,
    ) : Instruction {
        val arguments: List<RegisterId> = Collections.unmodifiableList(ArrayList(arguments))

        override fun equals(other: Any?): Boolean =
            other is TaskSpawn &&
                destination == other.destination &&
                function == other.function &&
                arguments == other.arguments

        override fun hashCode(): Int = 31 * (31 * destination.hashCode() + function.hashCode()) + arguments.hashCode()

        override fun toString(): String = "TaskSpawn(destination=$destination, function=$function, arguments=$arguments)"
    }

    data class TaskJoin(
        val destination: Destination,
        val task: RegisterId,
        val resumeBlock: BlockId,
    ) : Instruction

    class CapabilityCallAsync(
        val destination: Destination,
        val capability: CapabilityId,
        val operation: UInt,
        arguments: List<RegisterId>,
        val resumeBlock: BlockId,
    ) : Instruction {
        val arguments: List<RegisterId> = Collections.unmodifiableList(ArrayList(arguments))

        override fun equals(other: Any?): Boolean =
            other is CapabilityCallAsync &&
                destination == other.destination &&
                capability == other.capability &&
                operation == other.operation &&
                arguments == other.arguments &&
                resumeBlock == other.resumeBlock

        override fun hashCode(): Int =
            31 *
                (
                    31 *
                        (31 * (31 * destination.hashCode() + capability.hashCode()) + operation.hashCode()) +
                        arguments.hashCode()
                ) +
                resumeBlock.hashCode()

        override fun toString(): String =
            "CapabilityCallAsync(destination=$destination, capability=$capability, operation=$operation, " +
                "arguments=$arguments, resumeBlock=$resumeBlock)"
    }

    data class Jump(
        val target: BlockId,
    ) : Instruction

    data class Branch(
        val condition: RegisterId,
        val trueTarget: BlockId,
        val falseTarget: BlockId,
    ) : Instruction

    data class Return(
        val value: Destination,
    ) : Instruction

    data class Throw(
        val exception: RegisterId,
    ) : Instruction

    data object Unreachable : Instruction
}
