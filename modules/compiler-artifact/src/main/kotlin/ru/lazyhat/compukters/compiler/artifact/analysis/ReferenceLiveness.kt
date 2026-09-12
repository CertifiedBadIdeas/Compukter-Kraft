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

package ru.lazyhat.compukters.compiler.artifact.analysis

import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.PhysicalAtom
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.SafepointRoots
import ru.lazyhat.compukters.compiler.artifact.model.ValueComponent

object ReferenceLiveness {
    fun derive(module: Module): Module =
        module.copy(
            functions =
                module.functions.map { function ->
                    function.copy(safepointRoots = derive(module, function))
                },
        )

    fun derive(artifact: Artifact): Artifact =
        artifact.copy(
            modules = artifact.modules.map(::derive),
        )

    fun derive(
        module: Module,
        function: Function,
    ): List<SafepointRoots> {
        val firstBlock = function.firstBlock.value.toInt()
        val blockCount = function.blockCount.toInt()
        require(firstBlock >= 0 && blockCount >= 0 && firstBlock.toLong() + blockCount <= module.blocks.size) {
            "function block range is invalid"
        }
        val exceptionStart = function.firstException.toLong()
        val exceptionEnd = exceptionStart + function.exceptionCount.toLong()
        require(exceptionStart <= module.exceptions.size && exceptionEnd <= module.exceptions.size) {
            "function exception range is invalid"
        }
        val blockRange = firstBlock until firstBlock + blockCount
        val exceptions = module.exceptions.subList(exceptionStart.toInt(), exceptionEnd.toInt())
        val nodes =
            blockRange.flatMap { blockIndex ->
                module.blocks[blockIndex]
                    .instructions.indices
                    .map { instructionIndex -> Node(blockIndex, instructionIndex) }
            }
        if (nodes.isEmpty()) return emptyList()
        val nodeSet = nodes.toSet()
        val successors = nodes.associateWith { node -> successorEdges(module, node, exceptions).filter { it.target in nodeSet } }
        val predecessors = mutableMapOf<Node, MutableSet<Node>>()
        successors.forEach { (source, edges) ->
            edges.forEach { edge -> predecessors.getOrPut(edge.target, ::mutableSetOf).add(source) }
        }
        val entry = Node(firstBlock, 0)
        val reachable = mutableSetOf<Node>()
        val reachabilityQueue = ArrayDeque<Node>().apply { if (entry in nodeSet) add(entry) }
        while (reachabilityQueue.isNotEmpty()) {
            val node = reachabilityQueue.removeFirst()
            if (!reachable.add(node)) continue
            successors.getValue(node).map(Edge::target).forEach(reachabilityQueue::addLast)
        }

        val liveBefore = nodes.associateWith { mutableSetOf<RegisterId>() }
        val queue = ArrayDeque<Node>().apply { addAll(reachable.reversed()) }
        val queued = reachable.toMutableSet()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            queued.remove(node)
            val instruction = module.blocks[node.block].instructions[node.instruction]
            val liveOut =
                buildSet {
                    successors.getValue(node).forEach { edge ->
                        addAll(liveBefore.getValue(edge.target) - edge.definitions)
                    }
                }
            val updated =
                buildSet {
                    addAll(instruction.readRegisters())
                    addAll(liveOut - instruction.writtenRegisters().toSet())
                }
            val current = liveBefore.getValue(node)
            if (current != updated) {
                current.clear()
                current += updated
                predecessors[node]
                    .orEmpty()
                    .filter { it in reachable && queued.add(it) }
                    .forEach(queue::addLast)
            }
        }

        return nodes.filter { it in reachable }.map { node ->
            SafepointRoots(
                block = BlockId.of(node.block.toUInt()),
                instructionBoundary = node.instruction.toUInt(),
                references = referenceComponents(function, liveBefore.getValue(node)),
            )
        }
    }
}

private data class Node(
    val block: Int,
    val instruction: Int,
)

private data class Edge(
    val target: Node,
    val definitions: Set<RegisterId> = emptySet(),
)

private fun successorEdges(
    module: Module,
    node: Node,
    exceptions: List<ru.lazyhat.compukters.compiler.artifact.model.ExceptionEntry>,
): List<Edge> {
    val instructions = module.blocks[node.block].instructions
    val instruction = instructions[node.instruction]
    val normal =
        if (node.instruction + 1 < instructions.size) {
            listOf(Edge(Node(node.block, node.instruction + 1)))
        } else {
            instruction.successors().mapNotNull { successor ->
                module.blocks
                    .getOrNull(successor.value.toInt())
                    ?.instructions
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Edge(Node(successor.value.toInt(), 0)) }
            }
        }
    if (!instruction.mayThrow()) return normal
    val exceptional =
        exceptions.mapNotNull { exception ->
            val protectedStart = exception.firstProtectedBlock.value.toLong()
            val protectedEnd = protectedStart + exception.protectedBlockCount.toLong()
            if (node.block.toLong() !in protectedStart until protectedEnd) return@mapNotNull null
            module.blocks
                .getOrNull(exception.handlerBlock.value.toInt())
                ?.instructions
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    Edge(
                        target = Node(exception.handlerBlock.value.toInt(), 0),
                        definitions = setOf(exception.exceptionRegister),
                    )
                }
        }
    return normal + exceptional
}

private fun referenceComponents(
    function: Function,
    liveValues: Set<RegisterId>,
): List<ValueComponent> =
    liveValues
        .sortedBy(RegisterId::value)
        .flatMap { value ->
            function.values
                .getOrNull(value.value.toInt())
                ?.physicalShape
                ?.components
                ?.mapIndexedNotNull { component, atom ->
                    ValueComponent(value, component.toUShort()).takeIf { atom == PhysicalAtom.REF32 }
                }.orEmpty()
        }

internal fun Instruction.readRegisters(): List<RegisterId> =
    when (this) {
        is Instruction.Const,
        is Instruction.Null,
        is Instruction.NewObject,
        is Instruction.Jump,
        Instruction.Unreachable,
        -> emptyList()

        is Instruction.Move -> listOf(source)

        is Instruction.Convert -> listOf(source)

        is Instruction.AddI32 -> listOf(left, right)

        is Instruction.SubtractI32 -> listOf(left, right)

        is Instruction.MultiplyI32 -> listOf(left, right)

        is Instruction.DivideI32 -> listOf(left, right)

        is Instruction.RemainderI32 -> listOf(left, right)

        is Instruction.BitAndI32 -> listOf(left, right)

        is Instruction.BitOrI32 -> listOf(left, right)

        is Instruction.BitXorI32 -> listOf(left, right)

        is Instruction.ShiftLeftI32 -> listOf(left, right)

        is Instruction.ShiftUnsignedI32 -> listOf(left, right)

        is Instruction.Equal -> listOf(left, right)

        is Instruction.RefEqual -> listOf(left, right)

        is Instruction.RefNotEqual -> listOf(left, right)

        is Instruction.Less -> listOf(left, right)

        is Instruction.LessOrEqual -> listOf(left, right)

        is Instruction.Greater -> listOf(left, right)

        is Instruction.GreaterOrEqual -> listOf(left, right)

        is Instruction.NewArray -> listOf(length)

        is Instruction.ArrayLength -> listOf(array)

        is Instruction.ArrayLoad -> listOf(array, index)

        is Instruction.ArrayStore -> listOf(array, index, value)

        is Instruction.FieldGet -> listOf(receiver)

        is Instruction.FieldSet -> listOf(receiver, value)

        is Instruction.StaticGet -> emptyList()

        is Instruction.StaticSet -> listOf(value)

        is Instruction.IsType -> listOf(value)

        is Instruction.CheckedCast -> listOf(value)

        is Instruction.Call -> arguments

        is Instruction.CallSuspend -> arguments

        is Instruction.TaskSpawn -> arguments

        is Instruction.TaskJoin -> listOf(task)

        is Instruction.ChannelCreate -> listOf(capacity)

        is Instruction.ChannelSend -> listOf(channel, value)

        is Instruction.ChannelReceive -> listOf(channel)

        is Instruction.StringConcat -> listOf(left, right)

        is Instruction.StringValueOf -> listOf(source)

        is Instruction.StringLength -> listOf(string)

        is Instruction.StringGet -> listOf(string, index)

        is Instruction.StringEquals -> listOf(left, right)

        is Instruction.StringSubstring -> listOf(string, start, end)

        is Instruction.StringFromCharArray -> listOf(array, start, end)

        is Instruction.CapabilityCallSync -> arguments

        is Instruction.CapabilityCallAsync -> arguments

        is Instruction.Branch -> listOf(condition)

        is Instruction.Return -> (value as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.Throw -> listOf(exception)
    }

internal fun Instruction.writtenRegisters(): List<RegisterId> =
    when (this) {
        is Instruction.Move -> listOf(destination)

        is Instruction.Const -> listOf(destination)

        is Instruction.Null -> listOf(destination)

        is Instruction.Convert -> listOf(destination)

        is Instruction.AddI32 -> listOf(destination)

        is Instruction.SubtractI32 -> listOf(destination)

        is Instruction.MultiplyI32 -> listOf(destination)

        is Instruction.DivideI32 -> listOf(destination)

        is Instruction.RemainderI32 -> listOf(destination)

        is Instruction.BitAndI32 -> listOf(destination)

        is Instruction.BitOrI32 -> listOf(destination)

        is Instruction.BitXorI32 -> listOf(destination)

        is Instruction.ShiftLeftI32 -> listOf(destination)

        is Instruction.ShiftUnsignedI32 -> listOf(destination)

        is Instruction.Equal -> listOf(destination)

        is Instruction.RefEqual -> listOf(destination)

        is Instruction.RefNotEqual -> listOf(destination)

        is Instruction.Less -> listOf(destination)

        is Instruction.LessOrEqual -> listOf(destination)

        is Instruction.Greater -> listOf(destination)

        is Instruction.GreaterOrEqual -> listOf(destination)

        is Instruction.NewObject -> listOf(destination)

        is Instruction.NewArray -> listOf(destination)

        is Instruction.ArrayLength -> listOf(destination)

        is Instruction.ArrayLoad -> listOf(destination)

        is Instruction.FieldGet -> listOf(destination)

        is Instruction.StaticGet -> listOf(destination)

        is Instruction.IsType -> listOf(destination)

        is Instruction.CheckedCast -> listOf(destination)

        is Instruction.Call -> (destination as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.CallSuspend -> (destination as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.TaskSpawn -> listOf(destination)

        is Instruction.TaskJoin -> (destination as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.ChannelCreate -> listOf(destination)

        is Instruction.ChannelReceive -> listOf(destination)

        is Instruction.StringConcat -> listOf(destination)

        is Instruction.StringValueOf -> listOf(destination)

        is Instruction.StringLength -> listOf(destination)

        is Instruction.StringGet -> listOf(destination)

        is Instruction.StringEquals -> listOf(destination)

        is Instruction.StringSubstring -> listOf(destination)

        is Instruction.StringFromCharArray -> listOf(destination)

        is Instruction.CapabilityCallSync -> (destination as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.CapabilityCallAsync -> (destination as? Destination.Register)?.let { listOf(it.id) }.orEmpty()

        is Instruction.ArrayStore,
        is Instruction.ChannelSend,
        is Instruction.FieldSet,
        is Instruction.StaticSet,
        is Instruction.Jump,
        is Instruction.Branch,
        is Instruction.Return,
        is Instruction.Throw,
        Instruction.Unreachable,
        -> emptyList()
    }

internal fun Instruction.successors(): List<BlockId> =
    when (this) {
        is Instruction.Jump -> listOf(target)
        is Instruction.Branch -> listOf(trueTarget, falseTarget)
        is Instruction.CallSuspend -> listOf(resumeBlock)
        is Instruction.TaskJoin -> listOf(resumeBlock)
        is Instruction.ChannelSend -> listOf(resumeBlock)
        is Instruction.ChannelReceive -> listOf(resumeBlock)
        is Instruction.CapabilityCallAsync -> listOf(resumeBlock)
        else -> emptyList()
    }

internal fun Instruction.mayThrow(): Boolean =
    this is Instruction.NewObject ||
        this is Instruction.NewArray ||
        this is Instruction.ArrayLength ||
        this is Instruction.ArrayLoad ||
        this is Instruction.ArrayStore ||
        this is Instruction.FieldGet ||
        this is Instruction.FieldSet ||
        this is Instruction.StaticGet ||
        this is Instruction.StaticSet ||
        this is Instruction.CheckedCast ||
        this is Instruction.Call ||
        this is Instruction.CallSuspend ||
        this is Instruction.TaskSpawn ||
        this is Instruction.TaskJoin ||
        this is Instruction.ChannelCreate ||
        this is Instruction.ChannelSend ||
        this is Instruction.ChannelReceive ||
        this is Instruction.CapabilityCallSync ||
        this is Instruction.CapabilityCallAsync ||
        this is Instruction.StringGet ||
        this is Instruction.StringConcat ||
        this is Instruction.StringValueOf ||
        this is Instruction.StringSubstring ||
        this is Instruction.StringFromCharArray ||
        this is Instruction.Throw
