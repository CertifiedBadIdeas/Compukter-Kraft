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

import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.pom.PomModel
import com.intellij.pom.PomModelAspect
import com.intellij.pom.PomTransaction
import com.intellij.pom.tree.TreeAspect
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.BlockSupportImpl
import com.intellij.psi.impl.PsiDocumentManagerBase
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.source.tree.FileElement
import org.jetbrains.kotlin.psi.KtPsiFactory

/** Supplies the commit scope missing from IntelliJ's read-only core VFS. */
internal class StandalonePsiDocumentManager(
    project: Project,
) : PsiDocumentManagerBase(project) {
    private val standaloneCommitDepth = ThreadLocal.withInitial { 0 }

    override fun isCommitInProgress(): Boolean = standaloneCommitDepth.get() > 0 || super.isCommitInProgress()

    fun runStandaloneCommit(action: () -> Unit) {
        val previousDepth = standaloneCommitDepth.get()
        standaloneCommitDepth.set(previousDepth + 1)
        try {
            action()
        } finally {
            if (previousDepth == 0) {
                standaloneCommitDepth.remove()
            } else {
                standaloneCommitDepth.set(previousDepth)
            }
        }
    }
}

/** Reconciles a changed standalone document with its existing physical PSI identity. */
internal object StandaloneDocumentSynchronizer {
    fun capture(psiFile: PsiFile): StandaloneDocumentBaseline {
        val file = psiFile as? PsiFileImpl ?: error("standalone Kotlin PSI file has an unsupported implementation")
        val oldTree = file.calcTreeElement()
        return StandaloneDocumentBaseline(oldTree, oldTree.chars)
    }

    fun synchronize(
        document: Document,
        project: Project,
        psiFile: PsiFile,
        baseline: StandaloneDocumentBaseline,
    ) {
        val file = psiFile as? PsiFileImpl ?: error("standalone Kotlin PSI file has an unsupported implementation")
        val replacement = KtPsiFactory(project).createFile(file.name, document.immutableCharSequence.toString())
        val changes =
            BlockSupportImpl.mergeTrees(
                file,
                baseline.oldTree,
                replacement.node,
                EmptyProgressIndicator(),
                baseline.oldText,
            )
        file.beforeAstChange()
        changes.performActualPsiChange(file)
        file.viewProvider.contentsSynchronized()
    }
}

internal data class StandaloneDocumentBaseline(
    val oldTree: FileElement,
    val oldText: CharSequence,
)

/** Minimal POM model required by IntelliJ's public tree-diff application path. */
internal class StandalonePomModel :
    UserDataHolderBase(),
    PomModel {
    private val treeAspect = TreeAspect()

    override fun <T : PomModelAspect> getModelAspect(aspect: Class<T>): T =
        requireNotNull(treeAspect.takeIf(aspect::isInstance)?.let(aspect::cast)) {
            "unsupported standalone POM aspect: ${aspect.name}"
        }

    override fun runTransaction(transaction: PomTransaction) {
        transaction.run()
    }
}
