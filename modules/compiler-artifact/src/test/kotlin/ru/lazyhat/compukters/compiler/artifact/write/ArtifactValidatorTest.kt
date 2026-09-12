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
import ru.lazyhat.compukters.compiler.artifact.model.Capability
import ru.lazyhat.compukters.compiler.artifact.model.CapabilityId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryArguments
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.ExceptionEntry
import ru.lazyhat.compukters.compiler.artifact.model.Export
import ru.lazyhat.compukters.compiler.artifact.model.ExportVisibility
import ru.lazyhat.compukters.compiler.artifact.model.Field
import ru.lazyhat.compukters.compiler.artifact.model.FieldId
import ru.lazyhat.compukters.compiler.artifact.model.FieldRef
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
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.StringValueType
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueComponent
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun exactRoots(artifact: Artifact): Artifact = ReferenceLiveness.derive(artifact)

class ArtifactValidatorTest {
    @Test
    fun `task instructions require runtime ABI 1 1`() {
        val source = minimalArtifact()
        val module = source.modules.single()
        val taskArtifact =
            exactRoots(
                source.copy(
                    manifest = Manifest.minimal(maximumBlockCost = 5u),
                    semanticFeatures = setOf(SemanticFeature.COROUTINES),
                    modules =
                        listOf(
                            module.copy(
                                types =
                                    listOf(
                                        (module.types.single() as NominalType.Function).copy(suspending = true),
                                    ),
                                functions =
                                    listOf(
                                        module.functions.single().copy(
                                            flags = setOf(FunctionFlag.STATIC, FunctionFlag.SUSPENDING),
                                            values = listOf(FunctionValue.scalar(ValueType.I32)),
                                            blockCount = 2u,
                                        ),
                                    ),
                                blocks =
                                    listOf(
                                        Block(
                                            owner = FunctionId.of(0u),
                                            loopHeaderSafepoint = false,
                                            instructions =
                                                listOf(
                                                    Instruction.Const(RegisterId.of(0u), ConstantId.of(0u)),
                                                    Instruction.TaskJoin(
                                                        Destination.Unit,
                                                        RegisterId.of(0u),
                                                        BlockId.of(1u),
                                                    ),
                                                ),
                                        ),
                                        Block(
                                            owner = FunctionId.of(0u),
                                            loopHeaderSafepoint = false,
                                            instructions = listOf(Instruction.Return(Destination.Unit)),
                                        ),
                                    ),
                                constants = listOf(Constant.I32(1)),
                            ),
                        ),
                ),
            )

        val oldAbiErrors = validateArtifact(taskArtifact, ArtifactWriteLimits())
        assertTrue(oldAbiErrors.any { it.detail.contains("runtime ABI 1.1") }, oldAbiErrors.toString())

        val currentErrors =
            validateArtifact(taskArtifact.copy(minimumRuntimeAbi = AbiVersion(1u, 1u)), ArtifactWriteLimits())
        assertTrue(currentErrors.isEmpty(), currentErrors.toString())
    }

    @Test
    fun `functions require exact safepoint roots`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val missing = source.copy(modules = listOf(module.copy(functions = module.functions.map { it.copy(safepointRoots = emptyList()) })))
        val errors = validateArtifact(missing, ArtifactWriteLimits())

        assertTrue(errors.any { it.detail.contains("safepoint roots") }, errors.toString())
    }

    @Test
    fun `safepoint roots reject duplicate boundaries invalid components and non canonical maps`() {
        val source = exactRoots(languageRuntimeArtifact())
        val module = source.modules.single()
        val function = module.functions.single()
        val root = function.safepointRoots.first { it.references.isNotEmpty() }
        val invalidRoot =
            root.copy(
                references =
                    listOf(
                        ValueComponent(RegisterId.of(2u), 0u),
                        ValueComponent(RegisterId.of(2u), 0u),
                    ),
            )
        val invalid =
            source.copy(
                modules =
                    listOf(
                        module.copy(
                            functions =
                                listOf(
                                    function.copy(
                                        safepointRoots =
                                            function.safepointRoots
                                                .filterNot {
                                                    it.block == root.block && it.instructionBoundary == root.instructionBoundary
                                                } +
                                                invalidRoot +
                                                root,
                                    ),
                                ),
                        ),
                    ),
            )

        val errors = validateArtifact(invalid, ArtifactWriteLimits())

        assertTrue(errors.any { it.detail.contains("duplicate instruction boundary") }, errors.toString())
        assertTrue(errors.any { it.detail.contains("does not identify a Ref32") }, errors.toString())
        assertTrue(errors.any { it.detail.contains("duplicate reference") }, errors.toString())
        assertTrue(errors.any { it.detail.contains("exact canonical") }, errors.toString())
    }

    @Test
    fun `class initializer must resolve to a static owned non suspending unit function`() {
        val artifact = classInitializerArtifact()
        assertEquals(emptyList(), validateArtifact(exactRoots(artifact), ArtifactWriteLimits()))

        fun errors(transform: (Module) -> Module): List<ArtifactWriteError> =
            validateArtifact(
                artifact.copy(modules = listOf(transform(artifact.modules.single()))),
                ArtifactWriteLimits(),
            )

        assertTrue(
            errors { module ->
                module.copy(
                    types =
                        module.types.toMutableList().also { types ->
                            types[1] = (types[1] as NominalType.Class).copy(initializer = FunctionId.of(99u))
                        },
                )
            }.any { it.code == ArtifactWriteErrorCode.BAD_REFERENCE && it.detail.contains("class initializer") },
        )
        assertTrue(
            errors { module ->
                module.copy(
                    functions = module.functions.toMutableList().also { functions -> functions[1] = functions[1].copy(owner = null) },
                )
            }.any { it.code == ArtifactWriteErrorCode.BAD_REFERENCE && it.detail.contains("class initializer owner") },
        )
        assertTrue(
            errors { module ->
                module.copy(
                    functions =
                        module.functions.toMutableList().also { functions ->
                            functions[1] = functions[1].copy(flags = emptySet())
                        },
                )
            }.any { it.code == ArtifactWriteErrorCode.INVALID_RANGE && it.detail.contains("class initializer signature") },
        )
        assertTrue(
            errors { module ->
                module.copy(
                    types =
                        module.types +
                            NominalType.Function(StringId.of(1u), suspending = true, result = ValueType.Unit, parameters = emptyList()),
                    functions =
                        module.functions.toMutableList().also { functions ->
                            functions[1] =
                                functions[1].copy(
                                    signature = TypeRef.Local(TypeId.of(2u)),
                                    flags = setOf(FunctionFlag.STATIC, FunctionFlag.SUSPENDING),
                                )
                        },
                )
            }.any { it.code == ArtifactWriteErrorCode.INVALID_RANGE && it.detail.contains("class initializer signature") },
        )
        val async =
            artifact.copy(
                semanticFeatures = setOf(SemanticFeature.CAPABILITIES),
                manifest = Manifest.minimal(maximumBlockCost = 16u, minimumSliceCost = 16u),
                capabilities = listOf(Capability(StringId.of(0u), StringId.of(1u), AbiVersion(1u, 0u), true, 1u)),
                modules =
                    listOf(
                        artifact.modules.single().let { module ->
                            module.copy(
                                functions =
                                    module.functions.toMutableList().also { functions ->
                                        functions[1] = functions[1].copy(blockCount = 2u)
                                    },
                                blocks =
                                    listOf(
                                        module.blocks[0],
                                        Block(
                                            FunctionId.of(1u),
                                            false,
                                            listOf(
                                                Instruction.CapabilityCallAsync(
                                                    Destination.Unit,
                                                    CapabilityId.of(0u),
                                                    0u,
                                                    emptyList(),
                                                    BlockId.of(2u),
                                                ),
                                            ),
                                        ),
                                        Block(FunctionId.of(1u), false, listOf(Instruction.Return(Destination.Unit))),
                                    ),
                            )
                        },
                    ),
            )
        assertTrue(
            validateArtifact(async, ArtifactWriteLimits()).any {
                it.code == ArtifactWriteErrorCode.INVALID_RANGE && it.detail.contains("class initializer cannot suspend")
            },
        )
    }

    @Test
    fun `field instructions require resolving references with matching storage kind and mutability`() {
        val artifact = fieldInstructionArtifact()

        assertEquals(emptyList(), validateArtifact(exactRoots(artifact), ArtifactWriteLimits()))

        fun errors(replacement: Instruction): List<ArtifactWriteError> =
            validateArtifact(artifact.withFieldInstruction(2, replacement), ArtifactWriteLimits())

        assertTrue(
            errors(Instruction.FieldGet(RegisterId.of(2u), RegisterId.of(0u), FieldRef.Local(FieldId.of(1u))))
                .any { it.detail.contains("instance field") },
        )
        assertTrue(
            errors(Instruction.FieldGet(RegisterId.of(2u), RegisterId.of(0u), FieldRef.Local(FieldId.of(2u))))
                .any { it.detail.contains("does not resolve") },
        )

        val immutable =
            artifact.copy(
                modules =
                    listOf(
                        artifact.modules.single().let { module ->
                            module.copy(fields = module.fields.toMutableList().also { it[0] = it[0].copy(mutable = false) })
                        },
                    ),
            )
        assertTrue(
            validateArtifact(immutable, ArtifactWriteLimits()).any { it.detail.contains("mutable instance field") },
        )
    }

    @Test
    fun `field instructions require compatible receiver value and destination types`() {
        val artifact = fieldInstructionArtifact()

        fun errors(
            index: Int,
            replacement: Instruction,
        ): List<ArtifactWriteError> = validateArtifact(artifact.withFieldInstruction(index, replacement), ArtifactWriteLimits())

        assertTrue(
            errors(2, Instruction.FieldGet(RegisterId.of(2u), RegisterId.of(1u), FieldRef.Local(FieldId.of(0u))))
                .any { it.detail.contains("receiver") },
        )
        assertTrue(
            errors(1, Instruction.FieldSet(RegisterId.of(0u), FieldRef.Local(FieldId.of(0u)), RegisterId.of(0u)))
                .any { it.detail.contains("store value") },
        )
        assertTrue(
            errors(2, Instruction.FieldGet(RegisterId.of(0u), RegisterId.of(0u), FieldRef.Local(FieldId.of(0u))))
                .any { it.detail.contains("destination") },
        )
    }

    @Test
    fun `static field instructions require mutable static storage and compatible values`() {
        val artifact = fieldInstructionArtifact()

        assertTrue(
            validateArtifact(
                artifact.withFieldInstruction(
                    5,
                    Instruction.StaticGet(RegisterId.of(3u), FieldRef.Local(FieldId.of(0u))),
                ),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("static field") },
        )
        assertTrue(
            validateArtifact(
                artifact.withFieldInstruction(
                    4,
                    Instruction.StaticSet(FieldRef.Local(FieldId.of(1u)), RegisterId.of(0u)),
                ),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("store") },
        )
        assertTrue(
            validateArtifact(
                artifact.withFieldInstruction(
                    5,
                    Instruction.StaticGet(RegisterId.of(0u), FieldRef.Local(FieldId.of(1u))),
                ),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("destination") },
        )

        val immutable =
            artifact.copy(
                modules =
                    listOf(
                        artifact.modules.single().let { module ->
                            module.copy(fields = module.fields.toMutableList().also { it[1] = it[1].copy(mutable = false) })
                        },
                    ),
            )
        assertTrue(
            validateArtifact(immutable, ArtifactWriteLimits()).any { it.detail.contains("mutable static field") },
        )
    }

    @Test
    fun `reference identity comparisons require Bool destination and compatible references`() {
        val artifact =
            fieldInstructionArtifact()
                .withFieldRegisterType(2, ValueType.Bool)
                .withFieldInstruction(
                    3,
                    Instruction.RefEqual(RegisterId.of(2u), RegisterId.of(0u), RegisterId.of(0u)),
                )

        assertEquals(emptyList(), validateArtifact(exactRoots(artifact), ArtifactWriteLimits()))
        assertTrue(
            validateArtifact(
                artifact.withFieldInstruction(
                    3,
                    Instruction.RefEqual(RegisterId.of(2u), RegisterId.of(0u), RegisterId.of(1u)),
                ),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("reference") },
        )
        assertTrue(
            validateArtifact(
                artifact.withFieldInstruction(
                    3,
                    Instruction.RefNotEqual(RegisterId.of(0u), RegisterId.of(0u), RegisterId.of(0u)),
                ),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("Bool") },
        )
    }

    @Test
    fun `type tests and checked casts validate reference and narrowed destination types`() {
        val box = ValueType.Ref(nullable = false, type = TypeRef.Local(TypeId.of(0u)))
        val cast =
            fieldInstructionArtifact()
                .withFieldRegisterType(2, box)
                .withFieldInstruction(
                    3,
                    Instruction.CheckedCast(RegisterId.of(2u), RegisterId.of(0u), TypeRef.Local(TypeId.of(0u))),
                )
        val typeTest =
            fieldInstructionArtifact()
                .withFieldRegisterType(2, ValueType.Bool)
                .withFieldInstruction(
                    3,
                    Instruction.IsType(RegisterId.of(2u), RegisterId.of(0u), TypeRef.Local(TypeId.of(0u))),
                )

        assertEquals(emptyList(), validateArtifact(exactRoots(cast), ArtifactWriteLimits()))
        assertEquals(emptyList(), validateArtifact(exactRoots(typeTest), ArtifactWriteLimits()))
        assertTrue(
            validateArtifact(
                cast.withFieldInstruction(
                    3,
                    Instruction.CheckedCast(RegisterId.of(2u), RegisterId.of(1u), TypeRef.Local(TypeId.of(0u))),
                ),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("reference") },
        )
        assertTrue(
            validateArtifact(
                fieldInstructionArtifact().withFieldInstruction(
                    3,
                    Instruction.CheckedCast(RegisterId.of(2u), RegisterId.of(0u), TypeRef.Local(TypeId.of(0u))),
                ),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("target reference type") },
        )
    }

    @Test
    fun `entry arguments must match the exact entry function signature`() {
        val noArguments = minimalArtifact()
        val stringArguments = stringArrayEntryArtifact()

        assertEquals(emptyList(), validateArtifact(exactRoots(noArguments), ArtifactWriteLimits()))
        assertEquals(emptyList(), validateArtifact(exactRoots(stringArguments), ArtifactWriteLimits()))

        val missingArgumentContract =
            validateArtifact(
                stringArguments.copy(entry = stringArguments.entry.copy(arguments = EntryArguments.NONE)),
                ArtifactWriteLimits(),
            )
        val unexpectedArgumentContract =
            validateArtifact(
                noArguments.copy(entry = noArguments.entry.copy(arguments = EntryArguments.STRING_ARRAY)),
                ArtifactWriteLimits(),
            )

        assertTrue(missingArgumentContract.any { it.detail.contains("entry argument contract") })
        assertTrue(unexpectedArgumentContract.any { it.detail.contains("entry argument contract") })
    }

    @Test
    fun `invalid entry produces a stable bounded diagnostic without bytes`() {
        val artifact =
            Artifact(
                manifest = Manifest.minimal(),
                entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
                modules = emptyList(),
            )

        val errors = validateArtifact(artifact, ArtifactWriteLimits(diagnostics = 1))

        assertEquals(1, errors.size)
        assertEquals(ArtifactWriteErrorCode.BAD_REFERENCE, errors.single().code)
        assertTrue(errors.single().detail.contains("entry module"))
    }

    @Test
    fun `missing block terminator is rejected at its logical location`() {
        val artifact = minimalArtifact(instructions = emptyList())

        val error = validateArtifact(artifact, ArtifactWriteLimits()).first { it.detail.contains("terminator") }

        assertEquals(ArtifactWriteErrorCode.INCONSISTENT_RANGE, error.code)
        assertEquals(0u, error.location?.module)
        assertEquals(0u, error.location?.record)
    }

    @Test
    fun `block and output limits are checked before encoding`() {
        val errors = validateArtifact(minimalArtifact(), ArtifactWriteLimits(blocks = 0, artifactBytes = 100))
        assertTrue(errors.any { it.code == ArtifactWriteErrorCode.LIMIT_EXCEEDED && it.detail.contains("blocks") })
    }

    @Test
    fun `semantic feature bits exactly match tables functions and instructions`() {
        val artifact =
            executableArtifact(
                Instruction.CapabilityCallAsync(Destination.Unit, CapabilityId.of(0u), 0u, emptyList(), BlockId.of(1u)),
            )
        val expected = setOf(SemanticFeature.COROUTINES, SemanticFeature.CAPABILITIES, SemanticFeature.MODULE_IMPORTS)

        assertEquals(emptyList(), validateArtifact(exactRoots(artifact.copy(semanticFeatures = expected)), ArtifactWriteLimits()))
        assertTrue(
            validateArtifact(
                artifact.copy(semanticFeatures = expected - SemanticFeature.CAPABILITIES),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("semantic feature") },
        )
        assertTrue(
            validateArtifact(
                artifact.copy(semanticFeatures = expected + SemanticFeature.EXCEPTIONS),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("semantic feature") },
        )
    }

    @Test
    fun `imports require the exact target module semantic hash`() {
        val artifact =
            executableArtifact(
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Imported(ImportId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ),
            )
        val source = artifact.modules[0]
        val stale =
            artifact.copy(
                modules =
                    listOf(
                        source.copy(
                            imports = source.imports.map { it.copy(targetModuleHash = ByteArray(32)) },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(exactRoots(artifact), ArtifactWriteLimits()).none { it.detail.contains("semantic hash") })
        assertTrue(validateArtifact(exactRoots(stale), ArtifactWriteLimits()).any { it.detail.contains("semantic hash") })
    }

    @Test
    fun `block fixed cost is bounded by the manifest before serialization`() {
        val artifact =
            executableArtifact(
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ),
            )

        assertTrue(
            validateArtifact(
                artifact.copy(manifest = Manifest.minimal(maximumBlockCost = 7u)),
                ArtifactWriteLimits(),
            ).none { it.detail.contains("fixed cost") },
        )
        assertTrue(
            validateArtifact(
                artifact.copy(manifest = Manifest.minimal(maximumBlockCost = 6u)),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("fixed cost") },
        )
    }

    @Test
    fun `entry and function block metadata match pinned CFG rules`() {
        val artifact = executableArtifact(Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)))
        val module = artifact.modules[0]
        val badEntry = artifact.copy(entry = EntryPoint(ModuleId.of(0u), FunctionId.of(9u)))
        val zeroBlocks =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions = module.functions.toMutableList().also { it[1] = it[1].copy(blockCount = 0u) },
                        ),
                        artifact.modules[1],
                    ),
            )
        val abstractWithBlocks =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions =
                                module.functions.toMutableList().also {
                                    it[1] = it[1].copy(flags = it[1].flags + FunctionFlag.ABSTRACT)
                                },
                        ),
                        artifact.modules[1],
                    ),
            )
        val overlapping =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions =
                                module.functions.toMutableList().also {
                                    it[1] = it[1].copy(firstBlock = BlockId.of(1u), blockCount = 2u)
                                },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(badEntry, ArtifactWriteLimits()).any { it.detail.contains("entry function") })
        assertTrue(validateArtifact(zeroBlocks, ArtifactWriteLimits()).any { it.detail.contains("non-abstract function has no blocks") })
        assertTrue(validateArtifact(abstractWithBlocks, ArtifactWriteLimits()).any { it.detail.contains("abstract function") })
        assertTrue(validateArtifact(overlapping, ArtifactWriteLimits()).any { it.detail.contains("contiguous") })
    }

    @Test
    fun `valid executable instructions pass semantic validation`() {
        val cases =
            listOf(
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ),
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Imported(ImportId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ),
                Instruction.CallSuspend(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                    BlockId.of(1u),
                ),
                Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)),
                Instruction.Move(RegisterId.of(2u), RegisterId.of(1u)),
                Instruction.AddI32(RegisterId.of(0u), RegisterId.of(0u), RegisterId.of(0u)),
                Instruction.StringLength(RegisterId.of(0u), RegisterId.of(1u)),
                Instruction.StringSubstring(
                    RegisterId.of(3u),
                    RegisterId.of(1u),
                    RegisterId.of(0u),
                    RegisterId.of(0u),
                ),
                Instruction.CapabilityCallSync(
                    Destination.Unit,
                    CapabilityId.of(0u),
                    1u,
                    listOf(RegisterId.of(0u)),
                ),
                Instruction.CapabilityCallAsync(
                    Destination.Unit,
                    CapabilityId.of(0u),
                    1u,
                    listOf(RegisterId.of(0u)),
                    BlockId.of(1u),
                ),
            )

        cases.forEach { instruction ->
            assertEquals(
                emptyList(),
                validateArtifact(exactRoots(executableArtifact(instruction)), ArtifactWriteLimits()),
                instruction.toString(),
            )
        }
    }

    @Test
    fun `new typed instructions reject mismatched registers and capability operations`() {
        val cases =
            listOf(
                Instruction.Move(RegisterId.of(0u), RegisterId.of(1u)) to "move source and destination types differ",
                Instruction.AddI32(RegisterId.of(1u), RegisterId.of(0u), RegisterId.of(0u)) to "I32 add register",
                Instruction.MultiplyI32(RegisterId.of(1u), RegisterId.of(0u), RegisterId.of(0u)) to "I32 multiply register",
                Instruction.DivideI32(RegisterId.of(1u), RegisterId.of(0u), RegisterId.of(0u)) to "I32 divide register",
                Instruction.RemainderI32(RegisterId.of(1u), RegisterId.of(0u), RegisterId.of(0u)) to "I32 remainder register",
                Instruction.BitAndI32(RegisterId.of(1u), RegisterId.of(0u), RegisterId.of(0u)) to "I32 bit-and register",
                Instruction.ShiftUnsignedI32(RegisterId.of(1u), RegisterId.of(0u), RegisterId.of(0u)) to
                    "I32 shift-unsigned register",
                Instruction.StringLength(RegisterId.of(1u), RegisterId.of(1u)) to "string length destination",
                Instruction.CapabilityCallSync(Destination.Unit, CapabilityId.of(0u), 2u, emptyList()) to
                    "capability operation",
            )

        cases.forEach { (instruction, expected) ->
            val errors = validateArtifact(executableArtifact(instruction), ArtifactWriteLimits())
            assertTrue(errors.any { it.detail.contains(expected) }, "$instruction: $errors")
        }
    }

    @Test
    fun `instruction registers are validated against the owning function table`() {
        val bad = RegisterId.of(9u)
        val cases =
            listOf(
                Instruction.Call(
                    Destination.Register(bad),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ) to
                    "destination register",
                Instruction.StringConcat(RegisterId.of(3u), bad, RegisterId.of(2u)) to "source register",
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(bad, RegisterId.of(1u)),
                ) to
                    "argument register",
            )

        cases.forEach { (instruction, expected) ->
            val errors = validateArtifact(executableArtifact(instruction), ArtifactWriteLimits())
            assertTrue(errors.any { it.code == ArtifactWriteErrorCode.BAD_REFERENCE && it.detail.contains(expected) }, errors.toString())
        }
    }

    @Test
    fun `direct calls validate local and imported function references`() {
        val badLocal =
            executableArtifact(
                Instruction.Call(Destination.Unit, FunctionRef.Local(FunctionId.of(9u)), emptyList()),
            )
        val badImport =
            executableArtifact(
                Instruction.Call(Destination.Unit, FunctionRef.Imported(ImportId.of(0u)), emptyList()),
            )

        assertTrue(validateArtifact(badLocal, ArtifactWriteLimits()).any { it.detail.contains("local function reference") })
        assertTrue(validateArtifact(badImport, ArtifactWriteLimits()).any { it.detail.contains("imported function reference") })
    }

    @Test
    fun `imported calls resolve the unique export matching the expected signature`() {
        val call =
            Instruction.Call(
                Destination.Register(RegisterId.of(0u)),
                FunctionRef.Imported(ImportId.of(1u)),
                listOf(RegisterId.of(0u), RegisterId.of(1u)),
            )
        val artifact = executableArtifact(call)
        val module = artifact.modules[0]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            imports =
                                module.imports.toMutableList().also {
                                    it[1] = it[1].copy(expectedSignature = TypeRef.Local(TypeId.of(0u)))
                                },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("imported function reference") })
    }

    @Test
    fun `imported fields require the expected owner signature`() {
        val artifact = importedFieldArtifact()

        assertEquals(emptyList(), validateArtifact(exactRoots(artifact), ArtifactWriteLimits()))

        val application = artifact.modules[0]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        application.copy(
                            imports =
                                application.imports.toMutableList().also {
                                    it[4] = it[4].copy(expectedSignature = TypeRef.Imported(ImportId.of(2u)))
                                },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("field reference") })
    }

    @Test
    fun `direct calls validate signature arity argument types and result destination`() {
        val cases =
            listOf(
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u)),
                ) to "arity",
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(1u), RegisterId.of(0u)),
                ) to "argument type",
                Instruction.Call(
                    Destination.Register(RegisterId.of(1u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ) to "result destination",
                Instruction.Call(
                    Destination.Unit,
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ) to "result destination",
            )

        cases.forEach { (instruction, expected) ->
            val errors = validateArtifact(executableArtifact(instruction), ArtifactWriteLimits())
            assertTrue(errors.any { it.code == ArtifactWriteErrorCode.INVALID_RANGE && it.detail.contains(expected) }, errors.toString())
        }
    }

    @Test
    fun `capability async validates descriptor operation and registers`() {
        val cases =
            listOf(
                Instruction.CapabilityCallAsync(Destination.Unit, CapabilityId.of(1u), 0u, emptyList(), BlockId.of(1u)) to
                    "capability id",
                Instruction.CapabilityCallAsync(Destination.Unit, CapabilityId.of(0u), 2u, emptyList(), BlockId.of(1u)) to
                    "operation",
                Instruction.CapabilityCallAsync(
                    Destination.Register(RegisterId.of(9u)),
                    CapabilityId.of(0u),
                    0u,
                    emptyList(),
                    BlockId.of(1u),
                ) to "destination register",
                Instruction.CapabilityCallAsync(
                    Destination.Unit,
                    CapabilityId.of(0u),
                    0u,
                    listOf(RegisterId.of(9u)),
                    BlockId.of(1u),
                ) to "argument register",
            )

        cases.forEach { (instruction, expected) ->
            val errors = validateArtifact(executableArtifact(instruction), ArtifactWriteLimits())
            assertTrue(errors.any { it.detail.contains(expected) }, errors.toString())
        }
    }

    @Test
    fun `string concat requires string operands and destination`() {
        val cases =
            listOf(
                Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(0u), RegisterId.of(2u)),
                Instruction.StringConcat(RegisterId.of(0u), RegisterId.of(1u), RegisterId.of(2u)),
            )

        cases.forEach { instruction ->
            val errors = validateArtifact(executableArtifact(instruction), ArtifactWriteLimits())
            assertTrue(
                errors.any { it.code == ArtifactWriteErrorCode.INVALID_RANGE && it.detail.contains("kotlin.String") },
                errors.toString(),
            )
        }
    }

    @Test
    fun `scalar string conversion requires its declared source and string destination`() {
        val valid =
            executableArtifact(
                Instruction.StringValueOf(
                    StringValueType.I32,
                    RegisterId.of(3u),
                    RegisterId.of(0u),
                ),
            )
        assertEquals(emptyList(), validateArtifact(exactRoots(valid), ArtifactWriteLimits()))

        val wrongSource =
            executableArtifact(
                Instruction.StringValueOf(
                    StringValueType.BOOL,
                    RegisterId.of(3u),
                    RegisterId.of(0u),
                ),
            )
        assertTrue(
            validateArtifact(wrongSource, ArtifactWriteLimits()).any { it.detail.contains("string conversion source") },
        )

        val wrongDestination =
            executableArtifact(
                Instruction.StringValueOf(
                    StringValueType.I32,
                    RegisterId.of(0u),
                    RegisterId.of(0u),
                ),
            )
        assertTrue(
            validateArtifact(wrongDestination, ArtifactWriteLimits()).any { it.detail.contains("kotlin.String") },
        )
    }

    @Test
    fun `string concat requires the unique canonical standard library string export`() {
        val artifact = executableArtifact(Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)))
        val invalid =
            artifact.copy(
                modules = listOf(artifact.modules[0], artifact.modules[1].copy(exports = emptyList())),
            )

        val errors = validateArtifact(invalid, ArtifactWriteLimits())

        assertTrue(errors.any { it.detail.contains("kotlin.String") }, errors.toString())
    }

    @Test
    fun `string allocation instructions obey the pinned block placement rule`() {
        listOf(
            Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)),
            Instruction.StringValueOf(StringValueType.I32, RegisterId.of(3u), RegisterId.of(0u)),
        ).forEach { instruction ->
            val artifact = executableArtifact(instruction)
            val module = artifact.modules[0]
            val invalid =
                artifact.copy(
                    modules =
                        listOf(
                            module.copy(
                                blocks =
                                    module.blocks.toMutableList().also {
                                        it[0] =
                                            it[0].copy(
                                                instructions = listOf(Instruction.Null(RegisterId.of(1u))) + it[0].instructions,
                                            )
                                    },
                            ),
                            artifact.modules[1],
                        ),
                )

            assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("allocation") })
        }
    }

    @Test
    fun `array length and char array materialization validate exact register types`() {
        val valid =
            listOf(
                charArrayArtifact(Instruction.ArrayLength(RegisterId.of(0u), RegisterId.of(4u))),
                charArrayArtifact(
                    Instruction.StringFromCharArray(
                        RegisterId.of(3u),
                        RegisterId.of(4u),
                        RegisterId.of(0u),
                        RegisterId.of(0u),
                    ),
                ),
            )
        valid.forEach { artifact ->
            assertEquals(emptyList(), validateArtifact(exactRoots(artifact), ArtifactWriteLimits()))
        }

        val wrongArray =
            executableArtifact(
                Instruction.StringFromCharArray(
                    RegisterId.of(3u),
                    RegisterId.of(1u),
                    RegisterId.of(0u),
                    RegisterId.of(0u),
                ),
            )
        assertTrue(validateArtifact(wrongArray, ArtifactWriteLimits()).any { it.detail.contains("array type") })
    }

    @Test
    fun `char array materialization reads every source before publishing its destination`() {
        val instruction =
            Instruction.StringFromCharArray(
                RegisterId.of(3u),
                RegisterId.of(4u),
                RegisterId.of(0u),
                RegisterId.of(0u),
            )
        val uninitialized = charArrayArtifact(instruction, initializeArray = false)

        assertTrue(
            validateArtifact(uninitialized, ArtifactWriteLimits()).any { it.detail.contains("uninitialized register") },
        )
    }

    @Test
    fun `char array materialization obeys the pinned allocation block placement rule`() {
        val artifact =
            charArrayArtifact(
                Instruction.StringFromCharArray(
                    RegisterId.of(3u),
                    RegisterId.of(4u),
                    RegisterId.of(0u),
                    RegisterId.of(0u),
                ),
            )
        val module = artifact.modules[0]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            blocks =
                                module.blocks.toMutableList().also {
                                    it[0] = it[0].copy(instructions = listOf(Instruction.Null(RegisterId.of(3u))) + it[0].instructions)
                                },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("allocation") })
    }

    @Test
    fun `direct calls reject inconsistent target function metadata`() {
        val call =
            Instruction.Call(
                Destination.Register(RegisterId.of(0u)),
                FunctionRef.Local(FunctionId.of(1u)),
                listOf(RegisterId.of(0u), RegisterId.of(1u)),
            )
        val artifact = executableArtifact(call)
        val module = artifact.modules[0]
        val badParameterCount =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions = module.functions.toMutableList().also { it[1] = it[1].copy(parameterCount = 1u) },
                        ),
                        artifact.modules[1],
                    ),
            )
        val signature = module.types[1] as NominalType.Function
        val badSuspendingFlag =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            types = module.types.toMutableList().also { it[1] = signature.copy(suspending = true) },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(badParameterCount, ArtifactWriteLimits()).any { it.detail.contains("target function metadata") })
        assertTrue(validateArtifact(badSuspendingFlag, ArtifactWriteLimits()).any { it.detail.contains("target function metadata") })
    }

    @Test
    fun `resume blocks stay in the owning function and suspending instructions terminate`() {
        val outside =
            Instruction.CallSuspend(
                Destination.Register(RegisterId.of(0u)),
                FunctionRef.Local(FunctionId.of(1u)),
                listOf(RegisterId.of(0u), RegisterId.of(1u)),
                BlockId.of(2u),
            )
        val followed =
            executableArtifact(
                Instruction.CallSuspend(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                    BlockId.of(1u),
                ),
                appendControlFlow = true,
            )

        assertTrue(validateArtifact(executableArtifact(outside), ArtifactWriteLimits()).any { it.detail.contains("resume block") })
        assertTrue(validateArtifact(followed, ArtifactWriteLimits()).any { it.detail.contains("terminator before") })
    }

    @Test
    fun `ordinary successor blocks stay in the owning function`() {
        val artifact = executableArtifact(Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)))
        val module = artifact.modules[0]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            blocks =
                                module.blocks.toMutableList().also {
                                    it[0] = it[0].copy(instructions = listOf(Instruction.Jump(BlockId.of(2u))))
                                },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("successor block") })
    }

    @Test
    fun `block indices must belong to their declared owner function range`() {
        val artifact = executableArtifact(Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)))
        val module = artifact.modules[0]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            blocks = module.blocks.toMutableList().also { it[0] = it[0].copy(owner = FunctionId.of(1u)) },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("owner function range") })
    }

    @Test
    fun `only Kotlin suspend calls require a suspending owning function`() {
        fun ordinaryCaller(artifact: Artifact): Artifact {
            val module = artifact.modules[0]
            val signature = module.types[0] as NominalType.Function
            return artifact.copy(
                semanticFeatures = artifact.semanticFeatures - SemanticFeature.COROUTINES,
                modules =
                    listOf(
                        module.copy(
                            types = module.types.toMutableList().also { it[0] = signature.copy(suspending = false) },
                            functions =
                                module.functions.toMutableList().also {
                                    it[0] = it[0].copy(flags = setOf(FunctionFlag.STATIC))
                                },
                        ),
                        artifact.modules[1],
                    ),
            )
        }

        val vmBlocking =
            executableArtifact(
                Instruction.CapabilityCallAsync(Destination.Unit, CapabilityId.of(0u), 0u, emptyList(), BlockId.of(1u)),
            )
        assertEquals(emptyList(), validateArtifact(exactRoots(ordinaryCaller(vmBlocking)), ArtifactWriteLimits()))

        val kotlinSuspend =
            executableArtifact(
                Instruction.CallSuspend(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                    BlockId.of(1u),
                ),
            )

        assertTrue(
            validateArtifact(ordinaryCaller(kotlinSuspend), ArtifactWriteLimits())
                .any { it.detail.contains("non-suspending function") },
        )
    }

    @Test
    fun `new instruction reads require definite assignment`() {
        val cases =
            listOf(
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ),
                Instruction.CallSuspend(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                    BlockId.of(1u),
                ),
                Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)),
                Instruction.CapabilityCallAsync(
                    Destination.Unit,
                    CapabilityId.of(0u),
                    0u,
                    listOf(RegisterId.of(0u)),
                    BlockId.of(1u),
                ),
            )

        cases.forEach { instruction ->
            val artifact = executableArtifact(instruction)
            val module = artifact.modules[0]
            val callerSignature = module.types[0] as NominalType.Function
            val invalid =
                artifact.copy(
                    modules =
                        listOf(
                            module.copy(
                                types =
                                    module.types.toMutableList().also {
                                        it[0] = callerSignature.copy(parameters = emptyList())
                                    },
                                functions =
                                    module.functions.toMutableList().also {
                                        it[0] = it[0].copy(parameterCount = 0u)
                                    },
                            ),
                            artifact.modules[1],
                        ),
                )

            assertTrue(
                validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("uninitialized register") },
                instruction.toString(),
            )
        }
    }

    @Test
    fun `definite assignment intersects predecessor states at a join`() {
        val artifact = executableArtifact(Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)))
        val module = artifact.modules[0]
        val callerSignature = module.types[0] as NominalType.Function
        val caller = module.functions[0]
        val callee = module.functions[1]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            types =
                                module.types.toMutableList().also {
                                    it[0] = callerSignature.copy(parameters = listOf(ValueType.Bool))
                                },
                            constants =
                                listOf(
                                    ru.lazyhat.compukters.compiler.artifact.model.Constant
                                        .I32(1),
                                ),
                            functions =
                                listOf(
                                    caller.copy(
                                        values = listOf(ValueType.Bool, ValueType.I32).map(FunctionValue::scalar),
                                        parameterCount = 1u,
                                        blockCount = 4u,
                                    ),
                                    callee.copy(firstBlock = BlockId.of(4u)),
                                ),
                            blocks =
                                listOf(
                                    Block(
                                        FunctionId.of(0u),
                                        false,
                                        listOf(Instruction.Branch(RegisterId.of(0u), BlockId.of(1u), BlockId.of(2u))),
                                    ),
                                    Block(
                                        FunctionId.of(0u),
                                        false,
                                        listOf(
                                            Instruction.Const(
                                                RegisterId.of(1u),
                                                ru.lazyhat.compukters.compiler.artifact.model.ConstantId
                                                    .of(0u),
                                            ),
                                            Instruction.Jump(BlockId.of(3u)),
                                        ),
                                    ),
                                    Block(FunctionId.of(0u), false, listOf(Instruction.Jump(BlockId.of(3u)))),
                                    Block(
                                        FunctionId.of(0u),
                                        false,
                                        listOf(
                                            Instruction.ArrayStore(RegisterId.of(0u), RegisterId.of(1u), RegisterId.of(1u)),
                                            Instruction.Return(Destination.Unit),
                                        ),
                                    ),
                                    module.blocks[2],
                                ),
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("uninitialized register") })
    }

    @Test
    fun `ordinary control flow cannot target an exception handler`() {
        val artifact = languageRuntimeArtifact()
        val module = artifact.modules.single()
        val protectedBlock = module.blocks[2]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            blocks =
                                module.blocks.toMutableList().also {
                                    it[2] =
                                        protectedBlock.copy(
                                            instructions =
                                                protectedBlock.instructions.dropLast(1) + Instruction.Jump(BlockId.of(4u)),
                                        )
                                },
                        ),
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("exception handler") })
    }

    @Test
    fun `dataflow uses only the function declared exception slice`() {
        val artifact = languageRuntimeArtifact()
        val module = artifact.modules.single()
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions = listOf(module.functions.single().copy(exceptionCount = 0u)),
                        ),
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("unreachable") })
    }

    @Test
    fun `exception slices are validated for abstract functions`() {
        val artifact = languageRuntimeArtifact()
        val module = artifact.modules.single()
        val abstractFunction =
            module.functions.single().copy(
                flags = setOf(FunctionFlag.ABSTRACT),
                firstBlock = BlockId.of(module.blocks.size.toUInt()),
                blockCount = 0u,
                firstException = UInt.MAX_VALUE,
                exceptionCount = 1u,
            )
        val invalid =
            artifact.copy(
                modules = listOf(module.copy(functions = module.functions + abstractFunction)),
            )

        val errors = validateArtifact(invalid, ArtifactWriteLimits())

        assertTrue(errors.any { it.detail.contains("exception range") }, errors.toString())
    }

    @Test
    fun `exception entries require compatible non-null reference types`() {
        val artifact = languageRuntimeArtifact()
        val module = artifact.modules.single()
        val function = module.functions.single()
        val exception = module.exceptions.single()

        val nullableRegister =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions =
                                listOf(
                                    function.copy(
                                        values =
                                            function.values.toMutableList().also {
                                                it[exception.exceptionRegister.value.toInt()] =
                                                    FunctionValue.scalar(ValueType.Ref(true, TypeRef.Local(TypeId.of(0u))))
                                            },
                                    ),
                                ),
                        ),
                    ),
            )
        val nonReferenceCatch =
            artifact.copy(
                modules = listOf(module.copy(exceptions = listOf(exception.copy(catchType = TypeRef.Local(TypeId.of(1u)))))),
            )
        val incompatibleCatch =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            types = module.types + NominalType.Class(name = StringId.of(0u), final = true),
                            exceptions = listOf(exception.copy(catchType = TypeRef.Local(TypeId.of(3u)))),
                        ),
                    ),
            )

        assertTrue(validateArtifact(nullableRegister, ArtifactWriteLimits()).any { it.detail.contains("non-null reference") })
        assertTrue(validateArtifact(nonReferenceCatch, ArtifactWriteLimits()).any { it.detail.contains("catch type") })
        assertTrue(validateArtifact(incompatibleCatch, ArtifactWriteLimits()).any { it.detail.contains("incompatible") })
    }

    @Test
    fun `exception protected ranges may nest but never cross`() {
        val artifact = languageRuntimeArtifact()
        val module = artifact.modules.single()
        val base = module.exceptions.single().copy(catchType = null)
        val outer = base.copy(firstProtectedBlock = BlockId.of(0u), protectedBlockCount = 3u)
        val nested = base.copy(firstProtectedBlock = BlockId.of(1u), protectedBlockCount = 1u)
        val crossing = base.copy(firstProtectedBlock = BlockId.of(2u), protectedBlockCount = 2u)

        fun withExceptions(entries: List<ExceptionEntry>) =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions = listOf(module.functions.single().copy(exceptionCount = entries.size.toUInt())),
                            exceptions = entries,
                        ),
                    ),
            )

        assertTrue(validateArtifact(withExceptions(listOf(outer, nested)), ArtifactWriteLimits()).none { it.detail.contains("cross") })
        assertTrue(validateArtifact(withExceptions(listOf(outer, crossing)), ArtifactWriteLimits()).any { it.detail.contains("cross") })
    }

    @Test
    fun `writer reports oversized parameter count instead of throwing`() {
        val artifact = languageRuntimeArtifact()
        val module = artifact.modules.single()
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions = listOf(module.functions.single().copy(parameterCount = UInt.MAX_VALUE)),
                        ),
                    ),
            )

        val result = ArtifactWriter.write(invalid)

        val failure = assertIs<ArtifactWriteResult.Failure>(result)
        assertTrue(failure.errors.any { it.detail.contains("parameter count") })
    }
}

private fun classInitializerArtifact(): Artifact {
    val source = minimalArtifact()
    val module = source.modules.single()
    val initializer = FunctionId.of(1u)
    return source.copy(
        modules =
            listOf(
                module.copy(
                    types =
                        module.types +
                            NominalType.Class(
                                StringId.of(0u),
                                final = true,
                                initializer = initializer,
                            ),
                    functions =
                        module.functions +
                            Function(
                                owner = TypeRef.Local(TypeId.of(1u)),
                                name = StringId.of(1u),
                                signature = TypeRef.Local(TypeId.of(0u)),
                                flags = setOf(FunctionFlag.STATIC),
                                values = emptyList(),
                                parameterCount = 0u,
                                firstBlock = BlockId.of(1u),
                                blockCount = 1u,
                                firstException = 0u,
                                exceptionCount = 0u,
                            ),
                    blocks = module.blocks + Block(initializer, false, listOf(Instruction.Return(Destination.Unit))),
                ),
            ),
    )
}

private fun stringArrayEntryArtifact(): Artifact {
    val stringType = TypeRef.Local(TypeId.of(0u))
    val arrayType = TypeRef.Local(TypeId.of(1u))
    val arrayValue = ValueType.Ref(nullable = false, type = arrayType)
    return Artifact(
        manifest = Manifest.minimal(),
        entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u), EntryArguments.STRING_ARRAY),
        modules =
            listOf(
                Module(
                    name = StringId.of(0u),
                    kind = ModuleKind.APPLICATION,
                    strings =
                        listOf(
                            MetadataText.of("app"),
                            MetadataText.of("entry"),
                            MetadataText.of("kotlin.Array<kotlin.String>"),
                            MetadataText.of("kotlin.String"),
                        ),
                    types =
                        listOf(
                            NominalType.Class(name = StringId.of(3u), final = true),
                            NominalType.Array(
                                name = StringId.of(2u),
                                element = ValueType.Ref(nullable = false, type = stringType),
                            ),
                            NominalType.Function(
                                name = StringId.of(1u),
                                suspending = false,
                                result = ValueType.Unit,
                                parameters = listOf(arrayValue),
                            ),
                        ),
                    functions =
                        listOf(
                            Function(
                                owner = null,
                                name = StringId.of(1u),
                                signature = TypeRef.Local(TypeId.of(2u)),
                                flags = setOf(FunctionFlag.STATIC),
                                values = listOf(FunctionValue.scalar(arrayValue)),
                                parameterCount = 1u,
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
                                instructions = listOf(Instruction.Return(Destination.Unit)),
                            ),
                        ),
                ),
            ),
    )
}

private fun fieldInstructionArtifact(): Artifact {
    val boxType = TypeRef.Local(TypeId.of(0u))
    val boxValue = ValueType.Ref(nullable = false, type = boxType)
    return Artifact(
        manifest = Manifest.minimal(maximumBlockCost = 14u),
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
                            MetadataText.of("entry"),
                            MetadataText.of("instance"),
                            MetadataText.of("static"),
                        ),
                    types =
                        listOf(
                            NominalType.Class(name = StringId.of(0u), final = true, fieldCount = 2u),
                            NominalType.Function(
                                name = StringId.of(2u),
                                suspending = false,
                                result = ValueType.Unit,
                                parameters = emptyList(),
                            ),
                        ),
                    constants = listOf(Constant.I32(7)),
                    fields =
                        listOf(
                            Field(boxType, StringId.of(3u), ValueType.I32, mutable = true, static = false),
                            Field(boxType, StringId.of(4u), ValueType.I32, mutable = true, static = true),
                        ),
                    functions =
                        listOf(
                            Function(
                                owner = null,
                                name = StringId.of(2u),
                                signature = TypeRef.Local(TypeId.of(1u)),
                                flags = setOf(FunctionFlag.STATIC),
                                values =
                                    listOf(boxValue, ValueType.I32, ValueType.I32, ValueType.I32)
                                        .map(FunctionValue::scalar),
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
                                FunctionId.of(0u),
                                false,
                                listOf(
                                    Instruction.NewObject(RegisterId.of(0u), boxType),
                                    Instruction.Const(RegisterId.of(1u), ConstantId.of(0u)),
                                    Instruction.FieldSet(
                                        RegisterId.of(0u),
                                        FieldRef.Local(FieldId.of(0u)),
                                        RegisterId.of(1u),
                                    ),
                                    Instruction.FieldGet(
                                        RegisterId.of(2u),
                                        RegisterId.of(0u),
                                        FieldRef.Local(FieldId.of(0u)),
                                    ),
                                    Instruction.StaticSet(FieldRef.Local(FieldId.of(1u)), RegisterId.of(1u)),
                                    Instruction.StaticGet(RegisterId.of(3u), FieldRef.Local(FieldId.of(1u))),
                                    Instruction.Return(Destination.Unit),
                                ),
                            ),
                        ),
                ),
            ),
    )
}

private fun Artifact.withFieldInstruction(
    index: Int,
    replacement: Instruction,
): Artifact =
    copy(
        modules =
            listOf(
                modules.single().let { module ->
                    module.copy(
                        blocks =
                            listOf(
                                module.blocks.single().let { block ->
                                    block.copy(instructions = block.instructions.toMutableList().also { it[index] = replacement })
                                },
                            ),
                    )
                },
            ),
    )

private fun Artifact.withFieldRegisterType(
    index: Int,
    type: ValueType,
): Artifact =
    copy(
        modules =
            listOf(
                modules.single().let { module ->
                    module.copy(
                        functions =
                            listOf(
                                module.functions.single().let { function ->
                                    function.copy(
                                        values =
                                            function.values.toMutableList().also {
                                                it[index] = FunctionValue.scalar(type)
                                            },
                                    )
                                },
                            ),
                    )
                },
            ),
    )

private fun executableArtifact(
    instruction: Instruction,
    appendControlFlow: Boolean = false,
): Artifact {
    val isSuspending = instruction is Instruction.CallSuspend || instruction is Instruction.CapabilityCallAsync
    val firstInstructions =
        when {
            appendControlFlow -> listOf(instruction, Instruction.Jump(BlockId.of(1u)))
            isSuspending -> listOf(instruction)
            else -> listOf(instruction, Instruction.Jump(BlockId.of(1u)))
        }
    val strings = listOf("app", "callee", "entry", "kotlin.String", "testEntry").map(MetadataText::of)
    val stringType = ValueType.Ref(nullable = false, TypeRef.Imported(ImportId.of(0u)))
    val calleeSuspending = instruction is Instruction.CallSuspend
    val artifact =
        Artifact(
            semanticFeatures = setOf(SemanticFeature.COROUTINES, SemanticFeature.CAPABILITIES, SemanticFeature.MODULE_IMPORTS),
            manifest = Manifest.minimal(maximumBlockCost = 16u),
            entry = EntryPoint(ModuleId.of(0u), FunctionId.of(2u)),
            modules =
                listOf(
                    Module(
                        name = StringId.of(0u),
                        kind = ModuleKind.APPLICATION,
                        strings = strings,
                        types =
                            listOf(
                                NominalType.Function(
                                    StringId.of(2u),
                                    true,
                                    ValueType.Unit,
                                    listOf(ValueType.I32, stringType, stringType, stringType),
                                ),
                                NominalType.Function(StringId.of(1u), calleeSuspending, ValueType.I32, listOf(ValueType.I32, stringType)),
                                NominalType.Function(StringId.of(4u), false, ValueType.Unit, emptyList()),
                            ),
                        imports =
                            listOf(
                                Import(
                                    SymbolKind.TYPE,
                                    ModuleId.of(1u),
                                    StringId.of(3u),
                                    TypeRef.Imported(ImportId.of(0u)),
                                    ByteArray(32),
                                ),
                                Import(
                                    SymbolKind.FUNCTION,
                                    ModuleId.of(1u),
                                    StringId.of(1u),
                                    TypeRef.Local(TypeId.of(1u)),
                                    ByteArray(32),
                                ),
                            ),
                        functions =
                            listOf(
                                Function(
                                    null,
                                    StringId.of(2u),
                                    TypeRef.Local(TypeId.of(0u)),
                                    setOf(FunctionFlag.STATIC, FunctionFlag.SUSPENDING),
                                    listOf(ValueType.I32, stringType, stringType, stringType).map(FunctionValue::scalar),
                                    4u,
                                    BlockId.of(0u),
                                    2u,
                                    0u,
                                    0u,
                                ),
                                Function(
                                    null,
                                    StringId.of(1u),
                                    TypeRef.Local(TypeId.of(1u)),
                                    setOfNotNull(FunctionFlag.STATIC, FunctionFlag.SUSPENDING.takeIf { calleeSuspending }),
                                    listOf(ValueType.I32, stringType).map(FunctionValue::scalar),
                                    2u,
                                    BlockId.of(2u),
                                    1u,
                                    0u,
                                    0u,
                                ),
                                Function(
                                    null,
                                    StringId.of(4u),
                                    TypeRef.Local(TypeId.of(2u)),
                                    setOf(FunctionFlag.STATIC),
                                    emptyList(),
                                    0u,
                                    BlockId.of(3u),
                                    1u,
                                    0u,
                                    0u,
                                ),
                            ),
                        blocks =
                            listOf(
                                Block(FunctionId.of(0u), false, firstInstructions),
                                Block(FunctionId.of(0u), false, listOf(Instruction.Return(Destination.Unit))),
                                Block(FunctionId.of(1u), false, listOf(Instruction.Return(Destination.Register(RegisterId.of(0u))))),
                                Block(FunctionId.of(2u), false, listOf(Instruction.Return(Destination.Unit))),
                            ),
                    ),
                    Module(
                        name = StringId.of(0u),
                        kind = ModuleKind.LIBRARY,
                        strings = listOf(MetadataText.of("callee"), MetadataText.of("kotlin.String")),
                        types =
                            listOf(
                                NominalType.Class(name = StringId.of(1u), final = true),
                                NominalType.Function(
                                    StringId.of(0u),
                                    false,
                                    ValueType.I32,
                                    listOf(ValueType.I32, ValueType.Ref(false, TypeRef.Local(TypeId.of(0u)))),
                                ),
                            ),
                        exports =
                            listOf(
                                Export(
                                    SymbolKind.TYPE,
                                    ExportVisibility.PUBLIC_LIBRARY,
                                    StringId.of(1u),
                                    0u,
                                    TypeRef.Local(TypeId.of(0u)),
                                ),
                                Export(
                                    SymbolKind.FUNCTION,
                                    ExportVisibility.PUBLIC_LIBRARY,
                                    StringId.of(0u),
                                    0u,
                                    TypeRef.Local(TypeId.of(1u)),
                                ),
                            ),
                        functions =
                            listOf(
                                Function(
                                    null,
                                    StringId.of(0u),
                                    TypeRef.Local(TypeId.of(1u)),
                                    setOf(FunctionFlag.STATIC),
                                    listOf(ValueType.I32, ValueType.Ref(false, TypeRef.Local(TypeId.of(0u))))
                                        .map(FunctionValue::scalar),
                                    2u,
                                    BlockId.of(0u),
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
                                    listOf(Instruction.Return(Destination.Register(RegisterId.of(0u)))),
                                ),
                            ),
                    ),
                ),
            capabilities =
                listOf(
                    Capability(StringId.of(0u), StringId.of(1u), AbiVersion(1u, 0u), required = true, operationCount = 2u),
                ),
        )
    val targetHash = ArtifactWriter.moduleSemanticHash(artifact.modules[1])
    return artifact.copy(
        modules =
            listOf(
                artifact.modules[0].copy(
                    imports = artifact.modules[0].imports.map { it.copy(targetModuleHash = targetHash) },
                ),
                artifact.modules[1],
            ),
    )
}

private fun importedFieldArtifact(): Artifact {
    val base = executableArtifact(Instruction.Const(RegisterId.of(0u), ConstantId.of(0u)))
    val application = base.modules[0]
    val library = base.modules[1]
    val applicationStrings =
        application.strings + listOf(MetadataText.of("zz.Left"), MetadataText.of("zz.Right"), MetadataText.of("zz.Right.code"))
    val libraryStrings =
        library.strings +
            listOf(
                MetadataText.of("zz.Left"),
                MetadataText.of("zz.Left.code"),
                MetadataText.of("zz.Right"),
                MetadataText.of("zz.Right.code"),
            )
    val leftType = TypeRef.Local(TypeId.of(2u))
    val rightType = TypeRef.Local(TypeId.of(3u))
    val expandedLibrary =
        library.copy(
            strings = libraryStrings,
            types =
                library.types +
                    listOf(
                        NominalType.Class(StringId.of(2u), final = true, fieldStart = 0u, fieldCount = 1u),
                        NominalType.Class(StringId.of(4u), final = true, fieldStart = 1u, fieldCount = 1u),
                    ),
            fields =
                listOf(
                    Field(leftType, StringId.of(3u), ValueType.I32, mutable = false, static = true),
                    Field(rightType, StringId.of(5u), ValueType.I32, mutable = false, static = true),
                ),
            exports =
                library.exports +
                    listOf(
                        Export(SymbolKind.TYPE, ExportVisibility.PUBLIC_LIBRARY, StringId.of(2u), 2u, leftType),
                        Export(SymbolKind.TYPE, ExportVisibility.PUBLIC_LIBRARY, StringId.of(4u), 3u, rightType),
                        Export(SymbolKind.FIELD, ExportVisibility.PUBLIC_LIBRARY, StringId.of(3u), 0u, leftType),
                        Export(SymbolKind.FIELD, ExportVisibility.PUBLIC_LIBRARY, StringId.of(5u), 1u, rightType),
                    ),
        )
    val targetHash = ArtifactWriter.moduleSemanticHash(expandedLibrary)
    val expandedApplication =
        application.copy(
            strings = applicationStrings,
            imports =
                application.imports.map { it.copy(targetModuleHash = targetHash) } +
                    listOf(
                        Import(SymbolKind.TYPE, ModuleId.of(1u), StringId.of(5u), TypeRef.Imported(ImportId.of(2u)), targetHash),
                        Import(SymbolKind.TYPE, ModuleId.of(1u), StringId.of(6u), TypeRef.Imported(ImportId.of(3u)), targetHash),
                        Import(SymbolKind.FIELD, ModuleId.of(1u), StringId.of(7u), TypeRef.Imported(ImportId.of(3u)), targetHash),
                    ),
            blocks =
                application.blocks.toMutableList().also {
                    it[0] =
                        it[0].copy(
                            instructions =
                                listOf(
                                    Instruction.StaticGet(RegisterId.of(0u), FieldRef.Imported(ImportId.of(4u))),
                                    Instruction.Jump(BlockId.of(1u)),
                                ),
                        )
                },
        )
    return base.copy(modules = listOf(expandedApplication, expandedLibrary))
}

private fun charArrayArtifact(
    instruction: Instruction,
    initializeArray: Boolean = true,
): Artifact {
    val artifact = executableArtifact(instruction)
    val module = artifact.modules[0]
    val arrayType = ValueType.Ref(nullable = false, TypeRef.Local(TypeId.of(module.types.size.toUInt())))
    val signature = module.types[0] as NominalType.Function
    val entry = module.functions[0]
    val parameters = if (initializeArray) signature.parameters + arrayType else signature.parameters
    val parameterCount = if (initializeArray) entry.parameterCount + 1u else entry.parameterCount
    return artifact.copy(
        modules =
            listOf(
                module.copy(
                    types =
                        module.types.toMutableList().also {
                            it[0] = signature.copy(parameters = parameters)
                            it += NominalType.Array(StringId.of(0u), ValueType.Char)
                        },
                    functions =
                        module.functions.toMutableList().also {
                            it[0] =
                                entry.copy(
                                    values = entry.values + FunctionValue.scalar(arrayType),
                                    parameterCount = parameterCount,
                                )
                        },
                ),
                artifact.modules[1],
            ),
    )
}
