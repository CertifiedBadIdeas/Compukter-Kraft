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

data class AbiVersion(
    val major: UShort,
    val minor: UShort,
) : Comparable<AbiVersion> {
    override fun compareTo(other: AbiVersion): Int = compareValuesBy(this, other, AbiVersion::major, AbiVersion::minor)
}

enum class SemanticFeature {
    EXCEPTIONS,
    COROUTINES,
    CAPABILITIES,
    MODULE_IMPORTS,
}

enum class EntryArguments(
    internal val artifactTag: UInt,
) {
    NONE(0u),
    STRING_ARRAY(1u),
}

data class EntryPoint(
    val module: ModuleId,
    val function: FunctionId,
    val arguments: EntryArguments = EntryArguments.NONE,
)

class Manifest(
    val requiredHeapBytes: UInt,
    val requiredStackBytes: UInt,
    val maximumCoroutines: UInt,
    val maximumCallDepth: UInt,
    val maximumHostRequests: UInt,
    val maximumEvents: UInt,
    val maximumBlockCost: UInt,
    val minimumSliceCost: UInt,
    compilerAbi: ByteArray,
    platformAbi: ByteArray,
) {
    val compilerAbi: ByteArray = compilerAbi.copyOf()
    val platformAbi: ByteArray = platformAbi.copyOf()

    companion object {
        fun minimal(
            maximumBlockCost: UInt = 1u,
            minimumSliceCost: UInt = maximumBlockCost,
        ): Manifest =
            Manifest(
                requiredHeapBytes = 0u,
                requiredStackBytes = 0u,
                maximumCoroutines = 1u,
                maximumCallDepth = 1u,
                maximumHostRequests = 0u,
                maximumEvents = 0u,
                maximumBlockCost = maximumBlockCost,
                minimumSliceCost = minimumSliceCost,
                compilerAbi = ByteArray(32),
                platformAbi = ByteArray(32),
            )
    }
}

data class Artifact(
    val minimumRuntimeAbi: AbiVersion = AbiVersion(1u, 0u),
    val semanticFeatures: Set<SemanticFeature> = emptySet(),
    val manifest: Manifest,
    val entry: EntryPoint,
    val modules: List<Module>,
    val capabilities: List<Capability> = emptyList(),
)

enum class ModuleKind {
    APPLICATION,
    LIBRARY,
}

data class Module(
    val name: StringId,
    val kind: ModuleKind,
    val strings: List<MetadataText>,
    val utf16Literals: List<Utf16Literal> = emptyList(),
    val types: List<NominalType> = emptyList(),
    val constants: List<Constant> = emptyList(),
    val imports: List<Import> = emptyList(),
    val exports: List<Export> = emptyList(),
    val fields: List<Field> = emptyList(),
    val functions: List<Function> = emptyList(),
    val blocks: List<Block> = emptyList(),
    val exceptions: List<ExceptionEntry> = emptyList(),
    val debug: List<DebugEntry> = emptyList(),
)

data class Capability(
    val namespace: StringId,
    val name: StringId,
    val abi: AbiVersion,
    val required: Boolean,
    val operationCount: UInt,
)

enum class SymbolKind {
    TYPE,
    FUNCTION,
    FIELD,
}

data class Import(
    val kind: SymbolKind,
    val targetModule: ModuleId,
    val targetName: StringId,
    val expectedSignature: TypeRef,
    val targetModuleHash: ByteArray,
)

enum class ExportVisibility {
    BUNDLE,
    PUBLIC_LIBRARY,
}

data class Export(
    val kind: SymbolKind,
    val visibility: ExportVisibility,
    val name: StringId,
    val localSymbol: UInt,
    val signature: TypeRef,
)

data class Field(
    val owner: TypeRef,
    val name: StringId,
    val type: ValueType,
    val mutable: Boolean,
    val static: Boolean,
)

enum class FunctionFlag {
    SUSPENDING,
    STATIC,
    VIRTUAL,
    ABSTRACT,
}

data class Function(
    val owner: TypeRef?,
    val name: StringId,
    val signature: TypeRef,
    val flags: Set<FunctionFlag>,
    val values: List<FunctionValue>,
    val parameterCount: UInt,
    val firstBlock: BlockId,
    val blockCount: UInt,
    val firstException: UInt,
    val exceptionCount: UInt,
    val safepointRoots: List<SafepointRoots> = emptyList(),
)

data class Block(
    val owner: FunctionId,
    val loopHeaderSafepoint: Boolean,
    val instructions: List<Instruction>,
)

data class ExceptionEntry(
    val owner: FunctionId,
    val firstProtectedBlock: BlockId,
    val protectedBlockCount: UInt,
    val catchType: TypeRef?,
    val handlerBlock: BlockId,
    val exceptionRegister: RegisterId,
)

data class DebugEntry(
    val function: FunctionId,
    val block: BlockId,
    val instruction: UInt,
    val startUtf16: UInt,
    val endUtf16: UInt,
    val inlineParent: DebugEntryId?,
    val sourcePath: MetadataText,
)
