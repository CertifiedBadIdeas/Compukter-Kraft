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

@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.lazyhat.compukters.compiler.worker.k2

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.diagnostics.Severity
import ru.lazyhat.compukters.compiler.artifact.link.LibraryModuleLinker
import ru.lazyhat.compukters.compiler.artifact.model.*
import ru.lazyhat.compukters.compiler.artifact.read.ArtifactReader
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteLimits
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.k2.engine.CompilationSession
import ru.lazyhat.compukters.compiler.k2.engine.CompuktersFir2IrPipeline
import ru.lazyhat.compukters.compiler.k2.engine.PlatformFieldLink
import ru.lazyhat.compukters.compiler.k2.engine.PlatformFunctionLink
import ru.lazyhat.compukters.compiler.k2.engine.PlatformTypeLink
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.CanonicalTrustedIntrinsics
import ru.lazyhat.compukters.compiler.k2.engine.library.PlatformLibraryFragmentCodec
import ru.lazyhat.compukters.compiler.worker.controller.TemporaryBudget
import ru.lazyhat.compukters.compiler.worker.controller.TemporaryUsage
import ru.lazyhat.compukters.compiler.worker.protocol.*
import ru.lazyhat.compukters.platform.bundle.*
import ru.lazyhat.compukters.platform.k2.CompuktersPlatformCheckers
import ru.lazyhat.compukters.platform.k2.CompuktersPlatformDiagnosticCode
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirBuildEnvironment
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

data class K2CompilerInputs(
    val temporaryRoot: Path,
    val workerJar: Path,
    val expectedIdentity: WorkerIdentity,
)

data class K2CompilationResult(
    val exitCode: ExitCode,
    val diagnostics: List<WorkerDiagnostic>,
    val reachedIr: Boolean,
    val artifact: BinaryValue?,
    private val compilerHadErrors: Boolean,
) {
    val hasErrors: Boolean get() = compilerHadErrors
}

class K2CompilerAdapter(
    private val inputs: K2CompilerInputs,
    private val platform: PlatformBundle = loadPackagedPlatform(),
) {
    init {
        require(Files.isRegularFile(inputs.workerJar)) { "validated worker jar is missing" }
        require(platform.identity.languageVersion == inputs.expectedIdentity.languageVersion) { "worker/platform language mismatch" }
        require(platform.identity.platformAbi == PlatformBundleCodec.SUPPORTED_PLATFORM_ABI) { "unsupported Compukters platform ABI" }
        require(
            platform.identity.contentHash
                .toByteArray()
                .contentEquals(inputs.expectedIdentity.platformAbi.toByteArray()),
        ) { "worker identity does not match the packaged Compukters platform" }
    }

    fun compile(request: CompileRequest): K2CompilationResult {
        require(request.expectedIdentity == inputs.expectedIdentity) { "compile request identity does not match pinned worker" }
        val diagnostics = mutableListOf<WorkerDiagnostic>()
        request.sources.forEach { source ->
            val text = source.content.toByteArray().decodeToString(throwOnInvalidSequence = true)
            if (DEPENDENCY_ANNOTATION.containsMatchIn(text)) {
                diagnostics += targetDiagnostic(source.path, "script dependency refinement is unsupported")
            }
            CompuktersPlatformCheckers.checkGuestSource(text).forEach { diagnostic ->
                diagnostics +=
                    WorkerDiagnostic(
                        DiagnosticSeverity.ERROR,
                        DiagnosticCategory.TARGET,
                        diagnostic.code.name,
                        when (diagnostic.code) {
                            CompuktersPlatformDiagnosticCode.FOREIGN_PLATFORM_REFERENCE -> {
                                "foreign platform references are unsupported"
                            }

                            CompuktersPlatformDiagnosticCode.JVM_INLINE -> {
                                "@JvmInline is not part of the Compukters platform"
                            }

                            CompuktersPlatformDiagnosticCode.GUEST_EXTERNAL_DECLARATION -> {
                                "Guest external declarations are unsupported"
                            }
                        },
                        source.path,
                        diagnostic.startUtf16.toUInt(),
                        diagnostic.endUtf16.toUInt(),
                    )
            }
        }
        if (diagnostics.isNotEmpty()) return failure(diagnostics, reachedIr = false, request.limits)
        val selected = selectModules(request)
        val libraries = loadLibraries(selected)
        val budget = TemporaryBudget(inputs.temporaryRoot, request.limits)
        budget.requireCapacity(sourceFootprint(request))
        return budget.useRequestDirectory { requestRoot ->
            val sourceRoot =
                requestRoot
                    .resolve("source")
                    .toAbsolutePath()
                    .normalize()
                    .also(Path::createDirectories)
            request.sources.forEach { source ->
                val physical = sourceRoot.resolve(source.path.value).normalize()
                require(physical.startsWith(sourceRoot)) { "source path escapes request tree" }
                physical.parent.createDirectories()
                physical.writeBytes(source.content.toByteArray())
            }
            val platformSources =
                request.sources.map { source ->
                    PlatformSource(source.path.value, ImmutableBytes.of(source.content.toByteArray()))
                }
            var reachedIr = false
            var artifact: BinaryValue? = null
            val sourcePaths =
                request.sources
                    .flatMap { source ->
                        listOf(
                            Path.of(source.path.value).fileName.toString(),
                            source.path.value,
                            sourceRoot.resolve(source.path.value).normalize().toString(),
                        ).map { physical -> physical to source.path }
                    }.toMap()
            CompuktersFirBuildEnvironment.create().use { environment ->
                val output =
                    environment.compileGuest(
                        PlatformModuleId("guest", "application"),
                        platformSources,
                        selected,
                    )
                output.diagnostics.diagnosticsByFile.forEach { (file, fileDiagnostics) ->
                    val path = file?.name?.let(sourcePaths::get)
                    fileDiagnostics.forEach { diagnostic ->
                        diagnostics +=
                            WorkerDiagnostic(
                                when (diagnostic.severity) {
                                    Severity.ERROR -> DiagnosticSeverity.ERROR
                                    Severity.INFO -> DiagnosticSeverity.INFO
                                    else -> DiagnosticSeverity.WARNING
                                },
                                if (
                                    diagnostic.factoryName.contains("SYNTAX") ||
                                    diagnostic.renderMessage().contains("expecting", ignoreCase = true)
                                ) {
                                    DiagnosticCategory.SYNTAX
                                } else {
                                    DiagnosticCategory.TYPE
                                },
                                diagnostic.factoryName,
                                diagnostic.renderMessage(),
                                path,
                                diagnostic.firstRange.startOffset.toUInt(),
                                diagnostic.firstRange.endOffset.toUInt(),
                            )
                    }
                }
                if (!output.diagnostics.hasErrors) {
                    val session =
                        CompilationSession(
                            irSink = { _, _ -> reachedIr = true },
                            diagnosticSink = { diagnostic ->
                                diagnostics +=
                                    if (diagnostic.path == null && request.sources.size == 1) {
                                        diagnostic.copy(path = request.sources.single().path)
                                    } else {
                                        diagnostic
                                    }
                            },
                            sourcePaths = sourcePaths,
                            canonicalIntrinsicRegistry = CanonicalTrustedIntrinsics.registry,
                            selectedPlatformModules = selected.mapTo(mutableSetOf(), PlatformModule::id),
                            platformFunctions = libraries.functions,
                            platformTypes = libraries.types,
                            platformFields = libraries.fields,
                            platformScalarTypes = selected.flatMap(PlatformModule::scalarTypes),
                            platformScalarConstants = selected.flatMap(PlatformModule::scalarConstants),
                            limits = request.limits,
                        )
                    CompuktersFir2IrPipeline.lowerGuest(output, session)?.let { lowered ->
                        val linked = linkLibraries(lowered, libraries.artifacts)
                        when (
                            val result =
                                ArtifactWriter.write(
                                    linked,
                                    ArtifactWriteLimits(
                                        artifactBytes = request.limits.artifactBytes,
                                        diagnostics = request.limits.diagnostics,
                                    ),
                                )
                        ) {
                            is ArtifactWriteResult.Success -> {
                                artifact = BinaryValue.of(result.bytes)
                            }

                            is ArtifactWriteResult.Failure -> {
                                result.errors.forEach { error ->
                                    diagnostics +=
                                        WorkerDiagnostic(
                                            DiagnosticSeverity.ERROR,
                                            DiagnosticCategory.INTERNAL,
                                            "ARTIFACT_WRITE_${error.code}",
                                            "artifact writer rejected lowered IR: ${error.detail}",
                                            null,
                                            null,
                                            null,
                                        )
                                }
                            }
                        }
                    }
                }
            }
            val failed = diagnostics.any { it.severity == DiagnosticSeverity.ERROR }
            K2CompilationResult(
                if (failed) ExitCode.COMPILATION_ERROR else ExitCode.OK,
                diagnostics.bounded(request.limits),
                reachedIr,
                artifact?.takeIf { !failed },
                failed,
            )
        }
    }

    private fun selectModules(request: CompileRequest): List<PlatformModule> {
        val byName = platform.modules.associateBy { it.id.toString() }
        val requested =
            request.platformModules.map { identity ->
                val module = requireNotNull(byName[identity.name]) { "unknown platform module ${identity.name}" }
                require(PlatformBundleCodec.moduleContentHash(module).toByteArray().contentEquals(identity.hash.toByteArray())) {
                    "platform module ${identity.name} content hash mismatch"
                }
                module
            }
        val resolved = PlatformModuleGraph(platform).resolve(requested.mapTo(mutableSetOf(), PlatformModule::id)).modules
        require(resolved.map(PlatformModule::id).toSet() == requested.map(PlatformModule::id).toSet()) {
            "compile request platform module closure is incomplete"
        }
        return listOf(platform.builtins) + resolved
    }

    private fun loadLibraries(modules: List<PlatformModule>): LoadedLibraries {
        val artifacts =
            modules.mapNotNull { module ->
                module.libraryFragment?.let { fragmentBytes ->
                    val fragment = PlatformLibraryFragmentCodec.decode(fragmentBytes)
                    require(fragment.module == module.id) { "platform library fragment identity mismatch" }
                    module to ArtifactReader.read(fragment.artifact.toByteArray())
                }
            }
        val functions = mutableListOf<PlatformFunctionLink>()
        val types = mutableListOf<PlatformTypeLink>()
        val fields = mutableListOf<PlatformFieldLink>()
        artifacts.forEach { (platformModule, artifact) ->
            val library =
                artifact.modules.single { module ->
                    module.kind == ModuleKind.LIBRARY && module.exports.any { it.kind == SymbolKind.FUNCTION }
                }
            val moduleHash = ArtifactWriter.moduleSemanticHash(library)
            functions +=
                platformModule.declarations
                    .filter { declaration -> !declaration.trustedExternal && declaration.signature.startsWith("fun(") }
                    .map { declaration ->
                        val simpleName = declaration.symbol.substringAfterLast('.')
                        val candidates =
                            library.exports.filter { export ->
                                export.kind == SymbolKind.FUNCTION &&
                                    library.strings[export.name.value.toInt()].toString().let { name ->
                                        name == simpleName || name.startsWith("$simpleName#")
                                    }
                            }
                        val matching =
                            candidates.filter { export ->
                                library.functionSignature(export.signature).shortTypeNames() == declaration.signature.shortTypeNames()
                            }
                        val exactName = candidates.filter { export -> library.strings[export.name.value.toInt()].toString() == simpleName }
                        val sourceShape =
                            declaration.signature
                                .removePrefix("fun")
                                .replaceFirst(":", "->")
                                .shortTypeNames()
                        val ownerType = declaration.symbol.substringBeforeLast('.').substringAfterLast('.')
                        val sourceShapes = setOf(sourceShape, sourceShape.withLeadingSourceParameter(ownerType))
                        val mangled =
                            candidates.filter { export ->
                                library.strings[export.name.value.toInt()]
                                    .toString()
                                    .substringAfter('#', "")
                                    .shortTypeNames() in sourceShapes
                            }
                        val export =
                            mangled.singleOrNull() ?: matching.singleOrNull() ?: exactName.singleOrNull() ?: candidates.singleOrNull()
                                ?: error(
                                    "cannot uniquely match ${declaration.symbol} ${declaration.signature} to a platform export: " +
                                        candidates.joinToString { library.strings[it.name.value.toInt()].toString() },
                                )
                        PlatformFunctionLink(
                            declaration.symbol,
                            declaration.signature,
                            library.strings[export.name.value.toInt()].toString(),
                            moduleHash.copyOf(),
                            declaration.defaultArguments,
                        )
                    }
            library.exports.filter { it.kind == SymbolKind.TYPE }.forEach { export ->
                val exportName = library.strings[export.name.value.toInt()].toString()
                types += PlatformTypeLink(exportName, exportName, moduleHash.copyOf())
            }
            library.exports.filter { it.kind == SymbolKind.FIELD }.forEach { export ->
                val exportName = library.strings[export.name.value.toInt()].toString()
                val field = library.fields[export.localSymbol.toInt()]
                val owner = field.owner as? TypeRef.Local ?: error("platform field $exportName has an imported owner")
                val ownerSymbol =
                    library.strings[
                        library.types[owner.id.value.toInt()]
                            .name.value
                            .toInt(),
                    ].toString()
                fields +=
                    PlatformFieldLink(
                        exportName,
                        ownerSymbol,
                        exportName,
                        field.static,
                        moduleHash.copyOf(),
                    )
            }
        }
        val linkOrder =
            compareBy<PlatformFunctionLink>({ it.moduleHash.toHex() }, PlatformFunctionLink::exportName, PlatformFunctionLink::symbol)
        val typeOrder = compareBy<PlatformTypeLink>({ it.moduleHash.toHex() }, PlatformTypeLink::exportName, PlatformTypeLink::symbol)
        val fieldOrder = compareBy<PlatformFieldLink>({ it.moduleHash.toHex() }, PlatformFieldLink::exportName, PlatformFieldLink::symbol)
        return LoadedLibraries(
            functions.sortedWith(linkOrder),
            types.distinctBy { "${it.moduleHash.toHex()}:${it.exportName}" }.sortedWith(typeOrder),
            fields.distinctBy { "${it.moduleHash.toHex()}:${it.exportName}" }.sortedWith(fieldOrder),
            artifacts.map(Pair<PlatformModule, Artifact>::second),
        )
    }

    private fun linkLibraries(
        application: Artifact,
        libraryArtifacts: List<Artifact>,
    ): Artifact {
        val seen = application.modules.mapTo(mutableSetOf()) { ArtifactWriter.moduleSemanticHash(it).toHex() }
        val libraries = linkedMapOf<String, Module>()
        libraryArtifacts.flatMap(Artifact::modules).filter { it.kind == ModuleKind.LIBRARY }.forEach { module ->
            val hash = ArtifactWriter.moduleSemanticHash(module).toHex()
            if (seen.add(hash)) libraries[hash] = module
        }
        return LibraryModuleLinker.link(application, libraries)
    }

    private fun sourceFootprint(request: CompileRequest): TemporaryUsage {
        val directories = mutableSetOf("source")
        request.sources.forEach { source ->
            var parent = Path.of(source.path.value).parent
            while (parent != null) {
                directories += "source/$parent"
                parent = parent.parent
            }
        }
        return TemporaryUsage(request.sources.size + directories.size, request.sources.sumOf { it.content.size.toLong() })
    }

    private fun failure(
        diagnostics: List<WorkerDiagnostic>,
        reachedIr: Boolean,
        limits: WorkerLimits,
    ) = K2CompilationResult(ExitCode.COMPILATION_ERROR, diagnostics.bounded(limits), reachedIr, null, true)

    private fun targetDiagnostic(
        path: VirtualSourcePath,
        message: String,
    ) = WorkerDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.TARGET, null, message, path, 0u, 0u)

    internal companion object {
        val DEPENDENCY_ANNOTATION = Regex("(?m)^\\s*@file:(DependsOn|Repository)\\b")
        private const val PLATFORM_RESOURCE = "/compukters-platform/compukters-platform.cpb"

        fun loadPackagedPlatform(): PlatformBundle =
            checkNotNull(K2CompilerAdapter::class.java.getResourceAsStream(PLATFORM_RESOURCE)) {
                "packaged Compukters platform is missing"
            }.use { PlatformBundleCodec.decode(it.readBytes()) }
    }
}

private data class LoadedLibraries(
    val functions: List<PlatformFunctionLink>,
    val types: List<PlatformTypeLink>,
    val fields: List<PlatformFieldLink>,
    val artifacts: List<Artifact>,
)

private fun Module.functionSignature(reference: TypeRef): String {
    val type = types[(reference as TypeRef.Local).id.value.toInt()] as NominalType.Function
    return "fun(${type.parameters.joinToString(",", transform = ::canonicalType)}):${canonicalType(type.result)}"
}

private fun Module.canonicalType(type: ValueType): String =
    when (type) {
        ValueType.Unit -> {
            "kotlin.Unit"
        }

        ValueType.I32 -> {
            "kotlin.Int"
        }

        ValueType.I64 -> {
            "kotlin.Long"
        }

        ValueType.F32 -> {
            "kotlin.Float"
        }

        ValueType.F64 -> {
            "kotlin.Double"
        }

        ValueType.Bool -> {
            "kotlin.Boolean"
        }

        ValueType.Char -> {
            "kotlin.Char"
        }

        is ValueType.Ref -> {
            val name =
                when (val reference = type.type) {
                    is TypeRef.Local -> {
                        val nominal = types[reference.id.value.toInt()]
                        val base = strings[nominal.name.value.toInt()].toString()
                        if (nominal is NominalType.Array) "$base<${canonicalType(nominal.element)}>" else base
                    }

                    is TypeRef.Imported -> {
                        strings[imports[reference.id.value.toInt()].targetName.value.toInt()].toString()
                    }
                }
            name + if (type.nullable) "?" else ""
        }
    }

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun String.shortTypeNames(): String =
    Regex("[A-Za-z_][A-Za-z0-9_.]*").replace(this) { match -> match.value.substringAfterLast('.') }

private fun String.withLeadingSourceParameter(type: String): String {
    require(startsWith("(")) { "not a source function shape: $this" }
    val insertion = if (this[1] == ')') type else "$type,"
    return replaceRange(1, 1, insertion)
}
