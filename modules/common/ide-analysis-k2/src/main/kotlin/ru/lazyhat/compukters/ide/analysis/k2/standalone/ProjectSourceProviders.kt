@file:OptIn(org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class)

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

import com.intellij.mock.MockComponentManager
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinCompositeDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinCompositePackageProvider
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProvider
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderMerger
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.packages.KotlinStandalonePackageProviderFactory
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.isSubpackageOf
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtTypeAlias

internal fun installMutableProjectSourceProviders(
    session: StandaloneAnalysisAPISession,
    index: MutableProjectSourceIndex,
) {
    val stableDeclarations =
        KotlinStandaloneDeclarationProviderFactory(
            project = session.project,
            environment = session.coreApplicationEnvironment,
            sourceKtFiles = emptyList(),
            skipBuiltins = false,
        )
    val stablePackages =
        KotlinStandalonePackageProviderFactory(
            session.project,
            emptyList(),
            emptyList(),
        )
    val serviceProject = session.project
    val componentManager = serviceProject as MockComponentManager
    componentManager.replaceService(
        KotlinDeclarationProviderFactory::class.java,
        ProjectSourceDeclarationProviderFactory(index, stableDeclarations),
    )
    componentManager.replaceService(
        KotlinDeclarationProviderMerger::class.java,
        object : KotlinDeclarationProviderMerger {
            override fun merge(providers: List<KotlinDeclarationProvider>): KotlinDeclarationProvider =
                KotlinCompositeDeclarationProvider.create(providers)
        },
    )
    componentManager.replaceService(
        KotlinPackageProviderFactory::class.java,
        ProjectSourcePackageProviderFactory(index, stablePackages),
    )
    componentManager.replaceService(
        KotlinPackageProviderMerger::class.java,
        object : KotlinPackageProviderMerger {
            override fun merge(providers: List<KotlinPackageProvider>): KotlinPackageProvider =
                KotlinCompositePackageProvider.create(providers)
        },
    )
}

private class ProjectSourceDeclarationProviderFactory(
    private val index: MutableProjectSourceIndex,
    private val stable: KotlinDeclarationProviderFactory,
) : KotlinDeclarationProviderFactory {
    override fun createDeclarationProvider(
        scope: GlobalSearchScope,
        contextualModule: KaModule?,
    ): KotlinDeclarationProvider =
        KotlinCompositeDeclarationProvider.create(
            listOf(
                ProjectSourceDeclarationProvider(index.snapshot(), scope),
                stable.createDeclarationProvider(scope, contextualModule),
            ),
        )
}

private class ProjectSourceDeclarationProvider(
    private val snapshot: ProjectSourceIndexSnapshot,
    private val searchScope: GlobalSearchScope,
) : KotlinDeclarationProvider {
    override fun getClassLikeDeclarationByClassId(classId: ClassId): KtClassLikeDeclaration? =
        getAllClassesByClassId(classId).firstOrNull() ?: getAllTypeAliasesByClassId(classId).firstOrNull()

    override fun getAllClassesByClassId(classId: ClassId): Collection<KtClassOrObject> = snapshot.classesById[classId].orEmpty().inScope()

    override fun getAllTypeAliasesByClassId(classId: ClassId): Collection<KtTypeAlias> =
        snapshot.typeAliasesById[classId].orEmpty().inScope()

    override fun getTopLevelKotlinClassLikeDeclarationNamesInPackage(packageFqName: FqName): Set<Name> =
        snapshot.classifierNamesByPackage[packageFqName].orEmpty()

    override fun getTopLevelProperties(callableId: CallableId): Collection<KtProperty> =
        snapshot.propertiesById[callableId].orEmpty().inScope()

    override fun getTopLevelFunctions(callableId: CallableId): Collection<KtNamedFunction> =
        snapshot.functionsById[callableId].orEmpty().inScope()

    override fun getTopLevelCallableFiles(callableId: CallableId): Collection<KtFile> =
        (
            getTopLevelFunctions(callableId).map { it.containingKtFile } +
                getTopLevelProperties(callableId).map { it.containingKtFile }
        ).distinct()

    override fun getTopLevelCallableNamesInPackage(packageFqName: FqName): Set<Name> =
        snapshot.callableNamesByPackage[packageFqName].orEmpty()

    override fun findFilesForFacadeByPackage(packageFqName: FqName): Collection<KtFile> =
        snapshot.filesByPackage[packageFqName].orEmpty().inScopeFiles()

    override fun findFilesForFacade(facadeFqName: FqName): Collection<KtFile> =
        snapshot.filesByFacade[facadeFqName].orEmpty().inScopeFiles()

    override fun findInternalFilesForFacade(facadeFqName: FqName): Collection<KtFile> = findFilesForFacade(facadeFqName)

    override fun findFilesForScript(scriptFqName: FqName): Collection<KtScript> = snapshot.scriptsByFqName[scriptFqName].orEmpty().inScope()

    override fun computePackageNames(): Set<String> = snapshot.packages.mapTo(linkedSetOf(), FqName::asString)

    override val hasSpecificClassifierPackageNamesComputation: Boolean = true

    override fun computePackageNamesWithTopLevelClassifiers(): Set<String> =
        snapshot.classifierNamesByPackage.keys.mapTo(linkedSetOf(), FqName::asString)

    override val hasSpecificCallablePackageNamesComputation: Boolean = true

    override fun computePackageNamesWithTopLevelCallables(): Set<String> =
        snapshot.callableNamesByPackage.keys.mapTo(linkedSetOf(), FqName::asString)

    private fun <T : org.jetbrains.kotlin.psi.KtElement> Collection<T>.inScope(): List<T> =
        filter { declaration -> declaration.containingKtFile.virtualFile?.let(searchScope::contains) == true }

    private fun Collection<KtFile>.inScopeFiles(): List<KtFile> = filter { file -> file.virtualFile?.let(searchScope::contains) == true }
}

private class ProjectSourcePackageProviderFactory(
    private val index: MutableProjectSourceIndex,
    private val stable: KotlinPackageProviderFactory,
) : KotlinPackageProviderFactory {
    override fun createPackageProvider(searchScope: GlobalSearchScope): KotlinPackageProvider =
        KotlinCompositePackageProvider.create(
            listOf(
                ProjectSourcePackageProvider(index.snapshot(), searchScope),
                stable.createPackageProvider(searchScope),
            ),
        )
}

private class ProjectSourcePackageProvider(
    snapshot: ProjectSourceIndexSnapshot,
    private val searchScope: GlobalSearchScope,
) : KotlinPackageProvider {
    private val filesByPackage = snapshot.filesByPackage

    override fun doesPackageExist(
        packageFqName: FqName,
        platform: TargetPlatform,
    ): Boolean = doesKotlinOnlyPackageExist(packageFqName)

    override fun doesKotlinOnlyPackageExist(packageFqName: FqName): Boolean =
        visiblePackages().any { candidate -> candidate == packageFqName || candidate.isSubpackageOf(packageFqName) }

    override fun doesPlatformSpecificPackageExist(
        packageFqName: FqName,
        platform: TargetPlatform,
    ): Boolean = false

    override fun getSubpackageNames(
        packageFqName: FqName,
        platform: TargetPlatform,
    ): Set<Name> = getKotlinOnlySubpackageNames(packageFqName)

    override fun getKotlinOnlySubpackageNames(packageFqName: FqName): Set<Name> =
        visiblePackages()
            .asSequence()
            .filter { candidate -> candidate.isSubpackageOf(packageFqName) && candidate != packageFqName }
            .map { candidate -> candidate.pathSegments()[packageFqName.pathSegments().size] }
            .toSet()

    override fun getPlatformSpecificSubpackageNames(
        packageFqName: FqName,
        platform: TargetPlatform,
    ): Set<Name> = emptySet()

    private fun visiblePackages(): Set<FqName> =
        filesByPackage
            .asSequence()
            .filter { (_, files) -> files.any { file -> file.virtualFile?.let(searchScope::contains) == true } }
            .mapTo(linkedSetOf()) { (packageFqName) -> packageFqName }
}

private fun <T : Any> MockComponentManager.replaceService(
    serviceClass: Class<T>,
    service: T,
) {
    picoContainer.unregisterComponent(serviceClass.name)
    registerService(serviceClass, service)
}
