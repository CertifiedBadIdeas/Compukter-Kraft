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

import ru.lazyhat.compukters.compiler.artifact.analysis.ExecutionStorage
import ru.lazyhat.compukters.compiler.artifact.analysis.ReferenceLiveness
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Capability
import ru.lazyhat.compukters.compiler.artifact.model.CapabilityId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.DebugEntry
import ru.lazyhat.compukters.compiler.artifact.model.DebugEntryId
import ru.lazyhat.compukters.compiler.artifact.model.ExceptionEntry
import ru.lazyhat.compukters.compiler.artifact.model.Export
import ru.lazyhat.compukters.compiler.artifact.model.Field
import ru.lazyhat.compukters.compiler.artifact.model.FieldId
import ru.lazyhat.compukters.compiler.artifact.model.FieldRef
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.Import
import ru.lazyhat.compukters.compiler.artifact.model.ImportId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.Utf16LiteralId
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.artifact.write.encodeTypeRef
import ru.lazyhat.compukters.compiler.artifact.write.instructionFixedCost

/** Links named library modules by semantic identity and removes every unreachable record. */
object LibraryModuleLinker {
    fun link(
        application: Artifact,
        libraries: Map<String, Module>,
    ): Artifact {
        require(application.modules.count { it.kind == ModuleKind.APPLICATION } == 1) {
            "link input must contain exactly one application module"
        }
        libraries.forEach { (name, module) -> require(module.kind == ModuleKind.LIBRARY) { "$name is not a library module" } }
        val external =
            libraries.entries
                .sortedWith(
                    compareBy<Map.Entry<String, Module>>(
                        { moduleName(it.value) },
                        { ArtifactWriter.moduleSemanticHash(it.value).hex() },
                    ),
                ).map(Map.Entry<String, Module>::value)
        val combined = application.copy(modules = application.modules + external)
        require(
            combined.modules
                .map(ArtifactWriter::moduleSemanticHash)
                .distinctBy(ByteArray::hex)
                .size == combined.modules.size,
        ) {
            "link input contains duplicate module semantic identities"
        }
        val reachability = ReachabilityGraph(combined).analyze()
        val selected =
            combined.modules.indices.filter { index ->
                val module = combined.modules[index]
                module.kind == ModuleKind.APPLICATION || reachability.modules[index].hasDefinitions()
            }
        val applicationIndex = selected.single { combined.modules[it].kind == ModuleKind.APPLICATION }
        require(
            application.entry.module.value
                .toInt() == applicationIndex,
        ) { "entry function must belong to the application module" }
        val ordered =
            listOf(applicationIndex) +
                selected
                    .filter { it != applicationIndex }
                    .sortedWith(
                        compareBy({ moduleName(combined.modules[it]) }, { ArtifactWriter.moduleSemanticHash(combined.modules[it]).hex() }),
                    )
        val moduleIds = ordered.withIndex().associate { (new, old) -> old to new }
        val capabilityMap = reachability.capabilities.withIndex().associate { (new, old) -> old to new }
        val relocations =
            ordered.associateWith { old ->
                ModuleRelocation(
                    reachability.modules[old],
                    capabilityMap,
                    canonicalImportOrder(
                        combined.modules[old],
                        old,
                        reachability.modules[old],
                        moduleIds,
                        reachability.importTargets,
                    ),
                )
            }
        val baseModules =
            ordered.associateWith { old ->
                relocateModule(
                    combined.modules[old],
                    old,
                    requireNotNull(relocations[old]),
                    moduleIds,
                    reachability.importTargets,
                )
            }
        val completed = mutableMapOf<Int, Module>()
        val active = mutableSetOf<Int>()

        fun complete(old: Int): Module {
            completed[old]?.let { return it }
            require(active.add(old)) { "library module dependency cycle reaches ${moduleName(combined.modules[old])}" }
            val source = requireNotNull(baseModules[old])
            val relocation = requireNotNull(relocations[old])
            val imports =
                relocation.imports.oldIndices.mapIndexed { newIndex, oldImport ->
                    val target =
                        requireNotNull(reachability.importTargets[old to oldImport]) { "reachable import $old:$oldImport has no target" }
                    source.imports[newIndex].copy(targetModuleHash = ArtifactWriter.moduleSemanticHash(complete(target)))
                }
            active.remove(old)
            return source.copy(imports = imports).also { completed[old] = it }
        }
        val modules = ordered.map(::complete)
        val applicationRelocation = requireNotNull(relocations[applicationIndex])
        val capabilities =
            reachability.capabilities.map { old ->
                val capability = application.capabilities.getOrNull(old) ?: error("capability $old is absent")
                relocateCapability(capability, applicationRelocation)
            }
        val entry =
            application.entry.copy(
                module =
                    ModuleId.of(
                        requireNotNull(
                            moduleIds[
                                application.entry.module.value
                                    .toInt(),
                            ],
                        ).toUInt(),
                    ),
                function = applicationRelocation.function(application.entry.function),
            )
        val features = semanticFeatures(modules, capabilities)
        val maximumBlockCost =
            modules
                .asSequence()
                .flatMap { module -> module.blocks.asSequence() }
                .maxOfOrNull { block -> block.instructions.sumOf { instruction -> instructionFixedCost(instruction).toLong() } }
                ?.toUInt()
                ?: 0u
        val manifest =
            application.manifest.withLinkedRequirements(
                ExecutionStorage.requiredStackBytes(modules, application.manifest.maximumCallDepth),
                maximumBlockCost,
            )
        val linked =
            ReferenceLiveness.derive(
                application.copy(
                    semanticFeatures = features,
                    manifest = manifest,
                    entry = entry,
                    modules = modules,
                    capabilities = capabilities,
                ),
            )
        when (val write = ArtifactWriter.write(linked)) {
            is ArtifactWriteResult.Success -> {}

            is ArtifactWriteResult.Failure -> {
                throw IllegalArgumentException(
                    "linked artifact is invalid: ${write.errors.joinToString { "${it.location}: ${it.detail}" }}",
                )
            }
        }
        return linked
    }
}

private fun Manifest.withLinkedRequirements(
    requiredStackBytes: UInt,
    requiredMaximumBlockCost: UInt,
): Manifest =
    Manifest(
        requiredHeapBytes = requiredHeapBytes,
        requiredStackBytes = requiredStackBytes,
        maximumCoroutines = maximumCoroutines,
        maximumCallDepth = maximumCallDepth,
        maximumHostRequests = maximumHostRequests,
        maximumEvents = maximumEvents,
        maximumBlockCost = maxOf(maximumBlockCost, requiredMaximumBlockCost),
        minimumSliceCost = maxOf(minimumSliceCost, requiredMaximumBlockCost),
        compilerAbi = compilerAbi,
        platformAbi = platformAbi,
    )

private data class DenseIds(
    val oldIndices: List<Int>,
) {
    private val byOld = oldIndices.withIndex().associate { (new, old) -> old to new }

    fun get(
        old: Int,
        kind: String,
    ): Int = requireNotNull(byOld[old]) { "reachable $kind $old was not relocated" }
}

private class ModuleRelocation(
    reachable: ModuleReachability,
    private val capabilityMap: Map<Int, Int>,
    importOrder: List<Int>,
) {
    val strings = DenseIds(reachable.strings.toList())
    val literals = DenseIds(reachable.literals.toList())
    val types = DenseIds(reachable.types.toList())
    val constants = DenseIds(reachable.constants.toList())
    val imports = DenseIds(importOrder)
    val exports = DenseIds(reachable.exports.toList())
    val fields = DenseIds(reachable.fields.toList())
    val functions = DenseIds(reachable.functions.toList())
    val blocks = DenseIds(reachable.blocks.toList())
    val exceptions = DenseIds(reachable.exceptions.toList())
    val debug = DenseIds(reachable.debug.toList())

    fun string(id: StringId) = StringId.of(strings.get(id.value.toInt(), "string").toUInt())

    fun type(id: TypeId) = TypeId.of(types.get(id.value.toInt(), "type").toUInt())

    fun function(id: FunctionId) = FunctionId.of(functions.get(id.value.toInt(), "function").toUInt())

    fun field(id: FieldId) = FieldId.of(fields.get(id.value.toInt(), "field").toUInt())

    fun block(id: BlockId) = BlockId.of(blocks.get(id.value.toInt(), "block").toUInt())

    fun import(id: ImportId) = ImportId.of(imports.get(id.value.toInt(), "import").toUInt())

    fun constant(id: ConstantId) = ConstantId.of(constants.get(id.value.toInt(), "constant").toUInt())

    fun literal(id: Utf16LiteralId) = Utf16LiteralId.of(literals.get(id.value.toInt(), "literal").toUInt())

    fun debug(id: DebugEntryId) = DebugEntryId.of(debug.get(id.value.toInt(), "debug").toUInt())

    fun capability(id: CapabilityId) = CapabilityId.of(requireNotNull(capabilityMap[id.value.toInt()]).toUInt())

    fun type(ref: TypeRef): TypeRef =
        when (ref) {
            is TypeRef.Local -> TypeRef.Local(type(ref.id))
            is TypeRef.Imported -> TypeRef.Imported(import(ref.id))
        }

    fun value(type: ValueType): ValueType = if (type is ValueType.Ref) type.copy(type = type(type.type)) else type

    fun function(ref: FunctionRef): FunctionRef =
        when (ref) {
            is FunctionRef.Local -> FunctionRef.Local(function(ref.id))
            is FunctionRef.Imported -> FunctionRef.Imported(import(ref.id))
        }

    fun field(ref: FieldRef): FieldRef =
        when (ref) {
            is FieldRef.Local -> FieldRef.Local(field(ref.id))
            is FieldRef.Imported -> FieldRef.Imported(import(ref.id))
        }
}

private fun canonicalImportOrder(
    module: Module,
    oldModule: Int,
    reachable: ModuleReachability,
    moduleIds: Map<Int, Int>,
    importTargets: Map<Pair<Int, Int>, Int>,
): List<Int> {
    val typeIds = DenseIds(reachable.types.toList())
    val primaryComparator =
        compareBy<Int>(
            { oldImport ->
                val target = requireNotNull(importTargets[oldModule to oldImport])
                requireNotNull(moduleIds[target])
            },
            { oldImport -> module.imports[oldImport].kind.ordinal },
            { oldImport ->
                val targetName = module.imports[oldImport].targetName
                module.strings[targetName.value.toInt()]
            },
        )
    var order = reachable.imports.sortedWith(primaryComparator)
    repeat(order.size + 1) {
        val importIds = DenseIds(order)
        val next =
            reachable.imports.sortedWith(
                primaryComparator.thenBy { oldImport ->
                    when (val signature = module.imports[oldImport].expectedSignature) {
                        is TypeRef.Local -> typeIds.get(signature.id.value.toInt(), "type").toUInt()
                        is TypeRef.Imported -> importIds.get(signature.id.value.toInt(), "import").toUInt() or 0x8000_0000u
                    }
                },
            )
        if (next == order) return order
        order = next
    }
    error("canonical import relocation did not converge for module $oldModule")
}

private fun relocateModule(
    module: Module,
    oldModule: Int,
    ids: ModuleRelocation,
    moduleIds: Map<Int, Int>,
    importTargets: Map<Pair<Int, Int>, Int>,
): Module {
    val strings = ids.strings.oldIndices.map(module.strings::get)
    val exports =
        ids.exports.oldIndices
            .map { relocateExport(module.exports[it], ids) }
            .sortedWith { left, right -> compareExports(strings, left, right) }
    return Module(
        name = ids.string(module.name),
        kind = module.kind,
        strings = strings,
        utf16Literals = ids.literals.oldIndices.map(module.utf16Literals::get),
        types = ids.types.oldIndices.map { relocateType(module.types[it], ids) },
        constants = ids.constants.oldIndices.map { relocateConstant(module.constants[it], ids) },
        imports =
            ids.imports.oldIndices.map { oldImport ->
                val import = module.imports[oldImport]
                val target = requireNotNull(importTargets[oldModule to oldImport])
                import.copy(
                    targetModule = ModuleId.of(requireNotNull(moduleIds[target]).toUInt()),
                    targetName = ids.string(import.targetName),
                    expectedSignature = ids.type(import.expectedSignature),
                    targetModuleHash = ByteArray(32),
                )
            },
        exports = exports,
        fields = ids.fields.oldIndices.map { relocateField(module.fields[it], ids) },
        functions = ids.functions.oldIndices.map { relocateFunction(module.functions[it], ids) },
        blocks = ids.blocks.oldIndices.map { relocateBlock(module.blocks[it], ids) },
        exceptions = ids.exceptions.oldIndices.map { relocateException(module.exceptions[it], ids) },
        debug = ids.debug.oldIndices.map { relocateDebug(module.debug[it], ids) },
    )
}

private fun compareExports(
    strings: List<MetadataText>,
    left: Export,
    right: Export,
): Int {
    val kind = left.kind.ordinal.compareTo(right.kind.ordinal)
    if (kind != 0) return kind
    val name =
        compareUnsignedBytes(
            strings[left.name.value.toInt()].toByteArray(),
            strings[right.name.value.toInt()].toByteArray(),
        )
    if (name != 0) return name
    return encodeTypeRef(left.signature).compareTo(encodeTypeRef(right.signature))
}

private fun compareUnsignedBytes(
    left: ByteArray,
    right: ByteArray,
): Int {
    repeat(minOf(left.size, right.size)) { index ->
        val compared = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
        if (compared != 0) return compared
    }
    return left.size.compareTo(right.size)
}

private fun relocateType(
    type: NominalType,
    ids: ModuleRelocation,
): NominalType =
    when (type) {
        is NominalType.Array -> {
            type.copy(name = ids.string(type.name), element = ids.value(type.element))
        }

        is NominalType.Function -> {
            type.copy(name = ids.string(type.name), result = ids.value(type.result), parameters = type.parameters.map(ids::value))
        }

        is NominalType.Class -> {
            type.copy(
                name = ids.string(type.name),
                superType = type.superType?.let(ids::type),
                interfaces = type.interfaces.map(ids::type),
                fieldStart = relocateStart(type.fieldStart, type.fieldCount, ids.fields, "field"),
                methodStart = relocateStart(type.methodStart, type.methodCount, ids.functions, "method"),
                initializer = type.initializer?.let(ids::function),
            )
        }

        is NominalType.Interface -> {
            type.copy(
                name = ids.string(type.name),
                superType = type.superType?.let(ids::type),
                interfaces = type.interfaces.map(ids::type),
                methodStart = relocateStart(type.methodStart, type.methodCount, ids.functions, "method"),
            )
        }
    }

private fun relocateConstant(
    constant: Constant,
    ids: ModuleRelocation,
): Constant = if (constant is Constant.StringLiteral) constant.copy(literal = ids.literal(constant.literal)) else constant

private fun relocateExport(
    export: Export,
    ids: ModuleRelocation,
): Export =
    export.copy(
        name = ids.string(export.name),
        localSymbol =
            when (export.kind) {
                ru.lazyhat.compukters.compiler.artifact.model.SymbolKind.TYPE -> {
                    ids.types.get(export.localSymbol.toInt(), "type")
                }

                ru.lazyhat.compukters.compiler.artifact.model.SymbolKind.FUNCTION -> {
                    ids.functions.get(
                        export.localSymbol.toInt(),
                        "function",
                    )
                }

                ru.lazyhat.compukters.compiler.artifact.model.SymbolKind.FIELD -> {
                    ids.fields.get(export.localSymbol.toInt(), "field")
                }
            }.toUInt(),
        signature = ids.type(export.signature),
    )

private fun relocateField(
    field: Field,
    ids: ModuleRelocation,
): Field = field.copy(owner = ids.type(field.owner), name = ids.string(field.name), type = ids.value(field.type))

private fun relocateFunction(
    function: Function,
    ids: ModuleRelocation,
): Function =
    function.copy(
        owner = function.owner?.let(ids::type),
        name = ids.string(function.name),
        signature = ids.type(function.signature),
        values = function.values.map { it.copy(semanticType = ids.value(it.semanticType)) },
        firstBlock = ids.block(function.firstBlock),
        firstException = relocateStart(function.firstException, function.exceptionCount, ids.exceptions, "exception"),
        safepointRoots = function.safepointRoots.map { it.copy(block = ids.block(it.block)) },
    )

private fun relocateBlock(
    block: Block,
    ids: ModuleRelocation,
): Block = block.copy(owner = ids.function(block.owner), instructions = block.instructions.map { relocateInstruction(it, ids) })

private fun relocateException(
    exception: ExceptionEntry,
    ids: ModuleRelocation,
): ExceptionEntry =
    exception.copy(
        owner = ids.function(exception.owner),
        firstProtectedBlock = ids.block(exception.firstProtectedBlock),
        catchType = exception.catchType?.let(ids::type),
        handlerBlock = ids.block(exception.handlerBlock),
    )

private fun relocateDebug(
    debug: DebugEntry,
    ids: ModuleRelocation,
): DebugEntry =
    debug.copy(
        function = ids.function(debug.function),
        block = ids.block(debug.block),
        inlineParent = debug.inlineParent?.let(ids::debug),
    )

private fun relocateInstruction(
    instruction: Instruction,
    ids: ModuleRelocation,
): Instruction =
    when (instruction) {
        is Instruction.Const -> {
            instruction.copy(constant = ids.constant(instruction.constant))
        }

        is Instruction.NewObject -> {
            instruction.copy(type = ids.type(instruction.type))
        }

        is Instruction.NewArray -> {
            instruction.copy(type = ids.type(instruction.type))
        }

        is Instruction.FieldGet -> {
            instruction.copy(field = ids.field(instruction.field))
        }

        is Instruction.FieldSet -> {
            instruction.copy(field = ids.field(instruction.field))
        }

        is Instruction.StaticGet -> {
            instruction.copy(field = ids.field(instruction.field))
        }

        is Instruction.StaticSet -> {
            instruction.copy(field = ids.field(instruction.field))
        }

        is Instruction.IsType -> {
            instruction.copy(type = ids.type(instruction.type))
        }

        is Instruction.CheckedCast -> {
            instruction.copy(type = ids.type(instruction.type))
        }

        is Instruction.Call -> {
            Instruction.Call(instruction.destination, ids.function(instruction.function), instruction.arguments)
        }

        is Instruction.CallSuspend -> {
            Instruction.CallSuspend(
                instruction.destination,
                ids.function(instruction.function),
                instruction.arguments,
                ids.block(instruction.resumeBlock),
            )
        }

        is Instruction.TaskSpawn -> {
            Instruction.TaskSpawn(
                instruction.destination,
                ids.function(instruction.function),
                instruction.arguments,
            )
        }

        is Instruction.TaskJoin -> {
            instruction.copy(resumeBlock = ids.block(instruction.resumeBlock))
        }

        is Instruction.CapabilityCallSync -> {
            Instruction.CapabilityCallSync(
                instruction.destination,
                ids.capability(instruction.capability),
                instruction.operation,
                instruction.arguments,
            )
        }

        is Instruction.CapabilityCallAsync -> {
            Instruction.CapabilityCallAsync(
                instruction.destination,
                ids.capability(instruction.capability),
                instruction.operation,
                instruction.arguments,
                ids.block(instruction.resumeBlock),
            )
        }

        is Instruction.Jump -> {
            instruction.copy(target = ids.block(instruction.target))
        }

        is Instruction.Branch -> {
            instruction.copy(trueTarget = ids.block(instruction.trueTarget), falseTarget = ids.block(instruction.falseTarget))
        }

        else -> {
            instruction
        }
    }

private fun relocateCapability(
    capability: Capability,
    ids: ModuleRelocation,
): Capability = capability.copy(namespace = ids.string(capability.namespace), name = ids.string(capability.name))

private fun relocateStart(
    start: UInt,
    count: UInt,
    ids: DenseIds,
    kind: String,
): UInt = if (count == 0u) 0u else ids.get(start.toInt(), kind).toUInt()

private fun semanticFeatures(
    modules: List<Module>,
    capabilities: List<Capability>,
): Set<SemanticFeature> =
    buildSet {
        if (modules.any {
                it.exceptions.isNotEmpty() ||
                    it.blocks.any { block -> block.instructions.any { instruction -> instruction is Instruction.Throw } }
            }
        ) {
            add(SemanticFeature.EXCEPTIONS)
        }
        if (modules.any { module ->
                module.functions.any { ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag.SUSPENDING in it.flags } ||
                    module.blocks.any { block -> block.instructions.any { it is Instruction.CallSuspend } }
            }
        ) {
            add(SemanticFeature.COROUTINES)
        }
        if (modules.any { it.imports.isNotEmpty() }) add(SemanticFeature.MODULE_IMPORTS)
        if (capabilities.isNotEmpty()) add(SemanticFeature.CAPABILITIES)
    }

private fun ModuleReachability.hasDefinitions(): Boolean =
    types.isNotEmpty() || fields.isNotEmpty() || functions.isNotEmpty() || imports.isNotEmpty() || exports.isNotEmpty()

private fun moduleName(module: Module): String =
    module.strings.getOrNull(module.name.value.toInt())?.toString() ?: error("module name is invalid")

private fun ByteArray.hex(): String = joinToString(separator = "") { "%02x".format(it) }
