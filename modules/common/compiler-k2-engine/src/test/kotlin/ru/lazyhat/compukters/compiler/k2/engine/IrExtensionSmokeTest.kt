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

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IrExtensionSmokeTest {
    @Test
    fun `pinned K2 loads only the worker plugin and reaches typed script IR`() {
        val module = compileIr("val answer: Int = 42")
        val facts = IrFacts().also { module.accept(it, null) }

        assertTrue(facts.sourceFiles.any { it.replace('\\', '/').endsWith("/project/main.kt") })
        assertTrue(42 in facts.intConstants, "typed IR must contain the Int constant 42")
        assertFailsWith<IllegalStateException> { CompilationBridge.requireSession() }
    }

    @Test
    fun `pinned K2 exposes canonical Int range for loop IR`() {
        val module =
            compileIr(
                """
                fun inclusive(start: Int, end: Int) {
                    for (index in start..end) println(index)
                }

                fun exclusive(start: Int, end: Int) {
                    for (index in start until end) println(index)
                }

                fun rangeUntil(start: Int, end: Int) {
                    for (index in start..<end) println(index)
                }
                """.trimIndent(),
            )
        val facts = IrFacts().also { module.accept(it, null) }

        assertEquals(3, facts.blockOrigins.count { it == "FOR_LOOP" }, facts.blockOrigins.toString())
        assertEquals(0, facts.variableOrigins.count { it == "FOR_LOOP_ITERATOR" }, facts.variableOrigins.toString())
        assertEquals(3, facts.variableOrigins.count { it == "FOR_LOOP_VARIABLE" }, facts.variableOrigins.toString())
        assertEquals(0, facts.whileOrigins.size, facts.whileOrigins.toString())
        assertEquals(3, facts.doWhileOrigins.count { it == "DO_WHILE_COUNTER_LOOP" }, facts.doWhileOrigins.toString())
        assertEquals(3, facts.breakCount)
        assertEquals(3, facts.callNames.count { it == "kotlin.jvm.internal.<int-prefix-incr-decr>" }, facts.callNames.toString())
        assertTrue("kotlin.internal.ir.lessOrEqual" in facts.callNames, facts.callNames.toString())
        assertTrue("kotlin.internal.ir.EQEQ" in facts.callNames, facts.callNames.toString())
        assertTrue("kotlin.internal.ir.less" in facts.callNames, facts.callNames.toString())
        assertTrue(facts.callNames.none { it.endsWith(".rangeTo") || it.endsWith(".until") }, facts.callNames.toString())
        assertTrue(
            facts.callNames.none { it.endsWith(".iterator") || it.endsWith(".hasNext") || it.endsWith(".next") },
            facts.callNames.toString(),
        )
    }

    private fun compileIr(sourceText: String): IrModuleFragment {
        val root = createTempDirectory("compukters-k2-ir-test-")
        try {
            val source = root.resolve("project/main.kt")
            source.parent.createDirectories()
            source.writeText(sourceText)
            val output = root.resolve("classes")
            output.createDirectories()
            val modules = mutableListOf<IrModuleFragment>()
            val arguments =
                K2JVMCompilerArguments().apply {
                    freeArgs = listOf(source.toString())
                    destination = output.toString()
                    moduleName = "compukter-script"
                    languageVersion = "2.4"
                    apiVersion = "2.4"
                    jvmTarget = "17"
                    noReflect = true
                    classpath =
                        Path
                            .of(
                                Unit::class.java.protectionDomain.codeSource.location
                                    .toURI(),
                            ).toString()
                    pluginClasspaths = arrayOf(checkNotNull(System.getProperty("compukters.engine.jar")))
                }

            val exitCode =
                CompilationBridge.withSession(CompilationSession(irSink = { module, _ -> modules += module })) {
                    K2JVMCompiler().exec(MessageCollector.NONE, Services.EMPTY, arguments)
                }

            assertEquals(ExitCode.OK, exitCode)
            assertEquals(1, modules.size)
            return modules.single()
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private class IrFacts : IrVisitorVoid() {
    val sourceFiles = mutableListOf<String>()
    val intConstants = mutableListOf<Int>()
    val blockOrigins = mutableListOf<String>()
    val variableOrigins = mutableListOf<String>()
    val whileOrigins = mutableListOf<String>()
    val doWhileOrigins = mutableListOf<String>()
    val callNames = mutableListOf<String>()
    var breakCount = 0

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitFile(declaration: IrFile) {
        sourceFiles += declaration.fileEntry.name
        super.visitFile(declaration)
    }

    override fun visitConst(expression: IrConst) {
        (expression.value as? Int)?.let(intConstants::add)
        super.visitConst(expression)
    }

    override fun visitBlock(expression: IrBlock) {
        expression.origin?.toString()?.let(blockOrigins::add)
        super.visitBlock(expression)
    }

    override fun visitVariable(declaration: IrVariable) {
        declaration.origin.toString().let(variableOrigins::add)
        super.visitVariable(declaration)
    }

    override fun visitWhileLoop(loop: IrWhileLoop) {
        loop.origin?.toString()?.let(whileOrigins::add)
        super.visitWhileLoop(loop)
    }

    override fun visitDoWhileLoop(loop: IrDoWhileLoop) {
        loop.origin?.toString()?.let(doWhileOrigins::add)
        super.visitDoWhileLoop(loop)
    }

    override fun visitCall(expression: IrCall) {
        expression.symbol.owner.fqNameWhenAvailable
            ?.asString()
            ?.let(callNames::add)
        super.visitCall(expression)
    }

    override fun visitBreak(jump: IrBreak) {
        breakCount += 1
        super.visitBreak(jump)
    }
}
