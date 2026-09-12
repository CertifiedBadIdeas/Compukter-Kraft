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

package ru.lazyhat.compukters.compiler.worker.k2

import ru.lazyhat.compukters.compiler.artifact.model.AbiVersion
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.StringValueType
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.Utf16Literal
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.read.ArtifactReader
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinimalScriptLoweringTest {
    @Test
    fun `direct top level suspend task lowers to spawn and join`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.concurrent.Tasks
                        import compukter.redstone.Redstone

                        suspend fun reader() {
                            readln()
                        }

                        suspend fun writer() {
                            Redstone.right.set(13)
                        }

                        suspend fun main() {
                            val readerTask = Tasks.launch(::reader)
                            val writerTask = Tasks.launch(::writer)
                            readerTask.join()
                            writerTask.join()
                        }
                        """.trimIndent(),
                    ),
                )
            val artifactBytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val artifact = ArtifactReader.read(artifactBytes)
            val opcodes = allOpcodes(artifactBytes)

            assertTrue(0x50 in opcodes, "task launch must lower to task.spawn: $opcodes")
            assertTrue(0xe8 in opcodes, "task join must lower to task.join: $opcodes")
            assertEquals(AbiVersion(1u, 1u), artifact.minimumRuntimeAbi)
            assertEquals(64u, artifact.manifest.maximumCoroutines)
            assertTrue(SemanticFeature.COROUTINES in artifact.semanticFeatures)
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
            System.getProperty("compukter.vm.tasksArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifactBytes)
            }
        }

    @Test
    fun `task launch rejects callable shapes that require runtime function objects`() =
        withAdapter { adapter ->
            val unsupported =
                listOf(
                    "Tasks.launch { reader() }",
                    "suspend fun local() {}\n    Tasks.launch(::local)",
                    "Tasks.launch(::ordinary)",
                    "Tasks.launch(Reader()::read)",
                )
            unsupported.forEach { launch ->
                val result =
                    adapter.compile(
                        request(
                            """
                            import compukter.concurrent.Tasks

                            suspend fun reader() {}
                            fun ordinary() {}
                            class Reader { suspend fun read() {} }

                            suspend fun main() {
                                $launch
                            }
                            """.trimIndent(),
                        ),
                    )

                assertNull(result.artifact, launch)
                assertTrue(
                    result.diagnostics.any {
                        it.severity.name == "ERROR" &&
                            it.message.contains(
                                "Tasks.launch requires a direct reference to a top-level, zero-argument suspend function",
                            )
                    },
                    "$launch: ${result.diagnostics}",
                )
            }
        }

    @Test
    fun `sound beep lowers deterministically to a blocking Boolean capability operation`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.sound.Sound

                fun main() {
                    println(Sound.beep(12))
                    println(Sound.beep(24, 50))
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val opcodes = allOpcodes(artifact)

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            assertEquals(1, opcodes.count { it == 0xe9 }, "the shared beep implementation must block on its VM task: $opcodes")
            System.getProperty("compukter.vm.soundArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `redstone program lowers deterministically for vm conformance`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.redstone.Redstone

                fun main() {
                    Redstone.left.awaitAtLeast(7)
                    Redstone.right.set(15)
                    Redstone.front.await(15)
                    Redstone.top.set(15, Redstone.Power.DIRECT)
                    Redstone.bottom.set(0)
                    var writes = 0
                    while (writes < 80) {
                        Redstone.right.set(15, Redstone.Power.DIRECT)
                        writes = writes + 1
                    }
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.redstoneArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `Int for loop supplies its generated increment constant`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.redstone.Redstone

                        fun main() {
                            while (true) {
                                for (i in 0..15) {
                                    Redstone.right.set(i)
                                }
                            }
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `ordinary and suspend zero argument Unit main lower deterministically`() =
        withAdapter { adapter ->
            listOf(false, true).forEach { suspending ->
                val source = if (suspending) "suspend fun main() {}" else "fun main() {}"
                val first = adapter.compile(request(source))
                val second = adapter.compile(request(source))
                val expected =
                    (ArtifactWriter.write(expectedMainArtifact(suspending)) as ArtifactWriteResult.Success)
                        .bytes

                assertContentEquals(expected, assertNotNull(first.artifact).toByteArray())
                assertContentEquals(expected, assertNotNull(second.artifact).toByteArray())
                assertTrue(first.diagnostics.none { it.severity.name == "ERROR" })
            }
        }

    @Test
    fun `ordinary platform function is linked from its precompiled fragment`() =
        withAdapter { adapter ->
            val result = adapter.compile(request("fun main() { require(true) }"))
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val decoded =
                ru.lazyhat.compukters.compiler.artifact.read.ArtifactReader
                    .read(artifact)

            assertTrue(decoded.modules.count { it.kind == ModuleKind.LIBRARY } >= 2)
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `platform scalar side property lowers without static state`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.redstone.Redstone

                fun main() {
                    Redstone.left
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val opcodes = allOpcodes(artifact)

            assertTrue(0x38 !in opcodes, "platform scalar constant must not read static state: $opcodes")
        }

    @Test
    fun `redstone side set preserves its bounded Int precondition`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.redstone.Redstone

                fun main() {
                    Redstone.left.set(16)
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val opcodes = allOpcodes(artifact)

            assertTrue(0xe4 in opcodes, "platform scalar range failure must throw: $opcodes")
            assertTrue(0x13 !in opcodes, "platform scalar range failure must not masquerade as division: $opcodes")
            assertTrue(0x30 in opcodes, "platform scalar range failure must construct IllegalArgumentException: $opcodes")
            System.getProperty("compukter.vm.platformScalarArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `redstone side get remains an ordinary library call`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.redstone.Redstone

                fun main() {
                    Redstone.left.get()
                }
                """.trimIndent()
            val result = adapter.compile(request(source))
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()

            assertTrue(0x40 in applicationCodeOpcodes(artifact), "computed getter must call its library implementation")
        }

    @Test
    fun `guest object subset lowers sealed results data values enum identity and type branches`() =
        withAdapter { adapter ->
            val source =
                """
                sealed interface Result
                data class Exited(val code: Int) : Result
                data class Failed(val reason: Reason, val diagnostic: String) : Result
                enum class Reason { NOT_FOUND, TRAPPED }

                fun classify(value: Result): Int = when (value) {
                    is Exited -> value.code
                    is Failed -> if (value.reason == Reason.NOT_FOUND) value.diagnostic.length else -1
                }

                fun main() {
                    classify(Exited(7))
                }
                """.trimIndent()

            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(artifact).modules.single { it.kind == ModuleKind.APPLICATION }
            val typeTags = indexedSectionRecords(artifact, 0x0101).map { it.first().toInt() and 0xff }
            val opcodes = applicationCodeOpcodes(artifact)

            val reasonTypeIndex =
                application.types.indexOfFirst { type ->
                    type is NominalType.Class && application.strings[type.name.value.toInt()].toString() == "Reason"
                }
            val reasonType = assertNotNull(application.types[reasonTypeIndex] as? NominalType.Class)
            val initializerId = assertNotNull(reasonType.initializer)
            val initializer = application.functions[initializerId.value.toInt()]
            val initializerBlocks =
                application.blocks.subList(
                    initializer.firstBlock.value.toInt(),
                    (initializer.firstBlock.value + initializer.blockCount).toInt(),
                )

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertEquals(TypeRef.Local(TypeId.of(reasonTypeIndex.toUInt())), initializer.owner)
            assertTrue(FunctionFlag.STATIC in initializer.flags)
            assertTrue(initializerBlocks.flatMap(Block::instructions).any { it is Instruction.NewObject })
            assertEquals(2, initializerBlocks.flatMap(Block::instructions).count { it is Instruction.StaticSet })
            assertTrue(
                application.functions
                    .filterIndexed { index, _ -> index != initializerId.value.toInt() }
                    .flatMap { function ->
                        application.blocks.subList(
                            function.firstBlock.value.toInt(),
                            (function.firstBlock.value + function.blockCount).toInt(),
                        )
                    }.flatMap(Block::instructions)
                    .none { it is Instruction.StaticSet },
            )
            assertEquals(4, typeTags.count { it == 3 }, "two source functions, the reachable Exited constructor and enum initializer")
            assertEquals(3, typeTags.count { it == 0 }, "Exited, Failed and Reason classes")
            assertEquals(1, typeTags.count { it == 1 }, "sealed Result interface")
            assertEquals(5, indexedSectionRecords(artifact, 0x0105).size, "three properties and two enum roots")
            setOf(0x26, 0x30, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x40).forEach { opcode ->
                assertTrue(opcode in opcodes, "missing expected guest object opcode 0x${opcode.toString(16)} in $opcodes")
            }
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.objectArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `guest object subset rejects mutable generic initialized secondary and explicitly cast shapes`() =
        withAdapter { adapter ->
            val unsupported =
                listOf(
                    "data class Mutable(var value: Int)\nfun main() { Mutable(1) }",
                    "data class Generic<T>(val value: T)\nfun main() { Generic(1) }",
                    "class Initialized(val value: Int) { init { value + 1 } }\nfun main() { Initialized(1) }",
                    "class Secondary(val value: Int) { constructor() : this(0) }\nfun main() { Secondary() }",
                    "class Defaulted(val value: Int = 1)\nfun main() { Defaulted() }",
                    "class Computed(val value: Int) { val doubled: Int get() = value + value }\nfun main() { Computed(1) }",
                    "enum class Stateful(val code: Int) { ONE(1) }\nfun main() { Stateful.ONE }",
                    "sealed interface Value\ndata class NumberValue(val value: Int) : Value\nfun read(value: Value): Int = (value as NumberValue).value\nfun main() { read(NumberValue(1)) }",
                )

            unsupported.forEach { source ->
                val result = adapter.compile(request(source))
                assertNull(result.artifact, source)
                assertTrue(
                    result.diagnostics.any { it.code == "UNSUPPORTED_IR" },
                    result.diagnostics.toString(),
                )
            }
        }

    @Test
    fun `all four legal main forms lower deterministically with an explicit entry contract`() =
        withAdapter { adapter ->
            val sources =
                listOf(
                    "fun main() {}" to 0,
                    "suspend fun main() {}" to 0,
                    "fun main(args: Array<String>) {}" to 1,
                    "suspend fun main(args: Array<String>) {}" to 1,
                )

            sources.forEach { (source, expectedTag) ->
                val first = adapter.compile(request(source))
                val second = adapter.compile(request(source))
                val firstBytes = assertNotNull(first.artifact).toByteArray()
                val secondBytes = assertNotNull(second.artifact).toByteArray()

                assertContentEquals(firstBytes, secondBytes)
                assertEquals(expectedTag, firstBytes[48].toInt() and 0xff)
                assertTrue(first.diagnostics.none { it.severity.name == "ERROR" })
            }
        }

    @Test
    fun `string array entry lowers deterministically for vm argv conformance`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.terminal.Terminal

                fun main(args: Array<String>) {
                    Terminal.write(args[0] + ":" + args[1])
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertEquals(1, artifact[48].toInt() and 0xff)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.argvArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `entry policy rejects duplicate and invalid main functions`() =
        withAdapter { adapter ->
            val duplicate =
                adapter.compile(
                    request(
                        "a/Main.kt" to "package a\nfun main() {}",
                        "b/Main.kt" to "package b\nsuspend fun main() {}",
                    ),
                )
            val invalid =
                listOf(
                    adapter.compile(request("fun main(value: String) {}")),
                    adapter.compile(request("fun main(value: Array<Int>) {}")),
                    adapter.compile(request("fun main(value: Array<String>?) {}")),
                    adapter.compile(request("fun main(): Int = 0")),
                )

            (listOf(duplicate) + invalid).forEach { result ->
                assertNull(result.artifact)
                assertTrue(result.diagnostics.any { it.category == DiagnosticCategory.TARGET && it.code == "INVALID_ENTRY_POINT" })
            }
        }

    @Test
    fun `entry policy rejects a project without main`() =
        withAdapter { adapter ->
            val result = adapter.compile(request("val answer: Int = 42"))

            assertNull(result.artifact)
            assertTrue(
                result.diagnostics.any {
                    it.category == DiagnosticCategory.TARGET && it.code == "INVALID_ENTRY_POINT"
                },
                result.diagnostics.toString(),
            )
        }

    @Test
    fun `string arrays can be constructed read and written`() =
        withAdapter { adapter ->
            val source =
                """
                fun main(args: Array<String>) {
                    val empty = emptyArray<String>()
                    val values = arrayOf(args[0], "")
                    values[1] = args[1]
                    empty.size
                    values[0]
                    values[1]
                }
                """.trimIndent()

            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
        }

    @Test
    fun `native builtins expose only the supported string and array operations`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        fun main(args: Array<String>) {
                            val values = arrayOf(args[0], "")
                            val tail = values.copyOfRange(1, values.size)
                            val chars = CharArray(2)
                            chars[0] = 'o'
                            chars[1] = 'k'
                            val text = chars.concatToString(0, chars.size)
                            if (text.substring(0, 1) == tail[0]) return
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `string arrays support copyOfRange and supported default arguments`() =
        withAdapter { adapter ->
            val source =
                """
                fun select(args: Array<String> = emptyArray()): Array<String> =
                    args.copyOfRange(1, args.size)

                fun main(args: Array<String>) {
                    select()
                    select(arrayOf("prefix", args[0]))[0]
                }
                """.trimIndent()

            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
        }

    @Test
    fun `string array entry supports bounded loop access`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        fun main(args: Array<String>) {
                            var index = 0
                            while (index < args.size) {
                                val value = args[index]
                                index = index + value.length
                            }
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `multi-file terminal program lowers through trusted symbols`() =
        withAdapter { adapter ->
            val request =
                request(
                    "project/greeting.kt" to
                        "fun greeting(name: String): String = \"Hello, \" + name + \"!\"",
                    "project/main.kt" to
                        """
                        import compukter.terminal.Terminal

                        suspend fun main() {
                            Terminal.write(greeting("Ada"))
                            Terminal.awaitEvent()
                        }
                        """.trimIndent(),
                )
            val result = adapter.compile(request)
            val repeated = adapter.compile(request)

            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
            assertContentEquals(artifact.toByteArray(), assertNotNull(repeated.artifact).toByteArray())
            System.getProperty("compukter.vm.kotlinSubsetArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact.toByteArray())
            }
        }

    @Test
    fun `ordinary main lowers trusted terminal wait as vm blocking`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.terminal.Terminal

                fun main() {
                    val event = Terminal.awaitEvent()
                    if (event == 1) Terminal.write("event")
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.blockingCallArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `primitive char array lowers deterministically for exact utf16 materialization`() =
        withAdapter { adapter ->
            val request =
                request(
                    "project/main.kt" to
                        """
                        import compukter.redstone.Redstone
                        import compukter.terminal.Terminal

                        fun main() {
                            val value = CharArray(5)
                            value[0] = 'A'
                            value[1] = '\uD83D'
                            value[2] = '\uDE00'
                            value[3] = 'Z'
                            value[4] = '!'
                            val last = value[value.size - 1]
                            if (last == '!') Terminal.write(value.concatToString(0, 4))
                            val number = 2
                            val enabled = true
                            val marker = 'x'
                            Terminal.write("${'$'}number/${'$'}enabled/${'$'}marker/${'$'}{Redstone.left}")
                        }
                        """.trimIndent(),
                )
            val first = adapter.compile(request)
            val second = adapter.compile(request)

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.kotlinSubsetArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `specialized IntArray lowers to unboxed primitive array instructions`() =
        withAdapter { adapter ->
            val source =
                """
                fun next(value: Int): Int = value + 1

                fun main() {
                    val allocated = IntArray(2)
                    allocated[0] = next(6)
                    val literal = intArrayOf(next(8), next(10))
                    allocated[1] = literal[0] + literal[1] + literal.size
                    require(allocated.size == 2)
                    require(allocated[0] == 7)
                    require(allocated[1] == 22)
                    IntArray(0)
                    intArrayOf()
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(artifact).modules.single { it.kind == ModuleKind.APPLICATION }
            val instructions = application.blocks.flatMap(Block::instructions)

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            assertTrue(instructions.count { it is Instruction.NewArray } >= 4)
            assertTrue(instructions.any { it is Instruction.ArrayLength })
            assertTrue(instructions.any { it is Instruction.ArrayLoad })
            assertTrue(instructions.any { it is Instruction.ArrayStore })
            assertTrue(instructions.none { it is Instruction.NewObject })
        }

    @Test
    fun `unsupported IntArray forms publish no artifact`() =
        withAdapter { adapter ->
            listOf(
                "fun main() { IntArray(2) { it } }",
                "fun main() { arrayOf(1, 2) }",
                "fun main() { val values = intArrayOf(1); for (value in values) value + 1 }",
                "fun main() { val values = intArrayOf(1); values.indices }",
                "fun main() { val values = intArrayOf(1); intArrayOf(*values) }",
                "fun main() { LongArray(1) }",
            ).forEach { source ->
                val result = adapter.compile(request(source))

                assertNull(result.artifact, source)
                assertTrue(result.hasErrors, source)
            }
        }

    @Test
    fun `specialized IntArray lowers deterministically for vm conformance`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.terminal.Terminal

                fun marked(value: Int): Int {
                    Terminal.write("${'$'}value")
                    return value
                }

                fun verify(actual: Int, expected: Int) {
                    if (actual != expected) {
                        val zero = actual - actual
                        1 / zero
                    }
                }

                fun main() {
                    val mode = Terminal.eventKey()
                    if (mode == 0) {
                        val empty = IntArray(0)
                        val emptyLiteral = intArrayOf()
                        verify(empty.size, 0)
                        verify(emptyLiteral.size, 0)

                        val values = intArrayOf(marked(7), marked(11), marked(13))
                        verify(values.size, 3)
                        verify(values[0], 7)
                        values[1] = values[1] + values[2]
                        verify(values[1], 24)

                        val filled = IntArray(256)
                        var index = 0
                        while (index < filled.size) {
                            filled[index] = index
                            index = index + 1
                        }
                        verify(filled[255], 255)
                    } else if (mode == 1) {
                        IntArray(-1)
                    } else if (mode == 2) {
                        IntArray(Int.MAX_VALUE)
                    } else if (mode == 3) {
                        intArrayOf(1)[1]
                    } else {
                        val values = IntArray(1)
                        values[1] = 1
                    }
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.intArrayArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `suspend project call lowers deterministically for vm execution`() =
        withAdapter { adapter ->
            val request =
                request(
                    "project/main.kt" to
                        """
                        import compukter.terminal.Terminal

                        suspend fun readKey(): Int {
                            Terminal.awaitEvent()
                            return Terminal.eventKey()
                        }

                        suspend fun main() {
                            Terminal.write(if (readKey() == 13) "enter" else "other")
                        }
                        """.trimIndent(),
                )
            val first = adapter.compile(request)
            val second = adapter.compile(request)

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.suspendCallArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `bounded when lowers deterministically for vm execution`() =
        withAdapter { adapter ->
            val request =
                request(
                    "project/main.kt" to
                        """
                        import compukter.terminal.Terminal

                        suspend fun key(): Int {
                            Terminal.awaitEvent()
                            return Terminal.eventKey()
                        }

                        suspend fun main() {
                            val text = when (key()) {
                                13 -> "enter"
                                27 -> "escape"
                                else -> "other"
                            }
                            Terminal.write(text)
                        }
                        """.trimIndent(),
                )
            val first = adapter.compile(request)
            val second = adapter.compile(request)

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.whenArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `bounded when forms compile for admitted scalar types`() =
        withAdapter { adapter ->
            listOf(
                "fun classify(value: Int): String = when (value) { 1 -> \"one\"; else -> \"other\" }; fun main() { classify(1) }",
                "fun classify(value: Char): Int = when (value) { 'x' -> 1; else -> 0 }; fun main() { classify('x') }",
                "fun classify(value: Boolean): String = when (value) { true -> \"yes\"; else -> \"no\" }; fun main() { classify(true) }",
                "fun classify(value: String): Int = when (value) { \"run\" -> 1; else -> 0 }; fun main() { classify(\"run\") }",
                "fun classify(value: Int): String = when { value == 1 -> \"one\"; else -> \"other\" }; fun main() { classify(1) }",
                "fun main() { var value = 0; when (1) { 1 -> value = 1; else -> value = 2 }; when { value == 1 -> value = 3; else -> value = 4 } }",
            ).forEach { source ->
                val result = adapter.compile(request(source))

                assertNotNull(result.artifact, "$source\n${result.diagnostics.joinToString()}")
                assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, "$source\n${result.diagnostics}")
            }
        }

    @Test
    fun `unsupported when patterns produce no artifact`() =
        withAdapter { adapter ->
            listOf(
                "fun main() { when (2) { in 1..3 -> Unit; else -> Unit } }",
                "fun classify(value: Any): Int = when (value) { is String -> 1; else -> 0 }; fun main() { classify(\"x\") }",
                "fun main() { when (2) { 1, 2 -> Unit; else -> Unit } }",
            ).forEach { source ->
                val result = adapter.compile(request(source))

                assertNull(result.artifact, source)
                assertTrue(result.hasErrors, source)
                assertTrue(
                    result.diagnostics.any {
                        it.category != DiagnosticCategory.TARGET || it.code == "UNSUPPORTED_IR"
                    },
                    "$source\n${result.diagnostics}",
                )
            }
        }

    @Test
    fun `same-named char array helper remains an ordinary project call`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        fun CharArray.concatToString(startIndex: Int, endIndex: Int): String = "guest"

                        fun main() {
                            val value = CharArray(1)
                            value.concatToString(0, 1)
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `positional terminal facade lowers through exact trusted signatures`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.terminal.Terminal

                        fun main() {
                            Terminal.setCursor(1, 2)
                            Terminal.setCursorVisible(false)
                            Terminal.setColors(15, 0)
                            Terminal.writeAt(1, 2, "text")
                            Terminal.fill(0, 3, 51, 1, ' ')
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `typed redstone side API lowers deterministically to scalar capability operations`() =
        withAdapter { adapter ->
            val sources =
                listOf(
                    """
                    import compukter.redstone.Redstone

                    fun main() {
                        val current = Redstone.left.get()
                        Redstone.left.await()
                        Redstone.left.await(current)
                        Redstone.left.awaitAtLeast(7)
                    }
                    """.trimIndent(),
                    """
                    import compukter.redstone.Redstone

                    fun main() {
                        Redstone.bottom.set(0)
                        Redstone.top.set(15, Redstone.Power.DIRECT)
                    }
                    """.trimIndent(),
                )
            val artifacts =
                sources.map { source ->
                    val first = adapter.compile(request(source))
                    val second = adapter.compile(request(source))
                    val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

                    assertContentEquals(
                        artifact,
                        assertNotNull(second.artifact, second.diagnostics.joinToString()).toByteArray(),
                    )
                    artifact
                }
            val opcodes = artifacts.flatMap(::allOpcodes)

            assertEquals(1, opcodes.count { it == 0x51 }, "get must be a synchronous scalar call: $opcodes")
            assertEquals(4, opcodes.count { it == 0xe9 }, "await overloads and set must be VM-task-blocking: $opcodes")
            assertTrue(0x35 !in opcodes, "redstone value classes must not load fields: $opcodes")
            assertTrue(0x17 in opcodes, "redstone output packing must retain scalar or: $opcodes")
        }

    @Test
    fun `ordinary Kotlin standard streams lower to stdio capability operations`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.io.Stderr

                        fun main() {
                            print("name: ")
                            val name = readln()
                            println()
                            println(name)
                            println(7)
                            println(true)
                            println('x')
                            Stderr.write("done\n")
                        }
                        """.trimIndent(),
                    ),
                )

            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val opcodes = allOpcodes(artifact)

            assertEquals(1, opcodes.count { it == 0xe9 }, "readln must be the only async capability call")
            assertTrue(0x51 in opcodes, "standard output must lower through a sync capability call")
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())

            listOf(
                "fun main() { print(7) }",
                "import compukter.io.Stderr\nfun main() { Stderr.write(\"error\") }",
            ).forEach { source ->
                val endpoint = adapter.compile(request(source))
                val endpointArtifact = assertNotNull(endpoint.artifact, endpoint.diagnostics.joinToString()).toByteArray()
                val endpointOpcodes = allOpcodes(endpointArtifact)
                assertTrue(0x51 in endpointOpcodes, "$source: $endpointOpcodes")
                assertTrue(endpoint.diagnostics.none { it.severity.name == "ERROR" }, endpoint.diagnostics.toString())
            }
        }

    @Test
    fun `string templates lower scalar platform and Unit parts to canonical strings`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.redstone.Redstone

                        fun sideEffect(): Unit {}

                        fun main() {
                            val number = 2
                            val enabled = true
                            val marker = 'x'
                            println("${'$'}number")
                            println("value=${'$'}number/${'$'}enabled/${'$'}marker")
                            println("${'$'}{Redstone.left}")
                            println("${'$'}{sideEffect()}")
                        }
                        """.trimIndent(),
                    ),
                )

            val artifactBytes = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(artifactBytes).modules.single { it.kind == ModuleKind.APPLICATION }
            val conversions =
                application.blocks
                    .flatMap(Block::instructions)
                    .filterIsInstance<Instruction.StringValueOf>()

            assertEquals(
                listOf(StringValueType.I32, StringValueType.I32, StringValueType.BOOL, StringValueType.CHAR, StringValueType.I32),
                conversions.map(Instruction.StringValueOf::type),
            )
            assertTrue(Utf16Literal.fromString("kotlin.Unit") in application.utf16Literals)
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `string template rejects arbitrary objects until virtual dispatch exists`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        class Box(val value: Int)

                        fun main() {
                            println("${'$'}{Box(1)}")
                        }
                        """.trimIndent(),
                    ),
                )

            assertNull(result.artifact)
            assertTrue(result.hasErrors)
            assertTrue(
                result.diagnostics.any { "object string conversion requires virtual dispatch" in it.message },
                result.diagnostics.toString(),
            )
        }

    @Test
    fun `unbounded terminal loop compiles without executing guest code`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.terminal.Terminal

                        fun main() {
                            while (true) {
                                Terminal.write("yes")
                            }
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `while loop jumps lower locally and reject outer targets`() =
        withAdapter { adapter ->
            val local =
                adapter.compile(
                    request(
                        """
                        fun main() {
                            var outer = 0
                            var sum = 0
                            while (outer < 4) {
                                outer = outer + 1
                                var inner = 0
                                while (inner < 5) {
                                    inner = inner + 1
                                    if (inner == 2) continue
                                    if (inner == 4) break
                                    sum = sum + inner
                                }
                            }
                            require(sum == 16)
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(local.artifact, local.diagnostics.joinToString())
            assertTrue(local.diagnostics.none { it.severity.name == "ERROR" }, local.diagnostics.toString())

            val outerTarget =
                adapter.compile(
                    request(
                        """
                        fun main() {
                            outer@ while (true) {
                                while (true) break@outer
                            }
                        }
                        """.trimIndent(),
                    ),
                )

            assertNull(outerTarget.artifact)
            assertTrue(outerTarget.hasErrors)
            assertTrue(
                outerTarget.diagnostics.any { "outer loop jump" in it.message },
                outerTarget.diagnostics.toString(),
            )
        }

    @Test
    fun `inclusive Int for loops lower without range or iterator allocation`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        fun main() {
                            var sum = 0
                            for (value in 1..4) {
                                sum = sum + value
                            }
                            require(sum == 10)

                            var empty = 0
                            for (value in 4..1) {
                                empty = empty + value
                            }
                            require(empty == 0)

                            var singleton = 0
                            for (value in 7..7) {
                                singleton = singleton + value
                            }
                            require(singleton == 7)

                            var maximum = 0
                            for (value in Int.MAX_VALUE..Int.MAX_VALUE) {
                                maximum = maximum + 1
                            }
                            require(maximum == 1)

                            var filtered = 0
                            for (value in 0..10) {
                                if (value == 2) continue
                                if (value == 5) break
                                filtered = filtered + value
                            }
                            require(filtered == 8)
                        }
                        """.trimIndent(),
                    ),
                )
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(artifact).modules.single { it.kind == ModuleKind.APPLICATION }
            val instructions = application.blocks.flatMap(Block::instructions)

            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
            assertTrue(instructions.none { it is Instruction.NewObject || it is Instruction.NewArray })
            assertTrue(instructions.any { it is Instruction.AddI32 })
            assertTrue(application.blocks.any(Block::loopHeaderSafepoint))
        }

    @Test
    fun `exclusive Int for loops lower without range or iterator allocation`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        fun main() {
                            var untilSum = 0
                            for (value in 0 until 5) {
                                untilSum = untilSum + value
                            }
                            require(untilSum == 10)

                            var rangeUntilSum = 0
                            for (value in 0..<5) {
                                rangeUntilSum = rangeUntilSum + value
                            }
                            require(rangeUntilSum == 10)

                            var empty = 0
                            for (value in Int.MIN_VALUE until Int.MIN_VALUE) {
                                empty = empty + 1
                            }
                            require(empty == 0)

                            var nearMaximum = 0
                            for (value in (Int.MAX_VALUE - 2) until Int.MAX_VALUE) {
                                nearMaximum = nearMaximum + 1
                            }
                            require(nearMaximum == 2)
                        }
                        """.trimIndent(),
                    ),
                )
            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()
            val application = ArtifactReader.read(artifact).modules.single { it.kind == ModuleKind.APPLICATION }
            val instructions = application.blocks.flatMap(Block::instructions)

            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
            assertTrue(instructions.none { it is Instruction.NewObject || it is Instruction.NewArray })
            assertTrue(instructions.any { it is Instruction.AddI32 })
            assertTrue(application.blocks.any(Block::loopHeaderSafepoint))
        }

    @Test
    fun `allocation free Int loops lower deterministically for vm execution`() =
        withAdapter { adapter ->
            val source =
                """
                fun verify(actual: Int, expected: Int) {
                    if (actual == expected) {
                        actual + expected
                    } else {
                        val zero = actual - actual
                        1 / zero
                    }
                }

                fun main() {
                    var inclusive = 0
                    for (value in -2..2) {
                        inclusive = inclusive + value
                    }
                    verify(inclusive, 0)

                    var reversed = 0
                    for (value in 2..-2) {
                        reversed = reversed + 1
                    }
                    verify(reversed, 0)

                    var maximum = 0
                    for (value in Int.MAX_VALUE..Int.MAX_VALUE) {
                        maximum = maximum + 1
                    }
                    verify(maximum, 1)

                    var start = 1
                    var end = 3
                    var snapshot = 0
                    for (value in start..end) {
                        start = 100
                        end = 0
                        snapshot = snapshot + value
                    }
                    verify(snapshot, 6)

                    var exclusive = 0
                    for (value in 0 until 5) {
                        exclusive = exclusive + value
                    }
                    verify(exclusive, 10)

                    var rangeUntil = 0
                    for (value in 0..<5) {
                        rangeUntil = rangeUntil + value
                    }
                    verify(rangeUntil, 10)

                    var nested = 0
                    for (outer in 0 until 4) {
                        for (inner in 0..5) {
                            if (inner == 2) continue
                            if (inner == 4) break
                            nested = nested + outer + inner
                        }
                    }
                    verify(nested, 34)

                    var quota = 0
                    for (value in 0 until 1024) {
                        quota = quota + 1
                    }
                    verify(quota, 1024)
                }
                """.trimIndent()
            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.intLoopsArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `unsupported loop forms publish no artifact`() =
        withAdapter { adapter ->
            listOf(
                "fun main() { for (value in 3 downTo 1) { value + 1 } }",
                "fun main() { for (value in (1..5).step(2)) { value + 1 } }",
                "fun main() { for (value in arrayOf(1)) { value + 1 } }",
                "fun main() { var value = 0; do { value = value + 1 } while (value < 2) }",
            ).forEach { source ->
                val result = adapter.compile(request(source))

                assertNull(result.artifact, source)
                assertTrue(result.hasErrors, source)
            }
        }

    @Test
    fun `filesystem text facade lowers through exact trusted signatures`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.filesystem.FileSystem

                        fun main() {
                            val contents = FileSystem.readText("notes.txt")
                            FileSystem.writeText("copy.txt", contents)
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `typed process v2 facade lowers without public capability masks or suspend calls`() =
        withAdapter { adapter ->
            val source =
                """
                import compukter.process.Process
                import compukter.process.ProcessFailureReason
                import compukter.process.ProcessResult

                fun main() {
                    when (val result = Process.run("/rom/tool", arrayOf("a", ""))) {
                        is ProcessResult.Exited -> if (result.code != 0) Process.exit(result.code)
                        is ProcessResult.Failed -> if (result.reason == ProcessFailureReason.NOT_FOUND) {
                            Process.exit(2)
                        } else if (result.diagnostic != "") {
                            Process.exit(1)
                        }
                    }
                }
                """.trimIndent()

            val first = adapter.compile(request(source))
            val second = adapter.compile(request(source))
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()

            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            val application = ArtifactReader.read(artifact).modules.single { it.kind == ModuleKind.APPLICATION }
            val imports =
                application.imports.groupBy { it.kind }.mapValues { (_, values) ->
                    values.map { application.strings[it.targetName.value.toInt()].toString() }.toSet()
                }
            assertTrue("compukter.process.ProcessResult" in imports.getValue(SymbolKind.TYPE))
            assertTrue("compukter.process.ProcessResult.Exited" in imports.getValue(SymbolKind.TYPE))
            assertTrue("compukter.process.ProcessResult.Exited.code" in imports.getValue(SymbolKind.FIELD))
            assertTrue("compukter.process.ProcessResult.Failed.reason" in imports.getValue(SymbolKind.FIELD))
            assertTrue("compukter.process.ProcessResult.Failed.diagnostic" in imports.getValue(SymbolKind.FIELD))
            assertTrue("compukter.process.ProcessFailureReason.NOT_FOUND" in imports.getValue(SymbolKind.FIELD))

            listOf(
                "import compukter.process.Process\nfun main() { Process.run(\"/rom/tool\", 1) }",
                "import compukter.process.Process\nfun main() { Process.commandLine() }",
                "import compukter.process.ProcessBindings\nfun main() { ProcessBindings.takeFailureDiagnostic() }",
            ).forEach { forbiddenSource ->
                val forbidden = adapter.compile(request(forbiddenSource))
                assertNull(forbidden.artifact, forbiddenSource)
                assertTrue(forbidden.hasErrors, forbiddenSource)
            }
        }

    @Test
    fun `shell language subset lowers control flow scalars strings and raw terminal calls`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        import compukter.terminal.Terminal

                        suspend fun main() {
                            var line = ""
                            var running = true
                            var index = 0
                            Terminal.write("> ")
                            while (running) {
                                val kind = Terminal.awaitEvent()
                                if (kind == 1) {
                                    val text = Terminal.eventText()
                                    while (index < text.length) {
                                        val character = text[index]
                                        if (character >= ' ' && character != '\u007f' && line.length < 256) {
                                            val next = text.substring(index, index + 1)
                                            line = line + next
                                            Terminal.write(next)
                                        }
                                        index = index + 1
                                    }
                                } else if (Terminal.eventKey() == 13 && Terminal.eventAction() == 1) {
                                    Terminal.write("\n")
                                    if (line == "clear") Terminal.clear() else Terminal.write(line)
                                    line = ""
                                } else if (Terminal.eventKey() == 8) {
                                    if (line.length > 0) {
                                        line = line.substring(0, line.length - 1)
                                        Terminal.erasePrevious()
                                    }
                                }
                                Terminal.eventModifiers()
                                Terminal.finishEvent()
                            }
                        }
                        """.trimIndent(),
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `checked in shell compiles deterministically`() =
        withAdapter { adapter ->
            val source = Path.of("../..", "system/programs/shell.kt").readText()
            val lexer = Path.of("../..", "system/programs/shell/Lexer.kt").readText()
            val sources =
                arrayOf(
                    "system/programs/shell.kt" to source,
                    "system/programs/shell/Lexer.kt" to lexer,
                )
            val first = adapter.compile(request(*sources))
            val second = adapter.compile(request(*sources))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(artifact)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.shell.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `checked in boot compiles deterministically with process intrinsic`() =
        withAdapter { adapter ->
            val source = Path.of("../..", "system/programs/boot.kt").readText()
            val first = adapter.compile(request("system/programs/boot.kt" to source))
            val second = adapter.compile(request("system/programs/boot.kt" to source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(artifact)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.boot.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `checked in kotlinc compiles deterministically`() =
        withAdapter { adapter ->
            val source = Path.of("../..", "system/programs/kotlinc.kt").readText()
            val first = adapter.compile(request("system/programs/kotlinc.kt" to source))
            val second = adapter.compile(request("system/programs/kotlinc.kt" to source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(artifact)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.kotlinc.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `checked in vm benchmark compiles deterministically`() =
        withAdapter { adapter ->
            val source = Path.of("../..", "system/programs/vmbench.kt").readText()
            val workload = Path.of("../..", "system/programs/vmbench-workload.kt").readText()
            val first = adapter.compile(request("system/programs/vmbench-workload.kt" to workload, "system/programs/vmbench.kt" to source))
            val second = adapter.compile(request("system/programs/vmbench-workload.kt" to workload, "system/programs/vmbench.kt" to source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(artifact)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.vmbench.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `checked in vm benchmark agent compiles deterministically`() =
        withAdapter { adapter ->
            val source = Path.of("../..", "system/programs/vmbench-agent.kt").readText()
            val workload = Path.of("../..", "system/programs/vmbench-workload.kt").readText()
            val first =
                adapter.compile(
                    request("system/programs/vmbench-agent.kt" to source, "system/programs/vmbench-workload.kt" to workload),
                )
            val second =
                adapter.compile(
                    request("system/programs/vmbench-agent.kt" to source, "system/programs/vmbench-workload.kt" to workload),
                )

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(artifact)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.vmbenchAgent.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `checked in editor compiles deterministically`() =
        withAdapter { adapter ->
            val source = Path.of("../..", "system/programs/edit.kt").readText()
            val first = adapter.compile(request("system/programs/edit.kt" to source))
            val second = adapter.compile(request("system/programs/edit.kt" to source))

            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertOrdinaryEntry(artifact)
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukters.edit.artifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        }

    @Test
    fun `same-named guest function remains an ordinary project call`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        "project/main.kt" to
                            "import compukter.terminal.Terminal\nsuspend fun main() { Terminal.write(readln(\"guest\")); Terminal.awaitEvent() }",
                        "project/read.kt" to "fun readln(value: String): String = value",
                    ),
                )

            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `platform callable lookalike remains an ordinary project call`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        """
                        package kotlin.io

                        fun readln(): String = "guest"

                        fun main() {
                            readln().length
                        }
                        """.trimIndent(),
                    ),
                )

            val artifact = assertNotNull(result.artifact, result.diagnostics.joinToString()).toByteArray()

            assertTrue(0xe9 !in allOpcodes(artifact), "player lookalike must not become an async capability call")
            assertTrue(result.diagnostics.none { it.severity.name == "ERROR" }, result.diagnostics.toString())
        }

    @Test
    fun `unsupported source produces a stable native platform diagnostic and no artifact`() =
        withAdapter { adapter ->
            listOf(
                "fun main() { val answer: Long = 42L }",
                "fun main() { listOf(1) }",
                "fun main() { val answer: UInt = 42u }",
            ).forEach { source ->
                val result = adapter.compile(request(source))
                val errors = result.diagnostics.filter { it.severity.name == "ERROR" }

                assertNull(result.artifact, source)
                assertEquals(1, errors.size, source)
                assertTrue(errors.single().category in setOf(DiagnosticCategory.TYPE, DiagnosticCategory.TARGET), source)
                assertTrue(result.hasErrors, source)
            }
        }

    @Test
    fun `artifact writer failure becomes a bounded internal diagnostic`() =
        withAdapter { adapter ->
            val result =
                adapter.compile(
                    request(
                        source = "fun main() { val answer: Int = 42 }",
                        limits = WorkerLimits(artifactBytes = 1, diagnostics = 1, diagnosticTextBytes = 32),
                    ),
                )

            assertNull(result.artifact)
            assertEquals(1, result.diagnostics.size)
            val diagnostic = result.diagnostics.single()
            assertEquals(DiagnosticCategory.INTERNAL, diagnostic.category)
            assertTrue(diagnostic.code?.startsWith("ARTIFACT_WRITE_") == true)
            assertTrue(diagnostic.message.encodeToByteArray().size <= 32)
        }

    private fun expectedMainArtifact(suspending: Boolean): Artifact =
        Artifact(
            semanticFeatures =
                if (suspending) {
                    setOf(
                        ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature.COROUTINES,
                    )
                } else {
                    emptySet()
                },
            manifest = Manifest.minimal(maximumBlockCost = 1u),
            entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
            modules =
                listOf(
                    Module(
                        name = StringId.of(0u),
                        kind = ModuleKind.APPLICATION,
                        strings = listOf(MetadataText.of("app"), MetadataText.of("main")),
                        types =
                            listOf(
                                NominalType.Function(
                                    name = StringId.of(1u),
                                    suspending = suspending,
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
                                    flags =
                                        setOfNotNull(
                                            FunctionFlag.STATIC,
                                            FunctionFlag.SUSPENDING.takeIf { suspending },
                                        ),
                                    values = emptyList(),
                                    parameterCount = 0u,
                                    firstBlock = BlockId.of(0u),
                                    blockCount = 1u,
                                    firstException = 0u,
                                    exceptionCount = 0u,
                                ),
                            ),
                        blocks = listOf(Block(FunctionId.of(0u), false, listOf(Instruction.Return(Destination.Unit)))),
                    ),
                ),
        )

    private fun withAdapter(block: (K2CompilerAdapter) -> Unit) {
        val root = createTempDirectory("compukters-minimal-lowering-test-")
        try {
            block(
                K2CompilerAdapter(
                    K2CompilerInputs(
                        temporaryRoot = root,
                        workerJar = Path.of(checkNotNull(System.getProperty("compukters.worker.jar"))),
                        expectedIdentity = identity(),
                    ),
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun request(
        source: String,
        limits: WorkerLimits = WorkerLimits(),
    ): CompileRequest = request(listOf("project/main.kt" to source), limits)

    private fun request(vararg sources: Pair<String, String>): CompileRequest = request(sources.toList(), WorkerLimits())

    private fun request(
        sources: List<Pair<String, String>>,
        limits: WorkerLimits,
    ): CompileRequest =
        CompileRequest(
            RequestId.of(1u),
            sources.map { (path, source) ->
                ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(source.encodeToByteArray()))
            },
            TargetSettings.KOTLIN_2_4_JVM_17,
            identity(),
            limits,
            K2CompilerAdapter.loadPackagedPlatform().modules.map { module ->
                TrustedBundleIdentity.of(
                    module.id.toString(),
                    Hash256.of(
                        ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
                            .moduleContentHash(module)
                            .toByteArray(),
                    ),
                )
            },
        )

    private fun identity() =
        WorkerIdentity(
            "2.4.10",
            "2.4",
            1u,
            1u,
            Hash256.zero(),
            Hash256.of(
                K2CompilerAdapter
                    .loadPackagedPlatform()
                    .identity.contentHash
                    .toByteArray(),
            ),
        )

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

private fun applicationCodeOpcodes(artifact: ByteArray): List<Int> =
    indexedSectionRecords(artifact, 0x0108).flatMap { record ->
        buildList {
            var cursor = 0
            while (cursor < record.size) {
                add(record[cursor].toInt() and 0xff)
                val length = record.u16(cursor + 2)
                require(length >= 4 && cursor + length <= record.size)
                cursor += length
            }
        }
    }

private fun allOpcodes(artifact: ByteArray): List<Int> {
    val sectionCount = artifact.u32(16)
    return (0 until sectionCount)
        .map { 64 + it * 32 }
        .filter { offset -> artifact.u16(offset) == 0x0108 }
        .flatMap { entry ->
            val sectionOffset = artifact.u64(entry + 8)
            val sectionLength = artifact.u64(entry + 16)
            val payload = artifact.copyOfRange(sectionOffset, sectionOffset + sectionLength)
            indexedPayloadRecords(payload).flatMap { record ->
                buildList {
                    var cursor = 0
                    while (cursor < record.size) {
                        add(record[cursor].toInt() and 0xff)
                        val length = record.u16(cursor + 2)
                        require(length >= 4 && cursor + length <= record.size)
                        cursor += length
                    }
                }
            }
        }
}

private fun assertOrdinaryEntry(artifact: ByteArray) {
    val entryFunction = artifact.u32(44)
    val flags = indexedSectionRecords(artifact, 0x0106)[entryFunction].u32(12)
    assertEquals(0, flags and 1, "checked-in program entry must be an ordinary Kotlin function")
}

private fun indexedSectionRecords(
    artifact: ByteArray,
    kind: Int,
): List<ByteArray> {
    val sectionCount = artifact.u32(16)
    val entry =
        (0 until sectionCount)
            .map { 64 + it * 32 }
            .single { offset -> artifact.u16(offset) == kind && artifact.u32(offset + 4) == 1 }
    val sectionOffset = artifact.u64(entry + 8)
    val sectionLength = artifact.u64(entry + 16)
    return indexedPayloadRecords(artifact.copyOfRange(sectionOffset, sectionOffset + sectionLength))
}

private fun indexedPayloadRecords(payload: ByteArray): List<ByteArray> {
    val count = payload.u32(0)
    val dataStart = align8(16 + (count + 1) * 4)
    return (0 until count).map { index ->
        val start = payload.u32(16 + index * 4)
        val end = payload.u32(16 + (index + 1) * 4)
        payload.copyOfRange(dataStart + start, dataStart + end)
    }
}

private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.u32(offset: Int): Int =
    (0 until 4).fold(0) { value, byte -> value or ((this[offset + byte].toInt() and 0xff) shl (byte * 8)) }

private fun ByteArray.u64(offset: Int): Int {
    val value = (0 until 8).fold(0L) { result, byte -> result or ((this[offset + byte].toLong() and 0xffL) shl (byte * 8)) }
    require(value in 0..Int.MAX_VALUE)
    return value.toInt()
}

private fun align8(value: Int): Int = (value + 7) and -8
