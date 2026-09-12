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

import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.ExceptionEntry
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.PhysicalAtom
import ru.lazyhat.compukters.compiler.artifact.model.PhysicalShape
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueComponent
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceLivenessTest {
    @Test
    fun `drops a reference immediately after its final use`() {
        val module =
            module(
                values = listOf(reference(), reference()),
                blocks =
                    listOf(
                        listOf(
                            Instruction.Move(RegisterId.of(1u), RegisterId.of(0u)),
                            Instruction.Return(Destination.Unit),
                        ),
                    ),
            )

        val roots = ReferenceLiveness.derive(module, module.functions.single())

        assertEquals(listOf(ValueComponent(RegisterId.of(0u), 0u)), roots[0].references)
        assertEquals(emptyList(), roots[1].references)
    }

    @Test
    fun `keeps caller references that are used after a call`() {
        val module =
            module(
                values = listOf(reference()),
                blocks =
                    listOf(
                        listOf(
                            Instruction.Call(Destination.Unit, FunctionRef.Local(FunctionId.of(0u)), emptyList()),
                            Instruction.Return(Destination.Register(RegisterId.of(0u))),
                        ),
                    ),
            )

        val roots = ReferenceLiveness.derive(module, module.functions.single())

        assertEquals(listOf(ValueComponent(RegisterId.of(0u), 0u)), roots[0].references)
        assertEquals(listOf(ValueComponent(RegisterId.of(0u), 0u)), roots[1].references)
    }

    @Test
    fun `propagates liveness through a loop back edge`() {
        val module =
            module(
                values = listOf(reference(), reference()),
                blocks =
                    listOf(
                        listOf(Instruction.Jump(BlockId.of(1u))),
                        listOf(
                            Instruction.Move(RegisterId.of(1u), RegisterId.of(0u)),
                            Instruction.Jump(BlockId.of(1u)),
                        ),
                    ),
            )

        val roots = ReferenceLiveness.derive(module, module.functions.single())

        assertEquals(
            List(3) { listOf(ValueComponent(RegisterId.of(0u), 0u)) },
            roots.map { it.references },
        )
    }

    @Test
    fun `keeps live values on exceptional edges and defines the caught exception in the handler`() {
        val values = listOf(reference(), reference(), FunctionValue.scalar(ValueType.Bool))
        val base =
            module(
                values = values,
                blocks =
                    listOf(
                        listOf(Instruction.Call(Destination.Unit, FunctionRef.Local(FunctionId.of(0u)), emptyList())),
                        listOf(
                            Instruction.RefEqual(RegisterId.of(2u), RegisterId.of(0u), RegisterId.of(1u)),
                            Instruction.Return(Destination.Unit),
                        ),
                    ),
            )
        val module =
            base.copy(
                functions = base.functions.map { it.copy(firstException = 0u, exceptionCount = 1u) },
                exceptions =
                    listOf(
                        ExceptionEntry(
                            owner = FunctionId.of(0u),
                            firstProtectedBlock = BlockId.of(0u),
                            protectedBlockCount = 1u,
                            catchType = null,
                            handlerBlock = BlockId.of(1u),
                            exceptionRegister = RegisterId.of(1u),
                        ),
                    ),
            )

        val roots = ReferenceLiveness.derive(module, module.functions.single())

        assertEquals(listOf(ValueComponent(RegisterId.of(0u), 0u)), roots[0].references)
        assertEquals(
            listOf(ValueComponent(RegisterId.of(0u), 0u), ValueComponent(RegisterId.of(1u), 0u)),
            roots[1].references,
        )
        assertEquals(emptyList(), roots[2].references)
    }

    @Test
    fun `selects only reference components from a multi component value`() {
        val aggregate =
            FunctionValue(
                semanticType = ValueType.Ref(nullable = false, TypeRef.Local(TypeId.of(0u))),
                physicalShape = PhysicalShape(listOf(PhysicalAtom.I32, PhysicalAtom.REF32)),
            )
        val module =
            module(
                values = listOf(aggregate),
                blocks = listOf(listOf(Instruction.Return(Destination.Register(RegisterId.of(0u))))),
            )

        val roots = ReferenceLiveness.derive(module, module.functions.single())

        assertEquals(listOf(ValueComponent(RegisterId.of(0u), 1u)), roots.single().references)
    }

    private fun reference(): FunctionValue = FunctionValue.scalar(ValueType.Ref(nullable = false, TypeRef.Local(TypeId.of(0u))))

    private fun module(
        values: List<FunctionValue>,
        blocks: List<List<Instruction>>,
    ): Module =
        Module(
            name = StringId.of(0u),
            kind = ModuleKind.APPLICATION,
            strings = listOf(MetadataText.of("test")),
            functions =
                listOf(
                    Function(
                        owner = null,
                        name = StringId.of(0u),
                        signature = TypeRef.Local(TypeId.of(0u)),
                        flags = setOf(FunctionFlag.STATIC),
                        values = values,
                        parameterCount = values.size.toUInt(),
                        firstBlock = BlockId.of(0u),
                        blockCount = blocks.size.toUInt(),
                        firstException = 0u,
                        exceptionCount = 0u,
                    ),
                ),
            blocks =
                blocks.map { instructions ->
                    Block(FunctionId.of(0u), loopHeaderSafepoint = false, instructions = instructions)
                },
        )
}
