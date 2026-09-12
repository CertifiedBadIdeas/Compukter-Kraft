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

package ru.lazyhat.compukters.compiler.artifact.read

import ru.lazyhat.compukters.compiler.artifact.analysis.ReferenceLiveness
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.PhysicalAtom
import ru.lazyhat.compukters.compiler.artifact.model.PhysicalShape
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.artifact.write.channelArtifact
import ru.lazyhat.compukters.compiler.artifact.write.languageRuntimeArtifact
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ArtifactReaderTest {
    @Test
    fun `round trip preserves physical shapes and exact roots`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val function = module.functions.single()
        val shaped =
            function.copy(
                values =
                    function.values.toMutableList().also {
                        it[0] = it[0].copy(physicalShape = PhysicalShape(listOf(PhysicalAtom.I32, PhysicalAtom.REF32)))
                    },
            )
        val shapedModule = module.copy(functions = listOf(shaped))
        val rooted = shaped.copy(safepointRoots = ReferenceLiveness.derive(shapedModule, shaped))
        val artifact = source.copy(modules = listOf(shapedModule.copy(functions = listOf(rooted))))

        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(artifact)).bytes
        val decoded = ArtifactReader.read(encoded)

        assertEquals(
            rooted.values,
            decoded.modules
                .single()
                .functions
                .single()
                .values,
        )
        assertEquals(
            rooted.safepointRoots,
            decoded.modules
                .single()
                .functions
                .single()
                .safepointRoots,
        )
    }

    @Test
    fun `writer reader writer round trip preserves canonical bytes`() {
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact())).bytes
        val decoded = ArtifactReader.read(encoded)
        val repeatedResult = ArtifactWriter.write(decoded)
        val repeated = assertIs<ArtifactWriteResult.Success>(repeatedResult, repeatedResult.toString()).bytes

        assertContentEquals(encoded, repeated)
    }

    @Test
    fun `channel instructions and manifest limits round trip canonically`() {
        val source = channelArtifact()
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(source)).bytes
        val decoded = ArtifactReader.read(encoded)

        assertEquals(1u, decoded.manifest.maximumChannels)
        assertEquals(1u, decoded.manifest.maximumChannelValues)
        assertEquals(source.modules.single().blocks, decoded.modules.single().blocks)
        val repeated = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(decoded)).bytes
        assertContentEquals(encoded, repeated)
    }

    @Test
    fun `reader rejects corruption and trailing bytes`() {
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact())).bytes
        val corrupted = encoded.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(corrupted) }
        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(encoded + 0) }
    }

    @Test
    fun `class initializer survives writer reader round trip`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val initializerId = FunctionId.of(1u)
        val artifact =
            source.copy(
                modules =
                    listOf(
                        module.copy(
                            types =
                                module.types.toMutableList().also { types ->
                                    types[0] = (types[0] as NominalType.Class).copy(initializer = initializerId)
                                },
                            functions =
                                module.functions +
                                    module.functions.single().copy(
                                        owner = TypeRef.Local(TypeId.of(0u)),
                                        firstBlock = BlockId.of(module.blocks.size.toUInt()),
                                        blockCount = 1u,
                                        firstException = module.exceptions.size.toUInt(),
                                        exceptionCount = 0u,
                                    ),
                            blocks =
                                module.blocks +
                                    Block(initializerId, false, listOf(Instruction.Return(Destination.Unit))),
                        ),
                    ),
            )

        val writeResult = ArtifactWriter.write(artifact)
        val encoded = assertIs<ArtifactWriteResult.Success>(writeResult, writeResult.toString()).bytes
        val decoded = ArtifactReader.read(encoded)

        assertEquals(initializerId, (decoded.modules.single().types[0] as NominalType.Class).initializer)
    }
}
