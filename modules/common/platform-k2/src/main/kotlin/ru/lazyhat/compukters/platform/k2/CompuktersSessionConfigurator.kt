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

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSession
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionConfigurator
import org.jetbrains.kotlin.fir.SessionConfiguration
import org.jetbrains.kotlin.fir.deserialization.SingleModuleDataProvider
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.scopes.kotlinScopeProvider
import org.jetbrains.kotlin.platform.TargetPlatform
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleGraph
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import java.nio.file.Path
import java.util.Collections

class CompuktersSession internal constructor(
    val targetPlatform: TargetPlatform,
    val metadata: CompuktersMetadataProvider,
) {
    val binaryRoots: List<Path> = emptyList()

    fun resolve(symbol: String): PlatformDeclaration? = metadata.resolve(symbol)
}

object CompuktersSessionConfigurator {
    fun create(
        bundle: PlatformBundle,
        selectedModules: Set<PlatformModuleId>,
    ): CompuktersSession =
        CompuktersSession(
            CompuktersPlatforms.default,
            CompuktersMetadataProvider(bundle, Collections.unmodifiableSet(selectedModules.toSet())),
        )
}

object CompuktersLLFirSessionConfigurator : LLFirSessionConfigurator {
    @OptIn(SessionConfiguration::class)
    override fun configure(session: LLFirSession) {
        require(supports(session.ktModule.targetPlatform)) { "Compukters LL FIR configurator received a foreign target" }
        val library = session.ktModule as? KaLibraryModule ?: return
        if (library.libraryName != PLATFORM_LIBRARY_NAME) return
        val context = requireNotNull(session.project.getUserData(CONTEXT_KEY)) { "Compukters analysis platform is not admitted" }
        val moduleData = session.moduleData
        val provider =
            CompuktersMetadataSymbolProvider(
                session,
                SingleModuleDataProvider(moduleData),
                session.kotlinScopeProvider,
                context.modules,
                moduleData,
            )
        session.register(FirSymbolProvider::class, provider)
    }

    fun supports(platform: TargetPlatform): Boolean = platform == CompuktersPlatforms.default

    fun register(
        project: Project,
        bundle: PlatformBundle,
        selectedModules: Set<PlatformModuleId>,
    ) {
        val modules = listOf(bundle.builtins) + PlatformModuleGraph(bundle).resolve(selectedModules).modules
        project.putUserData(CONTEXT_KEY, CompuktersAnalysisPlatformContext(modules))
        val area = project.extensionArea
        if (!area.hasExtensionPoint(CONFIGURATOR_EXTENSION_POINT)) {
            CoreApplicationEnvironment.registerExtensionPoint(
                area,
                CONFIGURATOR_EXTENSION_POINT,
                LLFirSessionConfigurator::class.java,
            )
        }
        LLFirSessionConfigurator.registerExtension(project, this)
    }

    private val CONTEXT_KEY = Key.create<CompuktersAnalysisPlatformContext>("compukters.analysis.platform")
    private const val CONFIGURATOR_EXTENSION_POINT = "org.jetbrains.kotlin.llFirSessionConfigurator"
    const val PLATFORM_LIBRARY_NAME = "compukters-platform"
}

class CompuktersAnalysisPlatformContext(
    val modules: List<PlatformModule>,
) {
    val declarations: List<PlatformDeclaration> = modules.flatMap(PlatformModule::declarations)
}
