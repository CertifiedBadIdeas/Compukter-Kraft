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

package ru.lazyhat.compukters.compiler.artifact.link

import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.FieldRef
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import java.util.ArrayDeque

internal data class ModuleReachability(
    val strings: MutableSet<Int> = sortedSetOf(),
    val literals: MutableSet<Int> = sortedSetOf(),
    val types: MutableSet<Int> = sortedSetOf(),
    val constants: MutableSet<Int> = sortedSetOf(),
    val imports: MutableSet<Int> = sortedSetOf(),
    val exports: MutableSet<Int> = sortedSetOf(),
    val fields: MutableSet<Int> = sortedSetOf(),
    val functions: MutableSet<Int> = sortedSetOf(),
    val blocks: MutableSet<Int> = sortedSetOf(),
    val exceptions: MutableSet<Int> = sortedSetOf(),
    val debug: MutableSet<Int> = sortedSetOf(),
)

internal data class ReachabilityResult(
    val modules: List<ModuleReachability>,
    val capabilities: MutableSet<Int>,
    val importTargets: Map<Pair<Int, Int>, Int>,
)

internal class ReachabilityGraph(
    private val artifact: Artifact,
) {
    private sealed interface Node {
        val module: Int
        val index: Int

        data class Type(
            override val module: Int,
            override val index: Int,
        ) : Node

        data class Constant(
            override val module: Int,
            override val index: Int,
        ) : Node

        data class Import(
            override val module: Int,
            override val index: Int,
        ) : Node

        data class Field(
            override val module: Int,
            override val index: Int,
        ) : Node

        data class Function(
            override val module: Int,
            override val index: Int,
        ) : Node

        data class Block(
            override val module: Int,
            override val index: Int,
        ) : Node

        data class Exception(
            override val module: Int,
            override val index: Int,
        ) : Node

        data class Debug(
            override val module: Int,
            override val index: Int,
        ) : Node
    }

    private val reachable = List(artifact.modules.size) { ModuleReachability() }
    private val capabilities = sortedSetOf<Int>()
    private val queue = ArrayDeque<Node>()
    private val importTargets = mutableMapOf<Pair<Int, Int>, Int>()
    private val moduleHashes = artifact.modules.map(ArtifactWriter::moduleSemanticHash)

    fun analyze(): ReachabilityResult {
        require(
            artifact.entry.module.value
                .toInt() in artifact.modules.indices,
        ) { "entry module is absent" }
        markFunction(
            artifact.entry.module.value
                .toInt(),
            artifact.entry.function.value
                .toInt(),
        )
        artifact.modules.forEachIndexed { index, module -> markString(index, module.name.value.toInt()) }
        while (queue.isNotEmpty()) visit(queue.removeFirst())
        markCapabilityNames()
        markDebugForReachableInstructions()
        while (queue.isNotEmpty()) visit(queue.removeFirst())
        return ReachabilityResult(reachable, capabilities, importTargets)
    }

    private fun visit(node: Node) {
        val module = artifact.modules[node.module]
        when (node) {
            is Node.Type -> {
                visitType(node.module, module.types.at(node, "type"))
            }

            is Node.Constant -> {
                visitConstant(node.module, module.constants.at(node, "constant"))
            }

            is Node.Import -> {
                visitImport(node.module, node.index, module.imports.at(node, "import"))
            }

            is Node.Field -> {
                val field = module.fields.at(node, "field")
                markType(node.module, field.owner)
                markString(node.module, field.name.value.toInt())
                markValueType(node.module, field.type)
            }

            is Node.Function -> {
                visitFunction(node.module, node.index, module.functions.at(node, "function"))
            }

            is Node.Block -> {
                module.blocks
                    .at(node, "block")
                    .instructions
                    .forEach { visitInstruction(node.module, it) }
            }

            is Node.Exception -> {
                val exception = module.exceptions.at(node, "exception")
                exception.catchType?.let { markType(node.module, it) }
                markBlock(node.module, exception.handlerBlock.value.toInt())
            }

            is Node.Debug -> {
                val debug = module.debug.at(node, "debug")
                debug.inlineParent?.let { markDebug(node.module, it.value.toInt()) }
            }
        }
    }

    private fun visitType(
        module: Int,
        type: NominalType,
    ) {
        markString(module, type.name.value.toInt())
        when (type) {
            is NominalType.Array -> {
                markValueType(module, type.element)
            }

            is NominalType.Function -> {
                markValueType(module, type.result)
                type.parameters.forEach { markValueType(module, it) }
            }

            is NominalType.Class -> {
                type.superType?.let { markType(module, it) }
                type.interfaces.forEach { markType(module, it) }
                type.initializer?.let { markFunction(module, it.value.toInt()) }
                markRange(type.fieldStart, type.fieldCount) { markField(module, it) }
                markRange(type.methodStart, type.methodCount) { markFunction(module, it) }
            }

            is NominalType.Interface -> {
                type.superType?.let { markType(module, it) }
                type.interfaces.forEach { markType(module, it) }
                markRange(type.methodStart, type.methodCount) { markFunction(module, it) }
            }
        }
    }

    private fun visitConstant(
        module: Int,
        constant: Constant,
    ) {
        if (constant is Constant.StringLiteral) markLiteral(module, constant.literal.value.toInt())
    }

    private fun visitImport(
        moduleIndex: Int,
        importIndex: Int,
        import: ru.lazyhat.compukters.compiler.artifact.model.Import,
    ) {
        markString(moduleIndex, import.targetName.value.toInt())
        markType(moduleIndex, import.expectedSignature)
        val matches = moduleHashes.indices.filter { candidate -> import.targetModuleHash.contentEquals(moduleHashes[candidate]) }
        require(matches.size == 1) { "import $moduleIndex:$importIndex has ${matches.size} semantic-hash targets" }
        val targetModule = matches.single()
        val source = artifact.modules[moduleIndex]
        val expectedName =
            source.strings.getOrNull(import.targetName.value.toInt())?.toString()
                ?: error("import $moduleIndex:$importIndex has an invalid target name")
        val candidates =
            artifact.modules[targetModule].exports.withIndex().filter { (_, export) ->
                export.kind == import.kind &&
                    artifact.modules[targetModule]
                        .strings
                        .getOrNull(export.name.value.toInt())
                        ?.toString() == expectedName &&
                    (
                        import.kind == SymbolKind.TYPE ||
                            signaturesMatch(moduleIndex, import.expectedSignature, targetModule, export.signature)
                    )
            }
        require(candidates.size == 1) {
            "import $moduleIndex:$importIndex ${import.kind} $expectedName resolves to ${candidates.size} exports"
        }
        val (exportIndex, export) = candidates.single()
        importTargets[moduleIndex to importIndex] = targetModule
        reachable[targetModule].exports += exportIndex
        markString(targetModule, export.name.value.toInt())
        markType(targetModule, export.signature)
        when (export.kind) {
            SymbolKind.TYPE -> markType(targetModule, export.localSymbol.toInt())
            SymbolKind.FUNCTION -> markFunction(targetModule, export.localSymbol.toInt())
            SymbolKind.FIELD -> markField(targetModule, export.localSymbol.toInt())
        }
    }

    private fun visitFunction(
        module: Int,
        index: Int,
        function: ru.lazyhat.compukters.compiler.artifact.model.Function,
    ) {
        function.owner?.let { markType(module, it) }
        markString(module, function.name.value.toInt())
        markType(module, function.signature)
        function.values.forEach { markValueType(module, it.semanticType) }
        markRange(function.firstBlock.value, function.blockCount) { markBlock(module, it) }
        markRange(function.firstException, function.exceptionCount) { markException(module, it) }
        artifact.modules[module].debug.withIndex().forEach { (debugIndex, debug) ->
            if (debug.function.value.toInt() == index) markDebug(module, debugIndex)
        }
    }

    private fun visitInstruction(
        module: Int,
        instruction: Instruction,
    ) {
        when (instruction) {
            is Instruction.Const -> markConstant(module, instruction.constant.value.toInt())
            is Instruction.NewObject -> markType(module, instruction.type)
            is Instruction.NewArray -> markType(module, instruction.type)
            is Instruction.FieldGet -> markField(module, instruction.field)
            is Instruction.FieldSet -> markField(module, instruction.field)
            is Instruction.StaticGet -> markField(module, instruction.field)
            is Instruction.StaticSet -> markField(module, instruction.field)
            is Instruction.IsType -> markType(module, instruction.type)
            is Instruction.CheckedCast -> markType(module, instruction.type)
            is Instruction.Call -> markFunction(module, instruction.function)
            is Instruction.CallSuspend -> markFunction(module, instruction.function)
            is Instruction.TaskSpawn -> markFunction(module, instruction.function)
            is Instruction.CapabilityCallSync -> capabilities += instruction.capability.value.toInt()
            is Instruction.CapabilityCallAsync -> capabilities += instruction.capability.value.toInt()
            else -> Unit
        }
    }

    private fun markCapabilityNames() {
        val application = artifact.modules.indexOfFirst { it.kind == ru.lazyhat.compukters.compiler.artifact.model.ModuleKind.APPLICATION }
        require(application >= 0) { "application module is absent" }
        capabilities.forEach { index ->
            val capability = artifact.capabilities.getOrNull(index) ?: error("capability $index is absent")
            markString(application, capability.namespace.value.toInt())
            markString(application, capability.name.value.toInt())
        }
    }

    private fun signaturesMatch(
        leftModule: Int,
        left: TypeRef,
        rightModule: Int,
        right: TypeRef,
    ): Boolean {
        val leftIdentity = resolveType(leftModule, left) ?: return false
        val rightIdentity = resolveType(rightModule, right) ?: return false
        val leftType = artifact.modules[leftIdentity.first].types[leftIdentity.second]
        val rightType = artifact.modules[rightIdentity.first].types[rightIdentity.second]
        if (leftType !is NominalType.Function || rightType !is NominalType.Function) return leftIdentity == rightIdentity
        return leftType.suspending == rightType.suspending &&
            valueTypesMatch(leftIdentity.first, leftType.result, rightIdentity.first, rightType.result) &&
            leftType.parameters.size == rightType.parameters.size &&
            leftType.parameters.zip(rightType.parameters).all { (leftParameter, rightParameter) ->
                valueTypesMatch(leftIdentity.first, leftParameter, rightIdentity.first, rightParameter)
            }
    }

    private fun valueTypesMatch(
        leftModule: Int,
        left: ValueType,
        rightModule: Int,
        right: ValueType,
    ): Boolean =
        if (left is ValueType.Ref && right is ValueType.Ref) {
            if (left.nullable != right.nullable) {
                false
            } else {
                val leftIdentity = resolveType(leftModule, left.type) ?: return false
                val rightIdentity = resolveType(rightModule, right.type) ?: return false
                val leftType = artifact.modules[leftIdentity.first].types[leftIdentity.second]
                val rightType = artifact.modules[rightIdentity.first].types[rightIdentity.second]
                if (leftType is NominalType.Array && rightType is NominalType.Array) {
                    valueTypesMatch(leftIdentity.first, leftType.element, rightIdentity.first, rightType.element)
                } else {
                    leftIdentity == rightIdentity
                }
            }
        } else {
            left !is ValueType.Ref && right !is ValueType.Ref && left == right
        }

    private fun resolveType(
        sourceModule: Int,
        reference: TypeRef,
    ): Pair<Int, Int>? {
        return when (reference) {
            is TypeRef.Local -> {
                reference.id.value
                    .toInt()
                    .takeIf { it in artifact.modules[sourceModule].types.indices }
                    ?.let { sourceModule to it }
            }

            is TypeRef.Imported -> {
                val import = artifact.modules[sourceModule].imports.getOrNull(reference.id.value.toInt()) ?: return null
                if (import.kind != SymbolKind.TYPE) return null
                val targets = moduleHashes.indices.filter { import.targetModuleHash.contentEquals(moduleHashes[it]) }
                val targetModule = targets.singleOrNull() ?: return null
                val expectedName = artifact.modules[sourceModule].strings.getOrNull(import.targetName.value.toInt()) ?: return null
                artifact.modules[targetModule]
                    .exports
                    .singleOrNull { export ->
                        export.kind == SymbolKind.TYPE &&
                            artifact.modules[targetModule].strings.getOrNull(export.name.value.toInt()) == expectedName
                    }?.localSymbol
                    ?.toInt()
                    ?.let { targetModule to it }
            }
        }
    }

    private fun markDebugForReachableInstructions() {
        artifact.modules.forEachIndexed { moduleIndex, module ->
            module.debug.withIndex().forEach { (index, debug) ->
                if (debug.function.value.toInt() in reachable[moduleIndex].functions &&
                    debug.block.value.toInt() in reachable[moduleIndex].blocks
                ) {
                    markDebug(moduleIndex, index)
                }
            }
        }
    }

    private fun markType(
        module: Int,
        type: TypeRef,
    ) = when (type) {
        is TypeRef.Local -> markType(module, type.id.value.toInt())
        is TypeRef.Imported -> markImport(module, type.id.value.toInt())
    }

    private fun markValueType(
        module: Int,
        type: ValueType,
    ) {
        if (type is ValueType.Ref) markType(module, type.type)
    }

    private fun markFunction(
        module: Int,
        function: FunctionRef,
    ) = when (function) {
        is FunctionRef.Local -> markFunction(module, function.id.value.toInt())
        is FunctionRef.Imported -> markImport(module, function.id.value.toInt())
    }

    private fun markField(
        module: Int,
        field: FieldRef,
    ) = when (field) {
        is FieldRef.Local -> markField(module, field.id.value.toInt())
        is FieldRef.Imported -> markImport(module, field.id.value.toInt())
    }

    private fun markString(
        module: Int,
        index: Int,
    ) {
        require(index in artifact.modules[module].strings.indices) { "string $module:$index is absent" }
        reachable[module].strings += index
    }

    private fun markLiteral(
        module: Int,
        index: Int,
    ) {
        require(index in artifact.modules[module].utf16Literals.indices) { "literal $module:$index is absent" }
        reachable[module].literals += index
    }

    private fun markType(
        module: Int,
        index: Int,
    ) = mark(Node.Type(module, index), reachable[module].types)

    private fun markConstant(
        module: Int,
        index: Int,
    ) = mark(Node.Constant(module, index), reachable[module].constants)

    private fun markImport(
        module: Int,
        index: Int,
    ) = mark(Node.Import(module, index), reachable[module].imports)

    private fun markField(
        module: Int,
        index: Int,
    ) = mark(Node.Field(module, index), reachable[module].fields)

    private fun markFunction(
        module: Int,
        index: Int,
    ) = mark(Node.Function(module, index), reachable[module].functions)

    private fun markBlock(
        module: Int,
        index: Int,
    ) = mark(Node.Block(module, index), reachable[module].blocks)

    private fun markException(
        module: Int,
        index: Int,
    ) = mark(Node.Exception(module, index), reachable[module].exceptions)

    private fun markDebug(
        module: Int,
        index: Int,
    ) = mark(Node.Debug(module, index), reachable[module].debug)

    private fun mark(
        node: Node,
        set: MutableSet<Int>,
    ) {
        require(node.index >= 0) { "negative artifact identity" }
        if (set.add(node.index)) queue += node
    }

    private fun markRange(
        start: UInt,
        count: UInt,
        mark: (Int) -> Unit,
    ) {
        val end = start.toLong() + count.toLong()
        require(end <= Int.MAX_VALUE) { "artifact range is too large" }
        for (index in start.toInt() until end.toInt()) mark(index)
    }

    private fun <T> List<T>.at(
        node: Node,
        kind: String,
    ): T = getOrNull(node.index) ?: error("$kind ${node.module}:${node.index} is absent")
}
