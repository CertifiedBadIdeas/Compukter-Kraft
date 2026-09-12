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

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.FrontendConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.VfsBasedProjectEnvironment
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.diagnostics.KtRegisteredDiagnosticFactoriesStorage
import org.jetbrains.kotlin.diagnostics.impl.DiagnosticsCollectorImpl
import org.jetbrains.kotlin.fir.FirBinaryDependenciesModuleData
import org.jetbrains.kotlin.fir.FirModuleData
import org.jetbrains.kotlin.fir.FirSourceModuleData
import org.jetbrains.kotlin.fir.deserialization.SingleModuleDataProvider
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.pipeline.SingleModuleFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.buildResolveAndCheckFirFromKtFiles
import org.jetbrains.kotlin.fir.session.AbstractFirMetadataSessionFactory
import org.jetbrains.kotlin.fir.session.IncrementalCompilationContext
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectFileSearchScope
import org.jetbrains.kotlin.load.kotlin.PackageAndMetadataPartProvider
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.serialization.deserialization.ClassData
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.platform.k2.CompuktersFirSessionFactory
import ru.lazyhat.compukters.platform.k2.CompuktersMetadataSymbolProvider
import ru.lazyhat.compukters.platform.k2.CompuktersPlatforms

data class CompuktersFirModuleOutput(
    val moduleData: FirModuleData,
    val frontendOutput: SingleModuleFrontendOutput,
    val diagnostics: DiagnosticsCollectorImpl,
)

/** Owns one compiler project and creates native Compukters FIR sessions in graph order. */
@OptIn(CompilerConfiguration.Internals::class, K1Deprecation::class, ExperimentalCompilerApi::class)
class CompuktersFirBuildEnvironment private constructor(
    private val disposable: Disposable,
    private val environment: KotlinCoreEnvironment,
) : AutoCloseable {
    private val projectEnvironment =
        VfsBasedProjectEnvironment(environment.project, environment.projectEnvironment.jarFileSystem) { EMPTY_METADATA_PROVIDER }
    private val factory = CompuktersFirSessionFactory()
    private val context =
        AbstractFirMetadataSessionFactory.Context(
            createJvmContext = { error("Compukters FIR requested a JVM context") },
            createJsContext = { error("Compukters FIR requested a JS context") },
        )
    private val sharedLibrarySession =
        factory.createSharedLibrarySession(
            Name.special("<compukters-platform>"),
            LanguageVersionSettingsImpl.DEFAULT,
            emptyList(),
            context,
        )
    private val baseModuleData = FirBinaryDependenciesModuleData(Name.special("<compukters-base>"))
    private val baseLibrarySession =
        factory.createLibrarySession(
            sharedLibrarySession,
            SingleModuleDataProvider(baseModuleData),
            emptyList(),
            null,
            emptyList(),
            LanguageVersionSettingsImpl.DEFAULT,
            context,
        ) { _, _, _, _ -> emptyList() }

    fun compile(
        module: PlatformModuleId,
        sources: List<PlatformSource>,
        dependencies: List<CompuktersFirModuleOutput>,
    ): CompuktersFirModuleOutput {
        val configuration = configuration(module)
        val moduleData =
            FirSourceModuleData(
                Name.special("<$module>"),
                listOf(baseModuleData) + dependencies.map(CompuktersFirModuleOutput::moduleData),
                emptyList(),
                emptyList(),
                CompuktersPlatforms.default,
                false,
            )
        val session =
            factory.createSourceSession(
                moduleData,
                projectEnvironment,
                IncrementalCompilationContext(emptyList(), EMPTY_METADATA_PROVIDER, AbstractProjectFileSearchScope.EMPTY),
                emptyList<FirExtensionRegistrar>(),
                configuration,
                context,
                false,
            ) {}
        val files = sourceFiles(sources)
        val diagnostics = DiagnosticsCollectorImpl()
        val output = buildResolveAndCheckFirFromKtFiles(session, files, diagnostics)
        return CompuktersFirModuleOutput(moduleData, output, diagnostics)
    }

    fun compileGuest(
        module: PlatformModuleId,
        sources: List<PlatformSource>,
        platformModules: List<PlatformModule>,
    ): CompuktersFirModuleOutput {
        require(platformModules.isNotEmpty()) { "Guest FIR requires Compukters built-ins metadata" }
        val libraryModuleData = FirBinaryDependenciesModuleData(Name.special("<compukters-platform-binaries>"))
        factory.createLibrarySession(
            sharedLibrarySession,
            SingleModuleDataProvider(libraryModuleData),
            emptyList(),
            null,
            emptyList(),
            LanguageVersionSettingsImpl.DEFAULT,
            context,
        ) { session, moduleDataProvider, scopeProvider, _ ->
            listOf(
                CompuktersMetadataSymbolProvider(
                    session,
                    moduleDataProvider,
                    scopeProvider,
                    platformModules,
                    libraryModuleData,
                ),
            )
        }
        val moduleData =
            FirSourceModuleData(
                Name.special("<$module>"),
                listOf(libraryModuleData),
                emptyList(),
                emptyList(),
                CompuktersPlatforms.default,
                false,
            )
        val session =
            factory.createSourceSession(
                moduleData,
                projectEnvironment,
                IncrementalCompilationContext(emptyList(), EMPTY_METADATA_PROVIDER, AbstractProjectFileSearchScope.EMPTY),
                emptyList<FirExtensionRegistrar>(),
                configuration(module),
                context,
                false,
            ) {}
        val diagnostics = DiagnosticsCollectorImpl()
        val output = buildResolveAndCheckFirFromKtFiles(session, sourceFiles(sources), diagnostics)
        return CompuktersFirModuleOutput(moduleData, output, diagnostics)
    }

    override fun close() {
        Disposer.dispose(disposable)
    }

    private fun configuration(module: PlatformModuleId): CompilerConfiguration =
        CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, module.toString())
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
            put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS, LanguageVersionSettingsImpl.DEFAULT)
            put(CommonConfigurationKeys.TARGET_PLATFORM, CompuktersPlatforms.default)
            put(FrontendConfigurationKeys.DIAGNOSTIC_FACTORIES_STORAGE, KtRegisteredDiagnosticFactoriesStorage())
            put(FrontendConfigurationKeys.EXTENSIONS_STORAGE, CompilerPluginRegistrar.ExtensionStorage())
        }

    private fun sourceFiles(sources: List<PlatformSource>): List<KtFile> {
        val psiFactory = KtPsiFactory(environment.project, markGenerated = false)
        return sources.sortedBy(PlatformSource::path).map { source ->
            psiFactory.createPhysicalFile(
                source.path.substringAfterLast('/'),
                source.content.toByteArray().decodeToString(throwOnInvalidSequence = true),
            )
        }
    }

    companion object {
        private val EMPTY_METADATA_PROVIDER =
            object : PackageAndMetadataPartProvider {
                override fun findPackageParts(packageFqName: String): List<String> = emptyList()

                override fun findMetadataPackageParts(packageFqName: String): List<String> = emptyList()

                override fun computePackageSetWithNonClassDeclarations(): Set<String> = emptySet()

                override fun getAnnotationsOnBinaryModule(moduleName: String): List<ClassId> = emptyList()

                override fun getAllOptionalAnnotationClasses(): List<ClassData> = emptyList()

                override fun mayHaveOptionalAnnotationClasses(): Boolean = false
            }

        fun create(): CompuktersFirBuildEnvironment {
            val disposable = Disposer.newDisposable("Compukters FIR platform builder")
            val configuration =
                CompilerConfiguration().apply {
                    put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
                    put(CommonConfigurationKeys.TARGET_PLATFORM, CompuktersPlatforms.default)
                }
            val environment =
                KotlinCoreEnvironment.createForProduction(
                    disposable,
                    configuration,
                    EnvironmentConfigFiles.METADATA_CONFIG_FILES,
                )
            return CompuktersFirBuildEnvironment(disposable, environment)
        }
    }
}
