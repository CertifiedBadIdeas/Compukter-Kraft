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

package ru.lazyhat.compukters.platform.k2.build

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.backend.common.serialization.metadata.serializeKlibHeader
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.fir.pipeline.Fir2KlibMetadataSerializer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationContainer
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import ru.lazyhat.compukters.platform.bundle.CompuktersDefaultImports
import ru.lazyhat.compukters.platform.bundle.PlatformCompletionDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformCompletionKind
import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformDefaultArgument
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformScalarConstant
import ru.lazyhat.compukters.platform.bundle.PlatformScalarRepresentation
import ru.lazyhat.compukters.platform.bundle.PlatformScalarType
import ru.lazyhat.compukters.platform.bundle.PlatformScalarValue
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class CompiledPlatformMetadata(
    val metadata: ImmutableBytes,
    val declarations: List<PlatformDeclaration>,
    val exportedSymbols: List<String>,
    val completionDeclarations: List<PlatformCompletionDeclaration>,
    val libraryDeclarations: List<PlatformLibraryDeclaration>,
    val scalarTypes: List<PlatformScalarType> = emptyList(),
    val scalarConstants: List<PlatformScalarConstant> = emptyList(),
)

data class PlatformLibraryDeclaration(
    val symbol: String,
    val signature: String,
    val sourcePath: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val kind: PlatformLibraryDeclarationKind,
    val exported: Boolean = true,
)

enum class PlatformLibraryDeclarationKind {
    TYPE,
    FUNCTION,
    FIELD,
    PROPERTY,
}

data class DecodedPlatformMetadata(
    val module: PlatformModuleId,
    val declarations: List<PlatformDeclaration>,
    val exportedSymbols: List<String>,
    val moduleHeader: ByteArray = ByteArray(0),
    val fragments: List<PlatformMetadataFragment> = emptyList(),
)

class PlatformMetadataFragment(
    val packageName: String,
    val partName: String,
    bytes: ByteArray,
) {
    val bytes: ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PlatformMetadataFragment &&
            packageName == other.packageName &&
            partName == other.partName &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * packageName.hashCode() + partName.hashCode()) + bytes.contentHashCode()
}

/**
 * Compiles canonical declarations with Kotlin's common parser under the Compukters target identity.
 * FIR resolution consumes this deterministic declaration metadata in the next pipeline stage; this
 * compiler never installs a JVM classpath or a foreign standard library.
 */
@OptIn(CompilerConfiguration.Internals::class, org.jetbrains.kotlin.K1Deprecation::class)
class PlatformMetadataCompiler {
    fun compile(
        module: PlatformModuleId,
        sources: List<PlatformSource>,
    ): CompiledPlatformMetadata {
        require(sources.map(PlatformSource::path).toSet().size == sources.size) { "duplicate platform source paths" }
        val parsedPlatform = parse(module, sources)
        val declarations = parsedPlatform.declarations.sortedWith(DECLARATION_ORDER)
        val keys = mutableSetOf<Pair<String, String>>()
        declarations.forEach { parsed ->
            val declaration = parsed.declaration
            require(keys.add(declaration.symbol to declaration.signature)) {
                "duplicate platform declaration ${declaration.symbol} ${declaration.signature}"
            }
        }
        val exports =
            declarations
                .filterNot(ParsedDeclaration::private)
                .map(ParsedDeclaration::declaration)
                .map(PlatformDeclaration::symbol)
                .distinct()
                .sorted()
        val publicDeclarations = declarations.map(ParsedDeclaration::declaration)
        val decoded = DecodedPlatformMetadata(module, publicDeclarations, exports)
        return CompiledPlatformMetadata(
            metadata = PlatformMetadataCodec.encode(decoded),
            declarations = publicDeclarations,
            exportedSymbols = exports,
            completionDeclarations = parsedPlatform.completionDeclarations.sortedWith(COMPLETION_DECLARATION_ORDER),
            libraryDeclarations =
                declarations
                    .filter { parsed ->
                        parsed.hasBody ||
                            (
                                !parsed.private &&
                                    parsed.libraryKind in
                                    setOf(
                                        PlatformLibraryDeclarationKind.TYPE,
                                        PlatformLibraryDeclarationKind.FIELD,
                                    )
                            )
                    }.map { parsed ->
                        val declaration = parsed.declaration
                        PlatformLibraryDeclaration(
                            declaration.symbol,
                            declaration.signature,
                            declaration.sourcePath,
                            declaration.startUtf16,
                            declaration.endUtf16,
                            requireNotNull(parsed.libraryKind),
                            !parsed.private,
                        )
                    },
            scalarTypes = parsedPlatform.scalarTypes.sortedBy(PlatformScalarType::symbol),
            scalarConstants = parsedPlatform.scalarConstants.sortedBy(PlatformScalarConstant::symbol),
        )
    }

    fun attachResolvedMetadata(
        parsed: CompiledPlatformMetadata,
        output: CompuktersFirModuleOutput,
    ): CompiledPlatformMetadata {
        val decoded = PlatformMetadataCodec.decode(parsed.metadata)
        val configuration =
            CompilerConfiguration().apply {
                put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS, LanguageVersionSettingsImpl.DEFAULT)
            }
        val serializer = Fir2KlibMetadataSerializer(configuration, listOf(output.frontendOutput), null, false)
        val fragments = mutableListOf<PlatformMetadataFragment>()
        serializer.forEachFile { index, _, file, _, packageName ->
            fragments +=
                PlatformMetadataFragment(
                    packageName.asString(),
                    index.toString().padStart(8, '0'),
                    serializer.serializeSingleFileMetadata(file).toByteArray(),
                )
        }
        val ordered = fragments.sortedWith(compareBy(PlatformMetadataFragment::packageName, PlatformMetadataFragment::partName))
        val packageNames = ordered.map(PlatformMetadataFragment::packageName).distinct()
        val resolved =
            decoded.copy(
                moduleHeader =
                    serializeKlibHeader(
                        LanguageVersionSettingsImpl.DEFAULT,
                        decoded.module.toString(),
                        packageNames,
                        emptyList(),
                    ).toByteArray(),
                fragments = ordered,
            )
        return parsed.copy(metadata = PlatformMetadataCodec.encode(resolved))
    }

    private fun parse(
        module: PlatformModuleId,
        sources: List<PlatformSource>,
    ): ParsedPlatform {
        val disposable = Disposer.newDisposable("Compukters platform metadata compiler")
        return try {
            val configuration =
                CompilerConfiguration().apply {
                    put(CommonConfigurationKeys.MODULE_NAME, module.toString())
                    put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
                }
            val environment =
                KotlinCoreEnvironment.createForProduction(
                    disposable,
                    configuration,
                    EnvironmentConfigFiles.METADATA_CONFIG_FILES,
                )
            val factory = KtPsiFactory(environment.project, markGenerated = false)
            val declarations = mutableListOf<ParsedDeclaration>()
            val completionDeclarations = mutableListOf<PlatformCompletionDeclaration>()
            val scalarTypes = mutableListOf<PlatformScalarType>()
            val scalarConstants = mutableListOf<PlatformScalarConstant>()
            sources
                .sortedBy(PlatformSource::path)
                .forEach { source ->
                    val content = strictUtf8(source.content.toByteArray(), source.path)
                    val file = factory.createFile(source.path.substringAfterLast('/'), content)
                    val errors = file.collectDescendantsOfType<PsiErrorElement>()
                    require(errors.isEmpty()) {
                        "invalid Kotlin platform source ${source.path}: ${errors.joinToString { it.errorDescription }}"
                    }
                    val packageName = file.packageFqName.asString()
                    file.declarations.forEach { declaration ->
                        declarations += collect(module, source.path, packageName, emptyList(), declaration = declaration)
                        completionDeclaration(module, source.path, packageName, declaration)?.let(completionDeclarations::add)
                        collectScalars(source.path, packageName, emptyList(), declaration)?.let { scalar ->
                            scalarTypes += scalar.type
                            scalarConstants += scalar.constants
                        }
                    }
                }
            ParsedPlatform(declarations, completionDeclarations, scalarTypes, scalarConstants)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun completionDeclaration(
        module: PlatformModuleId,
        sourcePath: String,
        packageName: String,
        declaration: KtDeclaration,
    ): PlatformCompletionDeclaration? {
        if (declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) || declaration.hasModifier(KtTokens.INTERNAL_KEYWORD)) return null
        val named = declaration as? KtNamedDeclaration ?: return null
        val shortName = named.name ?: return null
        val kind =
            when (declaration) {
                is KtObjectDeclaration -> PlatformCompletionKind.OBJECT
                is KtClass -> if (declaration.isInterface()) PlatformCompletionKind.INTERFACE else PlatformCompletionKind.CLASS
                is KtNamedFunction -> PlatformCompletionKind.FUNCTION.takeIf { declaration.receiverTypeReference == null }
                is KtProperty -> PlatformCompletionKind.PROPERTY.takeIf { declaration.receiverTypeReference == null }
                is KtTypeAlias -> PlatformCompletionKind.TYPE_ALIAS
                else -> null
            } ?: return null
        val symbol = listOf(packageName, shortName).filter(String::isNotEmpty).joinToString(".")
        return PlatformCompletionDeclaration(
            symbol = symbol,
            shortName = shortName,
            signature = signature(declaration),
            kind = kind,
            module = module,
            sourcePath = sourcePath,
            startUtf16 = declaration.declarationStartOffset(),
            endUtf16 = declaration.textRange.endOffset,
            defaultImport = CompuktersDefaultImports.containsPackage(packageName),
        )
    }

    private fun collectScalars(
        sourcePath: String,
        packageName: String,
        owners: List<String>,
        declaration: KtDeclaration,
    ): ScalarExtraction? {
        val klass = declaration as? KtClass ?: return null
        if (!klass.hasModifier(KtTokens.VALUE_KEYWORD)) return null
        val name = requireNotNull(klass.name) { "value class must have a name" }
        val symbol = (listOf(packageName).filter(String::isNotEmpty) + owners + name).joinToString(".")
        val parameter =
            klass.primaryConstructorParameters.singleOrNull()
                ?: throw IllegalArgumentException("platform scalar type $symbol must have one constructor property")
        require(parameter.hasValOrVar() && parameter.valOrVarKeyword?.node?.elementType == KtTokens.VAL_KEYWORD) {
            "platform scalar type $symbol must have one immutable property"
        }
        val representation =
            when (parameter.typeReference?.text?.canonicalType()) {
                "Int", "kotlin.Int" -> PlatformScalarRepresentation.INT
                "Boolean", "kotlin.Boolean" -> PlatformScalarRepresentation.BOOLEAN
                "Char", "kotlin.Char" -> PlatformScalarRepresentation.CHAR
                else -> throw IllegalArgumentException("platform scalar type $symbol must use Int, Boolean, or Char")
            }
        val initializers = klass.declarations.filterIsInstance<KtClassInitializer>()
        val intRange =
            if (initializers.isEmpty()) {
                null
            } else {
                require(representation == PlatformScalarRepresentation.INT && initializers.size == 1) {
                    "platform scalar type $symbol must use one bounded Int precondition"
                }
                val propertyName = requireNotNull(parameter.name)
                val match =
                    Regex("require\\s*\\(\\s*${Regex.escape(propertyName)}\\s+in\\s+([^.]*)\\.\\.([^)]*)\\)")
                        .find(requireNotNull(initializers.single().body).text)
                        ?: throw IllegalArgumentException("platform scalar type $symbol must use one bounded Int precondition")
                parseIntLiteral(match.groupValues[1].trim()) to parseIntLiteral(match.groupValues[2].trim())
            }
        val companion = klass.declarations.filterIsInstance<KtObjectDeclaration>().singleOrNull(KtObjectDeclaration::isCompanion)
        val constants =
            companion?.declarations.orEmpty().filterIsInstance<KtProperty>().map { property ->
                require(!property.isVar && property.getter == null) {
                    "platform scalar constant $symbol.${property.name} must be an immutable initialized property"
                }
                require(property.typeReference?.text?.canonicalType() in setOf(name, symbol)) {
                    "platform scalar constant $symbol.${property.name} must have type $symbol"
                }
                PlatformScalarConstant(
                    "$symbol.${requireNotNull(property.name)}",
                    symbol,
                    scalarValue(symbol, representation, property),
                )
            }
        return ScalarExtraction(
            PlatformScalarType(
                symbol,
                representation,
                requireNotNull(parameter.name),
                sourcePath,
                klass.declarationStartOffset(),
                klass.textRange.endOffset,
                intRange?.first,
                intRange?.second,
            ),
            constants,
        )
    }

    private fun scalarValue(
        type: String,
        representation: PlatformScalarRepresentation,
        property: KtProperty,
    ): PlatformScalarValue {
        val call =
            property.initializer as? KtCallExpression
                ?: throw IllegalArgumentException("platform scalar constant $type.${property.name} must call its value class constructor")
        require(call.calleeExpression?.text in setOf(type, type.substringAfterLast('.'))) {
            "platform scalar constant $type.${property.name} must call $type"
        }
        val literal =
            call.valueArguments.singleOrNull()?.getArgumentExpression() as? KtConstantExpression
                ?: throw IllegalArgumentException("platform scalar constant $type.${property.name} must contain one literal")
        return when (representation) {
            PlatformScalarRepresentation.INT -> {
                PlatformScalarValue.IntValue(parseIntLiteral(literal.text))
            }

            PlatformScalarRepresentation.BOOLEAN -> {
                PlatformScalarValue.BooleanValue(
                    when (literal.text) {
                        "true" -> true
                        "false" -> false
                        else -> throw IllegalArgumentException("invalid Boolean scalar literal ${literal.text}")
                    },
                )
            }

            PlatformScalarRepresentation.CHAR -> {
                val value = literal.text
                require(value.length == 3 && value.first() == '\'' && value.last() == '\'') { "invalid Char scalar literal $value" }
                PlatformScalarValue.CharValue(value[1])
            }
        }
    }

    private fun parseIntLiteral(text: String): Int {
        val normalized = text.replace("_", "")
        val negative = normalized.startsWith('-')
        val unsigned = normalized.removePrefix("-")
        val (radix, digits) = if (unsigned.startsWith("0x", ignoreCase = true)) 16 to unsigned.drop(2) else 10 to unsigned
        val value = digits.toLong(radix)
        val signed = if (negative) -value else value
        require(signed in Int.MIN_VALUE..Int.MAX_VALUE) { "Int scalar literal is out of range: $text" }
        return signed.toInt()
    }

    private fun collect(
        module: PlatformModuleId,
        sourcePath: String,
        packageName: String,
        owners: List<String>,
        inheritedPrivate: Boolean = false,
        ownerHasDispatchReceiver: Boolean = false,
        declaration: KtDeclaration,
    ): List<ParsedDeclaration> {
        val named = declaration as? KtNamedDeclaration ?: return emptyList()
        val name =
            when {
                declaration is KtConstructor<*> -> "<init>"
                declaration is KtObjectDeclaration && declaration.isCompanion() -> "Companion"
                else -> named.name ?: return emptyList()
            }
        val symbol = (listOf(packageName).filter(String::isNotEmpty) + owners + name).joinToString(".")
        val external = declaration.hasModifier(KtTokens.EXTERNAL_KEYWORD)
        val platformDeclaration =
            PlatformDeclaration(
                symbol = symbol,
                signature = signature(declaration),
                module = module,
                sourcePath = sourcePath,
                startUtf16 = declaration.declarationStartOffset(),
                endUtf16 = declaration.textRange.endOffset,
                trustedExternal = external,
                defaultArguments = defaultArguments(packageName, ownerHasDispatchReceiver, declaration),
            )
        val private =
            inheritedPrivate || declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) || declaration.hasModifier(KtTokens.INTERNAL_KEYWORD)
        val propertyParameters =
            (declaration as? KtClass)
                ?.takeUnless { it.hasModifier(KtTokens.VALUE_KEYWORD) }
                ?.primaryConstructorParameters
                .orEmpty()
                .filter(KtParameter::hasValOrVar)
        val nested =
            (
                (declaration as? KtDeclarationContainer)?.declarations.orEmpty() +
                    listOfNotNull((declaration as? KtClass)?.primaryConstructor) +
                    propertyParameters
            ).flatMap { child ->
                collect(
                    module,
                    sourcePath,
                    packageName,
                    owners + name,
                    private,
                    declaration is KtClass,
                    child,
                )
            }
        val parsed =
            ParsedDeclaration(
                platformDeclaration,
                private,
                when (declaration) {
                    is KtNamedFunction -> declaration.hasBody()
                    is KtProperty -> declaration.hasInitializer() || declaration.accessors.any { it.hasBody() }
                    else -> false
                },
                when (declaration) {
                    is KtNamedFunction -> PlatformLibraryDeclarationKind.FUNCTION
                    is KtEnumEntry -> PlatformLibraryDeclarationKind.FIELD
                    is KtParameter -> PlatformLibraryDeclarationKind.FIELD.takeIf { declaration.hasValOrVar() }
                    is KtProperty -> PlatformLibraryDeclarationKind.PROPERTY
                    is KtClass -> PlatformLibraryDeclarationKind.TYPE.takeUnless { declaration.hasModifier(KtTokens.VALUE_KEYWORD) }
                    else -> null
                },
            )
        val getter =
            (declaration as? KtProperty)
                ?.takeIf { property -> property.getter?.hasBody() == true }
                ?.let { property ->
                    ParsedDeclaration(
                        platformDeclaration.copy(
                            symbol = (listOf(packageName).filter(String::isNotEmpty) + owners + "<get-$name>").joinToString("."),
                            signature = "fun():${property.typeReference?.text?.canonicalType() ?: "?"}",
                        ),
                        private,
                        hasBody = true,
                        libraryKind = PlatformLibraryDeclarationKind.FUNCTION,
                    )
                }
        return listOf(parsed) + listOfNotNull(getter) + nested
    }

    private fun defaultArguments(
        packageName: String,
        ownerHasDispatchReceiver: Boolean,
        declaration: KtDeclaration,
    ): List<PlatformDefaultArgument?> {
        val function = declaration as? KtNamedFunction ?: return emptyList()
        val values =
            function.valueParameters.map { parameter ->
                parameter.defaultValue?.let { expression ->
                    val reference = expression.text
                    val intValue = parseDefaultIntLiteral(reference)
                    if (intValue != null) {
                        PlatformDefaultArgument.IntValue(intValue)
                    } else {
                        require(reference.matches(QUALIFIED_IDENTIFIER)) {
                            "platform default argument must be a qualified enum entry or Int literal: $reference"
                        }
                        PlatformDefaultArgument.EnumEntry(
                            if (reference.startsWith("$packageName.")) reference else "$packageName.$reference",
                        )
                    }
                }
            }
        if (values.none { it != null }) return emptyList()
        val implicitParameters = (if (ownerHasDispatchReceiver) 1 else 0) + (if (function.receiverTypeReference == null) 0 else 1)
        return List(implicitParameters) { null } + values
    }

    private fun signature(declaration: KtDeclaration): String =
        when (declaration) {
            is KtNamedFunction -> {
                val receiver =
                    declaration.receiverTypeReference
                        ?.text
                        ?.canonicalType()
                        ?.plus(".")
                        .orEmpty()
                val parameters = declaration.valueParameters.joinToString(",") { it.typeReference?.text?.canonicalType() ?: "?" }
                val result = declaration.typeReference?.text?.canonicalType() ?: if (declaration.hasBlockBody()) "Unit" else "?"
                "fun($receiver$parameters):$result"
            }

            is KtProperty -> {
                val receiver =
                    declaration.receiverTypeReference
                        ?.text
                        ?.canonicalType()
                        ?.plus(".")
                        .orEmpty()
                "${if (declaration.isVar) "var" else "val"}($receiver):${declaration.typeReference?.text?.canonicalType() ?: "?"}"
            }

            is KtParameter -> {
                val mutability = if (declaration.valOrVarKeyword?.node?.elementType == KtTokens.VAR_KEYWORD) "var" else "val"
                "$mutability():${declaration.typeReference?.text?.canonicalType() ?: "?"}"
            }

            is KtEnumEntry -> {
                "enum-entry"
            }

            is KtTypeAlias -> {
                "typealias:${declaration.getTypeReference()?.text?.canonicalType() ?: "?"}"
            }

            is KtConstructor<*> -> {
                "constructor(${declaration.valueParameters.joinToString(",") { it.typeReference?.text?.canonicalType() ?: "?" }})"
            }

            is KtObjectDeclaration -> {
                if (declaration.isCompanion()) "companion" else "object"
            }

            is KtClass -> {
                val kind =
                    when {
                        declaration.isInterface() -> "interface"
                        declaration.isEnum() -> "enum"
                        declaration.isAnnotation() -> "annotation"
                        declaration.hasModifier(KtTokens.VALUE_KEYWORD) -> "value-class"
                        else -> "class"
                    }
                val parameters =
                    declaration.primaryConstructorParameters.joinToString(
                        ",",
                    ) { it.typeReference?.text?.canonicalType() ?: "?" }
                "$kind($parameters)"
            }

            is KtClassOrObject -> {
                "class"
            }

            else -> {
                declaration.javaClass.simpleName
            }
        }

    private data class ParsedDeclaration(
        val declaration: PlatformDeclaration,
        val private: Boolean,
        val hasBody: Boolean,
        val libraryKind: PlatformLibraryDeclarationKind?,
    )

    private data class ParsedPlatform(
        val declarations: List<ParsedDeclaration>,
        val completionDeclarations: List<PlatformCompletionDeclaration>,
        val scalarTypes: List<PlatformScalarType>,
        val scalarConstants: List<PlatformScalarConstant>,
    )

    private data class ScalarExtraction(
        val type: PlatformScalarType,
        val constants: List<PlatformScalarConstant>,
    )

    private companion object {
        val QUALIFIED_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
        val DECLARATION_ORDER =
            compareBy<ParsedDeclaration>(
                { it.declaration.symbol },
                { it.declaration.signature },
                { it.declaration.sourcePath },
                { it.declaration.startUtf16 },
            )
        val COMPLETION_DECLARATION_ORDER =
            compareBy<PlatformCompletionDeclaration>(
                PlatformCompletionDeclaration::shortName,
                PlatformCompletionDeclaration::symbol,
                PlatformCompletionDeclaration::signature,
                PlatformCompletionDeclaration::sourcePath,
                PlatformCompletionDeclaration::startUtf16,
            )
    }
}

object PlatformMetadataCodec {
    private const val FORMAT = 4
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte())

    fun encode(metadata: DecodedPlatformMetadata): ImmutableBytes {
        val bytes =
            ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { sink ->
                    sink.write(MAGIC)
                    sink.writeInt(FORMAT)
                    sink.string(metadata.module.namespace)
                    sink.string(metadata.module.name)
                    sink.writeInt(metadata.declarations.size)
                    metadata.declarations.forEach { declaration ->
                        sink.string(declaration.symbol)
                        sink.string(declaration.signature)
                        sink.string(declaration.sourcePath)
                        sink.writeInt(declaration.startUtf16)
                        sink.writeInt(declaration.endUtf16)
                        sink.writeBoolean(declaration.trustedExternal)
                        sink.writeInt(declaration.defaultArguments.size)
                        declaration.defaultArguments.forEach { argument ->
                            when (argument) {
                                null -> {
                                    sink.writeByte(0)
                                }

                                is PlatformDefaultArgument.EnumEntry -> {
                                    sink.writeByte(1)
                                    sink.string(argument.symbol)
                                }

                                is PlatformDefaultArgument.IntValue -> {
                                    sink.writeByte(2)
                                    sink.writeInt(argument.value)
                                }
                            }
                        }
                    }
                    sink.writeInt(metadata.exportedSymbols.size)
                    metadata.exportedSymbols.forEach(sink::string)
                    sink.bytes(metadata.moduleHeader)
                    sink.writeInt(metadata.fragments.size)
                    metadata.fragments.forEach { fragment ->
                        sink.string(fragment.packageName)
                        sink.string(fragment.partName)
                        sink.bytes(fragment.bytes)
                    }
                }
                output.toByteArray()
            }
        return ImmutableBytes.of(bytes)
    }

    fun decode(bytes: ImmutableBytes): DecodedPlatformMetadata =
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { source ->
            require(source.readNBytes(MAGIC.size).contentEquals(MAGIC)) { "invalid platform metadata magic" }
            require(source.readInt() == FORMAT) { "unsupported platform metadata format" }
            val module = PlatformModuleId(source.string(), source.string())
            val declarations =
                List(source.count("declaration")) {
                    PlatformDeclaration(
                        symbol = source.string(),
                        signature = source.string(),
                        module = module,
                        sourcePath = source.string(),
                        startUtf16 = source.readInt().also { require(it >= 0) },
                        endUtf16 = source.readInt().also { require(it >= 0) },
                        trustedExternal = source.readBoolean(),
                        defaultArguments =
                            List(source.count("default argument")) {
                                when (val tag = source.readUnsignedByte()) {
                                    0 -> null
                                    1 -> PlatformDefaultArgument.EnumEntry(source.string())
                                    2 -> PlatformDefaultArgument.IntValue(source.readInt())
                                    else -> throw IllegalArgumentException("invalid platform default argument tag: $tag")
                                }
                            },
                    )
                }
            val exports = List(source.count("export")) { source.string() }
            val moduleHeader = source.bytes("module header")
            val fragments =
                List(source.count("fragment")) {
                    PlatformMetadataFragment(
                        source.string(),
                        source.string(),
                        source.bytes("fragment"),
                    )
                }
            require(source.read() == -1) { "trailing platform metadata bytes" }
            require(declarations == declarations.sortedWith(DECLARATION_ORDER)) { "platform metadata declarations are not canonical" }
            require(exports == exports.distinct().sorted()) { "platform metadata exports are not canonical" }
            require(
                fragments == fragments.sortedWith(compareBy(PlatformMetadataFragment::packageName, PlatformMetadataFragment::partName)),
            ) {
                "platform metadata fragments are not canonical"
            }
            DecodedPlatformMetadata(module, declarations, exports, moduleHeader, fragments)
        }

    fun validateAgainstSources(
        metadata: DecodedPlatformMetadata,
        sources: List<PlatformSource>,
    ) {
        val compiled = PlatformMetadataCompiler().compile(metadata.module, sources)
        require(compiled.declarations == metadata.declarations) { "platform metadata/source declaration mismatch" }
        require(compiled.exportedSymbols == metadata.exportedSymbols) { "platform metadata/source export mismatch" }
    }

    private val DECLARATION_ORDER =
        compareBy<PlatformDeclaration>(
            PlatformDeclaration::symbol,
            PlatformDeclaration::signature,
            PlatformDeclaration::sourcePath,
            PlatformDeclaration::startUtf16,
        )
}

private fun DataOutputStream.string(value: String) {
    val bytes = value.encodeToByteArray()
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.bytes(value: ByteArray) {
    writeInt(value.size)
    write(value)
}

private fun DataInputStream.string(): String {
    val length = readInt()
    require(length in 0..1_048_576) { "invalid platform metadata string length" }
    return strictUtf8(readNBytes(length), "platform metadata string").also {
        require(it.encodeToByteArray().size == length) { "truncated platform metadata string" }
    }
}

private fun DataInputStream.count(label: String): Int =
    readInt().also { require(it in 0..262_144) { "invalid platform metadata $label count" } }

private fun DataInputStream.bytes(label: String): ByteArray {
    val length = readInt()
    require(length in 0..16_777_216) { "invalid platform metadata $label length" }
    return readNBytes(length).also { require(it.size == length) { "truncated platform metadata $label" } }
}

private fun String.canonicalType(): String = replace(Regex("\\s+"), "")

private fun parseDefaultIntLiteral(source: String): Int? {
    if (!source.matches(INT_LITERAL)) return null
    val compact = source.replace("_", "")
    val negative = compact.startsWith('-')
    val unsigned = compact.removePrefix("-").removePrefix("+")
    val (digits, radix) =
        when {
            unsigned.startsWith("0x", ignoreCase = true) -> unsigned.drop(2) to 16
            unsigned.startsWith("0b", ignoreCase = true) -> unsigned.drop(2) to 2
            else -> unsigned to 10
        }
    if (digits.isEmpty()) return null
    val magnitude = digits.toLongOrNull(radix) ?: return null
    val value = if (negative) -magnitude else magnitude
    return value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
}

private val INT_LITERAL = Regex("[+-]?(?:[0-9](?:_?[0-9])*|0[xX][0-9a-fA-F](?:_?[0-9a-fA-F])*|0[bB][01](?:_?[01])*)")

private fun KtDeclaration.declarationStartOffset(): Int =
    modifierList?.textRange?.startOffset
        ?: when (this) {
            is KtNamedFunction -> funKeyword?.textRange?.startOffset
            is KtClassOrObject -> nameIdentifier?.textRange?.startOffset
            is KtProperty -> valOrVarKeyword.textRange.startOffset
            else -> null
        }
        ?: textRange.startOffset

private fun strictUtf8(
    bytes: ByteArray,
    label: String,
): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("$label is not valid UTF-8", failure)
    }
