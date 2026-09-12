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

package ru.lazyhat.compukters.compiler.k2.engine.build

import ru.lazyhat.compukters.compiler.k2.engine.CompuktersFir2IrPipeline
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.CanonicalTrustedIntrinsics
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.PlatformCapabilityId
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.TrustedIntrinsicContract
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.TrustedIntrinsicRegistry
import ru.lazyhat.compukters.compiler.k2.engine.library.PlatformLibraryCompiler
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirBuildEnvironment
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirModuleOutput
import ru.lazyhat.compukters.platform.k2.build.PlatformMetadataCodec
import ru.lazyhat.compukters.platform.k2.build.PlatformMetadataCompiler
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText

class PlatformBundleBuilder(
    private val intrinsicRegistry: TrustedIntrinsicRegistry = CanonicalTrustedIntrinsics.registry,
    private val executableCapabilities: Set<PlatformCapabilityId> = CanonicalTrustedIntrinsics.executableCapabilities,
) {
    fun build(
        sourceRoot: Path,
        descriptor: Path,
    ): PlatformBundle {
        val catalog = PlatformSourceCatalog.parse(descriptor.readText())
        val builtinsId = PlatformModuleId("kotlin", "builtins")
        require(catalog.modules.any { it.id == builtinsId }) { "platform catalog must contain mandatory $builtinsId" }
        val allSources = discoverSources(sourceRoot)
        val ownedPaths = mutableSetOf<String>()
        val sourceByModule =
            catalog.modules.associate { module ->
                val matching = allSources.filter { path -> module.sourceGlobs.any { glob -> glob.matches(path) } }
                require(matching.isNotEmpty()) { "platform module ${module.id} has no sources" }
                matching.forEach { path -> require(ownedPaths.add(path)) { "platform source $path has multiple owners" } }
                module.id to
                    matching.map { path ->
                        PlatformSource(path, ImmutableBytes.of(Files.readAllBytes(sourceRoot.resolve(path))))
                    }
            }
        require(ownedPaths == allSources.toSet()) { "unowned platform sources: ${allSources.toSet() - ownedPaths}" }
        val sourceModules =
            sourceByModule.entries.flatMap { (module, sources) -> sources.map { source -> source.path to module } }.toMap()

        val ordered = catalog.topologicalOrder(builtinsId)
        val metadataCompiler = PlatformMetadataCompiler()
        val libraryCompiler = PlatformLibraryCompiler()
        val modules = linkedMapOf<PlatformModuleId, PlatformModule>()
        val firOutputs = linkedMapOf<PlatformModuleId, CompuktersFirModuleOutput>()
        val metadataByModule =
            ordered
                .associate { descriptorModule ->
                    val sources = sourceByModule.getValue(descriptorModule.id)
                    val metadata = metadataCompiler.compile(descriptorModule.id, sources)
                    PlatformMetadataCodec.validateAgainstSources(PlatformMetadataCodec.decode(metadata.metadata), sources)
                    descriptorModule.id to metadata
                }.toMutableMap()
        CompuktersFirBuildEnvironment.create().use { fir ->
            ordered.forEach { descriptorModule ->
                val sources = sourceByModule.getValue(descriptorModule.id)
                val dependencies = catalog.transitiveDependencies(descriptorModule.id).map(firOutputs::getValue)
                val firOutput = fir.compile(descriptorModule.id, sources, dependencies)
                val rejectedDiagnostics =
                    firOutput.diagnostics.diagnostics.filterNot { diagnostic ->
                        descriptorModule.id == builtinsId &&
                            diagnostic.factoryName == "WRONG_MODIFIER_TARGET" &&
                            diagnostic.renderMessage() == "Modifier 'external' is not applicable to 'constructor'."
                    }
                require(rejectedDiagnostics.isEmpty()) {
                    "Compukters FIR rejected ${descriptorModule.id}: ${rejectedDiagnostics.joinToString {
                        "${it.factoryName}@${it.firstRange}: ${it.renderMessage()}"
                    }}"
                }
                firOutputs[descriptorModule.id] = firOutput
            }
            ordered.forEach { descriptorModule ->
                metadataByModule[descriptorModule.id] =
                    metadataCompiler.attachResolvedMetadata(
                        metadataByModule.getValue(descriptorModule.id),
                        firOutputs.getValue(descriptorModule.id),
                    )
            }
            val ir =
                metadataByModule.values
                    .any { it.libraryDeclarations.isNotEmpty() }
                    .let { hasLibraries ->
                        if (hasLibraries) CompuktersFir2IrPipeline.convert(firOutputs.values.toList()) else null
                    }
            ordered.forEach { descriptorModule ->
                val sources = sourceByModule.getValue(descriptorModule.id)
                val metadata = metadataByModule.getValue(descriptorModule.id)
                val libraryFragment =
                    if (metadata.libraryDeclarations.isEmpty()) {
                        null
                    } else {
                        val converted = requireNotNull(ir)
                        libraryCompiler.compile(
                            descriptorModule.id,
                            metadata.libraryDeclarations,
                            converted.irModuleFragment,
                            converted.pluginContext,
                            sources.mapTo(mutableSetOf(), PlatformSource::path),
                            sourceModules,
                            intrinsicRegistry,
                        )
                    }
                modules[descriptorModule.id] =
                    PlatformModule(
                        id = descriptorModule.id,
                        version = descriptorModule.version,
                        dependencies = descriptorModule.dependencies,
                        metadata = metadata.metadata,
                        libraryFragment = libraryFragment,
                        sources = sources,
                        declarations = metadata.declarations,
                        completionDeclarations = metadata.completionDeclarations,
                        scalarTypes = metadata.scalarTypes,
                        scalarConstants = metadata.scalarConstants,
                    )
            }
        }

        TrustedIntrinsicContract.validate(modules.values.flatMap(PlatformModule::declarations), intrinsicRegistry, executableCapabilities)
        return PlatformBundleCodec.assemble(
            languageVersion = "2.4",
            platformAbi = PlatformBundleCodec.SUPPORTED_PLATFORM_ABI,
            builtins = modules.getValue(builtinsId),
            modules = modules.filterKeys { it != builtinsId }.values.toList(),
        )
    }

    private fun discoverSources(root: Path): List<String> =
        Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .map { root.relativize(it).invariantSeparatorsPathString }
                .sorted()
                .toList()
        }
}

fun main(args: Array<String>) {
    val arguments = args.toList().windowed(2, 2, partialWindows = false).associate { (name, value) -> name to value }
    val sourceRoot = Path.of(requireNotNull(arguments["--sources"]) { "missing --sources" }).toAbsolutePath().normalize()
    val descriptor = Path.of(requireNotNull(arguments["--descriptor"]) { "missing --descriptor" }).toAbsolutePath().normalize()
    val output = Path.of(requireNotNull(arguments["--output"]) { "missing --output" }).toAbsolutePath().normalize()
    require(descriptor.startsWith(sourceRoot)) { "platform descriptor must be inside the source root" }
    val bytes = PlatformBundleCodec.encode(PlatformBundleBuilder().build(sourceRoot, descriptor))
    output.parent.createDirectories()
    val temporary = Files.createTempFile(output.parent, output.fileName.toString(), ".tmp")
    try {
        Files.write(temporary, bytes)
        Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private data class PlatformSourceModule(
    val id: PlatformModuleId,
    val version: String,
    val dependencies: List<PlatformModuleId>,
    val sourceGlobs: List<SourceGlob>,
)

private data class PlatformSourceCatalog(
    val modules: List<PlatformSourceModule>,
) {
    fun transitiveDependencies(module: PlatformModuleId): List<PlatformModuleId> {
        val byId = modules.associateBy(PlatformSourceModule::id)
        val resolved = linkedSetOf<PlatformModuleId>()

        fun visit(id: PlatformModuleId) {
            byId.getValue(id).dependencies.sorted().forEach { dependency ->
                visit(dependency)
                resolved += dependency
            }
        }
        visit(module)
        return resolved.toList()
    }

    fun topologicalOrder(builtins: PlatformModuleId): List<PlatformSourceModule> {
        val byId = modules.associateBy(PlatformSourceModule::id)
        require(byId.size == modules.size) { "duplicate platform module ids" }
        modules.forEach { module ->
            module.dependencies.forEach { dependency -> require(dependency in byId) { "${module.id} depends on unknown $dependency" } }
        }
        val visiting = mutableSetOf<PlatformModuleId>()
        val visited = mutableSetOf<PlatformModuleId>()
        val ordered = mutableListOf<PlatformSourceModule>()

        fun visit(id: PlatformModuleId) {
            require(visiting.add(id)) { "platform module dependency cycle reaches $id" }
            byId
                .getValue(id)
                .dependencies
                .sorted()
                .filterNot(visited::contains)
                .forEach(::visit)
            visiting.remove(id)
            if (visited.add(id)) ordered += byId.getValue(id)
        }
        visit(builtins)
        byId.keys
            .sorted()
            .filterNot(visited::contains)
            .forEach(::visit)
        return ordered
    }

    companion object {
        fun parse(text: String): PlatformSourceCatalog {
            val modules = mutableListOf<MutablePlatformSourceModule>()
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                if (line == "[[module]]") {
                    modules += MutablePlatformSourceModule()
                    return@forEachIndexed
                }
                val module = modules.lastOrNull() ?: error("property before [[module]] at line ${index + 1}")
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=', missingDelimiterValue = "").trim()
                require(module.keys.add(key)) { "duplicate descriptor key $key at line ${index + 1}" }
                when (key) {
                    "id" -> module.id = value.quoted().moduleId()
                    "version" -> module.version = value.quoted()
                    "dependencies" -> module.dependencies = value.stringArray().map(String::moduleId)
                    "sources" -> module.sources = value.stringArray().map(::SourceGlob)
                    else -> error("unsupported descriptor key $key at line ${index + 1}")
                }
            }
            return PlatformSourceCatalog(modules.map(MutablePlatformSourceModule::freeze))
        }
    }
}

private class MutablePlatformSourceModule {
    val keys = mutableSetOf<String>()
    var id: PlatformModuleId? = null
    var version: String? = null
    var dependencies: List<PlatformModuleId>? = null
    var sources: List<SourceGlob>? = null

    fun freeze(): PlatformSourceModule =
        PlatformSourceModule(
            requireNotNull(id) { "platform module id is missing" },
            requireNotNull(version) { "platform module version is missing" },
            requireNotNull(dependencies) { "platform module dependencies are missing" },
            requireNotNull(sources) { "platform module sources are missing" },
        )
}

private data class SourceGlob(
    val pattern: String,
) {
    private val regex = Regex(pattern.toRegexPattern())

    fun matches(path: String): Boolean = regex.matches(path)
}

private fun String.moduleId(): PlatformModuleId =
    PlatformModuleId(substringBefore(':', missingDelimiterValue = ""), substringAfter(':', missingDelimiterValue = ""))

private fun String.quoted(): String {
    require(length >= 2 && first() == '"' && last() == '"') { "expected quoted string: $this" }
    return substring(1, lastIndex)
}

private fun String.stringArray(): List<String> {
    require(startsWith('[') && endsWith(']')) { "expected string array: $this" }
    val contents = substring(1, lastIndex).trim()
    return if (contents.isEmpty()) emptyList() else contents.split(',').map { it.trim().quoted() }
}

private fun String.toRegexPattern(): String =
    buildString {
        append('^')
        var index = 0
        while (index < this@toRegexPattern.length) {
            when {
                this@toRegexPattern.startsWith("**/", index) -> {
                    append("(?:.*/)?")
                    index += 3
                }

                this@toRegexPattern.startsWith("**", index) -> {
                    append(".*")
                    index += 2
                }

                this@toRegexPattern[index] == '*' -> {
                    append("[^/]*")
                    index += 1
                }

                else -> {
                    append(Regex.escape(this@toRegexPattern[index].toString()))
                    index += 1
                }
            }
        }
        append('$')
    }
