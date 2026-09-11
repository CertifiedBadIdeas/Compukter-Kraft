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
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrBreakContinue
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import ru.lazyhat.compukters.compiler.artifact.analysis.ExecutionStorage
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
import ru.lazyhat.compukters.compiler.artifact.model.OrderedScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.StringValueType
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.Utf16Literal
import ru.lazyhat.compukters.compiler.artifact.model.Utf16LiteralId
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.pool.ConstantPoolBuilder
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.CapabilityOperationHandler
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.IntrinsicBlockingMode
import ru.lazyhat.compukters.platform.bundle.PlatformDefaultArgument
import ru.lazyhat.compukters.platform.bundle.PlatformScalarConstant
import ru.lazyhat.compukters.platform.bundle.PlatformScalarRepresentation
import ru.lazyhat.compukters.platform.bundle.PlatformScalarType
import ru.lazyhat.compukters.platform.bundle.PlatformScalarValue

internal class UnsupportedKotlinIr(
    val element: IrElement,
    message: String,
) : IllegalArgumentException(message)

private data class GuestFieldLayout(
    val property: IrProperty,
    val constructorParameterIndex: Int,
    val id: FieldId,
    val type: ValueType,
)

private data class GuestEnumEntryLayout(
    val declaration: IrEnumEntry,
    val fieldId: FieldId,
    val ownerType: TypeRef.Local,
)

private data class GuestClassLayout(
    val declaration: IrClass,
    val typeId: TypeId,
    val firstField: UInt,
    val fields: List<GuestFieldLayout>,
    val enumEntries: List<GuestEnumEntryLayout>,
)

private data class GuestConstructorTarget(
    val layout: GuestClassLayout,
    val functionId: FunctionId,
)

private data class InlineValueClassLayout(
    val declaration: IrClass,
    val constructor: IrConstructorSymbol,
    val getter: IrSimpleFunctionSymbol,
    val underlyingType: IrType,
    val intRange: InlineIntRange?,
)

private data class InlineIntRange(
    val minimum: Int,
    val maximum: Int,
)

private data class InlineScalarConstant(
    val value: Any,
)

private class PlatformScalarRegistry(
    scalarTypes: List<PlatformScalarType>,
    scalarConstants: List<PlatformScalarConstant>,
) {
    private val typesBySymbol = scalarTypes.associateBy(PlatformScalarType::symbol)
    private val constantsBySymbol = scalarConstants.associateBy(PlatformScalarConstant::symbol)

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun representation(type: IrType): PlatformScalarRepresentation? =
        ((type as? IrSimpleType)?.classifier as? IrClassSymbol)
            ?.owner
            ?.fqNameWhenAvailable
            ?.asString()
            ?.let(typesBySymbol::get)
            ?.representation

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun constructor(symbol: IrConstructorSymbol): PlatformScalarType? =
        symbol.owner.parentAsClass.fqNameWhenAvailable
            ?.asString()
            ?.let(typesBySymbol::get)

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun isUnderlyingGetter(function: IrSimpleFunction): Boolean {
        val propertyName =
            function.name
                .asString()
                .removePrefix("<get-")
                .removeSuffix(">")
        return (function.parent as? IrClass)
            ?.fqNameWhenAvailable
            ?.asString()
            ?.let(typesBySymbol::get)
            ?.underlyingProperty == propertyName
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun constant(function: IrSimpleFunction): PlatformScalarValue? {
        val owner = function.parent as? IrClass ?: return null
        val scalarOwner =
            if (owner.name.asString() == "Companion") {
                owner.parent as? IrClass
            } else {
                owner
            } ?: return null
        val scalarSymbol = scalarOwner.fqNameWhenAvailable?.asString() ?: return null
        if (scalarSymbol !in typesBySymbol) return null
        val propertyName =
            function.name
                .asString()
                .removePrefix("<get-")
                .removeSuffix(">")
        return constantsBySymbol["$scalarSymbol.$propertyName"]?.value
    }

    fun constantValues(): List<Any> =
        constantsBySymbol.values.map { it.value.scalarValue() } +
            typesBySymbol.values.flatMap { type -> listOfNotNull(type.minimumInt, type.maximumInt) }
}

private fun PlatformScalarValue.scalarValue(): Any =
    when (this) {
        is PlatformScalarValue.IntValue -> value
        is PlatformScalarValue.BooleanValue -> value
        is PlatformScalarValue.CharValue -> value
    }

private class InlineValueClassRegistry private constructor(
    private val byClass: Map<IrClassSymbol, InlineValueClassLayout>,
    private val byConstructor: Map<IrConstructorSymbol, InlineValueClassLayout>,
    private val byGetter: Map<IrSimpleFunctionSymbol, InlineValueClassLayout>,
    private val companionClasses: Set<IrClassSymbol>,
    private val constantsByGetter: Map<IrSimpleFunctionSymbol, InlineScalarConstant>,
) {
    operator fun get(symbol: IrClassSymbol): InlineValueClassLayout? = byClass[symbol]

    fun constructor(symbol: IrConstructorSymbol): InlineValueClassLayout? = byConstructor[symbol]

    fun getter(symbol: IrSimpleFunctionSymbol): InlineValueClassLayout? = byGetter[symbol]

    fun contains(symbol: IrClassSymbol?): Boolean = symbol != null && symbol in byClass

    fun isCompanion(symbol: IrClassSymbol): Boolean = symbol in companionClasses

    fun constant(getter: IrSimpleFunctionSymbol): InlineScalarConstant? = constantsByGetter[getter]

    fun constantValues(): List<Any> =
        constantsByGetter.values.map(InlineScalarConstant::value) +
            byClass.values.flatMap { layout ->
                layout.intRange?.let { listOf(it.minimum, it.maximum) }.orEmpty()
            }

    companion object {
        @OptIn(UnsafeDuringIrConstructionAPI::class)
        fun build(
            classes: List<IrClass>,
            pluginContext: IrPluginContext,
        ): InlineValueClassRegistry {
            val layouts = classes.filter { it.isValue }.map { declaration -> validate(declaration, pluginContext) }
            val layoutsByClass = layouts.associateBy { it.declaration.symbol }
            val companions =
                classes.filter { declaration ->
                    declaration.kind == ClassKind.OBJECT &&
                        declaration.name.asString() == "Companion" &&
                        layoutsByClass.containsKey((declaration.parent as? IrClass)?.symbol)
                }
            val constants =
                companions
                    .flatMap { companion ->
                        companion.declarations.filterIsInstance<IrProperty>().map { property ->
                            if (property.isVar || property.getter == null || property.backingField == null) {
                                throw UnsupportedKotlinIr(property, "value class companion properties must be immutable scalar constants")
                            }
                            val initializer =
                                requireNotNull(property.backingField).initializer?.expression
                                    ?: throw UnsupportedKotlinIr(property, "value class companion constant requires an initializer")
                            requireNotNull(property.getter).symbol to
                                InlineScalarConstant(
                                    scalarConstant(
                                        initializer,
                                        layouts.associateBy(InlineValueClassLayout::constructor),
                                        pluginContext,
                                    ),
                                )
                        }
                    }.toMap()
            return InlineValueClassRegistry(
                byClass = layoutsByClass,
                byConstructor = layouts.associateBy(InlineValueClassLayout::constructor),
                byGetter = layouts.associateBy(InlineValueClassLayout::getter),
                companionClasses = companions.mapTo(mutableSetOf()) { it.symbol },
                constantsByGetter = constants,
            )
        }

        @OptIn(UnsafeDuringIrConstructionAPI::class)
        private fun scalarConstant(
            expression: IrExpression,
            layouts: Map<IrConstructorSymbol, InlineValueClassLayout>,
            pluginContext: IrPluginContext,
        ): Any =
            when (expression) {
                is IrConst -> {
                    expression.value
                        ?: throw UnsupportedKotlinIr(expression, "null companion constants are not supported")
                }

                is IrConstructorCall -> {
                    val layout =
                        layouts[expression.symbol]
                            ?: throw UnsupportedKotlinIr(expression, "companion constant constructor is not a supported value class")
                    val argument =
                        expression.symbol.owner.parameters
                            .mapIndexedNotNull { index, parameter ->
                                expression.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
                            }.singleOrNull()
                            ?: throw UnsupportedKotlinIr(expression, "value class companion constant requires one scalar argument")
                    val value = scalarConstant(argument, layouts, pluginContext)
                    val valid =
                        when (layout.underlyingType) {
                            pluginContext.irBuiltIns.intType -> value is Int
                            pluginContext.irBuiltIns.booleanType -> value is Boolean
                            pluginContext.irBuiltIns.charType -> value is Char
                            else -> false
                        }
                    if (!valid) throw UnsupportedKotlinIr(expression, "value class companion constant type mismatch")
                    layout.intRange?.let { range ->
                        val intValue = value as Int
                        if (intValue !in range.minimum..range.maximum) {
                            throw UnsupportedKotlinIr(expression, "value class companion constant violates its precondition")
                        }
                    }
                    value
                }

                else -> {
                    throw UnsupportedKotlinIr(expression, "value class companion initializer must be a scalar constant")
                }
            }

        @OptIn(UnsafeDuringIrConstructionAPI::class)
        private fun validate(
            declaration: IrClass,
            pluginContext: IrPluginContext,
        ): InlineValueClassLayout {
            if (declaration.typeParameters.isNotEmpty()) {
                throw UnsupportedKotlinIr(declaration, "generic value classes are not supported")
            }
            val constructor =
                declaration.constructors.singleOrNull { it.isPrimary }
                    ?: throw UnsupportedKotlinIr(declaration, "value class must have one primary constructor")
            if (declaration.constructors.any { !it.isPrimary }) {
                throw UnsupportedKotlinIr(declaration, "value class secondary constructors are not supported")
            }
            val parameter =
                constructor.parameters.singleOrNull { it.kind == IrParameterKind.Regular }
                    ?: throw UnsupportedKotlinIr(declaration, "value class must have one underlying property")
            val property =
                declaration.declarations.filterIsInstance<IrProperty>().singleOrNull { property ->
                    property.backingField != null && property.name == parameter.name
                } ?: throw UnsupportedKotlinIr(declaration, "value class must have one underlying property")
            if (property.isVar || property.getter == null || property.getter?.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
                throw UnsupportedKotlinIr(property, "value class underlying property must be immutable")
            }
            if (parameter.type.isNullable() ||
                parameter.type !in
                setOf(
                    pluginContext.irBuiltIns.intType,
                    pluginContext.irBuiltIns.booleanType,
                    pluginContext.irBuiltIns.charType,
                )
            ) {
                throw UnsupportedKotlinIr(parameter, "value class underlying type must be a supported non-null scalar")
            }
            val initializers = declaration.declarations.filterIsInstance<IrAnonymousInitializer>()
            val intRange =
                when (initializers.size) {
                    0 -> null
                    1 -> parseIntRange(initializers.single(), requireNotNull(property.getter).symbol)
                    else -> throw UnsupportedKotlinIr(declaration, "value class supports at most one scalar precondition")
                }
            val unsupportedParent =
                declaration.superTypes
                    .mapNotNull { (it as? IrSimpleType)?.classifier as? IrClassSymbol }
                    .firstOrNull { it.owner.fqNameWhenAvailable?.asString() != "kotlin.Any" }
            if (unsupportedParent != null) {
                throw UnsupportedKotlinIr(declaration, "value class interfaces and custom supertypes are not supported")
            }
            return InlineValueClassLayout(
                declaration = declaration,
                constructor = constructor.symbol,
                getter = requireNotNull(property.getter).symbol,
                underlyingType = parameter.type,
                intRange = intRange,
            )
        }

        @OptIn(UnsafeDuringIrConstructionAPI::class)
        private fun parseIntRange(
            initializer: IrAnonymousInitializer,
            getter: IrSimpleFunctionSymbol,
        ): InlineIntRange {
            val requireCall =
                initializer.body.statements.singleOrNull() as? IrCall
                    ?: throw UnsupportedKotlinIr(initializer, "value class initializer must be one require call")
            if (requireCall.symbol.owner.fqNameWhenAvailable
                    ?.asString() != "kotlin.require"
            ) {
                throw UnsupportedKotlinIr(initializer, "value class initializer must be one require call")
            }
            val contains =
                requireCall.arguments.filterNotNull().singleOrNull() as? IrCall
                    ?: throw UnsupportedKotlinIr(initializer, "value class require must check one inclusive Int range")
            if (contains.symbol.owner.fqNameWhenAvailable
                    ?.asString() != "kotlin.ranges.IntRange.contains"
            ) {
                throw UnsupportedKotlinIr(initializer, "value class require must check one inclusive Int range")
            }
            val containsArguments = contains.arguments.filterNotNull()
            val range =
                containsArguments.getOrNull(0) as? IrCall
                    ?: throw UnsupportedKotlinIr(initializer, "value class require must use a constant Int range")
            val value = containsArguments.getOrNull(1) as? IrCall
            if (range.symbol.owner.name
                    .asString() != "rangeTo" || value?.symbol != getter
            ) {
                throw UnsupportedKotlinIr(initializer, "value class require must check its underlying property")
            }
            val bounds = range.arguments.filterNotNull().map { (it as? IrConst)?.value }
            val minimum = bounds.getOrNull(0) as? Int
            val maximum = bounds.getOrNull(1) as? Int
            if (minimum == null || maximum == null || minimum > maximum) {
                throw UnsupportedKotlinIr(initializer, "value class require bounds must be ordered Int constants")
            }
            return InlineIntRange(minimum, maximum)
        }
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal object KotlinProjectLowering {
    private const val CHAR_ARRAY_RUNTIME_TYPE = 0u
    private const val STRING_RUNTIME_TYPE = 1u
    private const val INT_ARRAY_RUNTIME_TYPE = 4u
    private val runtimeTypeNames =
        listOf(
            "kotlin.CharArray",
            "kotlin.String",
            "kotlin.Throwable",
            "runtime.IllegalArgumentException",
            "kotlin.IntArray",
        )

    fun lower(
        functions: List<IrSimpleFunction>,
        classes: List<IrClass>,
        entry: IrSimpleFunction,
        pluginContext: IrPluginContext,
        session: CompilationSession,
        includeTrustedPlatformBodies: Boolean = false,
    ): Artifact {
        val guestTypes = GuestTypeRegistry(pluginContext)
        val platformScalars = PlatformScalarRegistry(session.platformScalarTypes, session.platformScalarConstants)
        val sourceClasses =
            classes
                .filterNot { includeTrustedPlatformBodies && it.kind == ClassKind.OBJECT }
                .filterNot {
                    !includeTrustedPlatformBodies &&
                        session.trustedPlatformModule(it.file.fileEntry.name) != null
                }.sortedBy { it.fqNameWhenAvailable?.asString().orEmpty() }
        val inlineValueClasses = InlineValueClassRegistry.build(classes, pluginContext)
        val playerFunctions =
            functions
                .filter { function ->
                    includeTrustedPlatformBodies ||
                        function.parent is IrFile ||
                        (
                            inlineValueClasses.contains((function.parent as? IrClass)?.symbol) &&
                                function.origin == IrDeclarationOrigin.DEFINED
                        )
                }.filter { function -> function.body != null }
                .filterNot { function ->
                    !includeTrustedPlatformBodies &&
                        session.trustedPlatformModule(function.file.fileEntry.name) != null
                }
        val userFunctions =
            playerFunctions.sortedWith(
                compareBy<IrSimpleFunction>(
                    { if (it === entry) 0 else 1 },
                    { session.virtualSourcePath(it.file.fileEntry.name)?.value.orEmpty() },
                    { it.startOffset },
                    { it.name.asString() },
                ),
            )
        val externalFunctions = linkedPlatformFunctions(userFunctions, session)
        val linkedSymbols = linkedPlatformSymbols(userFunctions, session)
        require(userFunctions.firstOrNull() === entry)
        val userClasses =
            collectGuestClasses(
                sourceClasses.filterNot {
                    inlineValueClasses.contains(it.symbol) || inlineValueClasses.isCompanion(it.symbol)
                },
                userFunctions,
            ).filterNot {
                inlineValueClasses.contains(it.symbol) || inlineValueClasses.isCompanion(it.symbol)
            }
        val constructorClasses =
            userClasses.filter { declaration ->
                declaration.kind == ClassKind.CLASS &&
                    declaration.modality !in setOf(Modality.ABSTRACT, Modality.SEALED) &&
                    declaration.constructors.any { it.isPrimary }
            }
        val initializerClasses =
            userClasses.filter { declaration ->
                declaration.kind == ClassKind.ENUM_CLASS && declaration.declarations.any { it is IrEnumEntry }
            }

        val intrinsicCollector =
            IntrinsicCollector { function ->
                resolveTrustedIntrinsic(function, session)
            }
        userFunctions.forEach { function -> function.accept(intrinsicCollector, null) }
        val capabilityIdentities =
            (
                intrinsicCollector.capabilities +
                    session.canonicalIntrinsicRegistry
                        ?.handlers
                        ?.values
                        ?.filterIsInstance<CapabilityOperationHandler>()
                        ?.map { handler ->
                            val capability = handler.requiredCapability
                            LoweredCapabilityIdentity(
                                capability.namespace,
                                capability.name,
                                capability.abiMajor.toUShort(),
                                0u.toUShort(),
                                capabilityOperationCount(capability.namespace, capability.name),
                            )
                        }.orEmpty()
            ).distinct().sorted()
        val capabilityIds =
            capabilityIdentities.withIndex().associate { (index, identity) -> identity to CapabilityId.of(index.toUInt()) }

        val stringArrayUsage = StringArrayUsageCollector(guestTypes)
        userFunctions.forEach { function -> function.accept(stringArrayUsage, null) }
        val usesStringArray =
            stringArrayUsage.used ||
                userFunctions.any { function ->
                    guestTypes.isStringArray(function.returnType) ||
                        loweredParameters(function, session).any { parameter -> guestTypes.isStringArray(parameter.type) }
                }
        val functionArtifactNames =
            userFunctions.associate { function ->
                function.symbol to artifactFunctionName(function, pluginContext, inlineValueClasses, session)
            }
        val metadataValues =
            (
                listOf("app") +
                    runtimeTypeNames +
                    listOfNotNull("kotlin.Array".takeIf { usesStringArray }) +
                    capabilityIdentities.flatMap { listOf(it.namespace, it.name) } +
                    externalFunctions.values.map(ExternalFunctionTarget::exportName) +
                    linkedSymbols.types.values.map(ExternalTypeTarget::exportName) +
                    linkedSymbols.fieldsByGetter.values.map(ExternalFieldTarget::exportName) +
                    linkedSymbols.enumEntries.values.map(ExternalFieldTarget::exportName) +
                    linkedSymbols.defaultEnumEntries.values.map(ExternalFieldTarget::exportName) +
                    userFunctions.map { requireNotNull(functionArtifactNames[it.symbol]) } +
                    userClasses.map { it.fqNameWhenAvailable?.asString() ?: it.name.asString() } +
                    userClasses.flatMap { declaration ->
                        val owner = declaration.fqNameWhenAvailable?.asString() ?: declaration.name.asString()
                        val fieldNames =
                            declaration.declarations.filterIsInstance<IrProperty>().map { it.name.asString() } +
                                declaration.declarations.filterIsInstance<IrEnumEntry>().map { it.name.asString() }
                        fieldNames +
                            if (includeTrustedPlatformBodies) {
                                fieldNames.map { field -> "$owner.$field" }
                            } else {
                                emptyList()
                            }
                    } + constructorClasses.map(::constructorName) + listOfNotNull("<clinit>".takeIf { initializerClasses.isNotEmpty() })
            ).distinct()
                .map(MetadataText::of)
                .sorted()
        val metadataIds = metadataValues.withIndex().associate { (index, value) -> value.toString() to StringId.of(index.toUInt()) }
        val literalCollector =
            LiteralCollector(pluginContext.irBuiltIns.unitType)
                .also { userFunctions.forEach { function -> function.accept(it, null) } }
        var needsAllBitsI32 = false
        userFunctions.forEach { function ->
            function.accept(
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) {
                        element.acceptChildren(this, null)
                    }

                    override fun visitCall(expression: IrCall) {
                        if (expression.symbol.owner.fqNameWhenAvailable
                                ?.asString() == "kotlin.Int.inv"
                        ) {
                            needsAllBitsI32 = true
                        }
                        super.visitCall(expression)
                    }
                },
                null,
            )
        }
        val literals =
            literalCollector.strings
                .distinct()
                .map {
                    Utf16Literal.fromString(it)
                }.sorted()
        val literalIds = literals.withIndex().associate { (index, value) -> value to Utf16LiteralId.of(index.toUInt()) }
        val constantPool = ConstantPoolBuilder()
        (
            (literalCollector.values + inlineValueClasses.constantValues() + platformScalars.constantValues())
                .map { value -> value.toArtifactConstant(literalIds) } +
                Constant.I32(0) +
                listOfNotNull(Constant.I32(-1).takeIf { needsAllBitsI32 }) +
                Constant.Bool(false)
        ).forEach(constantPool::intern)
        val constants = constantPool.freeze().records
        val constantIds = constants.withIndex().associate { (index, value) -> value to ConstantId.of(index.toUInt()) }

        val library = kotlinLibrary()
        val libraryHash = ArtifactWriter.moduleSemanticHash(library)
        val charArrayType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(CHAR_ARRAY_RUNTIME_TYPE)))
        val stringType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(STRING_RUNTIME_TYPE)))
        val intArrayType = ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(INT_ARRAY_RUNTIME_TYPE)))
        val functionIds = userFunctions.withIndex().associate { (index, function) -> function.symbol to FunctionId.of(index.toUInt()) }
        val functionTypeIds = userFunctions.withIndex().associate { (index, function) -> function.symbol to TypeId.of(index.toUInt()) }
        val constructorFunctionIds =
            constructorClasses.withIndex().associate { (index, declaration) ->
                requireNotNull(declaration.constructors.singleOrNull { it.isPrimary }).symbol to
                    FunctionId.of((userFunctions.size + index).toUInt())
            }
        val constructorTypeIds =
            constructorClasses.withIndex().associate { (index, declaration) ->
                declaration.symbol to TypeId.of((userFunctions.size + index).toUInt())
            }
        val initializerFunctionIds =
            initializerClasses.withIndex().associate { (index, declaration) ->
                declaration.symbol to FunctionId.of((userFunctions.size + constructorClasses.size + index).toUInt())
            }
        val classTypeIds =
            userClasses.withIndex().associate { (index, declaration) ->
                declaration.symbol to TypeId.of((userFunctions.size + constructorClasses.size + index).toUInt())
            }
        val initializerTypeBase =
            userFunctions.size + constructorClasses.size + userClasses.size + if (usesStringArray) 1 else 0
        val initializerTypeIds =
            initializerClasses.withIndex().associate { (index, declaration) ->
                declaration.symbol to TypeId.of((initializerTypeBase + index).toUInt())
            }
        val externalTypeImports =
            linkedSymbols.types.entries
                .sortedBy { (_, target) -> target.sortKey }
                .mapIndexed { index, (symbol, target) ->
                    symbol to target.copy(importId = ImportId.of((runtimeTypeNames.size + index).toUInt()))
                }.toMap()
        val externalClassTypes = externalTypeImports.mapValues { (_, target) -> TypeRef.Imported(target.importId) }
        val externalFieldImports =
            (linkedSymbols.fieldsByGetter.values + linkedSymbols.enumEntries.values + linkedSymbols.defaultEnumEntries.values)
                .distinctBy(ExternalFieldTarget::sortKey)
                .sortedBy(ExternalFieldTarget::sortKey)
                .mapIndexed { index, target ->
                    target.copy(importId = ImportId.of((runtimeTypeNames.size + externalTypeImports.size + index).toUInt()))
                }
        val externalFieldsBySortKey = externalFieldImports.associateBy(ExternalFieldTarget::sortKey)
        val externalGetterFieldImports =
            linkedSymbols.fieldsByGetter.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
        val externalEnumFieldImports =
            linkedSymbols.enumEntries.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
        val externalDefaultEnumFieldImports =
            linkedSymbols.defaultEnumEntries.mapValues { (_, target) -> requireNotNull(externalFieldsBySortKey[target.sortKey]) }
        val externalFieldImportCount = externalFieldImports.size
        val externalFunctionTypeBase = initializerTypeBase + initializerClasses.size
        val externalFunctionImports =
            externalFunctions.entries
                .sortedBy { (_, target) -> target.sortKey }
                .mapIndexed { index, (symbol, target) ->
                    symbol to
                        target.copy(
                            importId =
                                ImportId.of(
                                    (runtimeTypeNames.size + externalTypeImports.size + externalFieldImportCount + index).toUInt(),
                                ),
                        )
                }.toMap()
        val stringArrayType =
            ValueType.Ref(
                nullable = false,
                type = TypeRef.Local(TypeId.of((userFunctions.size + constructorClasses.size + userClasses.size).toUInt())),
            )
        userFunctions.forEach {
            validateFunction(it, pluginContext, guestTypes, classTypeIds, externalClassTypes, inlineValueClasses, platformScalars, session)
        }
        val classLayouts =
            buildClassLayouts(
                userClasses,
                classTypeIds,
                pluginContext,
                guestTypes,
                stringType,
                charArrayType,
                stringArrayType,
                inlineValueClasses,
                platformScalars,
                externalClassTypes,
            )
        val classLayoutsBySymbol = classLayouts.associateBy { it.declaration.symbol }
        val blocks = mutableListOf<Block>()
        val loweredFunctions = mutableListOf<Function>()

        userFunctions.forEach { function ->
            val functionId = requireNotNull(functionIds[function.symbol])
            val firstBlock = blocks.size
            val compiler =
                FunctionCompiler(
                    function = function,
                    functionId = functionId,
                    blockBase = firstBlock,
                    stringType = stringType,
                    charArrayType = charArrayType,
                    intArrayType = intArrayType,
                    stringArrayType = stringArrayType,
                    guestTypes = guestTypes,
                    unitType = pluginContext.irBuiltIns.unitType,
                    kotlinStringType = pluginContext.irBuiltIns.stringType,
                    kotlinCharArrayClass = pluginContext.irBuiltIns.charArray,
                    kotlinIntArrayClass = pluginContext.irBuiltIns.intArray,
                    intType = pluginContext.irBuiltIns.intType,
                    booleanType = pluginContext.irBuiltIns.booleanType,
                    charType = pluginContext.irBuiltIns.charType,
                    functionIds = functionIds,
                    constantIds = constantIds,
                    literalIds = literalIds,
                    session = session,
                    capabilityIds = capabilityIds,
                    classTypeIds = classTypeIds,
                    externalClassTypes = externalClassTypes,
                    inlineValueClasses = inlineValueClasses,
                    platformScalars = platformScalars,
                    constructorLayouts =
                        classLayouts
                            .mapNotNull { layout ->
                                layout.declaration.constructors.singleOrNull { it.isPrimary }?.symbol?.let { symbol ->
                                    constructorFunctionIds[symbol]?.let { symbol to GuestConstructorTarget(layout, it) }
                                }
                            }.toMap(),
                    fieldsByGetter =
                        classLayouts
                            .flatMap { layout ->
                                layout.fields.map { field -> requireNotNull(field.property.getter).symbol to field }
                            }.toMap(),
                    enumEntries =
                        classLayouts.flatMap { layout -> layout.enumEntries }.associateBy { it.declaration.symbol },
                    externalFieldsByGetter = externalGetterFieldImports,
                    externalEnumEntries = externalEnumFieldImports,
                    externalDefaultEnumEntries = externalDefaultEnumFieldImports,
                    externalFunctions = externalFunctionImports,
                )
            val compiled = compiler.compile()
            blocks += compiled.blocks
            val resultType =
                valueType(
                    function.returnType,
                    pluginContext,
                    guestTypes,
                    stringType,
                    charArrayType,
                    stringArrayType,
                    classTypeIds,
                    externalClassTypes,
                    inlineValueClasses,
                    platformScalars,
                    function,
                )
            val parameterTypes =
                loweredParameters(function, session).map {
                    valueType(
                        it.type,
                        pluginContext,
                        guestTypes,
                        stringType,
                        charArrayType,
                        stringArrayType,
                        classTypeIds,
                        externalClassTypes,
                        inlineValueClasses,
                        platformScalars,
                        it,
                    )
                }
            val flags = setOfNotNull(FunctionFlag.STATIC, FunctionFlag.SUSPENDING.takeIf { function.isSuspend })
            loweredFunctions +=
                Function(
                    owner = null,
                    name = requireNotNull(metadataIds[functionArtifactNames[function.symbol]]),
                    signature = TypeRef.Local(requireNotNull(functionTypeIds[function.symbol])),
                    flags = flags,
                    values = (parameterTypes + compiled.localTypes).map(FunctionValue::scalar),
                    parameterCount = parameterTypes.size.toUInt(),
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = compiled.blocks.size.toUInt(),
                    firstException = 0u,
                    exceptionCount = 0u,
                )
        }

        constructorClasses.forEach { declaration ->
            val layout = requireNotNull(classLayoutsBySymbol[declaration.symbol])
            val constructor = requireNotNull(declaration.constructors.singleOrNull { it.isPrimary })
            val functionId = requireNotNull(constructorFunctionIds[constructor.symbol])
            val parameterTypes =
                layout.fields.sortedBy { it.constructorParameterIndex }.map { it.type }
            val resultType = ValueType.Ref(nullable = false, type = TypeRef.Local(layout.typeId))
            val result = RegisterId.of(parameterTypes.size.toUInt())
            val firstBlock = blocks.size
            blocks +=
                Block(
                    owner = functionId,
                    loopHeaderSafepoint = false,
                    instructions =
                        listOf(Instruction.NewObject(result, resultType.type)) +
                            layout.fields.map { field ->
                                Instruction.FieldSet(
                                    result,
                                    FieldRef.Local(field.id),
                                    RegisterId.of(field.constructorParameterIndex.toUInt()),
                                )
                            } + Instruction.Return(Destination.Register(result)),
                )
            loweredFunctions +=
                Function(
                    owner = null,
                    name = requireNotNull(metadataIds[constructorName(declaration)]),
                    signature = TypeRef.Local(requireNotNull(constructorTypeIds[declaration.symbol])),
                    flags = setOf(FunctionFlag.STATIC),
                    values = (parameterTypes + resultType).map(FunctionValue::scalar),
                    parameterCount = parameterTypes.size.toUInt(),
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = 1u,
                    firstException = 0u,
                    exceptionCount = 0u,
                )
        }

        initializerClasses.forEach { declaration ->
            val layout = requireNotNull(classLayoutsBySymbol[declaration.symbol])
            val functionId = requireNotNull(initializerFunctionIds[declaration.symbol])
            val firstBlock = blocks.size
            layout.enumEntries.forEachIndexed { index, enumEntry ->
                val nextBlock = firstBlock + index + 1
                blocks +=
                    Block(
                        owner = functionId,
                        loopHeaderSafepoint = false,
                        instructions =
                            listOf(
                                Instruction.NewObject(RegisterId.of(index.toUInt()), TypeRef.Local(layout.typeId)),
                                Instruction.StaticSet(FieldRef.Local(enumEntry.fieldId), RegisterId.of(index.toUInt())),
                                Instruction.Jump(BlockId.of(nextBlock.toUInt())),
                            ),
                    )
            }
            blocks += Block(functionId, false, listOf(Instruction.Return(Destination.Unit)))
            loweredFunctions +=
                Function(
                    owner = TypeRef.Local(layout.typeId),
                    name = requireNotNull(metadataIds["<clinit>"]),
                    signature = TypeRef.Local(requireNotNull(initializerTypeIds[declaration.symbol])),
                    flags = setOf(FunctionFlag.STATIC),
                    values =
                        List(layout.enumEntries.size) {
                            FunctionValue.scalar(ValueType.Ref(nullable = false, type = TypeRef.Local(layout.typeId)))
                        },
                    parameterCount = 0u,
                    firstBlock = BlockId.of(firstBlock.toUInt()),
                    blockCount = (layout.enumEntries.size + 1).toUInt(),
                    firstException = 0u,
                    exceptionCount = 0u,
                )
        }

        val functionTypes =
            userFunctions.map { function ->
                NominalType.Function(
                    name = requireNotNull(metadataIds[functionArtifactNames[function.symbol]]),
                    suspending = function.isSuspend,
                    result =
                        valueType(
                            function.returnType,
                            pluginContext,
                            guestTypes,
                            stringType,
                            charArrayType,
                            stringArrayType,
                            classTypeIds,
                            externalClassTypes,
                            inlineValueClasses,
                            platformScalars,
                            function,
                        ),
                    parameters =
                        loweredParameters(function, session).map {
                            valueType(
                                it.type,
                                pluginContext,
                                guestTypes,
                                stringType,
                                charArrayType,
                                stringArrayType,
                                classTypeIds,
                                externalClassTypes,
                                inlineValueClasses,
                                platformScalars,
                                it,
                            )
                        },
                )
            }
        val constructorTypes =
            constructorClasses.map { declaration ->
                val layout = requireNotNull(classLayoutsBySymbol[declaration.symbol])
                NominalType.Function(
                    name = requireNotNull(metadataIds[constructorName(declaration)]),
                    suspending = false,
                    result = ValueType.Ref(nullable = false, type = TypeRef.Local(layout.typeId)),
                    parameters = layout.fields.sortedBy { it.constructorParameterIndex }.map { it.type },
                )
            }
        val initializerTypes =
            initializerClasses.map {
                NominalType.Function(
                    name = requireNotNull(metadataIds["<clinit>"]),
                    suspending = false,
                    result = ValueType.Unit,
                    parameters = emptyList(),
                )
            }
        val classTypes =
            classLayouts.map { layout ->
                val declaration = layout.declaration
                val sourceParents =
                    declaration.superTypes.mapNotNull { superType ->
                        val symbol = (superType as? IrSimpleType)?.classifier as? IrClassSymbol
                        symbol?.let { source -> classTypeIds[source]?.let { source to TypeRef.Local(it) } }
                    }
                val interfaces = sourceParents.filter { (symbol, _) -> symbol.owner.kind == ClassKind.INTERFACE }.map { it.second }
                val superType = sourceParents.firstOrNull { (symbol, _) -> symbol.owner.kind != ClassKind.INTERFACE }?.second
                val name = declaration.fqNameWhenAvailable?.asString() ?: declaration.name.asString()
                if (declaration.kind == ClassKind.INTERFACE) {
                    NominalType.Interface(
                        name = requireNotNull(metadataIds[name]),
                        sealed = declaration.modality == Modality.SEALED,
                        superType = superType,
                        interfaces = interfaces,
                    )
                } else {
                    NominalType.Class(
                        name = requireNotNull(metadataIds[name]),
                        abstract = declaration.modality == Modality.ABSTRACT || declaration.modality == Modality.SEALED,
                        final = declaration.modality == Modality.FINAL,
                        superType = superType,
                        interfaces = interfaces,
                        fieldStart = layout.firstField,
                        fieldCount = (layout.fields.size + layout.enumEntries.size).toUInt(),
                        initializer = initializerFunctionIds[declaration.symbol],
                    )
                }
            }
        val artifactFields =
            classLayouts.flatMap { layout ->
                val owner = TypeRef.Local(layout.typeId)
                layout.fields.map { field ->
                    Field(
                        owner = owner,
                        name = requireNotNull(metadataIds[field.property.name.asString()]),
                        type = field.type,
                        mutable = true,
                        static = false,
                    )
                } +
                    layout.enumEntries.map { enumEntry ->
                        Field(
                            owner = owner,
                            name = requireNotNull(metadataIds[enumEntry.declaration.name.asString()]),
                            type = ValueType.Ref(nullable = false, type = owner),
                            mutable = true,
                            static = true,
                        )
                    }
            }
        val externalFunctionTypes =
            externalFunctionImports.entries
                .sortedBy { (_, target) -> target.sortKey }
                .map { (symbol, target) ->
                    val function = symbol.owner
                    NominalType.Function(
                        name = requireNotNull(metadataIds[target.exportName]),
                        suspending = function.isSuspend,
                        result =
                            valueType(
                                function.returnType,
                                pluginContext,
                                guestTypes,
                                stringType,
                                charArrayType,
                                stringArrayType,
                                classTypeIds,
                                externalClassTypes,
                                inlineValueClasses,
                                platformScalars,
                                function,
                            ),
                        parameters =
                            loweredParameters(function, session).map { parameter ->
                                valueType(
                                    parameter.type,
                                    pluginContext,
                                    guestTypes,
                                    stringType,
                                    charArrayType,
                                    stringArrayType,
                                    classTypeIds,
                                    externalClassTypes,
                                    inlineValueClasses,
                                    platformScalars,
                                    parameter,
                                )
                            },
                    )
                }
        val app =
            Module(
                name = requireNotNull(metadataIds["app"]),
                kind = ModuleKind.APPLICATION,
                strings = metadataValues,
                utf16Literals = literals,
                types =
                    functionTypes +
                        constructorTypes +
                        classTypes +
                        if (usesStringArray) {
                            listOf(
                                NominalType.Array(
                                    name = requireNotNull(metadataIds["kotlin.Array"]),
                                    element = stringType,
                                ),
                            )
                        } else {
                            emptyList()
                        } + initializerTypes + externalFunctionTypes,
                constants = constants,
                fields = artifactFields,
                imports =
                    runtimeTypeNames.indices.map { index ->
                        runtimeTypeImport(index, requireNotNull(metadataIds[runtimeTypeNames[index]]), libraryHash)
                    } +
                        externalTypeImports.entries
                            .sortedBy { (_, target) -> target.sortKey }
                            .mapIndexed { index, (_, target) ->
                                Import(
                                    kind = SymbolKind.TYPE,
                                    targetModule = ModuleId.of((2 + index).toUInt()),
                                    targetName = requireNotNull(metadataIds[target.exportName]),
                                    expectedSignature = TypeRef.Imported(target.importId),
                                    targetModuleHash = target.moduleHash,
                                )
                            } +
                        externalFieldImports.mapIndexed { index, target ->
                            Import(
                                kind = SymbolKind.FIELD,
                                targetModule = ModuleId.of((2 + externalTypeImports.size + index).toUInt()),
                                targetName = requireNotNull(metadataIds[target.exportName]),
                                expectedSignature = requireNotNull(externalClassTypes[target.ownerSymbol]),
                                targetModuleHash = target.moduleHash,
                            )
                        } +
                        externalFunctionImports.entries
                            .sortedBy { (_, target) -> target.sortKey }
                            .mapIndexed { index, (_, target) ->
                                Import(
                                    kind = SymbolKind.FUNCTION,
                                    targetModule = ModuleId.of((2 + externalTypeImports.size + externalFieldImportCount + index).toUInt()),
                                    targetName = requireNotNull(metadataIds[target.exportName]),
                                    expectedSignature = TypeRef.Local(TypeId.of((externalFunctionTypeBase + index).toUInt())),
                                    targetModuleHash = target.moduleHash,
                                )
                            },
                functions = loweredFunctions,
                blocks = blocks,
            )
        val modules = listOf(app, library)
        val maximumCallDepth = 16u
        val requiredStackBytes = ExecutionStorage.requiredStackBytes(modules, maximumCallDepth)
        return Artifact(
            semanticFeatures =
                setOfNotNull(
                    SemanticFeature.COROUTINES.takeIf { userFunctions.any { it.isSuspend } },
                    SemanticFeature.CAPABILITIES.takeIf { capabilityIdentities.isNotEmpty() },
                    SemanticFeature.MODULE_IMPORTS,
                ),
            manifest =
                Manifest(
                    requiredHeapBytes = 64u * 1024u,
                    requiredStackBytes = requiredStackBytes,
                    maximumCoroutines = 1u,
                    maximumCallDepth = maximumCallDepth,
                    maximumHostRequests = 64u,
                    maximumEvents = 0u,
                    maximumBlockCost = 64u,
                    minimumSliceCost = 64u,
                    compilerAbi = ByteArray(32),
                    platformAbi = ByteArray(32),
                ),
            entry =
                EntryPoint(
                    ModuleId.of(0u),
                    requireNotNull(functionIds[entry.symbol]),
                    if (entry.parameters.isEmpty()) EntryArguments.NONE else EntryArguments.STRING_ARRAY,
                ),
            modules = modules,
            capabilities =
                capabilityIdentities.map { identity ->
                    Capability(
                        namespace = requireNotNull(metadataIds[identity.namespace]),
                        name = requireNotNull(metadataIds[identity.name]),
                        abi = AbiVersion(identity.abiMajor, identity.abiMinor),
                        required = true,
                        operationCount = identity.operationCount,
                    )
                },
        )
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun artifactFunctionName(
        function: IrSimpleFunction,
        pluginContext: IrPluginContext,
        inlineValueClasses: InlineValueClassRegistry,
        session: CompilationSession,
    ): String {
        val signatureTypes = loweredParameters(function, session).map { it.type } + function.returnType
        if (signatureTypes.none { type ->
                inlineValueClasses.contains((type as? IrSimpleType)?.classifier as? IrClassSymbol)
            }
        ) {
            return function.name.asString()
        }
        val parameters =
            loweredParameters(function, session).joinToString(",") { parameter ->
                sourceTypeName(parameter.type, pluginContext, inlineValueClasses)
            }
        val result = sourceTypeName(function.returnType, pluginContext, inlineValueClasses)
        return "${function.name.asString()}#($parameters)->$result"
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun sourceTypeName(
        type: IrType,
        pluginContext: IrPluginContext,
        inlineValueClasses: InlineValueClassRegistry,
    ): String =
        when (type) {
            pluginContext.irBuiltIns.unitType -> {
                "kotlin.Unit"
            }

            pluginContext.irBuiltIns.intType -> {
                "kotlin.Int"
            }

            pluginContext.irBuiltIns.booleanType -> {
                "kotlin.Boolean"
            }

            pluginContext.irBuiltIns.charType -> {
                "kotlin.Char"
            }

            pluginContext.irBuiltIns.stringType -> {
                "kotlin.String"
            }

            else -> {
                val classifier = requireNotNull((type as? IrSimpleType)?.classifier as? IrClassSymbol)
                inlineValueClasses[classifier]
                    ?.declaration
                    ?.name
                    ?.asString()
                    ?: classifier.owner.fqNameWhenAvailable?.asString()
                    ?: classifier.owner.name.asString()
            }
        }

    private fun validateFunction(
        function: IrSimpleFunction,
        pluginContext: IrPluginContext,
        guestTypes: GuestTypeRegistry,
        classTypeIds: Map<IrClassSymbol, TypeId>,
        externalClassTypes: Map<IrClassSymbol, TypeRef.Imported>,
        inlineValueClasses: InlineValueClassRegistry,
        platformScalars: PlatformScalarRegistry,
        session: CompilationSession,
    ) {
        val supported =
            setOf(
                pluginContext.irBuiltIns.unitType,
                pluginContext.irBuiltIns.stringType,
                pluginContext.irBuiltIns.intType,
                pluginContext.irBuiltIns.booleanType,
                pluginContext.irBuiltIns.charType,
            )

        fun isSupported(type: IrType): Boolean =
            type in supported || type.isNothing() ||
                type.isExactClass(pluginContext.irBuiltIns.charArray) ||
                type.isExactClass(pluginContext.irBuiltIns.intArray) ||
                guestTypes.isStringArray(type) ||
                (
                    !type.isNullable() &&
                        inlineValueClasses.contains((type as? IrSimpleType)?.classifier as? IrClassSymbol)
                ) ||
                (!type.isNullable() && platformScalars.representation(type) != null) ||
                classTypeIds.containsKey((type as? IrSimpleType)?.classifier) ||
                externalClassTypes.containsKey((type as? IrSimpleType)?.classifier)
        if (loweredParameters(function, session).any { !isSupported(it.type) } ||
            !isSupported(function.returnType)
        ) {
            throw UnsupportedKotlinIr(function, "unsupported function signature")
        }
    }

    private fun valueType(
        type: IrType,
        pluginContext: IrPluginContext,
        guestTypes: GuestTypeRegistry,
        stringType: ValueType,
        charArrayType: ValueType,
        stringArrayType: ValueType,
        classTypeIds: Map<IrClassSymbol, TypeId>,
        externalClassTypes: Map<IrClassSymbol, TypeRef.Imported>,
        inlineValueClasses: InlineValueClassRegistry,
        platformScalars: PlatformScalarRegistry,
        element: IrElement,
    ): ValueType =
        when (type) {
            pluginContext.irBuiltIns.unitType -> {
                ValueType.Unit
            }

            pluginContext.irBuiltIns.stringType -> {
                stringType
            }

            pluginContext.irBuiltIns.intType -> {
                ValueType.I32
            }

            pluginContext.irBuiltIns.booleanType -> {
                ValueType.Bool
            }

            pluginContext.irBuiltIns.charType -> {
                ValueType.Char
            }

            else -> {
                if (type.isNothing()) {
                    ValueType.Unit
                } else if (type.isExactClass(pluginContext.irBuiltIns.charArray)) {
                    charArrayType
                } else if (type.isExactClass(pluginContext.irBuiltIns.intArray)) {
                    ValueType.Ref(nullable = false, type = TypeRef.Imported(ImportId.of(INT_ARRAY_RUNTIME_TYPE)))
                } else if (guestTypes.isStringArray(type)) {
                    stringArrayType
                } else if (type is IrSimpleType && type.classifier is IrClassSymbol) {
                    val classifier = type.classifier as IrClassSymbol
                    val inline = inlineValueClasses[classifier]
                    val id = classTypeIds[classifier]
                    val external = externalClassTypes[classifier]
                    val platformScalar = platformScalars.representation(type)
                    if (platformScalar != null) {
                        if (type.isNullable()) throw UnsupportedKotlinIr(element, "nullable platform scalar types are not supported")
                        when (platformScalar) {
                            PlatformScalarRepresentation.INT -> ValueType.I32
                            PlatformScalarRepresentation.BOOLEAN -> ValueType.Bool
                            PlatformScalarRepresentation.CHAR -> ValueType.Char
                        }
                    } else if (inline != null) {
                        if (type.isNullable()) throw UnsupportedKotlinIr(element, "nullable value classes are not supported")
                        valueType(
                            inline.underlyingType,
                            pluginContext,
                            guestTypes,
                            stringType,
                            charArrayType,
                            stringArrayType,
                            classTypeIds,
                            externalClassTypes,
                            inlineValueClasses,
                            platformScalars,
                            element,
                        )
                    } else if (id != null) {
                        ValueType.Ref(nullable = type.isNullable(), type = TypeRef.Local(id))
                    } else if (external != null) {
                        ValueType.Ref(nullable = type.isNullable(), type = external)
                    } else {
                        throw UnsupportedKotlinIr(element, "unsupported value type")
                    }
                } else {
                    throw UnsupportedKotlinIr(element, "unsupported value type")
                }
            }
        }

    private fun buildClassLayouts(
        classes: List<IrClass>,
        classTypeIds: Map<IrClassSymbol, TypeId>,
        pluginContext: IrPluginContext,
        guestTypes: GuestTypeRegistry,
        stringType: ValueType,
        charArrayType: ValueType,
        stringArrayType: ValueType,
        inlineValueClasses: InlineValueClassRegistry,
        platformScalars: PlatformScalarRegistry,
        externalClassTypes: Map<IrClassSymbol, TypeRef.Imported>,
    ): List<GuestClassLayout> {
        var nextField = 0u
        return classes.map { declaration ->
            if (declaration.typeParameters.isNotEmpty() ||
                declaration.kind !in setOf(ClassKind.CLASS, ClassKind.INTERFACE, ClassKind.ENUM_CLASS)
            ) {
                throw UnsupportedKotlinIr(
                    declaration,
                    "class ${declaration.fqNameWhenAvailable?.asString() ?: declaration.name} (${declaration.kind}) is outside the project subset",
                )
            }
            if (declaration.declarations.any { it is IrAnonymousInitializer }) {
                throw UnsupportedKotlinIr(declaration, "custom class initializers are not supported")
            }
            val typeId = requireNotNull(classTypeIds[declaration.symbol])
            val firstField = nextField
            val constructor = declaration.constructors.singleOrNull { it.isPrimary }
            if (declaration.constructors.any { !it.isPrimary }) {
                throw UnsupportedKotlinIr(declaration, "secondary constructors are not supported")
            }
            val parameters = constructor?.parameters?.filter { it.kind == IrParameterKind.Regular }.orEmpty()
            val declaredProperties = declaration.declarations.filterIsInstance<IrProperty>()
            if (declaredProperties.any { it.backingField == null && it.origin == IrDeclarationOrigin.DEFINED }) {
                throw UnsupportedKotlinIr(declaration, "computed or abstract properties are not supported")
            }
            val properties = declaredProperties.filter { it.backingField != null }
            if (parameters.any { it.defaultValue != null }) {
                throw UnsupportedKotlinIr(declaration, "default constructor arguments are not supported")
            }
            if (declaration.kind == ClassKind.ENUM_CLASS && (parameters.isNotEmpty() || properties.isNotEmpty())) {
                throw UnsupportedKotlinIr(declaration, "enum constructor state is not supported")
            }
            val fields =
                properties.map { property ->
                    if (property.isVar ||
                        property.getter == null ||
                        property.getter?.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
                    ) {
                        throw UnsupportedKotlinIr(property, "only immutable constructor properties are supported")
                    }
                    val parameterIndex = parameters.indexOfFirst { it.name == property.name }
                    if (parameterIndex < 0) {
                        throw UnsupportedKotlinIr(property, "property is not backed by a primary-constructor parameter")
                    }
                    val fieldType =
                        valueType(
                            requireNotNull(property.backingField).type,
                            pluginContext,
                            guestTypes,
                            stringType,
                            charArrayType,
                            stringArrayType,
                            classTypeIds,
                            externalClassTypes,
                            inlineValueClasses,
                            platformScalars,
                            property,
                        )
                    GuestFieldLayout(property, parameterIndex, FieldId.of(nextField++), fieldType)
                }
            if (declaration.kind == ClassKind.CLASS && fields.size != parameters.size) {
                throw UnsupportedKotlinIr(declaration, "every constructor parameter must be an immutable property")
            }
            val owner = TypeRef.Local(typeId)
            val entries =
                declaration.declarations.filterIsInstance<IrEnumEntry>().map { enumEntry ->
                    GuestEnumEntryLayout(enumEntry, FieldId.of(nextField++), owner)
                }
            GuestClassLayout(declaration, typeId, firstField, fields, entries)
        }
    }

    private fun kotlinLibrary(): Module =
        Module(
            name = StringId.of(0u),
            kind = ModuleKind.LIBRARY,
            strings =
                runtimeTypeNames.map(MetadataText::of).sorted(),
            types =
                listOf(
                    NominalType.Array(name = StringId.of(0u), element = ValueType.Char),
                    NominalType.Class(name = StringId.of(2u), final = true),
                    NominalType.Class(name = StringId.of(3u)),
                    NominalType.Class(name = StringId.of(4u), final = true, superType = TypeRef.Local(TypeId.of(2u))),
                    NominalType.Array(name = StringId.of(1u), element = ValueType.I32),
                ),
            exports =
                listOf(
                    Export(
                        kind = SymbolKind.TYPE,
                        visibility = ExportVisibility.PUBLIC_LIBRARY,
                        name = StringId.of(0u),
                        localSymbol = 0u,
                        signature = TypeRef.Local(TypeId.of(0u)),
                    ),
                    Export(
                        kind = SymbolKind.TYPE,
                        visibility = ExportVisibility.PUBLIC_LIBRARY,
                        name = StringId.of(2u),
                        localSymbol = 1u,
                        signature = TypeRef.Local(TypeId.of(1u)),
                    ),
                    Export(
                        kind = SymbolKind.TYPE,
                        visibility = ExportVisibility.PUBLIC_LIBRARY,
                        name = StringId.of(3u),
                        localSymbol = 2u,
                        signature = TypeRef.Local(TypeId.of(2u)),
                    ),
                    Export(
                        kind = SymbolKind.TYPE,
                        visibility = ExportVisibility.PUBLIC_LIBRARY,
                        name = StringId.of(4u),
                        localSymbol = 3u,
                        signature = TypeRef.Local(TypeId.of(3u)),
                    ),
                    Export(
                        kind = SymbolKind.TYPE,
                        visibility = ExportVisibility.PUBLIC_LIBRARY,
                        name = StringId.of(1u),
                        localSymbol = 4u,
                        signature = TypeRef.Local(TypeId.of(4u)),
                    ),
                ),
        )

    private fun runtimeTypeImport(
        index: Int,
        targetName: StringId,
        libraryHash: ByteArray,
    ): Import {
        val id = index.toUInt()
        return Import(
            kind = SymbolKind.TYPE,
            targetModule = ModuleId.of(1u),
            targetName = targetName,
            expectedSignature = TypeRef.Imported(ImportId.of(id)),
            targetModuleHash = libraryHash,
        )
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun collectGuestClasses(
    sourceClasses: List<IrClass>,
    functions: List<IrSimpleFunction>,
): List<IrClass> {
    val sourceSymbols = sourceClasses.mapTo(mutableSetOf()) { it.symbol }
    val collected = linkedMapOf<IrClassSymbol, IrClass>()
    val pending = ArrayDeque<IrClass>()

    fun consider(declaration: IrClass) {
        val source = declaration.symbol in sourceSymbols
        if (source) {
            if (collected.putIfAbsent(declaration.symbol, declaration) == null) pending.addLast(declaration)
        }
    }

    fun consider(type: IrType) {
        ((type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.let(::consider)
    }

    sourceClasses.forEach(::consider)
    val references =
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildren(this, null)
            }

            override fun visitExpression(expression: IrExpression) {
                consider(expression.type)
                super.visitExpression(expression)
            }

            override fun visitTypeOperator(expression: IrTypeOperatorCall) {
                consider(expression.typeOperand)
                super.visitTypeOperator(expression)
            }
        }
    functions.forEach { function ->
        consider(function.returnType)
        function.parameters.forEach { consider(it.type) }
        function.accept(references, null)
    }

    while (pending.isNotEmpty()) {
        val declaration = pending.removeFirst()
        declaration.superTypes.forEach(::consider)
        declaration.declarations.filterIsInstance<IrProperty>().forEach { property ->
            property.backingField?.type?.let(::consider)
        }
    }
    return collected.values.sortedBy { it.fqNameWhenAvailable?.asString().orEmpty() }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun constructorName(declaration: IrClass): String =
    "<init:${declaration.fqNameWhenAvailable?.asString() ?: declaration.name.asString()}>"

private data class CompiledFunction(
    val localTypes: List<ValueType>,
    val blocks: List<Block>,
)

private sealed interface ResolvedCallArgument {
    data class Expression(
        val expression: IrExpression,
    ) : ResolvedCallArgument

    data class PlatformDefault(
        val value: PlatformDefaultArgument,
    ) : ResolvedCallArgument
}

private data class ExternalFunctionTarget(
    val exportName: String,
    val moduleHash: ByteArray,
    val importId: ImportId = ImportId.of(0u),
) {
    val sortKey: String get() = "${moduleHash.joinToString("") { "%02x".format(it) }}:$exportName"
}

private data class ExternalTypeTarget(
    val exportName: String,
    val moduleHash: ByteArray,
    val importId: ImportId = ImportId.of(0u),
) {
    val sortKey: String get() = "${moduleHash.joinToString("") { "%02x".format(it) }}:$exportName"
}

private data class ExternalFieldTarget(
    val exportName: String,
    val ownerSymbol: IrClassSymbol,
    val moduleHash: ByteArray,
    val static: Boolean,
    val importId: ImportId = ImportId.of(0u),
) {
    val sortKey: String get() = "${moduleHash.joinToString("") { "%02x".format(it) }}:$exportName"
}

private data class LinkedPlatformSymbols(
    val types: Map<IrClassSymbol, ExternalTypeTarget>,
    val fieldsByGetter: Map<IrSimpleFunctionSymbol, ExternalFieldTarget>,
    val enumEntries: Map<IrEnumEntrySymbol, ExternalFieldTarget>,
    val defaultEnumEntries: Map<String, ExternalFieldTarget>,
)

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun linkedPlatformSymbols(
    functions: List<IrSimpleFunction>,
    session: CompilationSession,
): LinkedPlatformSymbols {
    val typeLinks = session.platformTypes.associateBy { it.symbol }
    val fieldLinks = session.platformFields.associateBy { it.symbol }
    val types = linkedMapOf<IrClassSymbol, ExternalTypeTarget>()
    val fieldsByGetter = linkedMapOf<IrSimpleFunctionSymbol, ExternalFieldTarget>()
    val enumEntries = linkedMapOf<IrEnumEntrySymbol, ExternalFieldTarget>()
    val classSymbols = linkedMapOf<String, IrClassSymbol>()
    val neededDefaultArguments = mutableListOf<PlatformDefaultArgument>()

    fun considerTypeSymbol(symbol: IrClassSymbol) {
        val fqName = symbol.owner.fqNameWhenAvailable?.asString() ?: return
        classSymbols[fqName] = symbol
        if (fqName == "kotlin.IntArray") return
        val link = typeLinks[fqName] ?: return
        types[symbol] = ExternalTypeTarget(link.exportName, link.moduleHash.copyOf())
    }

    fun considerType(type: IrType) {
        val symbol = (type as? IrSimpleType)?.classifier as? IrClassSymbol ?: return
        considerTypeSymbol(symbol)
    }

    fun fieldTarget(
        symbol: String,
        owner: IrClassSymbol,
    ): ExternalFieldTarget? {
        val link = fieldLinks[symbol] ?: return null
        considerTypeSymbol(owner)
        return ExternalFieldTarget(link.exportName, owner, link.moduleHash.copyOf(), link.static)
    }

    val visitor =
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (element is IrExpression) considerType(element.type)
                element.acceptChildren(this, null)
            }

            override fun visitCall(expression: IrCall) {
                considerType(expression.type)
                val target = expression.symbol.owner
                considerType(target.returnType)
                target.parameters.forEach { considerType(it.type) }
                val targetSymbol = target.fqNameWhenAvailable?.asString()
                val targetSignature = target.canonicalPlatformSignature()
                session.platformFunctions
                    .singleOrNull { link -> link.symbol == targetSymbol && link.signature == targetSignature }
                    ?.defaultArguments
                    ?.filterNotNull()
                    ?.let(neededDefaultArguments::addAll)
                val property = target.correspondingPropertySymbol?.owner ?: target.parent as? IrProperty
                val owner = property?.parent as? IrClass
                val fieldSymbol = property?.fqNameWhenAvailable?.asString()
                if (owner != null && fieldSymbol != null) {
                    fieldTarget(fieldSymbol, owner.symbol)?.let { field ->
                        if (field.static) throw UnsupportedKotlinIr(expression, "platform property getter resolves to a static field")
                        fieldsByGetter[target.symbol] = field
                    }
                }
                super.visitCall(expression)
            }

            override fun visitGetEnumValue(expression: IrGetEnumValue) {
                considerType(expression.type)
                val entry = expression.symbol.owner
                val owner = entry.parent as? IrClass
                val fieldSymbol = entry.fqNameWhenAvailable?.asString()
                if (owner != null && fieldSymbol != null) {
                    fieldTarget(fieldSymbol, owner.symbol)?.let { field ->
                        if (!field.static) throw UnsupportedKotlinIr(expression, "platform enum entry resolves to an instance field")
                        enumEntries[expression.symbol] = field
                    }
                }
                super.visitGetEnumValue(expression)
            }

            override fun visitTypeOperator(expression: IrTypeOperatorCall) {
                considerType(expression.typeOperand)
                considerType(expression.type)
                super.visitTypeOperator(expression)
            }
        }
    functions.forEach { function ->
        considerType(function.returnType)
        function.parameters.forEach { considerType(it.type) }
        function.accept(visitor, null)
    }
    val defaultEnumEntries = linkedMapOf<String, ExternalFieldTarget>()
    neededDefaultArguments
        .filterIsInstance<PlatformDefaultArgument.EnumEntry>()
        .forEach { argument ->
            val owner =
                classSymbols[argument.symbol.substringBeforeLast('.')]
                    ?: throw IllegalArgumentException("platform enum default owner is unavailable: ${argument.symbol}")
            val field =
                fieldTarget(argument.symbol, owner)
                    ?: throw IllegalArgumentException("platform enum default entry is unavailable: ${argument.symbol}")
            require(field.static) { "platform enum default entry is not static: ${argument.symbol}" }
            defaultEnumEntries[argument.symbol] = field
        }
    return LinkedPlatformSymbols(types, fieldsByGetter, enumEntries, defaultEnumEntries)
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun linkedPlatformFunctions(
    functions: List<IrSimpleFunction>,
    session: CompilationSession,
): Map<IrSimpleFunctionSymbol, ExternalFunctionTarget> {
    val result = linkedMapOf<IrSimpleFunctionSymbol, ExternalFunctionTarget>()
    val visitor =
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildren(this, null)
            }

            override fun visitCall(expression: IrCall) {
                val target = expression.symbol.owner
                val symbol = target.fqNameWhenAvailable?.asString()
                if (symbol != null) {
                    val signature = target.canonicalPlatformSignature()
                    session.platformFunctions
                        .singleOrNull { link -> link.symbol == symbol && link.signature == signature }
                        ?.let { link ->
                            result[target.symbol] = ExternalFunctionTarget(link.exportName, link.moduleHash.copyOf())
                        }
                }
                super.visitCall(expression)
            }
        }
    functions.forEach { it.accept(visitor, null) }
    return result
}

private class FunctionCompiler(
    private val function: IrSimpleFunction,
    private val functionId: FunctionId,
    private val blockBase: Int,
    private val stringType: ValueType,
    private val charArrayType: ValueType,
    private val intArrayType: ValueType,
    private val stringArrayType: ValueType,
    private val guestTypes: GuestTypeRegistry,
    private val unitType: IrType,
    private val kotlinStringType: IrType,
    private val kotlinCharArrayClass: IrClassSymbol,
    private val kotlinIntArrayClass: IrClassSymbol,
    private val intType: IrType,
    private val booleanType: IrType,
    private val charType: IrType,
    private val functionIds: Map<org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol, FunctionId>,
    private val constantIds: Map<Constant, ConstantId>,
    private val literalIds: Map<Utf16Literal, Utf16LiteralId>,
    private val session: CompilationSession,
    private val capabilityIds: Map<LoweredCapabilityIdentity, CapabilityId>,
    private val classTypeIds: Map<IrClassSymbol, TypeId>,
    private val externalClassTypes: Map<IrClassSymbol, TypeRef.Imported>,
    private val inlineValueClasses: InlineValueClassRegistry,
    private val platformScalars: PlatformScalarRegistry,
    private val constructorLayouts: Map<IrConstructorSymbol, GuestConstructorTarget>,
    private val fieldsByGetter: Map<IrSimpleFunctionSymbol, GuestFieldLayout>,
    private val enumEntries: Map<IrEnumEntrySymbol, GuestEnumEntryLayout>,
    private val externalFieldsByGetter: Map<IrSimpleFunctionSymbol, ExternalFieldTarget>,
    private val externalEnumEntries: Map<IrEnumEntrySymbol, ExternalFieldTarget>,
    private val externalDefaultEnumEntries: Map<String, ExternalFieldTarget>,
    private val externalFunctions: Map<IrSimpleFunctionSymbol, ExternalFunctionTarget>,
) {
    private val localTypes = mutableListOf<ValueType>()
    private val values = mutableMapOf<IrValueSymbol, RegisterId>()
    private val blocks = mutableListOf(MutableBlock())
    private val loopContexts = ArrayDeque<LoopContext>()
    private var currentBlock = 0

    fun compile(): CompiledFunction {
        loweredParameters(function, session).forEachIndexed { index, parameter ->
            values[parameter.symbol] = RegisterId.of(index.toUInt())
        }
        val body = function.body as? IrBlockBody ?: throw UnsupportedKotlinIr(function, "function body is not a block")
        body.statements.forEach(::compileStatement)
        if (blocks[currentBlock].instructions.lastOrNull()?.isTerminator() != true) emit(Instruction.Return(Destination.Unit))
        return CompiledFunction(
            localTypes.toList(),
            blocks.map { Block(functionId, it.loopHeaderSafepoint, it.instructions.toList()) },
        )
    }

    private fun compileStatement(statement: IrElement) {
        when (statement) {
            is IrVariable -> {
                val initializer = statement.initializer ?: throw UnsupportedKotlinIr(statement, "local without initializer")
                val source = compileExpression(initializer)
                val destination = allocate(valueType(statement.type, statement))
                emit(Instruction.Move(destination, source))
                values[statement.symbol] = destination
            }

            is IrSetValue -> {
                val destination = values[statement.symbol] ?: throw UnsupportedKotlinIr(statement, "unknown mutable local")
                emit(Instruction.Move(destination, compileExpression(statement.value)))
            }

            is IrCall -> {
                compileCall(statement)
            }

            is IrReturn -> {
                if (function.returnType.isNothing()) {
                    compileStatement(statement.value)
                } else {
                    val destination =
                        if (function.returnType == unitType) {
                            when (val value = statement.value) {
                                is IrCall, is IrBlock -> compileStatement(value)
                            }
                            Destination.Unit
                        } else {
                            Destination.Register(compileExpression(statement.value))
                        }
                    emit(Instruction.Return(destination))
                }
            }

            is IrWhen -> {
                compileWhenStatement(statement)
            }

            is IrWhileLoop -> {
                compileWhile(statement)
            }

            is IrBlock -> {
                if (statement.origin?.toString() == "FOR_LOOP") {
                    compileIntForLoop(statement)
                } else {
                    statement.statements.forEach(::compileStatement)
                }
            }

            is IrComposite -> {
                statement.statements.forEach(::compileStatement)
            }

            is IrTypeOperatorCall -> {
                if (statement.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) {
                    compileStatement(statement.argument)
                } else {
                    compileExpression(statement)
                }
            }

            is IrThrow -> {
                emit(Instruction.Throw(compileExpression(statement.value)))
            }

            is IrBreak -> {
                compileLoopJump(statement, breakJump = true)
            }

            is IrContinue -> {
                compileLoopJump(statement, breakJump = false)
            }

            is IrExpression -> {
                compileExpression(statement)
            }

            else -> {
                throw UnsupportedKotlinIr(statement, "unsupported statement ${statement::class.simpleName}")
            }
        }
    }

    private fun compileExpression(expression: IrExpression): RegisterId =
        when (expression) {
            is IrConst -> {
                val constant = expression.toArtifactConstant(literalIds)
                val constantId = constantIds[constant] ?: throw UnsupportedKotlinIr(expression, "constant is absent from canonical pool")
                allocate(valueType(expression.type, expression)).also { emit(Instruction.Const(it, constantId)) }
            }

            is IrGetValue -> {
                values[expression.symbol] ?: throw UnsupportedKotlinIr(expression, "unknown local value")
            }

            is IrStringConcatenation -> {
                compileConcat(expression)
            }

            is IrConstructorCall -> {
                compileConstructor(expression)
            }

            is IrGetEnumValue -> {
                val entry = enumEntries[expression.symbol]
                if (entry != null) {
                    allocate(ValueType.Ref(nullable = false, type = entry.ownerType)).also { destination ->
                        emit(Instruction.StaticGet(destination, FieldRef.Local(entry.fieldId)))
                    }
                } else {
                    val external = externalEnumEntries[expression.symbol] ?: throw UnsupportedKotlinIr(expression, "unknown enum entry")
                    allocate(valueType(expression.type, expression)).also { destination ->
                        emit(Instruction.StaticGet(destination, FieldRef.Imported(external.importId)))
                    }
                }
            }

            is IrCall -> {
                compileCall(expression)
                    ?: throw UnsupportedKotlinIr(expression, "Unit call used as a value")
            }

            is IrWhen -> {
                compileWhenValue(expression)
            }

            is IrBlock -> {
                compileBlockValue(expression)
            }

            is IrTypeOperatorCall -> {
                compileTypeOperator(expression)
            }

            else -> {
                throw UnsupportedKotlinIr(expression, "unsupported expression ${expression::class.simpleName}")
            }
        }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileConstructor(call: IrConstructorCall): RegisterId {
        val target = call.symbol.owner
        val arguments = call.arguments.filterNotNull()
        platformScalars.constructor(call.symbol)?.let { scalarType ->
            val argument =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "platform scalar constructor requires one argument")
            val value = compileExpression(argument)
            scalarType.minimumInt?.let { minimum ->
                emitIntRangePrecondition(value, InlineIntRange(minimum, requireNotNull(scalarType.maximumInt)), call)
            }
            return value
        }
        inlineValueClasses.constructor(call.symbol)?.let { layout ->
            val argument =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "value class constructor argument is missing")
            if (argument.type != layout.underlyingType) {
                throw UnsupportedKotlinIr(call, "value class constructor argument type does not match its underlying scalar")
            }
            val value = compileExpression(argument)
            layout.intRange?.let { emitIntRangePrecondition(value, it, call) }
            return value
        }
        val exceptionImport =
            when (target.parentAsClass.fqNameWhenAvailable?.asString()) {
                "kotlin.Throwable", "kotlin.Exception", "kotlin.RuntimeException" -> ImportId.of(2u)
                "kotlin.IllegalArgumentException" -> ImportId.of(3u)
                else -> null
            }
        if (exceptionImport != null) {
            arguments.forEach(::compileExpression)
            prepareAllocationBlock()
            val type = TypeRef.Imported(exceptionImport)
            return allocate(ValueType.Ref(nullable = false, type = type)).also { destination ->
                emit(Instruction.NewObject(destination, type))
            }
        }
        if (target.parentAsClass.symbol == kotlinCharArrayClass &&
            call.type.isExactClass(kotlinCharArrayClass) &&
            arguments.size == 1 &&
            arguments[0].type == intType
        ) {
            val length = compileExpression(arguments.single())
            prepareAllocationBlock()
            return allocate(charArrayType).also { destination ->
                emit(Instruction.NewArray(destination, (charArrayType as ValueType.Ref).type, length))
            }
        }
        if (target.parentAsClass.symbol == kotlinIntArrayClass &&
            call.type.isExactClass(kotlinIntArrayClass) &&
            arguments.size == 1 &&
            arguments[0].type == intType
        ) {
            val length = compileExpression(arguments.single())
            prepareAllocationBlock()
            return allocate(intArrayType).also { destination ->
                emit(Instruction.NewArray(destination, (intArrayType as ValueType.Ref).type, length))
            }
        }
        if (call.type == kotlinStringType &&
            arguments.size == 3 &&
            arguments[0].type.isExactClass(kotlinCharArrayClass) &&
            arguments[1].type == intType &&
            arguments[2].type == intType
        ) {
            val compiled = arguments.map(::compileExpression)
            val end = allocate(ValueType.I32)
            emit(Instruction.AddI32(end, compiled[1], compiled[2]))
            prepareAllocationBlock()
            return allocate(stringType).also { destination ->
                emit(Instruction.StringFromCharArray(destination, compiled[0], compiled[1], end))
            }
        }
        val targetConstructor =
            constructorLayouts[call.symbol] ?: throw UnsupportedKotlinIr(call, "constructor is outside the project subset")
        val layout = targetConstructor.layout
        val regularArguments =
            target.parameters.mapIndexedNotNull { index, parameter ->
                call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.Regular }
            }
        val compiledArguments = regularArguments.map(::compileExpression)
        val ownerType = TypeRef.Local(layout.typeId)
        return allocate(ValueType.Ref(nullable = false, type = ownerType)).also { destination ->
            val ordered =
                layout.fields.sortedBy { it.constructorParameterIndex }.map { field ->
                    compiledArguments.getOrNull(field.constructorParameterIndex)
                        ?: throw UnsupportedKotlinIr(call, "constructor argument is missing")
                }
            emit(
                Instruction.Call(
                    Destination.Register(destination),
                    FunctionRef.Local(targetConstructor.functionId),
                    ordered,
                ),
            )
        }
    }

    private fun compileTypeOperator(expression: IrTypeOperatorCall): RegisterId {
        val source = compileExpression(expression.argument)
        val target = valueType(expression.typeOperand, expression)
        return when (expression.operator) {
            IrTypeOperator.INSTANCEOF -> {
                val reference = target as? ValueType.Ref ?: throw UnsupportedKotlinIr(expression, "type test target is not a reference")
                allocate(ValueType.Bool).also { destination ->
                    emit(Instruction.IsType(destination, source, reference.type))
                }
            }

            IrTypeOperator.IMPLICIT_CAST,
            -> {
                val reference = target as? ValueType.Ref ?: return source
                allocate(reference).also { destination ->
                    emit(Instruction.CheckedCast(destination, source, reference.type))
                }
            }

            else -> {
                throw UnsupportedKotlinIr(expression, "cast ${expression.operator} is outside the project subset")
            }
        }
    }

    private fun compileConcat(expression: IrStringConcatenation): RegisterId {
        val arguments = expression.arguments
        if (arguments.isEmpty()) throw UnsupportedKotlinIr(expression, "empty string concatenation")
        var result = compileStringPart(arguments.first())
        arguments.drop(1).forEach { argument ->
            val right = compileStringPart(argument)
            prepareAllocationBlock()
            val destination = allocate(stringType)
            emit(Instruction.StringConcat(destination, result, right))
            result = destination
        }
        return result
    }

    private fun compileStringPart(expression: IrExpression): RegisterId {
        if (expression.type == kotlinStringType) return compileExpression(expression)
        if (expression.type == unitType) {
            compileStatement(expression)
            return loadStringLiteral("kotlin.Unit")
        }
        val conversionType =
            when (valueType(expression.type, expression)) {
                ValueType.I32 -> StringValueType.I32
                ValueType.Bool -> StringValueType.BOOL
                ValueType.Char -> StringValueType.CHAR
                else -> throw UnsupportedKotlinIr(expression, "object string conversion requires virtual dispatch")
            }
        val source = compileExpression(expression)
        prepareAllocationBlock()
        return allocate(stringType).also { destination ->
            emit(Instruction.StringValueOf(conversionType, destination, source))
        }
    }

    private fun loadStringLiteral(value: String): RegisterId {
        val constant = value.toArtifactConstant(literalIds)
        val constantId =
            constantIds[constant]
                ?: throw IllegalStateException("canonical string literal is absent from the constant pool")
        return allocate(stringType).also { destination ->
            emit(Instruction.Const(destination, constantId))
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileCall(call: IrCall): RegisterId? {
        val target = call.symbol.owner
        platformScalars.constant(target)?.let { value ->
            val artifactConstant = value.scalarValue().toArtifactConstant(literalIds)
            val constantId =
                constantIds[artifactConstant]
                    ?: throw UnsupportedKotlinIr(call, "platform scalar constant is absent from canonical pool")
            return allocate(valueType(call.type, call)).also { destination ->
                emit(Instruction.Const(destination, constantId))
            }
        }
        if (platformScalars.isUnderlyingGetter(target)) {
            val receiver =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "platform scalar property getter receiver is missing")
            return compileExpression(receiver)
        }
        inlineValueClasses.constant(target.symbol)?.let { constant ->
            val artifactConstant = constant.value.toArtifactConstant(literalIds)
            val constantId =
                constantIds[artifactConstant]
                    ?: throw UnsupportedKotlinIr(call, "value class companion constant is absent from canonical pool")
            return allocate(valueType(call.type, call)).also { destination ->
                emit(Instruction.Const(destination, constantId))
            }
        }
        inlineValueClasses.getter(target.symbol)?.let {
            val receiver =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "value class property getter receiver is missing")
            return compileExpression(receiver)
        }
        fieldsByGetter[target.symbol]?.let { field ->
            val receiverExpression =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "property getter receiver is missing")
            val receiver = compileExpression(receiverExpression)
            return allocate(field.type).also { destination ->
                emit(Instruction.FieldGet(destination, receiver, FieldRef.Local(field.id)))
            }
        }
        externalFieldsByGetter[target.symbol]?.let { field ->
            val receiverExpression =
                target.parameters
                    .mapIndexedNotNull { index, parameter ->
                        call.arguments.getOrNull(index)?.takeIf { parameter.kind == IrParameterKind.DispatchReceiver }
                    }.singleOrNull()
                    ?: throw UnsupportedKotlinIr(call, "platform property getter receiver is missing")
            val receiver = compileExpression(receiverExpression)
            return allocate(valueType(call.type, call)).also { destination ->
                emit(Instruction.FieldGet(destination, receiver, FieldRef.Imported(field.importId)))
            }
        }
        compileIntArrayFactory(call, target)?.let { return it }
        compileStringArrayFactory(call, target)?.let { return it }
        trustedIntrinsic(target)?.let { intrinsic ->
            val arguments =
                target.parameters
                    .zip(call.arguments)
                    .filter { (parameter, _) -> parameter.kind == IrParameterKind.Regular }
                    .map { (_, argument) -> compileExpression(requireNotNull(argument)) }
            val capability = requireNotNull(capabilityIds[intrinsic.capability])
            val destination = if (intrinsic.terminal) Destination.Unit else destinationFor(call.type, call)
            if (intrinsic.blocking == IntrinsicBlockingMode.VM_TASK) {
                val resume = createBlock()
                emit(
                    Instruction.CapabilityCallAsync(
                        destination,
                        capability,
                        intrinsic.operation,
                        arguments,
                        blockId(resume),
                    ),
                )
                currentBlock = resume
            } else {
                emit(Instruction.CapabilityCallSync(destination, capability, intrinsic.operation, arguments))
            }
            if (intrinsic.terminal) emit(Instruction.Unreachable)
            return (destination as? Destination.Register)?.id
        }
        externalFunctions[target.symbol]?.let { external ->
            val argumentExpressions = resolveProjectCallArguments(call, target)
            val arguments = argumentExpressions.map(::compileCallArgument)
            val destination = destinationFor(target.returnType, call)
            if (target.isSuspend) {
                val resume = createBlock()
                emit(Instruction.CallSuspend(destination, FunctionRef.Imported(external.importId), arguments, blockId(resume)))
                currentBlock = resume
            } else {
                emit(Instruction.Call(destination, FunctionRef.Imported(external.importId), arguments))
            }
            if (target.returnType.isNothing()) emit(Instruction.Unreachable)
            return (destination as? Destination.Register)?.id
        }
        compileCompareToPredicate(call, target.name.asString())?.let { return it }
        val targetId = functionIds[target.symbol]
        if (targetId == null) {
            val argumentExpressions = call.arguments.filterNotNull()
            val arguments = argumentExpressions.map(::compileExpression)
            return compileBuiltinCall(call, target, argumentExpressions, arguments)
        }
        val arguments = resolveProjectCallArguments(call, target).map(::compileCallArgument)
        val destination = destinationFor(target.returnType, call)
        if (target.isSuspend) {
            val resume = createBlock()
            emit(Instruction.CallSuspend(destination, FunctionRef.Local(targetId), arguments, blockId(resume)))
            currentBlock = resume
        } else {
            emit(Instruction.Call(destination, FunctionRef.Local(targetId), arguments))
        }
        if (target.returnType.isNothing()) emit(Instruction.Unreachable)
        return (destination as? Destination.Register)?.id
    }

    private fun resolveProjectCallArguments(
        call: IrCall,
        target: IrSimpleFunction,
    ): List<ResolvedCallArgument> {
        val platformDefaults =
            session.platformFunctions
                .singleOrNull { link ->
                    link.symbol == target.fqNameWhenAvailable?.asString() &&
                        link.signature == target.canonicalPlatformSignature()
                }?.defaultArguments
                .orEmpty()
        return loweredParameters(target, session).mapIndexed { loweredIndex, parameter ->
            val index = target.parameters.indexOf(parameter)
            call.arguments.getOrNull(index)?.let(ResolvedCallArgument::Expression)
                ?: platformDefaults.getOrNull(loweredIndex)?.let(ResolvedCallArgument::PlatformDefault)
                ?: parameter.defaultValue
                    ?.expression
                    ?.takeIf { expression -> isSupportedScalarDefault(expression) || isSupportedStringArrayDefault(expression) }
                    ?.let(ResolvedCallArgument::Expression)
                ?: throw UnsupportedKotlinIr(
                    call,
                    "omitted argument ${parameter.name} is outside the project subset",
                )
        }
    }

    private fun compileCallArgument(argument: ResolvedCallArgument): RegisterId =
        when (argument) {
            is ResolvedCallArgument.Expression -> {
                compileExpression(argument.expression)
            }

            is ResolvedCallArgument.PlatformDefault -> {
                when (val value = argument.value) {
                    is PlatformDefaultArgument.EnumEntry -> {
                        val field =
                            externalDefaultEnumEntries[value.symbol]
                                ?: throw IllegalArgumentException("platform enum default entry is not linked: ${value.symbol}")
                        val ownerType =
                            externalClassTypes[field.ownerSymbol]
                                ?: throw IllegalArgumentException("platform enum default owner is not linked: ${value.symbol}")
                        allocate(ValueType.Ref(nullable = false, type = ownerType)).also { destination ->
                            emit(Instruction.StaticGet(destination, FieldRef.Imported(field.importId)))
                        }
                    }
                }
            }
        }

    private fun isSupportedScalarDefault(expression: IrExpression): Boolean =
        expression is IrConst &&
            expression.value != null &&
            expression.type in setOf(intType, booleanType, charType, kotlinStringType)

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun isSupportedStringArrayDefault(expression: IrExpression): Boolean {
        val call = expression as? IrCall ?: return false
        if (!guestTypes.isStringArray(call.type)) return false
        return when (
            call.symbol.owner.fqNameWhenAvailable
                ?.asString()
        ) {
            "kotlin.emptyArray" -> {
                call.arguments.all { it == null }
            }

            "kotlin.arrayOf" -> {
                val arguments = call.arguments.filterNotNull()
                arguments.isEmpty() ||
                    (arguments.singleOrNull() as? IrVararg)
                        ?.elements
                        ?.all { it is IrExpression } == true
            }

            else -> {
                false
            }
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileStringArrayFactory(
        call: IrCall,
        target: IrSimpleFunction,
    ): RegisterId? {
        if (!guestTypes.isStringArray(call.type)) return null
        val fqName = target.fqNameWhenAvailable?.asString() ?: return null
        val elements =
            when (fqName) {
                "kotlin.emptyArray" -> {
                    if (call.arguments.any { it != null }) {
                        throw UnsupportedKotlinIr(call, "emptyArray arguments are outside the project subset")
                    }
                    emptyList()
                }

                "kotlin.arrayOf" -> {
                    val arguments = call.arguments.filterNotNull()
                    if (arguments.isEmpty()) {
                        emptyList()
                    } else {
                        val vararg =
                            arguments.singleOrNull() as? IrVararg
                                ?: throw UnsupportedKotlinIr(call, "arrayOf requires a direct vararg")
                        vararg.elements.map { element ->
                            element as? IrExpression
                                ?: throw UnsupportedKotlinIr(call, "spread arrayOf arguments are outside the project subset")
                        }
                    }
                }

                else -> {
                    return null
                }
            }
        val values = elements.map(::compileExpression)
        val length = emitI32Constant(elements.size, call)
        prepareAllocationBlock()
        val array = allocate(stringArrayType)
        emit(Instruction.NewArray(array, (stringArrayType as ValueType.Ref).type, length))
        values.forEachIndexed { index, value ->
            emit(Instruction.ArrayStore(array, emitI32Constant(index, call), value))
        }
        return array
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileIntArrayFactory(
        call: IrCall,
        target: IrSimpleFunction,
    ): RegisterId? {
        if (!call.type.isExactClass(kotlinIntArrayClass) || target.fqNameWhenAvailable?.asString() != "kotlin.intArrayOf") {
            return null
        }
        val arguments = call.arguments.filterNotNull()
        val elements =
            if (arguments.isEmpty()) {
                emptyList()
            } else {
                val vararg =
                    arguments.singleOrNull() as? IrVararg
                        ?: throw UnsupportedKotlinIr(call, "intArrayOf requires a direct vararg")
                vararg.elements.map { element ->
                    element as? IrExpression
                        ?: throw UnsupportedKotlinIr(call, "spread intArrayOf arguments are outside the project subset")
                }
            }
        val values = elements.map(::compileExpression)
        val length = emitI32Constant(elements.size, call)
        prepareAllocationBlock()
        val array = allocate(intArrayType)
        emit(Instruction.NewArray(array, (intArrayType as ValueType.Ref).type, length))
        values.forEachIndexed { index, value ->
            emit(Instruction.ArrayStore(array, emitI32Constant(index, call), value))
        }
        return array
    }

    private fun emitI32Constant(
        value: Int,
        element: IrElement,
    ): RegisterId {
        val id =
            constantIds[Constant.I32(value)]
                ?: throw UnsupportedKotlinIr(element, "generated Int constant is absent from canonical pool")
        return allocate(ValueType.I32).also { emit(Instruction.Const(it, id)) }
    }

    private fun emitIntRangePrecondition(
        value: RegisterId,
        range: InlineIntRange,
        element: IrElement,
    ) {
        val minimum = emitI32Constant(range.minimum, element)
        val maximum = emitI32Constant(range.maximum, element)
        val below = allocate(ValueType.Bool)
        emit(Instruction.Less(OrderedScalarValueType.I32, below, value, minimum))
        val upperCheck = createBlock()
        val failure = createBlock()
        val success = createBlock()
        emit(Instruction.Branch(below, blockId(failure), blockId(upperCheck)))

        currentBlock = upperCheck
        val above = allocate(ValueType.Bool)
        emit(Instruction.Greater(OrderedScalarValueType.I32, above, value, maximum))
        emit(Instruction.Branch(above, blockId(failure), blockId(success)))

        currentBlock = failure
        prepareAllocationBlock()
        val exceptionType = TypeRef.Imported(ImportId.of(3u))
        val exception = allocate(ValueType.Ref(nullable = false, type = exceptionType))
        emit(Instruction.NewObject(exception, exceptionType))
        emit(Instruction.Throw(exception))

        currentBlock = success
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileCompareToPredicate(
        call: IrCall,
        predicateName: String,
    ): RegisterId? {
        if (predicateName !in setOf("less", "lessOrEqual", "greater", "greaterOrEqual")) return null
        val outerArguments = call.arguments.filterNotNull()
        val compareCall = outerArguments.firstOrNull() as? IrCall ?: return null
        if (compareCall.symbol.owner.name
                .asString() != "compareTo"
        ) {
            return null
        }
        val zero = outerArguments.getOrNull(1) as? IrConst ?: return null
        if (zero.value != 0) return null
        val operands = compareCall.arguments.filterNotNull()
        if (operands.size != 2) return null
        val left = compileExpression(operands[0])
        val right = compileExpression(operands[1])
        val type = orderedType(operands[0].type, call)
        return allocate(ValueType.Bool).also { destination ->
            emit(
                when (predicateName) {
                    "less" -> Instruction.Less(type, destination, left, right)
                    "lessOrEqual" -> Instruction.LessOrEqual(type, destination, left, right)
                    "greater" -> Instruction.Greater(type, destination, left, right)
                    else -> Instruction.GreaterOrEqual(type, destination, left, right)
                },
            )
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun compileBuiltinCall(
        call: IrCall,
        target: IrSimpleFunction,
        argumentExpressions: List<IrExpression>,
        arguments: List<RegisterId>,
    ): RegisterId? {
        val fqName = target.fqNameWhenAvailable?.asString().orEmpty()
        val name = target.name.asString()

        fun result(
            type: ValueType,
            instruction: (RegisterId) -> Instruction,
        ): RegisterId = allocate(type).also { emit(instruction(it)) }
        if (arguments.size == 2 && call.type == kotlinStringType && fqName == "kotlin.String.plus") {
            prepareAllocationBlock()
            return result(stringType) { Instruction.StringConcat(it, arguments[0], arguments[1]) }
        }
        if (arguments.size == 2 && argumentExpressions.all { it.type == intType }) {
            when (name) {
                "plus" -> return result(ValueType.I32) { Instruction.AddI32(it, arguments[0], arguments[1]) }
                "minus" -> return result(ValueType.I32) { Instruction.SubtractI32(it, arguments[0], arguments[1]) }
                "times" -> return result(ValueType.I32) { Instruction.MultiplyI32(it, arguments[0], arguments[1]) }
                "div" -> return result(ValueType.I32) { Instruction.DivideI32(it, arguments[0], arguments[1]) }
                "rem" -> return result(ValueType.I32) { Instruction.RemainderI32(it, arguments[0], arguments[1]) }
                "and" -> return result(ValueType.I32) { Instruction.BitAndI32(it, arguments[0], arguments[1]) }
                "or" -> return result(ValueType.I32) { Instruction.BitOrI32(it, arguments[0], arguments[1]) }
                "xor" -> return result(ValueType.I32) { Instruction.BitXorI32(it, arguments[0], arguments[1]) }
                "shl" -> return result(ValueType.I32) { Instruction.ShiftLeftI32(it, arguments[0], arguments[1]) }
                "ushr" -> return result(ValueType.I32) { Instruction.ShiftUnsignedI32(it, arguments[0], arguments[1]) }
            }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == booleanType && name == "not") {
            val falseRegister = allocate(ValueType.Bool)
            emit(Instruction.Const(falseRegister, requireNotNull(constantIds[Constant.Bool(false)])))
            return result(ValueType.Bool) { Instruction.Equal(ScalarValueType.BOOL, it, arguments[0], falseRegister) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == intType && name == "unaryMinus") {
            val zero = emitI32Constant(0, call)
            return result(ValueType.I32) { Instruction.SubtractI32(it, zero, arguments[0]) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == intType && name == "inv") {
            val allBits = emitI32Constant(-1, call)
            return result(ValueType.I32) { Instruction.BitXorI32(it, arguments[0], allBits) }
        }
        if (arguments.size == 1 && argumentExpressions[0].type == intType && call.type == charType && name == "toChar") {
            return result(ValueType.Char) { Instruction.Convert(it, arguments[0]) }
        }
        comparison(call, name, argumentExpressions, arguments)?.let { return it }
        if (arguments.size == 1 && argumentExpressions[0].type == kotlinStringType && name == "<get-length>") {
            return result(ValueType.I32) { Instruction.StringLength(it, arguments[0]) }
        }
        if (arguments.size == 2 && argumentExpressions[0].type == kotlinStringType && name == "get") {
            return result(ValueType.Char) { Instruction.StringGet(it, arguments[0], arguments[1]) }
        }
        if (arguments.size == 3 && argumentExpressions[0].type == kotlinStringType && name == "substring") {
            prepareAllocationBlock()
            return result(stringType) { Instruction.StringSubstring(it, arguments[0], arguments[1], arguments[2]) }
        }
        if (arguments.size == 3 &&
            guestTypes.isStringArray(argumentExpressions[0].type) &&
            argumentExpressions[1].type == intType &&
            argumentExpressions[2].type == intType &&
            name == "copyOfRange" &&
            fqName == "kotlin.collections.copyOfRange"
        ) {
            return compileStringArrayCopyOfRange(call, arguments)
        }
        if (arguments.size == 1 &&
            argumentExpressions[0].type.isExactClass(kotlinCharArrayClass) &&
            name == "<get-size>" &&
            fqName == "kotlin.CharArray.<get-size>"
        ) {
            return result(ValueType.I32) { Instruction.ArrayLength(it, arguments[0]) }
        }
        if (arguments.size == 1 &&
            argumentExpressions[0].type.isExactClass(kotlinIntArrayClass) &&
            name == "<get-size>" &&
            fqName == "kotlin.IntArray.<get-size>"
        ) {
            return result(ValueType.I32) { Instruction.ArrayLength(it, arguments[0]) }
        }
        if (arguments.size == 1 && guestTypes.isStringArray(argumentExpressions[0].type) && name == "<get-size>") {
            return result(ValueType.I32) { Instruction.ArrayLength(it, arguments[0]) }
        }
        if (arguments.size == 2 &&
            argumentExpressions[0].type.isExactClass(kotlinCharArrayClass) &&
            name == "get" &&
            fqName == "kotlin.CharArray.get"
        ) {
            return result(ValueType.Char) { Instruction.ArrayLoad(it, arguments[0], arguments[1]) }
        }
        if (arguments.size == 2 &&
            argumentExpressions[0].type.isExactClass(kotlinIntArrayClass) &&
            name == "get" &&
            fqName == "kotlin.IntArray.get"
        ) {
            return result(ValueType.I32) { Instruction.ArrayLoad(it, arguments[0], arguments[1]) }
        }
        if (arguments.size == 2 && guestTypes.isStringArray(argumentExpressions[0].type) && name == "get") {
            return result(stringType) { Instruction.ArrayLoad(it, arguments[0], arguments[1]) }
        }
        if (arguments.size == 3 &&
            argumentExpressions[0].type.isExactClass(kotlinCharArrayClass) &&
            name == "set" &&
            fqName == "kotlin.CharArray.set"
        ) {
            emit(Instruction.ArrayStore(arguments[0], arguments[1], arguments[2]))
            return null
        }
        if (arguments.size == 3 &&
            argumentExpressions[0].type.isExactClass(kotlinIntArrayClass) &&
            name == "set" &&
            fqName == "kotlin.IntArray.set"
        ) {
            emit(Instruction.ArrayStore(arguments[0], arguments[1], arguments[2]))
            return null
        }
        if (arguments.size == 3 && guestTypes.isStringArray(argumentExpressions[0].type) && name == "set") {
            emit(Instruction.ArrayStore(arguments[0], arguments[1], arguments[2]))
            return null
        }
        if (arguments.size == 3 &&
            argumentExpressions[0].type.isExactClass(kotlinCharArrayClass) &&
            name == "concatToString" &&
            fqName == "kotlin.text.concatToString"
        ) {
            prepareAllocationBlock()
            return result(stringType) { Instruction.StringFromCharArray(it, arguments[0], arguments[1], arguments[2]) }
        }
        if (arguments.size == 3 &&
            call.type == kotlinStringType &&
            argumentExpressions[0].type.isExactClass(kotlinCharArrayClass) &&
            argumentExpressions[1].type == intType &&
            argumentExpressions[2].type == intType &&
            fqName == "kotlin.text.String"
        ) {
            val end = allocate(ValueType.I32)
            emit(Instruction.AddI32(end, arguments[1], arguments[2]))
            prepareAllocationBlock()
            return result(stringType) { Instruction.StringFromCharArray(it, arguments[0], arguments[1], end) }
        }
        throw UnsupportedKotlinIr(call, "call target ${fqName.ifEmpty { name }} is outside the project subset")
    }

    private fun compileStringArrayCopyOfRange(
        call: IrCall,
        arguments: List<RegisterId>,
    ): RegisterId {
        val source = arguments[0]
        val start = arguments[1]
        val end = arguments[2]
        val length = allocate(ValueType.I32)
        emit(Instruction.SubtractI32(length, end, start))
        prepareAllocationBlock()
        val destination = allocate(stringArrayType)
        emit(Instruction.NewArray(destination, (stringArrayType as ValueType.Ref).type, length))

        val sourceIndex = allocate(ValueType.I32)
        emit(Instruction.Move(sourceIndex, start))
        val destinationIndex = allocate(ValueType.I32)
        emit(Instruction.Move(destinationIndex, emitI32Constant(0, call)))
        val one = emitI32Constant(1, call)

        val header = createBlock(loopHeader = true)
        jumpTo(header)
        currentBlock = header
        val condition = allocate(ValueType.Bool)
        emit(Instruction.Less(OrderedScalarValueType.I32, condition, destinationIndex, length))
        val body = createBlock()
        val exit = createBlock()
        emit(Instruction.Branch(condition, blockId(body), blockId(exit)))

        currentBlock = body
        val value = allocate(stringType)
        emit(Instruction.ArrayLoad(value, source, sourceIndex))
        emit(Instruction.ArrayStore(destination, destinationIndex, value))
        val nextSource = allocate(ValueType.I32)
        emit(Instruction.AddI32(nextSource, sourceIndex, one))
        emit(Instruction.Move(sourceIndex, nextSource))
        val nextDestination = allocate(ValueType.I32)
        emit(Instruction.AddI32(nextDestination, destinationIndex, one))
        emit(Instruction.Move(destinationIndex, nextDestination))
        jumpTo(header)

        currentBlock = exit
        return destination
    }

    private fun comparison(
        call: IrCall,
        name: String,
        expressions: List<IrExpression>,
        arguments: List<RegisterId>,
    ): RegisterId? {
        if (arguments.size != 2) return null
        val leftType = expressions[0].type
        val rightType = expressions[1].type
        if (name in setOf("EQEQ", "equals", "eqeq")) {
            return allocate(ValueType.Bool).also { destination ->
                if (leftType == kotlinStringType && rightType == kotlinStringType) {
                    emit(Instruction.StringEquals(destination, arguments[0], arguments[1]))
                } else if (valueType(leftType, call) is ValueType.Ref && valueType(rightType, call) is ValueType.Ref) {
                    emit(Instruction.RefEqual(destination, arguments[0], arguments[1]))
                } else {
                    emit(Instruction.Equal(scalarType(leftType, call), destination, arguments[0], arguments[1]))
                }
            }
        }
        val instructionFactory: (OrderedScalarValueType, RegisterId) -> Instruction =
            when (name) {
                "less" -> { type, destination -> Instruction.Less(type, destination, arguments[0], arguments[1]) }
                "lessOrEqual" -> { type, destination -> Instruction.LessOrEqual(type, destination, arguments[0], arguments[1]) }
                "greater" -> { type, destination -> Instruction.Greater(type, destination, arguments[0], arguments[1]) }
                "greaterOrEqual" -> { type, destination -> Instruction.GreaterOrEqual(type, destination, arguments[0], arguments[1]) }
                else -> return null
            }
        val orderedType = orderedType(leftType, call)
        return allocate(ValueType.Bool).also { destination ->
            val instruction = instructionFactory(orderedType, destination)
            emit(instruction)
        }
    }

    private fun compileWhile(loop: IrWhileLoop) {
        val header = createBlock(loopHeader = true)
        jumpTo(header)
        currentBlock = header
        val condition = compileExpression(loop.condition)
        val body = createBlock()
        val branchBlock = currentBlock
        val branchIndex = blocks[branchBlock].instructions.size
        emit(Instruction.Branch(condition, blockId(body), blockId(header)))
        currentBlock = body
        val context = LoopContext(loop, continueTarget = header)
        withLoopContext(context) {
            loop.body?.let(::compileStatement)
        }
        if (!isTerminated()) jumpTo(header)
        val exit = createBlock()
        blocks[branchBlock].instructions[branchIndex] = Instruction.Branch(condition, blockId(body), blockId(exit))
        context.breakBlocks.forEach { block ->
            check(blocks[block].instructions.lastOrNull() is Instruction.Jump)
            blocks[block].instructions[blocks[block].instructions.lastIndex] = Instruction.Jump(blockId(exit))
        }
        currentBlock = exit
    }

    private fun compileIntForLoop(block: IrBlock) {
        val plan = intForLoopPlan(block) ?: throw UnsupportedKotlinIr(block, "unsupported canonical for-loop shape")
        val startValue = compileExpression(plan.start)
        val index = allocate(ValueType.I32)
        emit(Instruction.Move(index, startValue))
        val endValue = compileExpression(plan.endInclusive)
        val endInclusive = allocate(ValueType.I32)
        emit(Instruction.Move(endInclusive, endValue))

        val initialCondition = allocate(ValueType.Bool)
        if (plan.inclusive) {
            emit(Instruction.LessOrEqual(OrderedScalarValueType.I32, initialCondition, index, endInclusive))
        } else {
            emit(Instruction.Less(OrderedScalarValueType.I32, initialCondition, index, endInclusive))
        }
        val initialBranchBlock = currentBlock
        val initialBranchIndex = blocks[initialBranchBlock].instructions.size
        val body = createBlock(loopHeader = true)
        emit(Instruction.Branch(initialCondition, blockId(body), blockId(body)))

        currentBlock = body
        val loopValue = allocate(ValueType.I32)
        values[plan.loopVariable.symbol] = loopValue
        emit(Instruction.Move(loopValue, index))
        val context = LoopContext(plan.loop, continueTarget = null, placeholderTarget = body)
        withLoopContext(context) {
            compileStatement(plan.body)
        }

        val condition = createBlock()
        if (!isTerminated()) jumpTo(condition)
        context.continueBlocks.forEach { patchJumpTarget(it, condition) }
        currentBlock = condition
        val exitBranchBlock: Int
        val exitBranchIndex: Int
        val exitBranchCondition: RegisterId
        val repeatTarget: Int
        val exitOnTrue: Boolean
        if (plan.inclusive) {
            val atEnd = allocate(ValueType.Bool)
            emit(Instruction.Equal(ScalarValueType.I32, atEnd, index, endInclusive))
            exitBranchBlock = currentBlock
            exitBranchIndex = blocks[exitBranchBlock].instructions.size
            val increment = createBlock()
            emit(Instruction.Branch(atEnd, blockId(increment), blockId(increment)))
            currentBlock = increment
            val next = allocate(ValueType.I32)
            emit(Instruction.AddI32(next, index, emitI32Constant(1, block)))
            emit(Instruction.Move(index, next))
            jumpTo(body)
            exitBranchCondition = atEnd
            repeatTarget = increment
            exitOnTrue = true
        } else {
            val next = allocate(ValueType.I32)
            emit(Instruction.AddI32(next, index, emitI32Constant(1, block)))
            emit(Instruction.Move(index, next))
            val hasNext = allocate(ValueType.Bool)
            emit(Instruction.Less(OrderedScalarValueType.I32, hasNext, index, endInclusive))
            exitBranchBlock = currentBlock
            exitBranchIndex = blocks[exitBranchBlock].instructions.size
            emit(Instruction.Branch(hasNext, blockId(body), blockId(body)))
            exitBranchCondition = hasNext
            repeatTarget = body
            exitOnTrue = false
        }

        val exit = createBlock()
        blocks[initialBranchBlock].instructions[initialBranchIndex] =
            Instruction.Branch(initialCondition, blockId(body), blockId(exit))
        blocks[exitBranchBlock].instructions[exitBranchIndex] =
            if (exitOnTrue) {
                Instruction.Branch(exitBranchCondition, blockId(exit), blockId(repeatTarget))
            } else {
                Instruction.Branch(exitBranchCondition, blockId(repeatTarget), blockId(exit))
            }
        context.breakBlocks.forEach { patchJumpTarget(it, exit) }
        currentBlock = exit
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun intForLoopPlan(block: IrBlock): IntForLoopPlan? {
        if (block.statements.size != 2) return null
        val iterator = block.statements[0] as? IrVariable ?: return null
        if (iterator.origin.toString() != "FOR_LOOP_ITERATOR") return null
        val iteratorCall = iterator.initializer as? IrCall ?: return null
        if (iteratorCall.targetFqName() != "kotlin.ranges.IntRange.iterator") return null
        val rangeCall = iteratorCall.arguments.filterNotNull().singleOrNull() as? IrCall ?: return null
        val rangeFunction = rangeCall.targetFqName()
        val inclusive =
            when (rangeFunction) {
                "kotlin.ranges.rangeTo" -> true
                "kotlin.ranges.rangeUntil", "kotlin.ranges.until" -> false
                else -> return null
            }
        val bounds = rangeCall.arguments.filterNotNull()
        if (bounds.size != 2 || bounds.any { it.type != intType }) return null

        val loop = block.statements[1] as? IrWhileLoop ?: return null
        if (loop.origin?.toString() != "FOR_LOOP_INNER_WHILE") return null
        val hasNext = loop.condition as? IrCall ?: return null
        if (hasNext.targetFqName() != "kotlin.collections.IntIterator.hasNext") return null
        val iteratorRead = hasNext.arguments.filterNotNull().singleOrNull() as? IrGetValue ?: return null
        if (iteratorRead.symbol !== iterator.symbol) return null

        val loopBody = loop.body as? IrBlock ?: return null
        if (loopBody.statements.size != 2) return null
        val loopVariable = loopBody.statements[0] as? IrVariable ?: return null
        if (loopVariable.origin.toString() != "FOR_LOOP_VARIABLE" || loopVariable.type != intType) return null
        val nextCall = loopVariable.initializer as? IrCall ?: return null
        if (nextCall.targetFqName() != "kotlin.collections.IntIterator.next") return null
        val nextReceiver = nextCall.arguments.filterNotNull().singleOrNull() as? IrGetValue ?: return null
        if (nextReceiver.symbol !== iterator.symbol) return null
        val body = loopBody.statements[1] as? IrExpression ?: return null
        return IntForLoopPlan(loop, loopVariable, bounds[0], bounds[1], inclusive, body)
    }

    private fun compileLoopJump(
        jump: IrBreakContinue,
        breakJump: Boolean,
    ) {
        val context = loopContexts.lastOrNull()
        if (context == null || jump.loop !== context.loop) {
            throw UnsupportedKotlinIr(jump, "outer loop jump is not supported")
        }
        if (breakJump) {
            context.breakBlocks += currentBlock
            jumpTo(context.placeholderTarget)
        } else {
            val target = context.continueTarget
            if (target == null) {
                context.continueBlocks += currentBlock
                jumpTo(context.placeholderTarget)
            } else {
                jumpTo(target)
            }
        }
    }

    private fun patchJumpTarget(
        block: Int,
        target: Int,
    ) {
        check(blocks[block].instructions.lastOrNull() is Instruction.Jump)
        blocks[block].instructions[blocks[block].instructions.lastIndex] = Instruction.Jump(blockId(target))
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun IrCall.targetFqName(): String? =
        symbol.owner
            .fqNameWhenAvailable
            ?.asString()

    private inline fun withLoopContext(
        context: LoopContext,
        action: () -> Unit,
    ) {
        loopContexts.addLast(context)
        try {
            action()
        } finally {
            check(loopContexts.removeLast() === context)
        }
    }

    private fun compileWhenStatement(expression: IrWhen) {
        val exits = mutableListOf<Int>()
        expression.branches.forEachIndexed { index, branch ->
            val isElse = index == expression.branches.lastIndex && branch.condition.isTrueConstant()
            if (isElse) {
                if (branch.result.isNoWhenBranchMatchedCall() && function.returnType == unitType) {
                    emit(Instruction.Return(Destination.Unit))
                } else {
                    compileStatement(branch.result)
                }
            } else {
                val condition = compileExpression(branch.condition)
                val body = createBlock()
                val otherwise = createBlock()
                emit(Instruction.Branch(condition, blockId(body), blockId(otherwise)))
                currentBlock = body
                compileStatement(branch.result)
                if (!isTerminated()) exits += currentBlock
                currentBlock = otherwise
            }
        }
        if (!isTerminated()) exits += currentBlock
        val join = createBlock()
        exits.forEach { exit ->
            currentBlock = exit
            jumpTo(join)
        }
        currentBlock = join
    }

    private fun compileWhenValue(expression: IrWhen): RegisterId {
        val resultType = valueType(expression.type, expression)
        val destination = allocate(resultType)
        val exits = mutableListOf<Int>()
        expression.branches.forEachIndexed { index, branch ->
            val isElse = index == expression.branches.lastIndex && branch.condition.isTrueConstant()
            if (isElse) {
                if (branch.result.isNoWhenBranchMatchedCall()) {
                    emitImpossibleWhenDefault(destination, resultType, branch.result)
                } else if (branch.result.type.isNothing()) {
                    compileStatement(branch.result)
                } else {
                    emit(Instruction.Move(destination, compileExpression(branch.result)))
                }
            } else {
                val condition = compileExpression(branch.condition)
                val body = createBlock()
                val otherwise = createBlock()
                emit(Instruction.Branch(condition, blockId(body), blockId(otherwise)))
                currentBlock = body
                if (branch.result.type.isNothing()) {
                    compileStatement(branch.result)
                } else {
                    emit(Instruction.Move(destination, compileExpression(branch.result)))
                    exits += currentBlock
                }
                currentBlock = otherwise
            }
        }
        if (!isTerminated()) exits += currentBlock
        val join = createBlock()
        exits.forEach { exit ->
            currentBlock = exit
            jumpTo(join)
        }
        currentBlock = join
        return destination
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun IrExpression.isNoWhenBranchMatchedCall(): Boolean =
        (this as? IrCall)
            ?.symbol
            ?.owner
            ?.fqNameWhenAvailable
            ?.asString() ==
            "kotlin.internal.ir.noWhenBranchMatchedException"

    private fun emitImpossibleWhenDefault(
        destination: RegisterId,
        type: ValueType,
        element: IrElement,
    ) {
        val constant =
            when (type) {
                ValueType.I32 -> Constant.I32(0)
                ValueType.Bool -> Constant.Bool(false)
                else -> throw UnsupportedKotlinIr(element, "exhaustive when fallback has an unsupported result type")
            }
        emit(Instruction.Const(destination, requireNotNull(constantIds[constant])))
    }

    private fun compileBlockValue(block: IrBlock): RegisterId {
        val result =
            block.statements.lastOrNull() as? IrExpression
                ?: throw UnsupportedKotlinIr(block, "value block has no result expression")
        block.statements.dropLast(1).forEach(::compileStatement)
        return compileExpression(result)
    }

    private fun trustedIntrinsic(function: IrSimpleFunction): LoweredCapabilityOperation? = resolveTrustedIntrinsic(function, session)

    private fun valueType(
        type: IrType,
        element: IrElement,
    ): ValueType =
        when (type) {
            unitType -> {
                ValueType.Unit
            }

            kotlinStringType -> {
                stringType
            }

            intType -> {
                ValueType.I32
            }

            booleanType -> {
                ValueType.Bool
            }

            charType -> {
                ValueType.Char
            }

            else -> {
                if (type.isNothing()) {
                    ValueType.Unit
                } else if (type.isExactClass(kotlinCharArrayClass)) {
                    charArrayType
                } else if (type.isExactClass(kotlinIntArrayClass)) {
                    intArrayType
                } else if (guestTypes.isStringArray(type)) {
                    stringArrayType
                } else if (type is IrSimpleType && type.classifier is IrClassSymbol) {
                    val classifier = type.classifier as IrClassSymbol
                    val inline = inlineValueClasses[classifier]
                    val id = classTypeIds[classifier]
                    val external = externalClassTypes[classifier]
                    val platformScalar = platformScalars.representation(type)
                    if (platformScalar != null) {
                        if (type.isNullable()) throw UnsupportedKotlinIr(element, "nullable platform scalar types are not supported")
                        when (platformScalar) {
                            PlatformScalarRepresentation.INT -> ValueType.I32
                            PlatformScalarRepresentation.BOOLEAN -> ValueType.Bool
                            PlatformScalarRepresentation.CHAR -> ValueType.Char
                        }
                    } else if (inline != null) {
                        if (type.isNullable()) throw UnsupportedKotlinIr(element, "nullable value classes are not supported")
                        valueType(inline.underlyingType, element)
                    } else if (id != null) {
                        ValueType.Ref(nullable = type.isNullable(), type = TypeRef.Local(id))
                    } else if (external != null) {
                        ValueType.Ref(nullable = type.isNullable(), type = external)
                    } else {
                        throw UnsupportedKotlinIr(element, "unsupported value type")
                    }
                } else {
                    throw UnsupportedKotlinIr(element, "unsupported value type")
                }
            }
        }

    private fun destinationFor(
        type: IrType,
        element: IrElement,
    ): Destination =
        if (type == unitType || type.isNothing()) Destination.Unit else Destination.Register(allocate(valueType(type, element)))

    private fun scalarType(
        type: IrType,
        element: IrElement,
    ): ScalarValueType =
        when (valueType(type, element)) {
            ValueType.I32 -> ScalarValueType.I32
            ValueType.Bool -> ScalarValueType.BOOL
            ValueType.Char -> ScalarValueType.CHAR
            else -> throw UnsupportedKotlinIr(element, "unsupported equality operand")
        }

    private fun orderedType(
        type: IrType,
        element: IrElement,
    ): OrderedScalarValueType =
        when (valueType(type, element)) {
            ValueType.I32 -> OrderedScalarValueType.I32
            ValueType.Char -> OrderedScalarValueType.CHAR
            else -> throw UnsupportedKotlinIr(element, "unsupported ordered-comparison operand")
        }

    private fun allocate(type: ValueType): RegisterId =
        RegisterId.of((loweredParameters(function, session).size + localTypes.size).toUInt()).also { localTypes += type }

    private fun emit(instruction: Instruction) {
        blocks[currentBlock].instructions += instruction
    }

    private fun createBlock(loopHeader: Boolean = false): Int {
        blocks += MutableBlock(loopHeader)
        return blocks.lastIndex
    }

    private fun blockId(local: Int): BlockId = BlockId.of((blockBase + local).toUInt())

    private fun jumpTo(local: Int) = emit(Instruction.Jump(blockId(local)))

    private fun prepareAllocationBlock() {
        val instructions = blocks[currentBlock].instructions
        if (instructions.isNotEmpty()) {
            val allocationBlock = createBlock()
            jumpTo(allocationBlock)
            currentBlock = allocationBlock
        }
    }

    private fun isTerminated(): Boolean = blocks[currentBlock].instructions.lastOrNull()?.isTerminator() == true

    private fun Instruction.isTerminator(): Boolean =
        this is Instruction.Jump ||
            this is Instruction.Branch ||
            this is Instruction.Return ||
            this is Instruction.Throw ||
            this is Instruction.Unreachable ||
            this is Instruction.CallSuspend ||
            this is Instruction.CapabilityCallAsync

    private data class MutableBlock(
        var loopHeaderSafepoint: Boolean = false,
        val instructions: MutableList<Instruction> = mutableListOf(),
    )

    private data class LoopContext(
        val loop: IrLoop,
        val continueTarget: Int?,
        val placeholderTarget: Int = requireNotNull(continueTarget),
        val breakBlocks: MutableList<Int> = mutableListOf(),
        val continueBlocks: MutableList<Int> = mutableListOf(),
    )

    private data class IntForLoopPlan(
        val loop: IrWhileLoop,
        val loopVariable: IrVariable,
        val start: IrExpression,
        val endInclusive: IrExpression,
        val inclusive: Boolean,
        val body: IrExpression,
    )
}

private fun IrType.isExactClass(symbol: IrClassSymbol): Boolean = (this as? IrSimpleType)?.classifier == symbol

private fun loweredParameters(
    function: IrSimpleFunction,
    session: CompilationSession,
) = if (
    session.platformFunctions.any { link ->
        link.symbol == function.fqNameWhenAvailable?.asString() && link.signature == function.canonicalPlatformSignature()
    }
) {
    if ((function.parent as? IrClass)?.kind == ClassKind.OBJECT) {
        function.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }
    } else {
        function.parameters
    }
} else if (
    session.trustedPlatformModule(function.file.fileEntry.name) != null &&
    (function.parent as? IrClass)?.kind == ClassKind.OBJECT
) {
    function.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }
} else {
    function.parameters
}

private fun IrSimpleFunction.canonicalPlatformSignature(): String {
    val parameters =
        parameters
            .filter { it.kind == IrParameterKind.ExtensionReceiver || it.kind == IrParameterKind.Regular }
            .joinToString(",") { it.type.canonicalPlatformType() }
    return "fun($parameters):${returnType.canonicalPlatformType()}"
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private class LiteralCollector(
    private val unitType: IrType,
) : IrVisitorVoid() {
    val values = mutableListOf<Any>()
    val strings: List<String>
        get() = values.filterIsInstance<String>()

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitConst(expression: IrConst) {
        expression.value?.takeIf { it is String || it is Int || it is Boolean || it is Char }?.let(values::add)
        super.visitConst(expression)
    }

    override fun visitBlock(expression: IrBlock) {
        if (expression.origin?.toString() == "FOR_LOOP") values += 1
        super.visitBlock(expression)
    }

    override fun visitCall(expression: IrCall) {
        val fqName =
            expression.symbol.owner.fqNameWhenAvailable
                ?.asString()
        if (fqName == "kotlin.Boolean.not") {
            values += false
        }
        if (fqName == "kotlin.emptyArray") {
            values += 0
        } else if (fqName == "kotlin.arrayOf" || fqName == "kotlin.intArrayOf") {
            val size = (expression.arguments.filterNotNull().singleOrNull() as? IrVararg)?.elements?.size
            if (size != null) values.addAll(0..size)
        } else if (fqName == "kotlin.collections.copyOfRange") {
            values.addAll(listOf(0, 1))
        }
        super.visitCall(expression)
    }

    override fun visitStringConcatenation(expression: IrStringConcatenation) {
        if (expression.arguments.any { it.type == unitType }) values += "kotlin.Unit"
        super.visitStringConcatenation(expression)
    }
}

private class StringArrayUsageCollector(
    private val guestTypes: GuestTypeRegistry,
) : IrVisitorVoid() {
    var used: Boolean = false
        private set

    override fun visitElement(element: IrElement) {
        if (element is IrExpression && guestTypes.isStringArray(element.type)) used = true
        element.acceptChildren(this, null)
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private class IntrinsicCollector(
    private val resolve: (IrSimpleFunction) -> LoweredCapabilityOperation?,
) : IrVisitorVoid() {
    val capabilities = mutableListOf<LoweredCapabilityIdentity>()

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }

    override fun visitCall(expression: IrCall) {
        resolve(expression.symbol.owner)?.capability?.let(capabilities::add)
        super.visitCall(expression)
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun resolveTrustedIntrinsic(
    function: IrSimpleFunction,
    session: CompilationSession,
): LoweredCapabilityOperation? {
    var parent = function.parent
    while (parent !is IrFile) {
        parent = (parent as? IrDeclaration)?.parent ?: break
    }
    val platformModule = (parent as? IrFile)?.let { session.trustedPlatformModule(it.fileEntry.name) }
    if (parent is IrFile && platformModule == null) return null
    val fqName = function.fqNameWhenAvailable?.asString() ?: return null
    val signature = function.canonicalPlatformSignature()
    val handler =
        session.canonicalIntrinsicRegistry
            ?.handlers
            ?.entries
            ?.singleOrNull { (key, _) ->
                (platformModule?.let { key.module == it } ?: (key.module in session.selectedPlatformModules)) &&
                    key.callableId.asSingleFqName().asString() == fqName &&
                    key.signature.value == signature
            }?.value as? CapabilityOperationHandler ?: return null
    val capability = handler.requiredCapability
    return LoweredCapabilityOperation(
        LoweredCapabilityIdentity(
            capability.namespace,
            capability.name,
            capability.abiMajor.toUShort(),
            0u.toUShort(),
            capabilityOperationCount(capability.namespace, capability.name),
        ),
        handler.operation,
        handler.blocking,
        handler.terminal,
    )
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrType.canonicalPlatformType(): String {
    val simple = this as? IrSimpleType ?: return toString()
    val classifier = simple.classifier
    val name =
        when (classifier) {
            is IrClassSymbol -> classifier.owner.relativeClassName()
            is IrTypeParameterSymbol -> classifier.owner.name.asString()
            else -> classifier.toString()
        }
    val arguments =
        simple.arguments
            .mapNotNull { (it as? IrTypeProjection)?.type?.canonicalPlatformType() }
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(prefix = "<", postfix = ">", separator = ",")
            .orEmpty()
    return name + arguments + if (simple.isNullable()) "?" else ""
}

private fun IrClass.relativeClassName(): String =
    generateSequence(this) { declaration -> declaration.parent as? IrClass }
        .map { declaration -> declaration.name.asString() }
        .toList()
        .asReversed()
        .joinToString(".")

private fun capabilityOperationCount(
    namespace: String,
    name: String,
): UInt =
    when (namespace to name) {
        "compukter" to "terminal" -> 14u
        "compukter" to "stdio" -> 3u
        "compukter" to "process" -> 3u
        "compukter" to "filesystem" -> 7u
        "compukter" to "compiler" -> 2u
        "compukter" to "redstone" -> 8u
        "compukter" to "sound" -> 1u
        else -> error("unknown Compukters capability $namespace:$name")
    }

private fun Any.toArtifactConstant(literalIds: Map<Utf16Literal, Utf16LiteralId>): Constant =
    when (this) {
        is String -> Constant.StringLiteral(requireNotNull(literalIds[Utf16Literal.fromString(this)]))
        is Int -> Constant.I32(this)
        is Boolean -> Constant.Bool(this)
        is Char -> Constant.Char(code.toUShort())
        else -> error("unsupported literal $this")
    }

private fun IrConst.toArtifactConstant(literalIds: Map<Utf16Literal, Utf16LiteralId>): Constant =
    when (val literal = value) {
        is String -> Constant.StringLiteral(requireNotNull(literalIds[Utf16Literal.fromString(literal)]))
        is Int -> Constant.I32(literal)
        is Boolean -> Constant.Bool(literal)
        is Char -> Constant.Char(literal.code.toUShort())
        else -> throw UnsupportedKotlinIr(this, "unsupported constant")
    }

private fun IrExpression.isTrueConstant(): Boolean = this is IrConst && value == true
