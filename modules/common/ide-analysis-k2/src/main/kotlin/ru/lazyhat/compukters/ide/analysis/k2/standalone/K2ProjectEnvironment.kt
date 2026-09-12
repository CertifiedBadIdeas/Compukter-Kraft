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

package ru.lazyhat.compukters.ide.analysis.k2.standalone

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.mock.MockComponentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.impl.DocumentWriteAccessGuard
import com.intellij.openapi.util.Disposer
import com.intellij.pom.PomModel
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiTreeChangeListener
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.cli.common.CliModuleVisibilityManagerImpl
import org.jetbrains.kotlin.load.kotlin.ModuleVisibilityManager
import org.jetbrains.kotlin.psi.KtFile
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.k2.CompuktersLLFirSessionConfigurator
import ru.lazyhat.compukters.platform.k2.CompuktersPlatforms
import java.nio.file.Path

internal class K2ProjectEnvironment private constructor(
    private val disposable: Disposable,
    val session: StandaloneAnalysisAPISession,
    private val sourceIndex: MutableProjectSourceIndex,
) : AutoCloseable {
    val sourceIndexRebuildCount: Int
        get() = sourceIndex.rebuildCount

    fun reindex(file: KtFile) {
        sourceIndex.replace(file)
    }

    override fun close() {
        session.application.runWriteAction { Disposer.dispose(disposable) }
    }

    companion object {
        fun create(
            sourceRoot: Path,
            platformBundle: PlatformBundle,
            selectedModules: Set<PlatformModuleId>,
        ): K2ProjectEnvironment {
            val disposable = Disposer.newDisposable("Compukters analysis snapshot")
            return try {
                val platform = CompuktersPlatforms.default
                val session =
                    buildStandaloneAnalysisAPISession(disposable, unitTestMode = false) {
                        val area = application.extensionArea
                        synchronized(area) {
                            if (!area.hasExtensionPoint(DocumentWriteAccessGuard.EP_NAME)) {
                                CoreApplicationEnvironment.registerExtensionPoint(
                                    area,
                                    DocumentWriteAccessGuard.EP_NAME,
                                    DocumentWriteAccessGuard::class.java,
                                )
                            }
                        }
                        StandaloneProgressManager.register(this)
                        CoreApplicationEnvironment.registerExtensionPoint(
                            project.extensionArea,
                            PsiTreeChangeListener.EP.name,
                            PsiTreeChangeListener::class.java,
                        )
                        registerProjectService(
                            ModuleVisibilityManager::class.java,
                            CliModuleVisibilityManagerImpl(enabled = true),
                        )
                        val mockProject = project as MockComponentManager
                        mockProject.picoContainer.unregisterComponent(PsiDocumentManager::class.java.name)
                        registerProjectService(PsiDocumentManager::class.java, StandalonePsiDocumentManager(project))
                        mockProject.picoContainer.unregisterComponent(PomModel::class.java.name)
                        registerProjectService(PomModel::class.java, StandalonePomModel())
                        CompuktersLLFirSessionConfigurator.register(project, platformBundle, selectedModules)
                        buildKtModuleProvider {
                            this.platform = platform
                            addModule(
                                buildKtSourceModule {
                                    this.platform = platform
                                    moduleName = "compukters-snapshot"
                                    addSourceRoot(sourceRoot)
                                    if (selectedModules.isNotEmpty()) {
                                        addRegularDependency(
                                            buildKtLibraryModule {
                                                this.platform = platform
                                                libraryName = CompuktersLLFirSessionConfigurator.PLATFORM_LIBRARY_NAME
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }
                val sourceFiles =
                    session.modulesWithFiles.values
                        .flatten()
                        .filterIsInstance<KtFile>()
                val sourceIndex = MutableProjectSourceIndex(sourceFiles)
                installMutableProjectSourceProviders(session, sourceIndex)
                K2ProjectEnvironment(disposable, session, sourceIndex)
            } catch (exception: Exception) {
                Disposer.dispose(disposable)
                throw exception
            }
        }
    }
}
