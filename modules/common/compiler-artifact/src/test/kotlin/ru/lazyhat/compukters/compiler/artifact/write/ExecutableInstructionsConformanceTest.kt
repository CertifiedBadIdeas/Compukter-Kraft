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

import ru.lazyhat.compukters.compiler.artifact.model.AbiVersion
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Capability
import ru.lazyhat.compukters.compiler.artifact.model.CapabilityId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.Export
import ru.lazyhat.compukters.compiler.artifact.model.ExportVisibility
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
import ru.lazyhat.compukters.compiler.artifact.model.Import
import ru.lazyhat.compukters.compiler.artifact.model.ImportId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.OrderedScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.Utf16Literal
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertIs

class ExecutableInstructionsConformanceTest {
    @Test
    fun `writes representative executable artifact for the pinned Rust verifier`() {
        val result = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(executableInstructionsArtifact()))
        val output = Path.of(requireNotNull(System.getProperty("compukter.vm.executableArtifact")))
        output.parent.createDirectories()
        output.writeBytes(result.bytes)
    }
}

private fun executableInstructionsArtifact(): Artifact {
    val limits = ArtifactWriteLimits()
    val library =
        Module(
            name = StringId.of(0u),
            kind = ModuleKind.LIBRARY,
            strings = listOf(MetadataText.of("kotlin.String")),
            types = listOf(NominalType.Class(name = StringId.of(0u), final = true)),
            exports =
                listOf(
                    Export(
                        SymbolKind.TYPE,
                        ExportVisibility.PUBLIC_LIBRARY,
                        StringId.of(0u),
                        0u,
                        TypeRef.Local(TypeId.of(0u)),
                    ),
                ),
        )
    val libraryHash = ArtifactWriter.moduleSemanticHash(library, limits)
    val stringType = ValueType.Ref(false, TypeRef.Imported(ImportId.of(0u)))
    val charArrayType = ValueType.Ref(false, TypeRef.Local(TypeId.of(3u)))
    val app =
        Module(
            name = StringId.of(0u),
            kind = ModuleKind.APPLICATION,
            strings = listOf("app", "callee", "entry", "host", "kotlin.String", "terminal").map(MetadataText::of),
            utf16Literals = listOf(Utf16Literal.fromString("x")),
            types =
                listOf(
                    NominalType.Function(StringId.of(2u), true, ValueType.Unit, emptyList()),
                    NominalType.Function(StringId.of(1u), false, ValueType.I32, listOf(ValueType.I32, stringType)),
                    NominalType.Function(StringId.of(1u), true, ValueType.Unit, emptyList()),
                    NominalType.Array(StringId.of(2u), ValueType.Char),
                ),
            constants =
                listOf(
                    Constant.I32(7),
                    Constant.Bool(true),
                    Constant.Char('x'.code.toUShort()),
                    Constant.StringLiteral(
                        ru.lazyhat.compukters.compiler.artifact.model.Utf16LiteralId
                            .of(0u),
                    ),
                ),
            imports =
                listOf(
                    Import(
                        SymbolKind.TYPE,
                        ModuleId.of(1u),
                        StringId.of(4u),
                        TypeRef.Imported(ImportId.of(0u)),
                        libraryHash,
                    ),
                ),
            functions =
                listOf(
                    Function(
                        null,
                        StringId.of(2u),
                        TypeRef.Local(TypeId.of(0u)),
                        setOf(FunctionFlag.STATIC, FunctionFlag.SUSPENDING),
                        listOf(
                            ValueType.I32,
                            stringType,
                            stringType,
                            stringType,
                            ValueType.I32,
                            ValueType.Bool,
                            ValueType.Char,
                            charArrayType,
                        ).map(FunctionValue::scalar),
                        0u,
                        BlockId.of(0u),
                        8u,
                        0u,
                        0u,
                    ),
                    Function(
                        null,
                        StringId.of(1u),
                        TypeRef.Local(TypeId.of(1u)),
                        setOf(FunctionFlag.STATIC),
                        listOf(ValueType.I32, stringType).map(FunctionValue::scalar),
                        2u,
                        BlockId.of(8u),
                        1u,
                        0u,
                        0u,
                    ),
                    Function(
                        null,
                        StringId.of(1u),
                        TypeRef.Local(TypeId.of(2u)),
                        setOf(FunctionFlag.STATIC, FunctionFlag.SUSPENDING),
                        emptyList(),
                        0u,
                        BlockId.of(9u),
                        1u,
                        0u,
                        0u,
                    ),
                ),
            blocks =
                listOf(
                    Block(
                        FunctionId.of(0u),
                        false,
                        listOf(
                            Instruction.Const(RegisterId.of(0u), ConstantId.of(0u)),
                            Instruction.Const(RegisterId.of(1u), ConstantId.of(3u)),
                            Instruction.Const(RegisterId.of(2u), ConstantId.of(3u)),
                            Instruction.Const(RegisterId.of(5u), ConstantId.of(1u)),
                            Instruction.Const(RegisterId.of(6u), ConstantId.of(2u)),
                            Instruction.Move(RegisterId.of(3u), RegisterId.of(1u)),
                            Instruction.AddI32(RegisterId.of(4u), RegisterId.of(0u), RegisterId.of(0u)),
                            Instruction.SubtractI32(RegisterId.of(4u), RegisterId.of(4u), RegisterId.of(0u)),
                            Instruction.Equal(
                                ScalarValueType.I32,
                                RegisterId.of(5u),
                                RegisterId.of(0u),
                                RegisterId.of(4u),
                            ),
                            Instruction.Less(
                                OrderedScalarValueType.CHAR,
                                RegisterId.of(5u),
                                RegisterId.of(6u),
                                RegisterId.of(6u),
                            ),
                            Instruction.LessOrEqual(
                                OrderedScalarValueType.I32,
                                RegisterId.of(5u),
                                RegisterId.of(0u),
                                RegisterId.of(4u),
                            ),
                            Instruction.Greater(
                                OrderedScalarValueType.I32,
                                RegisterId.of(5u),
                                RegisterId.of(0u),
                                RegisterId.of(4u),
                            ),
                            Instruction.GreaterOrEqual(
                                OrderedScalarValueType.I32,
                                RegisterId.of(5u),
                                RegisterId.of(0u),
                                RegisterId.of(4u),
                            ),
                            Instruction.StringLength(RegisterId.of(4u), RegisterId.of(1u)),
                            Instruction.StringGet(RegisterId.of(6u), RegisterId.of(1u), RegisterId.of(0u)),
                            Instruction.StringEquals(RegisterId.of(5u), RegisterId.of(1u), RegisterId.of(2u)),
                            Instruction.CapabilityCallSync(
                                Destination.Unit,
                                CapabilityId.of(0u),
                                0u,
                                listOf(RegisterId.of(3u)),
                            ),
                            Instruction.Jump(BlockId.of(1u)),
                        ),
                    ),
                    Block(
                        FunctionId.of(0u),
                        false,
                        listOf(
                            Instruction.NewArray(RegisterId.of(7u), TypeRef.Local(TypeId.of(3u)), RegisterId.of(0u)),
                            Instruction.ArrayLength(RegisterId.of(4u), RegisterId.of(7u)),
                            Instruction.Jump(BlockId.of(2u)),
                        ),
                    ),
                    Block(
                        FunctionId.of(0u),
                        false,
                        listOf(
                            Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)),
                            Instruction.Jump(BlockId.of(3u)),
                        ),
                    ),
                    Block(
                        FunctionId.of(0u),
                        false,
                        listOf(
                            Instruction.StringSubstring(
                                RegisterId.of(3u),
                                RegisterId.of(1u),
                                RegisterId.of(0u),
                                RegisterId.of(4u),
                            ),
                            Instruction.Jump(BlockId.of(4u)),
                        ),
                    ),
                    Block(
                        FunctionId.of(0u),
                        false,
                        listOf(
                            Instruction.StringFromCharArray(
                                RegisterId.of(3u),
                                RegisterId.of(7u),
                                RegisterId.of(0u),
                                RegisterId.of(4u),
                            ),
                            Instruction.Jump(BlockId.of(5u)),
                        ),
                    ),
                    Block(
                        FunctionId.of(0u),
                        false,
                        listOf(
                            Instruction.Call(
                                Destination.Register(RegisterId.of(0u)),
                                FunctionRef.Local(FunctionId.of(1u)),
                                listOf(RegisterId.of(0u), RegisterId.of(3u)),
                            ),
                            Instruction.CallSuspend(Destination.Unit, FunctionRef.Local(FunctionId.of(2u)), emptyList(), BlockId.of(6u)),
                        ),
                    ),
                    Block(
                        FunctionId.of(0u),
                        false,
                        listOf(
                            Instruction.CapabilityCallAsync(
                                Destination.Unit,
                                CapabilityId.of(0u),
                                0u,
                                listOf(RegisterId.of(3u)),
                                BlockId.of(7u),
                            ),
                        ),
                    ),
                    Block(FunctionId.of(0u), false, listOf(Instruction.Return(Destination.Unit))),
                    Block(FunctionId.of(1u), false, listOf(Instruction.Return(Destination.Register(RegisterId.of(0u))))),
                    Block(FunctionId.of(2u), false, listOf(Instruction.Return(Destination.Unit))),
                ),
        )
    return Artifact(
        semanticFeatures = setOf(SemanticFeature.COROUTINES, SemanticFeature.CAPABILITIES, SemanticFeature.MODULE_IMPORTS),
        manifest =
            Manifest(
                requiredHeapBytes = 0u,
                requiredStackBytes = 0u,
                maximumCoroutines = 1u,
                maximumCallDepth = 2u,
                maximumHostRequests = 1u,
                maximumEvents = 0u,
                maximumBlockCost = 32u,
                minimumSliceCost = 32u,
                compilerAbi = ByteArray(32),
                platformAbi = ByteArray(32),
            ),
        entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
        modules = listOf(app, library),
        capabilities = listOf(Capability(StringId.of(3u), StringId.of(5u), AbiVersion(1u, 0u), true, 1u)),
    )
}
