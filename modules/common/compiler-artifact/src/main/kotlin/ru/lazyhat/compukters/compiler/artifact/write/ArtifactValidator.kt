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

import ru.lazyhat.compukters.compiler.artifact.analysis.ReferenceLiveness
import ru.lazyhat.compukters.compiler.artifact.analysis.mayThrow
import ru.lazyhat.compukters.compiler.artifact.analysis.readRegisters
import ru.lazyhat.compukters.compiler.artifact.analysis.successors
import ru.lazyhat.compukters.compiler.artifact.analysis.writtenRegisters
import ru.lazyhat.compukters.compiler.artifact.model.AbiVersion
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryArguments
import ru.lazyhat.compukters.compiler.artifact.model.ExceptionEntry
import ru.lazyhat.compukters.compiler.artifact.model.ExportVisibility
import ru.lazyhat.compukters.compiler.artifact.model.FieldRef
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType

internal fun validateArtifact(
    artifact: Artifact,
    limits: ArtifactWriteLimits,
): List<ArtifactWriteError> {
    val errors = mutableListOf<ArtifactWriteError>()

    fun add(
        code: ArtifactWriteErrorCode,
        detail: String,
        location: ArtifactWriteLocation? = null,
    ) {
        if (errors.size < limits.diagnostics) errors += ArtifactWriteError(code, location, detail)
    }

    data class TypeIdentity(
        val module: Int,
        val type: Int,
    )

    data class FunctionIdentity(
        val module: Int,
        val function: Int,
    )

    data class FieldIdentity(
        val module: Int,
        val field: Int,
    )

    fun resolveType(
        sourceModule: Int,
        reference: TypeRef,
    ): TypeIdentity? =
        when (reference) {
            is TypeRef.Local -> {
                reference.id.value.toInt().takeIf { it in artifact.modules[sourceModule].types.indices }?.let {
                    TypeIdentity(sourceModule, it)
                }
            }

            is TypeRef.Imported -> {
                val import = artifact.modules[sourceModule].imports.getOrNull(reference.id.value.toInt())
                if (import?.kind != SymbolKind.TYPE) {
                    null
                } else {
                    val targetModule = artifact.modules.getOrNull(import.targetModule.value.toInt())
                    val targetModuleIndex = import.targetModule.value.toInt()
                    targetModule
                        ?.exports
                        ?.singleOrNull {
                            it.kind == SymbolKind.TYPE &&
                                artifact.modules[sourceModule].strings.getOrNull(import.targetName.value.toInt()) ==
                                targetModule.strings.getOrNull(it.name.value.toInt())
                        }?.localSymbol
                        ?.toInt()
                        ?.takeIf { it in targetModule.types.indices }
                        ?.let { TypeIdentity(targetModuleIndex, it) }
                }
            }
        }

    fun nominalAssignable(
        source: TypeIdentity,
        destination: TypeIdentity,
    ): Boolean {
        val pending = ArrayDeque<TypeIdentity>()
        val visited = mutableSetOf<TypeIdentity>()
        pending += source
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (current == destination) return true
            if (!visited.add(current)) continue
            when (val nominal = artifact.modules[current.module].types[current.type]) {
                is NominalType.Class -> {
                    nominal.superType?.let { resolveType(current.module, it) }?.let(pending::add)
                    nominal.interfaces.mapNotNull { resolveType(current.module, it) }.forEach(pending::add)
                }

                is NominalType.Interface -> {
                    nominal.superType?.let { resolveType(current.module, it) }?.let(pending::add)
                    nominal.interfaces.mapNotNull { resolveType(current.module, it) }.forEach(pending::add)
                }

                is NominalType.Array,
                is NominalType.Function,
                -> {}
            }
        }
        return false
    }

    fun valueAssignable(
        sourceModule: Int,
        source: ValueType,
        destinationModule: Int,
        destination: ValueType,
    ): Boolean =
        when {
            source is ValueType.Ref && destination is ValueType.Ref -> {
                (!source.nullable || destination.nullable) &&
                    resolveType(sourceModule, source.type)
                        ?.let { sourceIdentity ->
                            resolveType(destinationModule, destination.type)
                                ?.let { destinationIdentity ->
                                    val sourceType = artifact.modules[sourceIdentity.module].types[sourceIdentity.type]
                                    val destinationType = artifact.modules[destinationIdentity.module].types[destinationIdentity.type]
                                    if (sourceType is NominalType.Array && destinationType is NominalType.Array) {
                                        valueAssignable(
                                            sourceIdentity.module,
                                            sourceType.element,
                                            destinationIdentity.module,
                                            destinationType.element,
                                        ) &&
                                            valueAssignable(
                                                destinationIdentity.module,
                                                destinationType.element,
                                                sourceIdentity.module,
                                                sourceType.element,
                                            )
                                    } else {
                                        nominalAssignable(sourceIdentity, destinationIdentity)
                                    }
                                }
                        } == true
            }

            source is ValueType.Ref || destination is ValueType.Ref -> {
                false
            }

            else -> {
                source == destination
            }
        }

    fun valueTypesMatch(
        leftModule: Int,
        left: ValueType,
        rightModule: Int,
        right: ValueType,
    ): Boolean =
        when {
            left is ValueType.Ref && right is ValueType.Ref -> {
                if (left.nullable != right.nullable) {
                    false
                } else {
                    val leftIdentity = resolveType(leftModule, left.type) ?: return false
                    val rightIdentity = resolveType(rightModule, right.type) ?: return false
                    val leftType = artifact.modules[leftIdentity.module].types[leftIdentity.type]
                    val rightType = artifact.modules[rightIdentity.module].types[rightIdentity.type]
                    if (leftType is NominalType.Array && rightType is NominalType.Array) {
                        valueTypesMatch(leftIdentity.module, leftType.element, rightIdentity.module, rightType.element)
                    } else {
                        leftIdentity == rightIdentity
                    }
                }
            }

            left is ValueType.Ref || right is ValueType.Ref -> {
                false
            }

            else -> {
                left == right
            }
        }

    fun signaturesMatch(
        leftModule: Int,
        left: TypeRef,
        rightModule: Int,
        right: TypeRef,
    ): Boolean {
        val leftIdentity = resolveType(leftModule, left) ?: return false
        val rightIdentity = resolveType(rightModule, right) ?: return false
        val leftType = artifact.modules[leftIdentity.module].types[leftIdentity.type]
        val rightType = artifact.modules[rightIdentity.module].types[rightIdentity.type]
        return if (leftType is NominalType.Function && rightType is NominalType.Function) {
            leftType.suspending == rightType.suspending &&
                valueTypesMatch(leftIdentity.module, leftType.result, rightIdentity.module, rightType.result) &&
                leftType.parameters.size == rightType.parameters.size &&
                leftType.parameters.zip(rightType.parameters).all { (leftParameter, rightParameter) ->
                    valueTypesMatch(leftIdentity.module, leftParameter, rightIdentity.module, rightParameter)
                }
        } else {
            leftIdentity == rightIdentity
        }
    }

    fun resolveFunction(
        sourceModule: Int,
        reference: FunctionRef,
    ): FunctionIdentity? =
        when (reference) {
            is FunctionRef.Local -> {
                reference.id.value.toInt().takeIf { it in artifact.modules[sourceModule].functions.indices }?.let {
                    FunctionIdentity(sourceModule, it)
                }
            }

            is FunctionRef.Imported -> {
                val import = artifact.modules[sourceModule].imports.getOrNull(reference.id.value.toInt())
                if (import?.kind != SymbolKind.FUNCTION) {
                    null
                } else {
                    val targetModule = artifact.modules.getOrNull(import.targetModule.value.toInt())
                    val targetModuleIndex = import.targetModule.value.toInt()
                    targetModule
                        ?.exports
                        ?.singleOrNull {
                            it.kind == SymbolKind.FUNCTION &&
                                artifact.modules[sourceModule].strings.getOrNull(import.targetName.value.toInt()) ==
                                targetModule.strings.getOrNull(it.name.value.toInt()) &&
                                signaturesMatch(sourceModule, import.expectedSignature, targetModuleIndex, it.signature)
                        }?.localSymbol
                        ?.toInt()
                        ?.takeIf { it in targetModule.functions.indices }
                        ?.let { FunctionIdentity(targetModuleIndex, it) }
                }
            }
        }

    fun resolveField(
        sourceModule: Int,
        reference: FieldRef,
    ): FieldIdentity? =
        when (reference) {
            is FieldRef.Local -> {
                reference.id.value.toInt().takeIf { it in artifact.modules[sourceModule].fields.indices }?.let {
                    FieldIdentity(sourceModule, it)
                }
            }

            is FieldRef.Imported -> {
                val import = artifact.modules[sourceModule].imports.getOrNull(reference.id.value.toInt())
                if (import?.kind != SymbolKind.FIELD) {
                    null
                } else {
                    val targetModule = artifact.modules.getOrNull(import.targetModule.value.toInt())
                    val targetModuleIndex = import.targetModule.value.toInt()
                    targetModule
                        ?.exports
                        ?.singleOrNull {
                            it.kind == SymbolKind.FIELD &&
                                artifact.modules[sourceModule].strings.getOrNull(import.targetName.value.toInt()) ==
                                targetModule.strings.getOrNull(it.name.value.toInt()) &&
                                signaturesMatch(sourceModule, import.expectedSignature, targetModuleIndex, it.signature)
                        }?.localSymbol
                        ?.toInt()
                        ?.takeIf { it in targetModule.fields.indices }
                        ?.let { FieldIdentity(targetModuleIndex, it) }
                }
            }
        }

    fun isExactStringArray(
        sourceModule: Int,
        value: ValueType,
    ): Boolean {
        val arrayReference = value as? ValueType.Ref ?: return false
        if (arrayReference.nullable) return false
        val arrayIdentity = resolveType(sourceModule, arrayReference.type) ?: return false
        val arrayType = artifact.modules[arrayIdentity.module].types[arrayIdentity.type] as? NominalType.Array ?: return false
        val elementReference = arrayType.element as? ValueType.Ref ?: return false
        if (elementReference.nullable) return false
        val elementIdentity = resolveType(arrayIdentity.module, elementReference.type) ?: return false
        val elementType = artifact.modules[elementIdentity.module].types[elementIdentity.type]
        if (elementType !is NominalType.Class) return false
        return artifact.modules[elementIdentity.module]
            .strings
            .getOrNull(elementType.name.value.toInt())
            ?.toString() == "kotlin.String"
    }

    fun stringIdentity(): TypeIdentity? {
        val matches =
            artifact.modules.flatMapIndexed { moduleIndex, module ->
                if (module.kind != ModuleKind.LIBRARY) {
                    emptyList()
                } else {
                    module.exports.mapNotNull { export ->
                        val isString =
                            export.kind == SymbolKind.TYPE &&
                                export.visibility == ExportVisibility.PUBLIC_LIBRARY &&
                                module.strings.getOrNull(export.name.value.toInt())?.toString() == "kotlin.String"
                        export.localSymbol
                            .toInt()
                            .takeIf { isString && it in module.types.indices }
                            ?.takeIf {
                                val type = module.types[it]
                                type is NominalType.Class && !type.abstract && type.final && type.fieldCount == 0u
                            }?.let { TypeIdentity(moduleIndex, it) }
                    }
                }
            }
        return matches.singleOrNull()
    }

    if (artifact.entry.module.value
            .toLong() >= artifact.modules.size
    ) {
        add(ArtifactWriteErrorCode.BAD_REFERENCE, "entry module is outside the module table")
    } else if (
        artifact.entry.function.value
            .toLong() >=
        artifact.modules[
            artifact.entry.module.value
                .toInt(),
        ].functions.size
    ) {
        add(ArtifactWriteErrorCode.BAD_REFERENCE, "entry function is outside the function table")
    } else {
        val entryModuleIndex =
            artifact.entry.module.value
                .toInt()
        val entryFunction =
            artifact.modules[entryModuleIndex].functions[
                artifact.entry.function.value
                    .toInt(),
            ]
        val signatureIdentity = resolveType(entryModuleIndex, entryFunction.signature)
        val signature = signatureIdentity?.let { artifact.modules[it.module].types[it.type] as? NominalType.Function }
        val matches =
            when (artifact.entry.arguments) {
                EntryArguments.NONE -> {
                    signature?.parameters?.isEmpty() == true && entryFunction.parameterCount == 0u
                }

                EntryArguments.STRING_ARRAY -> {
                    signature?.parameters?.singleOrNull()?.let {
                        isExactStringArray(requireNotNull(signatureIdentity).module, it)
                    } == true && entryFunction.parameterCount == 1u
                }
            }
        if (!matches) {
            add(
                ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                "entry argument contract disagrees with the entry function signature",
            )
        }
    }
    if (artifact.modules.size > limits.modules) {
        add(ArtifactWriteErrorCode.LIMIT_EXCEEDED, "module count exceeds ${limits.modules}")
    }
    if (artifact.capabilities.size > limits.capabilities) {
        add(ArtifactWriteErrorCode.LIMIT_EXCEEDED, "capability count exceeds ${limits.capabilities}")
    }
    if (artifact.modules.count { it.kind == ModuleKind.APPLICATION } != 1) {
        add(ArtifactWriteErrorCode.INCONSISTENT_RANGE, "artifact must contain exactly one application module")
    }
    if (artifact.manifest.compilerAbi.size != 32 || artifact.manifest.platformAbi.size != 32) {
        add(ArtifactWriteErrorCode.INVALID_RANGE, "manifest ABI identities must contain 32 bytes")
    }
    if (artifact.manifest.minimumSliceCost < artifact.manifest.maximumBlockCost) {
        add(ArtifactWriteErrorCode.INVALID_RANGE, "minimum slice cost is below maximum block cost")
    }
    val expectedFeatures = mutableSetOf<SemanticFeature>()
    artifact.modules.forEach { module ->
        if (module.exceptions.isNotEmpty() || module.blocks.any { block -> block.instructions.any { it is Instruction.Throw } }) {
            expectedFeatures += SemanticFeature.EXCEPTIONS
        }
        if (
            module.functions.any { FunctionFlag.SUSPENDING in it.flags } ||
            module.blocks.any { block ->
                block.instructions.any {
                    it is Instruction.CallSuspend || it is Instruction.TaskSpawn || it is Instruction.TaskJoin ||
                        it is Instruction.ChannelSend || it is Instruction.ChannelReceive
                }
            }
        ) {
            expectedFeatures += SemanticFeature.COROUTINES
        }
        if (module.imports.isNotEmpty()) expectedFeatures += SemanticFeature.MODULE_IMPORTS
    }
    if (artifact.capabilities.isNotEmpty() ||
        artifact.modules.any { module ->
            module.blocks.any { block ->
                block.instructions.any { it is Instruction.CapabilityCallSync || it is Instruction.CapabilityCallAsync }
            }
        }
    ) {
        expectedFeatures += SemanticFeature.CAPABILITIES
    }
    val usesChannels =
        artifact.modules.any { module ->
            module.blocks.any { block ->
                block.instructions.any {
                    it is Instruction.ChannelCreate ||
                        it is Instruction.ChannelSend ||
                        it is Instruction.ChannelReceive
                }
            }
        }
    if (usesChannels) expectedFeatures += SemanticFeature.CHANNELS
    if (artifact.semanticFeatures != expectedFeatures) {
        add(
            ArtifactWriteErrorCode.INCOMPATIBLE_FEATURE_SET,
            "semantic feature bits do not exactly match artifact use",
        )
    }
    val usesTasks =
        artifact.modules.any { module ->
            module.blocks.any { block ->
                block.instructions
                    .any { it is Instruction.TaskSpawn || it is Instruction.TaskJoin }
            }
        }
    if (usesTasks && artifact.minimumRuntimeAbi < AbiVersion(1u, 1u)) {
        add(
            ArtifactWriteErrorCode.INVALID_RANGE,
            "task instructions require minimum runtime ABI 1.1",
        )
    }
    if (usesChannels && artifact.minimumRuntimeAbi < AbiVersion(1u, 2u)) {
        add(
            ArtifactWriteErrorCode.INVALID_RANGE,
            "channel instructions require minimum runtime ABI 1.2",
        )
    }
    if (usesChannels && (artifact.manifest.maximumChannels == 0u || artifact.manifest.maximumChannelValues == 0u)) {
        add(
            ArtifactWriteErrorCode.INVALID_RANGE,
            "channel instructions require non-zero channel manifest limits",
        )
    }
    if (!usesChannels && (artifact.manifest.maximumChannels != 0u || artifact.manifest.maximumChannelValues != 0u)) {
        add(
            ArtifactWriteErrorCode.INVALID_RANGE,
            "channel manifest limits require channel instructions",
        )
    }

    val semanticHashes =
        artifact.modules.map { module ->
            runCatching { encodeModuleSections(module, limits).semanticHash }.getOrNull()
        }

    artifact.modules.forEachIndexed { moduleIndex, module ->
        val moduleLocation = moduleIndex.toUInt()
        if (module.blocks.size > limits.blocks) {
            add(
                ArtifactWriteErrorCode.LIMIT_EXCEEDED,
                "blocks exceed ${limits.blocks}",
                ArtifactWriteLocation(module = moduleLocation, table = "BLOCKS"),
            )
        }
        if (module.functions.size > limits.functions) {
            add(
                ArtifactWriteErrorCode.LIMIT_EXCEEDED,
                "functions exceed ${limits.functions}",
                ArtifactWriteLocation(module = moduleLocation, table = "FUNCTIONS"),
            )
        }
        if (module.strings.zipWithNext().any { (left, right) -> left >= right }) {
            add(
                ArtifactWriteErrorCode.NON_CANONICAL_ORDER,
                "metadata strings are not strictly canonical",
                ArtifactWriteLocation(module = moduleLocation, table = "STRINGS"),
            )
        }
        if (module.utf16Literals.zipWithNext().any { (left, right) -> left >= right }) {
            add(
                ArtifactWriteErrorCode.NON_CANONICAL_ORDER,
                "UTF-16 literals are not strictly canonical",
                ArtifactWriteLocation(module = moduleLocation, table = "UTF16_LITERALS"),
            )
        }
        module.imports.forEachIndexed { importIndex, import ->
            val actual = semanticHashes.getOrNull(import.targetModule.value.toInt())
            if (actual != null && !import.targetModuleHash.contentEquals(actual)) {
                add(
                    ArtifactWriteErrorCode.BAD_REFERENCE,
                    "import target semantic hash does not match",
                    ArtifactWriteLocation(moduleLocation, "IMPORTS", importIndex.toUInt()),
                )
            }
        }
        module.types.forEachIndexed { typeIndex, nominal ->
            val initializer = (nominal as? NominalType.Class)?.initializer ?: return@forEachIndexed
            val location = ArtifactWriteLocation(moduleLocation, "TYPES", typeIndex.toUInt())
            val function = module.functions.getOrNull(initializer.value.toInt())
            if (function == null) {
                add(ArtifactWriteErrorCode.BAD_REFERENCE, "class initializer is outside the function table", location)
                return@forEachIndexed
            }
            if (function.owner != TypeRef.Local(TypeId.of(typeIndex.toUInt()))) {
                add(ArtifactWriteErrorCode.BAD_REFERENCE, "class initializer owner does not match its class", location)
            }
            val signatureIdentity = resolveType(moduleIndex, function.signature)
            val signature = signatureIdentity?.let { artifact.modules[it.module].types[it.type] as? NominalType.Function }
            if (
                FunctionFlag.STATIC !in function.flags ||
                FunctionFlag.SUSPENDING in function.flags ||
                FunctionFlag.ABSTRACT in function.flags ||
                function.parameterCount != 0u ||
                signature?.let { !it.suspending && it.parameters.isEmpty() && it.result == ValueType.Unit } != true
            ) {
                add(
                    ArtifactWriteErrorCode.INVALID_RANGE,
                    "class initializer signature must be static non-suspending () -> Unit",
                    location,
                )
            }
            val firstBlock = function.firstBlock.value.toLong()
            val blockEnd = firstBlock + function.blockCount.toLong()
            if (
                firstBlock <= module.blocks.size.toLong() &&
                blockEnd <= module.blocks.size.toLong() &&
                module.blocks.subList(firstBlock.toInt(), blockEnd.toInt()).any { block ->
                    block.instructions.any { it is Instruction.CallSuspend || it is Instruction.CapabilityCallAsync }
                }
            ) {
                add(ArtifactWriteErrorCode.INVALID_RANGE, "class initializer cannot suspend", location)
            }
        }
        module.functions.forEachIndexed { functionIndex, function ->
            if (function.values.size > limits.registersPerFunction || function.parameterCount.toLong() > function.values.size) {
                add(
                    ArtifactWriteErrorCode.INVALID_RANGE,
                    "function register or parameter count is invalid",
                    ArtifactWriteLocation(moduleLocation, "FUNCTIONS", functionIndex.toUInt()),
                )
            }
            if (function.values.any { it.physicalShape.components.size > limits.physicalComponentsPerValue }) {
                add(
                    ArtifactWriteErrorCode.LIMIT_EXCEEDED,
                    "function value physical component count exceeds ${limits.physicalComponentsPerValue}",
                    ArtifactWriteLocation(moduleLocation, "FUNCTIONS", functionIndex.toUInt()),
                )
            }
            if (function.safepointRoots.size > limits.safepointsPerFunction) {
                add(
                    ArtifactWriteErrorCode.LIMIT_EXCEEDED,
                    "function safepoint count exceeds ${limits.safepointsPerFunction}",
                    ArtifactWriteLocation(moduleLocation, "SAFEPOINT_ROOTS", functionIndex.toUInt()),
                )
            }
            if (function.safepointRoots.any { it.references.size > limits.rootsPerSafepoint }) {
                add(
                    ArtifactWriteErrorCode.LIMIT_EXCEEDED,
                    "safepoint root count exceeds ${limits.rootsPerSafepoint}",
                    ArtifactWriteLocation(moduleLocation, "SAFEPOINT_ROOTS", functionIndex.toUInt()),
                )
            }
            val blockEnd = function.firstBlock.value.toLong() + function.blockCount.toLong()
            if (blockEnd > module.blocks.size) {
                add(
                    ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                    "function block range is outside BLOCKS",
                    ArtifactWriteLocation(moduleLocation, "FUNCTIONS", functionIndex.toUInt()),
                )
            }
            val isAbstract = FunctionFlag.ABSTRACT in function.flags
            val isEntry =
                artifact.entry.module.value
                    .toInt() == moduleIndex && artifact.entry.function.value
                    .toInt() == functionIndex
            if (isAbstract && (function.blockCount != 0u || isEntry)) {
                add(
                    ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                    "abstract function has blocks or is the entry function",
                    ArtifactWriteLocation(moduleLocation, "FUNCTIONS", functionIndex.toUInt()),
                )
            } else if (!isAbstract && function.blockCount == 0u) {
                add(
                    ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                    "non-abstract function has no blocks",
                    ArtifactWriteLocation(moduleLocation, "FUNCTIONS", functionIndex.toUInt()),
                )
            }
            val start = function.firstBlock.value.toInt()
            val end = blockEnd.takeIf { it <= module.blocks.size.toLong() }?.toInt()
            if (end != null && start in 0..end) {
                for (blockIndex in start until end) {
                    if (module.blocks[blockIndex]
                            .owner.value
                            .toInt() != functionIndex
                    ) {
                        add(
                            ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                            "function blocks are not contiguous or have the wrong owner",
                            ArtifactWriteLocation(moduleLocation, "FUNCTIONS", functionIndex.toUInt()),
                        )
                        break
                    }
                }
            }
        }
        module.blocks.forEachIndexed { blockIndex, block ->
            var fixedCost = 0uL
            block.instructions.forEach { instruction ->
                fixedCost += instructionFixedCost(instruction).toULong()
            }
            if (fixedCost > UInt.MAX_VALUE.toULong()) {
                add(
                    ArtifactWriteErrorCode.OVERFLOW,
                    "block fixed cost overflows u32",
                    ArtifactWriteLocation(moduleLocation, "BLOCKS", blockIndex.toUInt()),
                )
            } else if (fixedCost > artifact.manifest.maximumBlockCost.toULong()) {
                add(
                    ArtifactWriteErrorCode.INVALID_RANGE,
                    "block fixed cost exceeds manifest maximumBlockCost",
                    ArtifactWriteLocation(moduleLocation, "BLOCKS", blockIndex.toUInt()),
                )
            }
            if (block.owner.value.toLong() >= module.functions.size) {
                add(
                    ArtifactWriteErrorCode.BAD_REFERENCE,
                    "block owner is outside FUNCTIONS",
                    ArtifactWriteLocation(moduleLocation, "BLOCKS", blockIndex.toUInt()),
                )
            }
            if (block.instructions.lastOrNull()?.isTerminator() != true) {
                add(
                    ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                    "block must end in exactly one terminator",
                    ArtifactWriteLocation(moduleLocation, "BLOCKS", blockIndex.toUInt()),
                )
            }
            if (block.instructions.dropLast(1).any(Instruction::isTerminator)) {
                add(
                    ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                    "block contains a terminator before its end",
                    ArtifactWriteLocation(moduleLocation, "BLOCKS", blockIndex.toUInt()),
                )
            }
            val owner = module.functions.getOrNull(block.owner.value.toInt())
            if (owner != null) {
                val ownerStart = owner.firstBlock.value.toLong()
                val ownerEnd = ownerStart + owner.blockCount.toLong()
                if (blockIndex.toLong() !in ownerStart until ownerEnd) {
                    add(
                        ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                        "block index is outside its declared owner function range",
                        ArtifactWriteLocation(moduleLocation, "BLOCKS", blockIndex.toUInt()),
                    )
                }
                val allocations =
                    block.instructions.withIndex().filter { (_, instruction) ->
                        instruction is Instruction.NewObject ||
                            instruction is Instruction.NewArray ||
                            instruction is Instruction.StringConcat ||
                            instruction is Instruction.StringValueOf ||
                            instruction is Instruction.StringSubstring ||
                            instruction is Instruction.StringFromCharArray
                    }
                if (allocations.size > 1 || allocations.singleOrNull()?.index?.let { it != 0 } == true) {
                    add(
                        ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                        "allocation must be the first and only allocating instruction in its block",
                        ArtifactWriteLocation(moduleLocation, "CODE", blockIndex.toUInt()),
                    )
                }
                block.instructions.forEachIndexed { instructionIndex, instruction ->
                    val location =
                        ArtifactWriteLocation(
                            module = moduleLocation,
                            table = "CODE",
                            record = blockIndex.toUInt(),
                            instruction = instructionIndex.toUInt(),
                        )

                    fun register(
                        id: RegisterId,
                        role: String,
                    ): ValueType? =
                        owner.values
                            .getOrNull(id.value.toInt())
                            ?.semanticType
                            .also {
                                if (it ==
                                    null
                                ) {
                                    add(
                                        ArtifactWriteErrorCode.BAD_REFERENCE,
                                        "$role register is outside the owning function table",
                                        location,
                                    )
                                }
                            }

                    fun destination(destination: Destination): ValueType? =
                        when (destination) {
                            is Destination.Register -> register(destination.id, "destination")
                            Destination.Unit -> null
                        }

                    fun successor(
                        blockId: BlockId,
                        role: String,
                    ) {
                        val first = owner.firstBlock.value.toLong()
                        val end = first + owner.blockCount.toLong()
                        if (blockId.value.toLong() !in first until end) {
                            add(ArtifactWriteErrorCode.BAD_REFERENCE, "$role block is outside the owning function range", location)
                        }
                    }

                    fun call(
                        targetReference: FunctionRef,
                        callDestination: Destination,
                        arguments: List<RegisterId>,
                        suspending: Boolean,
                    ) {
                        val argumentTypes = arguments.map { register(it, "argument") }
                        val identity = resolveFunction(moduleIndex, targetReference)
                        if (identity == null) {
                            val kind = if (targetReference is FunctionRef.Local) "local" else "imported"
                            add(ArtifactWriteErrorCode.BAD_REFERENCE, "$kind function reference does not resolve", location)
                            destination(callDestination)
                            return
                        }
                        val targetModule = artifact.modules[identity.module]
                        val target = targetModule.functions[identity.function]
                        if (!suspending && FunctionFlag.ABSTRACT in target.flags) {
                            add(ArtifactWriteErrorCode.INVALID_RANGE, "direct call targets an abstract function", location)
                        }
                        if (suspending != (FunctionFlag.SUSPENDING in target.flags)) {
                            val detail =
                                if (suspending) {
                                    "suspending call targets a non-suspending function"
                                } else {
                                    "direct call targets a suspending function"
                                }
                            add(
                                ArtifactWriteErrorCode.INVALID_RANGE,
                                detail,
                                location,
                            )
                        }
                        val signatureIdentity = resolveType(identity.module, target.signature)
                        val signature = signatureIdentity?.let { artifact.modules[it.module].types[it.type] as? NominalType.Function }
                        if (signature == null) {
                            add(ArtifactWriteErrorCode.BAD_REFERENCE, "call target signature is not a function type", location)
                            destination(callDestination)
                            return
                        }
                        val metadataIsConsistent =
                            target.parameterCount.toLong() == signature.parameters.size.toLong() &&
                                target.values.size >= signature.parameters.size &&
                                (FunctionFlag.SUSPENDING in target.flags) == signature.suspending &&
                                target.values
                                    .take(signature.parameters.size)
                                    .map(FunctionValue::semanticType)
                                    .zip(signature.parameters)
                                    .all { (register, parameter) ->
                                        valueTypesMatch(identity.module, register, requireNotNull(signatureIdentity).module, parameter)
                                    }
                        if (!metadataIsConsistent) {
                            add(
                                ArtifactWriteErrorCode.INVALID_RANGE,
                                "target function metadata disagrees with its signature",
                                location,
                            )
                        }
                        if (arguments.size != signature.parameters.size) {
                            add(ArtifactWriteErrorCode.INVALID_RANGE, "call arity disagrees with its signature", location)
                        } else {
                            argumentTypes.zip(signature.parameters).forEach { (actual, expected) ->
                                if (actual != null &&
                                    !valueAssignable(moduleIndex, actual, requireNotNull(signatureIdentity).module, expected)
                                ) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "call argument type disagrees with its signature", location)
                                }
                            }
                        }
                        val actualDestination = destination(callDestination)
                        val destinationIsValid =
                            when (signature.result) {
                                ValueType.Unit -> {
                                    callDestination == Destination.Unit
                                }

                                else -> {
                                    actualDestination != null &&
                                        valueAssignable(
                                            requireNotNull(signatureIdentity).module,
                                            signature.result,
                                            moduleIndex,
                                            actualDestination,
                                        )
                                }
                            }
                        if (!destinationIsValid) {
                            add(ArtifactWriteErrorCode.INVALID_RANGE, "call result destination disagrees with its signature", location)
                        }
                    }

                    fun stringRegister(actual: ValueType?) {
                        val expected = stringIdentity()
                        val actualIdentity =
                            (actual as? ValueType.Ref)?.takeIf { !it.nullable }?.let {
                                resolveType(moduleIndex, it.type)
                            }
                        if (actual != null && (expected == null || actualIdentity != expected)) {
                            add(
                                ArtifactWriteErrorCode.INVALID_RANGE,
                                "string register is not the non-null kotlin.String type",
                                location,
                            )
                        }
                    }

                    fun arrayElement(
                        actual: ValueType?,
                        role: String,
                    ): ValueType? {
                        val identity =
                            (actual as? ValueType.Ref)?.takeIf { !it.nullable }?.let {
                                resolveType(moduleIndex, it.type)
                            }
                        val array = identity?.let { artifact.modules[it.module].types[it.type] as? NominalType.Array }
                        if (actual != null && array == null) {
                            add(
                                ArtifactWriteErrorCode.INVALID_RANGE,
                                "$role register is not a non-null array type",
                                location,
                            )
                        }
                        return array?.element
                    }

                    fun field(reference: FieldRef): Pair<FieldIdentity, ru.lazyhat.compukters.compiler.artifact.model.Field>? {
                        val identity = resolveField(moduleIndex, reference)
                        if (identity == null) {
                            add(ArtifactWriteErrorCode.BAD_REFERENCE, "field reference does not resolve", location)
                            return null
                        }
                        return identity to artifact.modules[identity.module].fields[identity.field]
                    }

                    fun fieldReceiver(
                        registerId: RegisterId,
                        identity: FieldIdentity,
                        declaration: ru.lazyhat.compukters.compiler.artifact.model.Field,
                    ) {
                        val actual = register(registerId, "receiver")
                        val expected = ValueType.Ref(nullable = false, type = declaration.owner)
                        if (actual != null && !valueAssignable(moduleIndex, actual, identity.module, expected)) {
                            add(ArtifactWriteErrorCode.INVALID_RANGE, "field receiver has an incompatible type", location)
                        }
                    }

                    if (
                        instruction.isKotlinSuspendingTerminator() &&
                        FunctionFlag.SUSPENDING !in owner.flags
                    ) {
                        add(
                            ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                            "suspending terminator appears in a non-suspending function",
                            location,
                        )
                    }

                    when (instruction) {
                        Instruction.Unreachable -> {}

                        is Instruction.Move -> {
                            val destinationType = register(instruction.destination, "destination")
                            val sourceType = register(instruction.source, "source")
                            if (destinationType != null && sourceType != null &&
                                !valueTypesMatch(moduleIndex, destinationType, moduleIndex, sourceType)
                            ) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "move source and destination types differ", location)
                            }
                        }

                        is Instruction.Convert -> {
                            register(instruction.destination, "destination")
                            register(instruction.source, "source")
                        }

                        is Instruction.AddI32 -> {
                            listOf(
                                register(instruction.destination, "destination"),
                                register(instruction.left, "source"),
                                register(instruction.right, "source"),
                            ).filterNotNull().forEach { actual ->
                                if (actual != ValueType.I32) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "I32 add register is not I32", location)
                                }
                            }
                        }

                        is Instruction.SubtractI32 -> {
                            listOf(
                                register(instruction.destination, "destination"),
                                register(instruction.left, "source"),
                                register(instruction.right, "source"),
                            ).filterNotNull().forEach { actual ->
                                if (actual != ValueType.I32) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "I32 subtract register is not I32", location)
                                }
                            }
                        }

                        is Instruction.MultiplyI32,
                        is Instruction.DivideI32,
                        is Instruction.RemainderI32,
                        is Instruction.BitAndI32,
                        is Instruction.BitOrI32,
                        is Instruction.BitXorI32,
                        is Instruction.ShiftLeftI32,
                        is Instruction.ShiftUnsignedI32,
                        -> {
                            val name =
                                when (instruction) {
                                    is Instruction.MultiplyI32 -> "multiply"
                                    is Instruction.DivideI32 -> "divide"
                                    is Instruction.RemainderI32 -> "remainder"
                                    is Instruction.BitAndI32 -> "bit-and"
                                    is Instruction.BitOrI32 -> "bit-or"
                                    is Instruction.BitXorI32 -> "bit-xor"
                                    is Instruction.ShiftLeftI32 -> "shift-left"
                                    is Instruction.ShiftUnsignedI32 -> "shift-unsigned"
                                }
                            val registers =
                                when (instruction) {
                                    is Instruction.MultiplyI32 -> {
                                        listOf(instruction.destination, instruction.left, instruction.right)
                                    }

                                    is Instruction.DivideI32 -> {
                                        listOf(instruction.destination, instruction.left, instruction.right)
                                    }

                                    is Instruction.RemainderI32 -> {
                                        listOf(instruction.destination, instruction.left, instruction.right)
                                    }

                                    is Instruction.BitAndI32 -> {
                                        listOf(instruction.destination, instruction.left, instruction.right)
                                    }

                                    is Instruction.BitOrI32 -> {
                                        listOf(instruction.destination, instruction.left, instruction.right)
                                    }

                                    is Instruction.BitXorI32 -> {
                                        listOf(instruction.destination, instruction.left, instruction.right)
                                    }

                                    is Instruction.ShiftLeftI32 -> {
                                        listOf(instruction.destination, instruction.left, instruction.right)
                                    }

                                    is Instruction.ShiftUnsignedI32 -> {
                                        listOf(instruction.destination, instruction.left, instruction.right)
                                    }
                                }
                            registers
                                .mapIndexed { index, id ->
                                    register(id, if (index == 0) "destination" else "source")
                                }.filterNotNull()
                                .forEach { actual ->
                                    if (actual != ValueType.I32) {
                                        add(ArtifactWriteErrorCode.INVALID_RANGE, "I32 $name register is not I32", location)
                                    }
                                }
                        }

                        is Instruction.Equal,
                        is Instruction.Less,
                        is Instruction.LessOrEqual,
                        is Instruction.Greater,
                        is Instruction.GreaterOrEqual,
                        -> {
                            val expectedSourceType =
                                when (instruction) {
                                    is Instruction.Equal -> instruction.type.valueType
                                    is Instruction.Less -> instruction.type.valueType
                                    is Instruction.LessOrEqual -> instruction.type.valueType
                                    is Instruction.Greater -> instruction.type.valueType
                                    is Instruction.GreaterOrEqual -> instruction.type.valueType
                                }
                            val destinationRegister =
                                when (instruction) {
                                    is Instruction.Equal -> instruction.destination
                                    is Instruction.Less -> instruction.destination
                                    is Instruction.LessOrEqual -> instruction.destination
                                    is Instruction.Greater -> instruction.destination
                                    is Instruction.GreaterOrEqual -> instruction.destination
                                }
                            val leftRegister =
                                when (instruction) {
                                    is Instruction.Equal -> instruction.left
                                    is Instruction.Less -> instruction.left
                                    is Instruction.LessOrEqual -> instruction.left
                                    is Instruction.Greater -> instruction.left
                                    is Instruction.GreaterOrEqual -> instruction.left
                                }
                            val rightRegister =
                                when (instruction) {
                                    is Instruction.Equal -> instruction.right
                                    is Instruction.Less -> instruction.right
                                    is Instruction.LessOrEqual -> instruction.right
                                    is Instruction.Greater -> instruction.right
                                    is Instruction.GreaterOrEqual -> instruction.right
                                }
                            val destinationType = register(destinationRegister, "destination")
                            if (destinationType != null && destinationType != ValueType.Bool) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "comparison destination is not Bool", location)
                            }
                            listOf(leftRegister, rightRegister).forEach { source ->
                                val actual = register(source, "source")
                                if (actual != null && actual != expectedSourceType) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "comparison source disagrees with scalar form", location)
                                }
                            }
                        }

                        is Instruction.RefEqual,
                        is Instruction.RefNotEqual,
                        -> {
                            val destinationRegister =
                                when (instruction) {
                                    is Instruction.RefEqual -> instruction.destination
                                    is Instruction.RefNotEqual -> instruction.destination
                                }
                            val leftRegister =
                                when (instruction) {
                                    is Instruction.RefEqual -> instruction.left
                                    is Instruction.RefNotEqual -> instruction.left
                                }
                            val rightRegister =
                                when (instruction) {
                                    is Instruction.RefEqual -> instruction.right
                                    is Instruction.RefNotEqual -> instruction.right
                                }
                            val destinationType = register(destinationRegister, "destination")
                            if (destinationType != null && destinationType != ValueType.Bool) {
                                add(
                                    ArtifactWriteErrorCode.INVALID_RANGE,
                                    "reference comparison destination is not Bool",
                                    location,
                                )
                            }
                            val leftType = register(leftRegister, "source")
                            val rightType = register(rightRegister, "source")
                            val leftIdentity =
                                (leftType as? ValueType.Ref)?.let { resolveType(moduleIndex, it.type) }
                            val rightIdentity =
                                (rightType as? ValueType.Ref)?.let { resolveType(moduleIndex, it.type) }
                            if ((leftType != null && leftIdentity == null) || (rightType != null && rightIdentity == null)) {
                                add(
                                    ArtifactWriteErrorCode.INVALID_RANGE,
                                    "reference comparison operands must be references",
                                    location,
                                )
                            } else if (leftIdentity != null && rightIdentity != null && leftIdentity != rightIdentity) {
                                add(
                                    ArtifactWriteErrorCode.INVALID_RANGE,
                                    "reference comparison operands have incompatible reference types",
                                    location,
                                )
                            }
                        }

                        is Instruction.IsType -> {
                            val destinationType = register(instruction.destination, "destination")
                            if (destinationType != null && destinationType != ValueType.Bool) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "type test destination is not Bool", location)
                            }
                            val sourceType = register(instruction.value, "source")
                            if (sourceType != null && sourceType !is ValueType.Ref) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "type test source is not a reference", location)
                            }
                            if (resolveType(moduleIndex, instruction.type) == null) {
                                add(ArtifactWriteErrorCode.BAD_REFERENCE, "type test target does not resolve", location)
                            }
                        }

                        is Instruction.CheckedCast -> {
                            val sourceType = register(instruction.value, "source")
                            if (sourceType != null && sourceType !is ValueType.Ref) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "checked cast source is not a reference", location)
                            }
                            val targetIdentity = resolveType(moduleIndex, instruction.type)
                            if (targetIdentity == null) {
                                add(ArtifactWriteErrorCode.BAD_REFERENCE, "checked cast target does not resolve", location)
                            }
                            val destinationType = register(instruction.destination, "destination")
                            val destinationIdentity =
                                (destinationType as? ValueType.Ref)?.let { resolveType(moduleIndex, it.type) }
                            if (destinationType != null && destinationIdentity != targetIdentity) {
                                add(
                                    ArtifactWriteErrorCode.INVALID_RANGE,
                                    "checked cast destination does not have the target reference type",
                                    location,
                                )
                            }
                        }

                        is Instruction.Call -> {
                            call(instruction.function, instruction.destination, instruction.arguments, suspending = false)
                        }

                        is Instruction.CallSuspend -> {
                            call(instruction.function, instruction.destination, instruction.arguments, suspending = true)
                            successor(instruction.resumeBlock, "resume")
                        }

                        is Instruction.TaskSpawn -> {
                            call(instruction.function, Destination.Unit, instruction.arguments, suspending = true)
                            val task = register(instruction.destination, "destination")
                            if (task != null && task != ValueType.I32) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "task handle destination is not I32", location)
                            }
                        }

                        is Instruction.TaskJoin -> {
                            destination(instruction.destination)
                            if (instruction.destination != Destination.Unit) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "task join destination must be Unit", location)
                            }
                            val task = register(instruction.task, "task")
                            if (task != null && task != ValueType.I32) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "task handle is not I32", location)
                            }
                            successor(instruction.resumeBlock, "resume")
                        }

                        is Instruction.ChannelCreate -> {
                            val destination = register(instruction.destination, "destination")
                            if (destination != null && destination != ValueType.I32) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "channel handle destination is not I32", location)
                            }
                            val capacity = register(instruction.capacity, "capacity")
                            if (capacity != null && capacity != ValueType.I32) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "channel capacity is not I32", location)
                            }
                        }

                        is Instruction.ChannelSend -> {
                            val channel = register(instruction.channel, "channel")
                            if (channel != null && channel != ValueType.I32) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "channel handle is not I32", location)
                            }
                            val value = register(instruction.value, "value")
                            if (value != null && value != ValueType.I32) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "channel value is not I32", location)
                            }
                            successor(instruction.resumeBlock, "resume")
                        }

                        is Instruction.ChannelReceive -> {
                            val destination = register(instruction.destination, "destination")
                            if (destination != null && destination != ValueType.I32) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "channel receive destination is not I32", location)
                            }
                            val channel = register(instruction.channel, "channel")
                            if (channel != null && channel != ValueType.I32) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "channel handle is not I32", location)
                            }
                            successor(instruction.resumeBlock, "resume")
                        }

                        is Instruction.StringConcat -> {
                            val expected = stringIdentity()
                            listOf(
                                register(instruction.destination, "destination"),
                                register(instruction.left, "source"),
                                register(instruction.right, "source"),
                            ).forEach { actual ->
                                val actualIdentity =
                                    (actual as? ValueType.Ref)?.takeIf { !it.nullable }?.let {
                                        resolveType(
                                            moduleIndex,
                                            it.type,
                                        )
                                    }
                                if (actual != null && (expected == null || actualIdentity != expected)) {
                                    add(
                                        ArtifactWriteErrorCode.INVALID_RANGE,
                                        "string concat register is not the non-null kotlin.String type",
                                        location,
                                    )
                                }
                            }
                        }

                        is Instruction.StringValueOf -> {
                            val source = register(instruction.source, "source")
                            if (source != null && source != instruction.type.valueType) {
                                add(
                                    ArtifactWriteErrorCode.INVALID_RANGE,
                                    "string conversion source does not match its declared scalar type",
                                    location,
                                )
                            }
                            val expected = stringIdentity()
                            val destination = register(instruction.destination, "destination")
                            val destinationIdentity =
                                (destination as? ValueType.Ref)?.takeIf { !it.nullable }?.let {
                                    resolveType(moduleIndex, it.type)
                                }
                            if (destination != null && (expected == null || destinationIdentity != expected)) {
                                add(
                                    ArtifactWriteErrorCode.INVALID_RANGE,
                                    "string conversion destination is not the non-null kotlin.String type",
                                    location,
                                )
                            }
                        }

                        is Instruction.ArrayLength -> {
                            val result = register(instruction.destination, "destination")
                            if (result != null && result != ValueType.I32) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "array length destination is not I32", location)
                            }
                            arrayElement(register(instruction.array, "source"), "array length source")
                        }

                        is Instruction.FieldGet -> {
                            val resolved = field(instruction.field)
                            if (resolved != null) {
                                val (identity, declaration) = resolved
                                if (declaration.static) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "field get requires an instance field", location)
                                }
                                fieldReceiver(instruction.receiver, identity, declaration)
                                val destinationType = register(instruction.destination, "destination")
                                if (destinationType != null &&
                                    !valueAssignable(identity.module, declaration.type, moduleIndex, destinationType)
                                ) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "field value is not assignable to destination", location)
                                }
                            }
                        }

                        is Instruction.FieldSet -> {
                            val resolved = field(instruction.field)
                            if (resolved != null) {
                                val (identity, declaration) = resolved
                                if (!declaration.mutable || declaration.static) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "field set requires a mutable instance field", location)
                                }
                                fieldReceiver(instruction.receiver, identity, declaration)
                                val sourceType = register(instruction.value, "value")
                                if (sourceType != null &&
                                    !valueAssignable(moduleIndex, sourceType, identity.module, declaration.type)
                                ) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "field store value has the wrong type", location)
                                }
                            }
                        }

                        is Instruction.StaticGet -> {
                            val resolved = field(instruction.field)
                            if (resolved != null) {
                                val (identity, declaration) = resolved
                                if (!declaration.static) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "static get requires a static field", location)
                                }
                                val destinationType = register(instruction.destination, "destination")
                                if (destinationType != null &&
                                    !valueAssignable(identity.module, declaration.type, moduleIndex, destinationType)
                                ) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "static field value has the wrong destination type", location)
                                }
                            }
                        }

                        is Instruction.StaticSet -> {
                            val resolved = field(instruction.field)
                            if (resolved != null) {
                                val (identity, declaration) = resolved
                                if (!declaration.mutable || !declaration.static) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "static set requires a mutable static field", location)
                                }
                                val sourceType = register(instruction.value, "value")
                                if (sourceType != null &&
                                    !valueAssignable(moduleIndex, sourceType, identity.module, declaration.type)
                                ) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "static field store has the wrong type", location)
                                }
                            }
                        }

                        is Instruction.StringLength -> {
                            val result = register(instruction.destination, "destination")
                            if (result != null && result != ValueType.I32) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "string length destination is not I32", location)
                            }
                            stringRegister(register(instruction.string, "source"))
                        }

                        is Instruction.StringGet -> {
                            val result = register(instruction.destination, "destination")
                            val index = register(instruction.index, "index")
                            if (result != null && result != ValueType.Char) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "string get destination is not Char", location)
                            }
                            if (index != null && index != ValueType.I32) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "string index is not I32", location)
                            }
                            stringRegister(register(instruction.string, "source"))
                        }

                        is Instruction.StringEquals -> {
                            val result = register(instruction.destination, "destination")
                            if (result != null && result != ValueType.Bool) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "string equality destination is not Bool", location)
                            }
                            listOf(instruction.left, instruction.right).forEach { source ->
                                stringRegister(register(source, "source"))
                            }
                        }

                        is Instruction.StringSubstring -> {
                            listOf(instruction.start, instruction.end).forEach { indexRegister ->
                                val index = register(indexRegister, "index")
                                if (index != null && index != ValueType.I32) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "substring index is not I32", location)
                                }
                            }
                            listOf(instruction.destination, instruction.string).forEach { registerId ->
                                stringRegister(
                                    register(
                                        registerId,
                                        if (registerId == instruction.destination) "destination" else "source",
                                    ),
                                )
                            }
                        }

                        is Instruction.StringFromCharArray -> {
                            listOf(instruction.start, instruction.end).forEach { indexRegister ->
                                val index = register(indexRegister, "index")
                                if (index != null && index != ValueType.I32) {
                                    add(ArtifactWriteErrorCode.INVALID_RANGE, "char array range index is not I32", location)
                                }
                            }
                            val element =
                                arrayElement(
                                    register(instruction.array, "source"),
                                    "string materialization source",
                                )
                            if (element != null && element != ValueType.Char) {
                                add(
                                    ArtifactWriteErrorCode.INVALID_RANGE,
                                    "string materialization source is not CharArray",
                                    location,
                                )
                            }
                            stringRegister(register(instruction.destination, "destination"))
                        }

                        is Instruction.CapabilityCallSync -> {
                            destination(instruction.destination)
                            instruction.arguments.forEach { register(it, "argument") }
                            val capability = artifact.capabilities.getOrNull(instruction.capability.value.toInt())
                            if (capability == null) {
                                add(ArtifactWriteErrorCode.BAD_REFERENCE, "capability id is outside the capability table", location)
                            } else if (instruction.operation >= capability.operationCount) {
                                add(ArtifactWriteErrorCode.BAD_REFERENCE, "capability operation is outside the descriptor range", location)
                            }
                        }

                        is Instruction.CapabilityCallAsync -> {
                            destination(instruction.destination)
                            instruction.arguments.forEach { register(it, "argument") }
                            val capability = artifact.capabilities.getOrNull(instruction.capability.value.toInt())
                            if (capability == null) {
                                add(ArtifactWriteErrorCode.BAD_REFERENCE, "capability id is outside the capability table", location)
                            } else if (instruction.operation >= capability.operationCount) {
                                add(ArtifactWriteErrorCode.BAD_REFERENCE, "capability operation is outside the descriptor range", location)
                            }
                            successor(instruction.resumeBlock, "resume")
                        }

                        is Instruction.Jump -> {
                            successor(instruction.target, "successor")
                        }

                        is Instruction.Branch -> {
                            val condition = register(instruction.condition, "condition")
                            if (condition != null && condition != ValueType.Bool) {
                                add(ArtifactWriteErrorCode.INVALID_RANGE, "branch condition register is not Bool", location)
                            }
                            successor(instruction.trueTarget, "successor")
                            successor(instruction.falseTarget, "successor")
                        }

                        else -> {}
                    }
                }
            }
        }
        module.functions.forEachIndexed { functionIndex, function ->
            val blockStart = function.firstBlock.value.toInt()
            val blockCountLong = function.blockCount.toLong()
            val blockEnd = blockStart.toLong() + blockCountLong
            val blockRangeValid =
                blockStart.toLong() <= module.blocks.size.toLong() &&
                    blockEnd <= module.blocks.size.toLong()

            val exceptionStart = function.firstException.toLong()
            val exceptionEnd = exceptionStart + function.exceptionCount.toLong()
            val declaredHandlers =
                if (exceptionStart > module.exceptions.size || exceptionEnd > module.exceptions.size) {
                    add(
                        ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                        "function exception range is outside the exception table",
                        ArtifactWriteLocation(moduleLocation, "FUNCTIONS", functionIndex.toUInt()),
                    )
                    emptyList()
                } else {
                    module.exceptions.subList(exceptionStart.toInt(), exceptionEnd.toInt())
                }
            val handlers =
                declaredHandlers.filter { exception ->
                    val protectedStart = exception.firstProtectedBlock.value.toLong()
                    val protectedEnd = protectedStart + exception.protectedBlockCount.toLong()
                    val structurallyValid =
                        exception.owner.value.toInt() == functionIndex &&
                            exception.protectedBlockCount != 0u &&
                            protectedStart >= blockStart.toLong() &&
                            protectedEnd <= blockEnd &&
                            exception.handlerBlock.value.toLong() in blockStart.toLong() until blockEnd &&
                            exception.exceptionRegister.value.toLong() < function.values.size.toLong()
                    if (!structurallyValid) {
                        add(
                            ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                            "exception owner, range, handler, or register is outside the function",
                            ArtifactWriteLocation(moduleLocation, "EXCEPTIONS"),
                        )
                    }
                    val registerType = function.values.getOrNull(exception.exceptionRegister.value.toInt())?.semanticType
                    if (registerType != null && (registerType !is ValueType.Ref || registerType.nullable)) {
                        add(
                            ArtifactWriteErrorCode.INVALID_RANGE,
                            "exception register is not a non-null reference",
                            ArtifactWriteLocation(moduleLocation, "EXCEPTIONS"),
                        )
                    }
                    exception.catchType?.let { catchType ->
                        val catchIdentity = resolveType(moduleIndex, catchType)
                        val catchNominal = catchIdentity?.let { artifact.modules[it.module].types[it.type] }
                        if (catchNominal !is NominalType.Class && catchNominal !is NominalType.Interface) {
                            add(
                                ArtifactWriteErrorCode.BAD_REFERENCE,
                                "catch type does not resolve to a reference nominal type",
                                ArtifactWriteLocation(moduleLocation, "EXCEPTIONS"),
                            )
                        } else if (registerType is ValueType.Ref && !registerType.nullable) {
                            val catchValue = ValueType.Ref(nullable = false, type = catchType)
                            if (!valueAssignable(moduleIndex, registerType, moduleIndex, catchValue) &&
                                !valueAssignable(moduleIndex, catchValue, moduleIndex, registerType)
                            ) {
                                add(
                                    ArtifactWriteErrorCode.INVALID_RANGE,
                                    "exception register and catch type are incompatible",
                                    ArtifactWriteLocation(moduleLocation, "EXCEPTIONS"),
                                )
                            }
                        }
                    }
                    structurallyValid
                }
            val orderedHandlers =
                handlers.sortedWith(
                    compareBy<ExceptionEntry> {
                        it.firstProtectedBlock.value
                    }.thenByDescending {
                        it.firstProtectedBlock.value.toLong() + it.protectedBlockCount.toLong()
                    },
                )
            val containingRanges = ArrayDeque<Long>()
            orderedHandlers.forEach { exception ->
                val start = exception.firstProtectedBlock.value.toLong()
                val end = start + exception.protectedBlockCount.toLong()
                while (containingRanges.lastOrNull()?.let { it <= start } == true) containingRanges.removeLast()
                if (containingRanges.lastOrNull()?.let { end > it } == true) {
                    add(
                        ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                        "exception protected ranges cross without nesting",
                        ArtifactWriteLocation(moduleLocation, "EXCEPTIONS"),
                    )
                }
                containingRanges.addLast(end)
            }

            val rootLocation = ArtifactWriteLocation(moduleLocation, "SAFEPOINT_ROOTS", functionIndex.toUInt())
            val seenBoundaries = mutableSetOf<Pair<BlockId, UInt>>()
            function.safepointRoots.forEach { roots ->
                val blockIndex = roots.block.value.toLong()
                val block = module.blocks.getOrNull(roots.block.value.toInt())
                if (blockIndex !in blockStart.toLong() until blockEnd || block == null ||
                    roots.instructionBoundary.toLong() >= block.instructions.size.toLong()
                ) {
                    add(ArtifactWriteErrorCode.INVALID_RANGE, "safepoint roots identify an invalid instruction boundary", rootLocation)
                }
                if (!seenBoundaries.add(roots.block to roots.instructionBoundary)) {
                    add(ArtifactWriteErrorCode.INVALID_RANGE, "safepoint roots contain a duplicate instruction boundary", rootLocation)
                }
                val seenReferences = mutableSetOf<ru.lazyhat.compukters.compiler.artifact.model.ValueComponent>()
                roots.references.forEach { reference ->
                    val value = function.values.getOrNull(reference.value.value.toInt())
                    val atom = value?.physicalShape?.components?.getOrNull(reference.component.toInt())
                    if (atom != ru.lazyhat.compukters.compiler.artifact.model.PhysicalAtom.REF32) {
                        add(ArtifactWriteErrorCode.INVALID_RANGE, "safepoint root does not identify a Ref32 component", rootLocation)
                    }
                    if (!seenReferences.add(reference)) {
                        add(ArtifactWriteErrorCode.INVALID_RANGE, "safepoint roots contain a duplicate reference", rootLocation)
                    }
                }
            }
            if (blockRangeValid && exceptionStart <= module.exceptions.size && exceptionEnd <= module.exceptions.size) {
                val expected = ReferenceLiveness.derive(module, function)
                if (function.safepointRoots != expected) {
                    add(
                        ArtifactWriteErrorCode.INVALID_RANGE,
                        "function safepoint roots are not the exact canonical liveness map",
                        rootLocation,
                    )
                }
            }

            if (FunctionFlag.ABSTRACT in function.flags || function.blockCount == 0u || !blockRangeValid) {
                return@forEachIndexed
            }
            val blockCount = blockCountLong.toInt()
            val handlerBlocks = handlers.mapTo(mutableSetOf()) { it.handlerBlock.value.toInt() }

            val states = arrayOfNulls<MutableSet<Int>>(blockCount)
            val parameterCount =
                function.parameterCount
                    .toLong()
                    .takeIf { it <= function.values.size.toLong() }
                    ?.toInt() ?: 0
            states[0] = (0 until parameterCount).toMutableSet()
            val queue = ArrayDeque<Int>()
            queue += 0

            fun merge(
                target: Int,
                incoming: Set<Int>,
            ) {
                if (target !in states.indices) return
                val existing = states[target]
                if (existing == null) {
                    states[target] = incoming.toMutableSet()
                    queue += target
                } else {
                    val intersection = existing intersect incoming
                    if (intersection.size != existing.size) {
                        existing.retainAll(intersection)
                        queue += target
                    }
                }
            }

            while (queue.isNotEmpty()) {
                val localBlock = queue.removeFirst()
                val blockIndex = blockStart + localBlock
                val block = module.blocks[blockIndex]
                val state = requireNotNull(states[localBlock]).toMutableSet()
                block.instructions.forEachIndexed { instructionIndex, instruction ->
                    if (instruction.mayThrow()) {
                        handlers
                            .filter { exception ->
                                blockIndex >= exception.firstProtectedBlock.value.toInt() &&
                                    blockIndex <
                                    exception.firstProtectedBlock.value.toLong() + exception.protectedBlockCount.toLong()
                            }.forEach { exception ->
                                val incoming = state.toMutableSet()
                                repeat(parameterCount) { incoming += it }
                                incoming += exception.exceptionRegister.value.toInt()
                                merge(exception.handlerBlock.value.toInt() - blockStart, incoming)
                            }
                    }
                    instruction.readRegisters().forEach { register ->
                        val registerIndex = register.value.toInt()
                        if (registerIndex in function.values.indices && registerIndex !in state) {
                            add(
                                ArtifactWriteErrorCode.INVALID_RANGE,
                                "instruction reads an uninitialized register",
                                ArtifactWriteLocation(
                                    module = moduleLocation,
                                    table = "CODE",
                                    record = blockIndex.toUInt(),
                                    instruction = instructionIndex.toUInt(),
                                ),
                            )
                        }
                    }
                    instruction.writtenRegisters().forEach { register ->
                        if (register.value.toInt() in function.values.indices) state += register.value.toInt()
                    }
                }
                block.instructions.lastOrNull()?.successors()?.forEach { successor ->
                    val target = successor.value.toInt()
                    if (target in blockStart until (blockStart + blockCount)) {
                        if (target in handlerBlocks) {
                            add(
                                ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                                "ordinary control flow targets an exception handler",
                                ArtifactWriteLocation(moduleLocation, "BLOCKS", blockIndex.toUInt()),
                            )
                        } else if (target <= blockIndex && !module.blocks[target].loopHeaderSafepoint) {
                            add(
                                ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                                "backedge target is not a loop-header safepoint",
                                ArtifactWriteLocation(moduleLocation, "BLOCKS", blockIndex.toUInt()),
                            )
                        }
                        if (target !in handlerBlocks) merge(target - blockStart, state)
                    }
                }
            }
            if (states.any { it == null }) {
                add(
                    ArtifactWriteErrorCode.INCONSISTENT_RANGE,
                    "function contains an unreachable block or handler without a throwing predecessor",
                    ArtifactWriteLocation(moduleLocation, "FUNCTIONS", functionIndex.toUInt()),
                )
            }
        }
    }

    return errors
}

private fun Instruction.isTerminator(): Boolean =
    this is Instruction.Jump ||
        this is Instruction.Branch ||
        this is Instruction.Return ||
        this is Instruction.Throw ||
        this is Instruction.Unreachable ||
        this is Instruction.CallSuspend ||
        this is Instruction.TaskJoin ||
        this is Instruction.ChannelSend ||
        this is Instruction.ChannelReceive ||
        this is Instruction.CapabilityCallAsync

private fun Instruction.isKotlinSuspendingTerminator(): Boolean =
    this is Instruction.CallSuspend ||
        this is Instruction.TaskJoin ||
        this is Instruction.ChannelSend ||
        this is Instruction.ChannelReceive
