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

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias

internal class MutableProjectSourceIndex(
    files: Collection<KtFile>,
) {
    private val entries = LinkedHashMap<VirtualFile, ProjectSourceFileIndex>()

    @Volatile
    private var currentSnapshot = ProjectSourceIndexSnapshot.EMPTY

    var rebuildCount: Int = 0
        private set

    init {
        files.forEach { file -> entries[file.virtualFile] = index(file) }
        currentSnapshot = aggregate(entries.values)
    }

    fun snapshot(): ProjectSourceIndexSnapshot = currentSnapshot

    @Synchronized
    fun replace(file: KtFile) {
        entries[file.virtualFile] = index(file)
        currentSnapshot = aggregate(entries.values)
        rebuildCount++
    }

    private fun index(file: KtFile): ProjectSourceFileIndex {
        val classesById = linkedMapOf<ClassId, MutableList<KtClassOrObject>>()
        val typeAliasesById = linkedMapOf<ClassId, MutableList<KtTypeAlias>>()
        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                    classOrObject.getClassId()?.let { classesById.getOrPut(it, ::mutableListOf).add(classOrObject) }
                    super.visitClassOrObject(classOrObject)
                }

                override fun visitTypeAlias(typeAlias: KtTypeAlias) {
                    typeAlias.getClassId()?.let { typeAliasesById.getOrPut(it, ::mutableListOf).add(typeAlias) }
                    super.visitTypeAlias(typeAlias)
                }
            },
        )

        val functionsById = linkedMapOf<CallableId, MutableList<KtNamedFunction>>()
        val propertiesById = linkedMapOf<CallableId, MutableList<KtProperty>>()
        file.declarations.forEach { declaration ->
            val name = (declaration as? KtNamedDeclaration)?.name?.let(Name::identifier) ?: return@forEach
            val callableId = CallableId(file.packageFqName, name)
            when (declaration) {
                is KtNamedFunction -> functionsById.getOrPut(callableId, ::mutableListOf).add(declaration)
                is KtProperty -> propertiesById.getOrPut(callableId, ::mutableListOf).add(declaration)
            }
        }

        val classifierNames =
            file.declarations
                .asSequence()
                .filter { it is KtClassOrObject || it is KtTypeAlias }
                .mapNotNull { (it as KtNamedDeclaration).name?.let(Name::identifier) }
                .toSet()
        val callableNames =
            file.declarations
                .asSequence()
                .filter { it is KtNamedFunction || it is KtProperty }
                .mapNotNull { (it as KtNamedDeclaration).name?.let(Name::identifier) }
                .toSet()

        return ProjectSourceFileIndex(
            file = file,
            packageFqName = file.packageFqName,
            classesById = classesById.freezeLists(),
            typeAliasesById = typeAliasesById.freezeLists(),
            functionsById = functionsById.freezeLists(),
            propertiesById = propertiesById.freezeLists(),
            classifierNames = classifierNames,
            callableNames = callableNames,
            facadeFqName = file.javaFileFacadeFqName,
            script = file.script,
        )
    }

    private fun aggregate(entries: Collection<ProjectSourceFileIndex>): ProjectSourceIndexSnapshot {
        val classesById = linkedMapOf<ClassId, MutableList<KtClassOrObject>>()
        val typeAliasesById = linkedMapOf<ClassId, MutableList<KtTypeAlias>>()
        val functionsById = linkedMapOf<CallableId, MutableList<KtNamedFunction>>()
        val propertiesById = linkedMapOf<CallableId, MutableList<KtProperty>>()
        val classifierNamesByPackage = linkedMapOf<FqName, MutableSet<Name>>()
        val callableNamesByPackage = linkedMapOf<FqName, MutableSet<Name>>()
        val filesByPackage = linkedMapOf<FqName, MutableList<KtFile>>()
        val filesByFacade = linkedMapOf<FqName, MutableList<KtFile>>()
        val scriptsByFqName = linkedMapOf<FqName, MutableList<KtScript>>()

        entries.forEach { entry ->
            entry.classesById.appendTo(classesById)
            entry.typeAliasesById.appendTo(typeAliasesById)
            entry.functionsById.appendTo(functionsById)
            entry.propertiesById.appendTo(propertiesById)
            classifierNamesByPackage.getOrPut(entry.packageFqName, ::linkedSetOf).addAll(entry.classifierNames)
            callableNamesByPackage.getOrPut(entry.packageFqName, ::linkedSetOf).addAll(entry.callableNames)
            filesByPackage.getOrPut(entry.packageFqName, ::mutableListOf).add(entry.file)
            filesByFacade.getOrPut(entry.facadeFqName, ::mutableListOf).add(entry.file)
            entry.script?.let { script ->
                scriptsByFqName.getOrPut(script.fqName, ::mutableListOf).add(script)
            }
        }

        return ProjectSourceIndexSnapshot(
            classesById = classesById.freezeLists(),
            typeAliasesById = typeAliasesById.freezeLists(),
            functionsById = functionsById.freezeLists(),
            propertiesById = propertiesById.freezeLists(),
            classifierNamesByPackage = classifierNamesByPackage.freezeSets(),
            callableNamesByPackage = callableNamesByPackage.freezeSets(),
            filesByPackage = filesByPackage.freezeLists(),
            filesByFacade = filesByFacade.freezeLists(),
            scriptsByFqName = scriptsByFqName.freezeLists(),
            packages = filesByPackage.keys.toSet(),
        )
    }
}

internal data class ProjectSourceIndexSnapshot(
    val classesById: Map<ClassId, List<KtClassOrObject>>,
    val typeAliasesById: Map<ClassId, List<KtTypeAlias>>,
    val functionsById: Map<CallableId, List<KtNamedFunction>>,
    val propertiesById: Map<CallableId, List<KtProperty>>,
    val classifierNamesByPackage: Map<FqName, Set<Name>>,
    val callableNamesByPackage: Map<FqName, Set<Name>>,
    val filesByPackage: Map<FqName, List<KtFile>>,
    val filesByFacade: Map<FqName, List<KtFile>>,
    val scriptsByFqName: Map<FqName, List<KtScript>>,
    val packages: Set<FqName>,
) {
    companion object {
        val EMPTY =
            ProjectSourceIndexSnapshot(
                emptyMap(),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                emptySet(),
            )
    }
}

private data class ProjectSourceFileIndex(
    val file: KtFile,
    val packageFqName: FqName,
    val classesById: Map<ClassId, List<KtClassOrObject>>,
    val typeAliasesById: Map<ClassId, List<KtTypeAlias>>,
    val functionsById: Map<CallableId, List<KtNamedFunction>>,
    val propertiesById: Map<CallableId, List<KtProperty>>,
    val classifierNames: Set<Name>,
    val callableNames: Set<Name>,
    val facadeFqName: FqName,
    val script: KtScript?,
)

private fun <K, V> Map<K, List<V>>.appendTo(target: MutableMap<K, MutableList<V>>) {
    forEach { (key, values) -> target.getOrPut(key, ::mutableListOf).addAll(values) }
}

private fun <K, V> Map<K, MutableList<V>>.freezeLists(): Map<K, List<V>> =
    entries.associateTo(linkedMapOf()) { (key, values) -> key to values.toList() }

private fun <K, V> Map<K, MutableSet<V>>.freezeSets(): Map<K, Set<V>> =
    entries.associateTo(linkedMapOf()) { (key, values) -> key to values.toSet() }
