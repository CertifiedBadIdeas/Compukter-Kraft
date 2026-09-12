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

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrErrorExpression
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
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
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic

internal object MinimalScriptLowering {
    fun lower(
        module: IrModuleFragment,
        pluginContext: IrPluginContext,
        session: CompilationSession,
    ): Artifact? {
        val declarations = SourceDeclarationCollector().also { module.accept(it, null) }
        val functions = declarations.functions
        val guestTypes = GuestTypeRegistry(pluginContext)
        val namedMain = functions.filter { it.name.asString() == "main" && it.parent is IrFile }
        if (namedMain.isNotEmpty()) {
            val validMain =
                namedMain.filter { function ->
                    (
                        function.parameters.isEmpty() ||
                            (function.parameters.size == 1 && guestTypes.isStringArray(function.parameters.single().type))
                    ) &&
                        function.returnType == pluginContext.irBuiltIns.unitType
                }
            if (validMain.size != 1 || namedMain.size != 1) {
                session.diagnosticSink(invalidEntry(session, namedMain.firstOrNull()))
                return null
            }
            val entry = validMain.single()
            val body = entry.body as? IrBlockBody
            if (body == null) {
                session.diagnosticSink(unsupported(session, entry))
                return null
            }
            if (body.statements.isEmpty() && entry.parameters.isEmpty()) {
                return mainArtifact(entry.isSuspend)
            }
            try {
                return KotlinProjectLowering.lower(
                    functions,
                    declarations.properties,
                    declarations.classes,
                    entry,
                    pluginContext,
                    session,
                )
            } catch (unsupported: UnsupportedKotlinIr) {
                session.diagnosticSink(unsupported(session, unsupported.element, unsupported.message, entry))
                return null
            }
        }

        session.diagnosticSink(invalidEntry(session, null))
        return null
    }

    private fun invalidEntry(
        session: CompilationSession,
        function: IrSimpleFunction?,
    ) = WorkerDiagnostic(
        DiagnosticSeverity.ERROR,
        DiagnosticCategory.TARGET,
        "INVALID_ENTRY_POINT",
        "project must declare exactly one fun main() or suspend fun main() returning Unit, with no parameters or one Array<String>",
        session.virtualSourcePath(function?.file?.fileEntry?.name),
        function?.startOffset?.takeIf { it >= 0 }?.toUInt(),
        function?.endOffset?.takeIf { it >= 0 }?.toUInt(),
    )

    private fun unsupported(
        session: CompilationSession,
        element: IrElement?,
        detail: String? = null,
        fallback: IrSimpleFunction? = null,
    ) = WorkerDiagnostic(
        DiagnosticSeverity.ERROR,
        if (element is IrErrorExpression) DiagnosticCategory.SYNTAX else DiagnosticCategory.TARGET,
        if (element is IrErrorExpression) "SYNTAX_ERROR" else "UNSUPPORTED_IR",
        detail?.let { "source IR is outside the minimal script subset: $it" }
            ?: "source IR is outside the minimal script subset",
        session.virtualSourcePath(
            (element as? org.jetbrains.kotlin.ir.declarations.IrDeclaration)?.file?.fileEntry?.name
                ?: fallback?.file?.fileEntry?.name,
        ),
        element?.startOffset?.takeIf { it >= 0 }?.toUInt(),
        element?.endOffset?.takeIf { it >= 0 }?.toUInt(),
    )

    private fun mainArtifact(suspending: Boolean): Artifact =
        Artifact(
            semanticFeatures = if (suspending) setOf(SemanticFeature.COROUTINES) else emptySet(),
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
                                    flags = setOfNotNull(FunctionFlag.STATIC, FunctionFlag.SUSPENDING.takeIf { suspending }),
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
}

private class SourceDeclarationCollector : IrVisitorVoid() {
    val functions = mutableListOf<IrSimpleFunction>()
    val properties = mutableListOf<IrProperty>()
    val classes = mutableListOf<IrClass>()

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction) {
        if (declaration.startOffset >= 0) functions += declaration
        super.visitSimpleFunction(declaration)
    }

    override fun visitClass(declaration: IrClass) {
        if (declaration.startOffset >= 0) classes += declaration
        super.visitClass(declaration)
    }

    override fun visitProperty(declaration: IrProperty) {
        if (declaration.startOffset >= 0 && declaration.parent is IrFile) properties += declaration
        super.visitProperty(declaration)
    }
}
