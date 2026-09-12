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
import ru.lazyhat.compukters.compiler.artifact.model.AbiVersion
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.ExceptionEntry
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType

internal fun minimalArtifact(instructions: List<Instruction> = listOf(Instruction.Return(Destination.Unit))): Artifact =
    Artifact(
        manifest = Manifest.minimal(),
        entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
        modules =
            listOf(
                Module(
                    name = StringId.of(0u),
                    kind = ModuleKind.APPLICATION,
                    strings = listOf(MetadataText.of("app"), MetadataText.of("entry")),
                    types =
                        listOf(
                            NominalType.Function(
                                name = StringId.of(1u),
                                suspending = false,
                                result = ValueType.Unit,
                                parameters = emptyList(),
                            ),
                        ),
                    functions =
                        listOf(
                            Function(
                                owner = null,
                                name = StringId.of(1u),
                                signature = TypeRef.Local(TypeId.of(0u)),
                                flags = setOf(FunctionFlag.STATIC),
                                values = emptyList(),
                                parameterCount = 0u,
                                firstBlock = BlockId.of(0u),
                                blockCount = 1u,
                                firstException = 0u,
                                exceptionCount = 0u,
                            ),
                        ),
                    blocks =
                        listOf(
                            Block(
                                owner = FunctionId.of(0u),
                                loopHeaderSafepoint = false,
                                instructions = instructions,
                            ),
                        ),
                ),
            ),
    )

internal fun channelArtifact(): Artifact {
    val source = minimalArtifact()
    val module = source.modules.single()
    return ReferenceLiveness.derive(
        source.copy(
            minimumRuntimeAbi = AbiVersion(1u, 2u),
            semanticFeatures = setOf(SemanticFeature.COROUTINES, SemanticFeature.CHANNELS),
            manifest =
                Manifest(
                    requiredHeapBytes = 0u,
                    requiredStackBytes = 0u,
                    maximumCoroutines = 1u,
                    maximumCallDepth = 1u,
                    maximumHostRequests = 0u,
                    maximumEvents = 0u,
                    maximumBlockCost = 8u,
                    minimumSliceCost = 8u,
                    compilerAbi = ByteArray(32),
                    platformAbi = ByteArray(32),
                    maximumChannels = 1u,
                    maximumChannelValues = 1u,
                ),
            modules =
                listOf(
                    module.copy(
                        types =
                            listOf(
                                (module.types.single() as NominalType.Function).copy(suspending = true),
                            ),
                        constants = listOf(Constant.I32(1)),
                        functions =
                            listOf(
                                module.functions.single().copy(
                                    flags = setOf(FunctionFlag.STATIC, FunctionFlag.SUSPENDING),
                                    values = List(3) { FunctionValue.scalar(ValueType.I32) },
                                    blockCount = 3u,
                                ),
                            ),
                        blocks =
                            listOf(
                                Block(
                                    FunctionId.of(0u),
                                    false,
                                    listOf(
                                        Instruction.Const(RegisterId.of(0u), ConstantId.of(0u)),
                                        Instruction.ChannelCreate(RegisterId.of(1u), RegisterId.of(0u)),
                                        Instruction.ChannelSend(RegisterId.of(1u), RegisterId.of(0u), BlockId.of(1u)),
                                    ),
                                ),
                                Block(
                                    FunctionId.of(0u),
                                    false,
                                    listOf(Instruction.ChannelReceive(RegisterId.of(2u), RegisterId.of(1u), BlockId.of(2u))),
                                ),
                                Block(FunctionId.of(0u), false, listOf(Instruction.Return(Destination.Unit))),
                            ),
                    ),
                ),
        ),
    )
}

internal fun languageRuntimeArtifact(): Artifact =
    Artifact(
        semanticFeatures = setOf(SemanticFeature.EXCEPTIONS),
        manifest = Manifest.minimal(maximumBlockCost = 10u),
        entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
        modules =
            listOf(
                Module(
                    name = StringId.of(1u),
                    kind = ModuleKind.APPLICATION,
                    strings =
                        listOf(
                            MetadataText.of("Box"),
                            MetadataText.of("app"),
                            MetadataText.of("array"),
                            MetadataText.of("entry"),
                        ),
                    types =
                        listOf(
                            NominalType.Class(name = StringId.of(0u)),
                            NominalType.Array(name = StringId.of(2u), element = ValueType.I32),
                            NominalType.Function(
                                name = StringId.of(3u),
                                suspending = false,
                                result = ValueType.Unit,
                                parameters = emptyList(),
                            ),
                        ),
                    constants = listOf(Constant.I32(0), Constant.I32(1)),
                    functions =
                        listOf(
                            Function(
                                owner = null,
                                name = StringId.of(3u),
                                signature = TypeRef.Local(TypeId.of(2u)),
                                flags = setOf(FunctionFlag.STATIC),
                                values =
                                    listOf(
                                        ValueType.Ref(false, TypeRef.Local(TypeId.of(0u))),
                                        ValueType.Ref(true, TypeRef.Local(TypeId.of(0u))),
                                        ValueType.Bool,
                                        ValueType.I32,
                                        ValueType.Ref(false, TypeRef.Local(TypeId.of(1u))),
                                        ValueType.I32,
                                        ValueType.Ref(false, TypeRef.Local(TypeId.of(0u))),
                                        ValueType.I32,
                                    ).map(FunctionValue::scalar),
                                parameterCount = 0u,
                                firstBlock = BlockId.of(0u),
                                blockCount = 5u,
                                firstException = 0u,
                                exceptionCount = 1u,
                            ),
                        ),
                    blocks =
                        listOf(
                            Block(
                                owner = FunctionId.of(0u),
                                loopHeaderSafepoint = false,
                                instructions =
                                    listOf(
                                        Instruction.NewObject(RegisterId.of(0u), TypeRef.Local(TypeId.of(0u))),
                                        Instruction.Null(RegisterId.of(1u)),
                                        Instruction.Const(RegisterId.of(3u), ConstantId.of(1u)),
                                        Instruction.Const(RegisterId.of(7u), ConstantId.of(0u)),
                                        Instruction.IsType(RegisterId.of(2u), RegisterId.of(1u), TypeRef.Local(TypeId.of(0u))),
                                        Instruction.Branch(RegisterId.of(2u), BlockId.of(1u), BlockId.of(2u)),
                                    ),
                            ),
                            Block(
                                owner = FunctionId.of(0u),
                                loopHeaderSafepoint = false,
                                instructions =
                                    listOf(
                                        Instruction.NewArray(RegisterId.of(4u), TypeRef.Local(TypeId.of(1u)), RegisterId.of(3u)),
                                        Instruction.ArrayStore(RegisterId.of(4u), RegisterId.of(7u), RegisterId.of(3u)),
                                        Instruction.ArrayLoad(RegisterId.of(5u), RegisterId.of(4u), RegisterId.of(7u)),
                                        Instruction.Jump(BlockId.of(3u)),
                                    ),
                            ),
                            Block(
                                owner = FunctionId.of(0u),
                                loopHeaderSafepoint = false,
                                instructions =
                                    listOf(
                                        Instruction.NewObject(RegisterId.of(6u), TypeRef.Local(TypeId.of(0u))),
                                        Instruction.Throw(RegisterId.of(6u)),
                                    ),
                            ),
                            Block(
                                owner = FunctionId.of(0u),
                                loopHeaderSafepoint = true,
                                instructions = listOf(Instruction.Jump(BlockId.of(3u))),
                            ),
                            Block(
                                owner = FunctionId.of(0u),
                                loopHeaderSafepoint = false,
                                instructions = listOf(Instruction.Return(Destination.Unit)),
                            ),
                        ),
                    exceptions =
                        listOf(
                            ExceptionEntry(
                                owner = FunctionId.of(0u),
                                firstProtectedBlock = BlockId.of(2u),
                                protectedBlockCount = 1u,
                                catchType = TypeRef.Local(TypeId.of(0u)),
                                handlerBlock = BlockId.of(4u),
                                exceptionRegister = RegisterId.of(6u),
                            ),
                        ),
                ),
            ),
    )
