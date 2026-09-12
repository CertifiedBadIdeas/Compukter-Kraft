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

package ru.lazyhat.compukters.compiler.k2.engine.library

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryArguments
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.Export
import ru.lazyhat.compukters.compiler.artifact.model.ExportVisibility
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.k2.engine.CompilationSession
import ru.lazyhat.compukters.compiler.k2.engine.KotlinProjectLowering
import ru.lazyhat.compukters.compiler.k2.engine.UnsupportedKotlinIr
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.TrustedIntrinsicRegistry
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.k2.build.PlatformLibraryDeclaration
import ru.lazyhat.compukters.platform.k2.build.PlatformLibraryDeclarationKind
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class PlatformLibraryFragment(
    val module: PlatformModuleId,
    val artifact: ImmutableBytes,
)

/** Lowers ordinary canonical Kotlin bodies once into relocatable Compukters artifact code. */
class PlatformLibraryCompiler {
    fun compile(
        module: PlatformModuleId,
        declarations: List<PlatformLibraryDeclaration>,
        ir: IrModuleFragment,
        pluginContext: IrPluginContext,
        currentSourcePaths: Set<String>,
        sourceModules: Map<String, PlatformModuleId>,
        intrinsicRegistry: TrustedIntrinsicRegistry,
    ): ImmutableBytes? {
        if (declarations.none { it.kind == PlatformLibraryDeclarationKind.FUNCTION }) return null
        val filesByPath = ir.files.associateBy { file -> matchSourcePath(file.fileEntry.name, sourceModules.keys) }
        val currentFiles = currentSourcePaths.mapNotNull(filesByPath::get).toSet()
        require(currentFiles.isNotEmpty()) { "FIR-to-IR produced no files for platform module $module" }
        val collected = LibraryDeclarationCollector(currentFiles).also { ir.accept(it, null) }
        val ordinarySymbols =
            declarations.filter { it.kind == PlatformLibraryDeclarationKind.FUNCTION }.mapTo(mutableSetOf()) { it.symbol }
        val ordinaryFunctions = collected.functions.filter { it.fqNameWhenAvailable?.asString() in ordinarySymbols }
        val entry =
            ordinaryFunctions
                .filter { it.body != null }
                .sortedWith(compareBy({ it.file.fileEntry.name }, IrSimpleFunction::startOffset, { it.name.asString() }))
                .firstOrNull()
                ?: return null
        val physicalModules =
            filesByPath.entries.associate { (path, file) -> file.fileEntry.name to sourceModules.getValue(path) }
        val session =
            CompilationSession(
                irSink = { _, _ -> },
                trustedPlatformSourceModules = physicalModules,
                canonicalIntrinsicRegistry = intrinsicRegistry,
            )
        val artifact =
            try {
                KotlinProjectLowering.lower(
                    ordinaryFunctions,
                    emptyList(),
                    collected.classes,
                    entry,
                    pluginContext,
                    session,
                    includeTrustedPlatformBodies = true,
                )
            } catch (unsupported: UnsupportedKotlinIr) {
                throw IllegalArgumentException(
                    "cannot compile ordinary platform code in $module at ${unsupported.element.startOffset}: ${unsupported.message}",
                    unsupported,
                )
            }
        val wrapper = artifact.withLibraryFragmentEntry(declarations)
        val bytes =
            when (val result = ArtifactWriter.write(wrapper)) {
                is ArtifactWriteResult.Success -> {
                    result.bytes
                }

                is ArtifactWriteResult.Failure -> {
                    val application = wrapper.modules.first()
                    error(
                        "artifact writer rejected platform module $module: ${result.errors.joinToString { error ->
                            val block =
                                error.location
                                    ?.record
                                    ?.toInt()
                                    ?.let(application.blocks::getOrNull)
                            val function =
                                block
                                    ?.owner
                                    ?.value
                                    ?.toInt()
                                    ?.let(application.functions::getOrNull)
                            "${error.location}: ${error.detail}; instruction=${error.location?.instruction?.toInt()?.let {
                                block
                                    ?.instructions
                                    ?.getOrNull(
                                        it,
                                    )
                            }}; values=${function?.values}"
                        }}",
                    )
                }
            }
        return PlatformLibraryFragmentCodec.encode(PlatformLibraryFragment(module, ImmutableBytes.of(bytes)))
    }

    private fun matchSourcePath(
        physicalPath: String,
        sourcePaths: Set<String>,
    ): String {
        val normalized = physicalPath.replace('\\', '/')
        val matches = sourcePaths.filter { path -> normalized.endsWith(path) || normalized.endsWith("/${path.substringAfterLast('/')}") }
        return matches.singleOrNull() ?: error("cannot identify canonical platform source for $physicalPath: $matches")
    }
}

private fun ru.lazyhat.compukters.compiler.artifact.model.Artifact.withLibraryFragmentEntry(
    declarations: List<PlatformLibraryDeclaration>,
): ru.lazyhat.compukters.compiler.artifact.model.Artifact {
    val lowered = modules.first()
    val typeDeclarations =
        declarations
            .filter { it.exported && it.kind == PlatformLibraryDeclarationKind.TYPE }
            .associateBy(PlatformLibraryDeclaration::symbol)
    val fieldDeclarations =
        declarations
            .filter { it.exported && it.kind == PlatformLibraryDeclarationKind.FIELD }
            .associateBy(PlatformLibraryDeclaration::symbol)
    val fieldSymbols =
        lowered.fields
            .mapIndexedNotNull { index, field ->
                val owner = field.owner as? TypeRef.Local ?: return@mapIndexedNotNull null
                val ownerName =
                    lowered.strings[
                        lowered.types[owner.id.value.toInt()]
                            .name.value
                            .toInt(),
                    ].toString()
                val fieldName = lowered.strings[field.name.value.toInt()].toString()
                "$ownerName.$fieldName" to index
            }.toMap()
    val libraryStrings = lowered.strings
    val stringIds = libraryStrings.withIndex().associate { (index, value) -> value.toString() to StringId.of(index.toUInt()) }
    val missingFieldNames = fieldDeclarations.keys.filterNot(stringIds::containsKey)
    require(missingFieldNames.isEmpty()) { "platform field ABI names are absent from canonical metadata: $missingFieldNames" }
    val typeExports =
        typeDeclarations.keys.sorted().map { symbol ->
            val matches =
                lowered.types.withIndex().filter { (_, type) ->
                    lowered.strings[type.name.value.toInt()].toString() == symbol
                }
            val match = matches.singleOrNull() ?: error("cannot uniquely match platform type $symbol: $matches")
            Export(
                SymbolKind.TYPE,
                ExportVisibility.PUBLIC_LIBRARY,
                requireNotNull(stringIds[symbol]),
                match.index.toUInt(),
                TypeRef.Local(TypeId.of(match.index.toUInt())),
            )
        }
    val initializerFunctions = lowered.types.filterIsInstance<NominalType.Class>().mapNotNullTo(mutableSetOf()) { it.initializer?.value }
    val functionExports =
        lowered.functions.mapIndexedNotNull { index, function ->
            if (index.toUInt() in initializerFunctions) return@mapIndexedNotNull null
            Export(
                SymbolKind.FUNCTION,
                ExportVisibility.PUBLIC_LIBRARY,
                function.name,
                index.toUInt(),
                function.signature,
            )
        }
    val fieldExports =
        fieldDeclarations.keys.sorted().map { symbol ->
            val fieldIndex = requireNotNull(fieldSymbols[symbol]) { "cannot match platform field $symbol" }
            Export(
                SymbolKind.FIELD,
                ExportVisibility.PUBLIC_LIBRARY,
                requireNotNull(stringIds[symbol]),
                fieldIndex.toUInt(),
                lowered.fields[fieldIndex].owner,
            )
        }
    val library =
        lowered.copy(
            kind = ru.lazyhat.compukters.compiler.artifact.model.ModuleKind.LIBRARY,
            strings = libraryStrings,
            imports =
                lowered.imports.map { value ->
                    value.copy(targetModule = ModuleId.of(value.targetModule.value + 1u))
                },
            exports = typeExports + functionExports + fieldExports,
        )
    val functionId = FunctionId.of(0u)
    val typeId = TypeId.of(0u)
    val blockId = BlockId.of(0u)
    val anchor =
        Function(
            owner = null,
            name = library.name,
            signature = TypeRef.Local(typeId),
            flags = setOf(FunctionFlag.STATIC),
            values = emptyList(),
            parameterCount = 0u,
            firstBlock = blockId,
            blockCount = 1u,
            firstException = 0u,
            exceptionCount = 0u,
        )
    val wrapperApplication =
        ru.lazyhat.compukters.compiler.artifact.model.Module(
            name = library.name,
            kind = ru.lazyhat.compukters.compiler.artifact.model.ModuleKind.APPLICATION,
            strings = library.strings,
            types = listOf(NominalType.Function(library.name, suspending = false, result = ValueType.Unit, parameters = emptyList())),
            functions = listOf(anchor),
            blocks = listOf(Block(functionId, false, listOf(Instruction.Return(Destination.Unit)))),
        )
    val features =
        semanticFeatures +
            listOfNotNull(
                SemanticFeature.EXCEPTIONS.takeIf {
                    library.blocks.any { block -> block.instructions.any { it is Instruction.Throw } }
                },
            )
    return copy(
        semanticFeatures = features,
        entry = EntryPoint(ModuleId.of(0u), functionId, EntryArguments.NONE),
        modules = listOf(wrapperApplication, library) + modules.drop(1),
    )
}

object PlatformLibraryFragmentCodec {
    private const val FORMAT = 2
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    fun encode(fragment: PlatformLibraryFragment): ImmutableBytes =
        ImmutableBytes.of(
            ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { sink ->
                    sink.write(MAGIC)
                    sink.writeInt(FORMAT)
                    sink.string(fragment.module.namespace)
                    sink.string(fragment.module.name)
                    val artifact = fragment.artifact.toByteArray()
                    sink.writeInt(artifact.size)
                    sink.write(artifact)
                }
                output.toByteArray()
            },
        )

    fun decode(bytes: ImmutableBytes): PlatformLibraryFragment =
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { source ->
            require(source.readNBytes(MAGIC.size).contentEquals(MAGIC)) { "invalid platform library fragment magic" }
            require(source.readInt() == FORMAT) { "unsupported platform library fragment format" }
            val module = PlatformModuleId(source.string(), source.string())
            val length = source.readInt().also { require(it in 1..16_777_216) { "invalid platform library artifact length" } }
            val artifact = source.readNBytes(length)
            require(artifact.size == length) { "truncated platform library artifact" }
            require(
                artifact
                    .take(
                        4,
                    ).toByteArray()
                    .contentEquals(byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte())),
            ) {
                "platform library payload is not a Compukters artifact"
            }
            require(source.read() == -1) { "trailing platform library fragment bytes" }
            PlatformLibraryFragment(module, ImmutableBytes.of(artifact))
        }
}

private class LibraryDeclarationCollector(
    private val files: Set<IrFile>,
) : IrVisitorVoid() {
    val functions = mutableListOf<IrSimpleFunction>()
    val classes = mutableListOf<IrClass>()

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction) {
        if (declaration.startOffset >= 0 && declaration.fileOrNull() in files) functions += declaration
        super.visitSimpleFunction(declaration)
    }

    override fun visitClass(declaration: IrClass) {
        if (declaration.startOffset >= 0 && declaration.fileOrNull() in files) classes += declaration
        super.visitClass(declaration)
    }

    private fun org.jetbrains.kotlin.ir.declarations.IrDeclaration.fileOrNull(): IrFile? {
        var current: org.jetbrains.kotlin.ir.declarations.IrDeclarationParent = parent
        while (current is org.jetbrains.kotlin.ir.declarations.IrDeclaration) current = current.parent
        return current as? IrFile
    }
}

private fun DataOutputStream.string(value: String) {
    val bytes = value.encodeToByteArray()
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.string(): String {
    val length = readInt()
    require(length in 0..1_048_576) { "invalid platform library string length" }
    val bytes = readNBytes(length)
    require(bytes.size == length) { "truncated platform library fragment" }
    return bytes.decodeToString(throwOnInvalidSequence = true)
}
