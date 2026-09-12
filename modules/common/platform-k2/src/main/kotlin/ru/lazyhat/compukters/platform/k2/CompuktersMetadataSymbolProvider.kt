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

package ru.lazyhat.compukters.platform.k2

import org.jetbrains.kotlin.fir.FirModuleData
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.deserialization.FirTypeDeserializer
import org.jetbrains.kotlin.fir.deserialization.ModuleDataProvider
import org.jetbrains.kotlin.fir.scopes.FirKotlinScopeProvider
import org.jetbrains.kotlin.fir.session.MetadataLibraryBasedSymbolProvider
import org.jetbrains.kotlin.library.components.KlibMetadataComponent
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.k2.build.DecodedPlatformMetadata
import ru.lazyhat.compukters.platform.k2.build.PlatformMetadataCodec

/** Deserializes Compukters-owned common FIR metadata without a KLIB or foreign classpath. */
internal class CompuktersMetadataSymbolProvider(
    session: FirSession,
    moduleDataProvider: ModuleDataProvider,
    kotlinScopeProvider: FirKotlinScopeProvider,
    modules: List<PlatformModule>,
    private val moduleData: FirModuleData,
) : MetadataLibraryBasedSymbolProvider<CompuktersMetadataLibrary>(
        session,
        moduleDataProvider,
        kotlinScopeProvider,
        FirTypeDeserializer.FlexibleTypeFactory.Default,
        FirDeclarationOrigin.Library,
        CompuktersMetadataLibrary::component,
    ) {
    private val libraries =
        modules.map { module ->
            val decoded = PlatformMetadataCodec.decode(module.metadata)
            require(decoded.module == module.id) { "platform metadata identity does not match ${module.id}" }
            require(decoded.moduleHeader.isNotEmpty()) { "platform module ${module.id} has no resolved FIR metadata" }
            CompuktersMetadataLibrary(decoded)
        }

    override val fragmentNamesInLibraries: Map<String, List<CompuktersMetadataLibrary>> =
        buildMap<String, MutableList<CompuktersMetadataLibrary>> {
            libraries.forEach { library ->
                library.metadata.fragments.map { it.packageName }.distinct().forEach { packageName ->
                    getOrPut(packageName) { mutableListOf() } += library
                }
            }
        }

    override val knownPackagesInLibraries: Set<FqName> =
        buildSet {
            libraries.flatMap { it.metadata.fragments }.forEach { fragment ->
                var current = FqName(fragment.packageName)
                add(current)
                while (!current.isRoot) {
                    current = current.parent()
                    add(current)
                }
            }
        }

    override fun moduleData(library: CompuktersMetadataLibrary): FirModuleData = moduleData

    override fun createDeserializedContainerSource(
        resolvedLibrary: CompuktersMetadataLibrary,
        packageFqName: FqName,
    ): DeserializedContainerSource? = null
}

internal class CompuktersMetadataLibrary(
    val metadata: DecodedPlatformMetadata,
) {
    val component: KlibMetadataComponent =
        object : KlibMetadataComponent {
            override val moduleHeaderData: ByteArray
                get() = metadata.moduleHeader.copyOf()

            override fun getPackageFragmentNames(packageFqName: String): Set<String> =
                metadata.fragments.filter { it.packageName == packageFqName }.mapTo(linkedSetOf()) { it.partName }

            override fun getPackageFragment(
                packageFqName: String,
                partName: String,
            ): ByteArray =
                metadata.fragments
                    .single { it.packageName == packageFqName && it.partName == partName }
                    .bytes
                    .copyOf()
        }
}
