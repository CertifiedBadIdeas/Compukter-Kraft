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

package ru.lazyhat.compukters.ide.project.document

import ru.lazyhat.compukters.ide.editor.EditorChangeOrigin
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorLimits
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.fs.ProjectPath

class ProjectDocumentSession private constructor(
    private val handle: ProjectHandle,
    private val store: ProjectDocumentStore,
    private val autosave: AutosaveController,
    initial: DocumentSnapshot,
    editorLimits: EditorLimits,
) {
    private var snapshot = initial
    private var dirty = false
    private var conflict: ProjectSessionEvent.Conflict? = null
    val editor = EditorDocument(initial.text, editorLimits)
    private val editorSubscription =
        editor.addChangeListener { change ->
            dirty = !editor.contentEquals(snapshot.text)
            if (change.origin != EditorChangeOrigin.ExternalReset) {
                if (dirty) autosave.edited() else autosave.saveSucceeded()
            }
        }
    var isOpen: Boolean = true
        private set

    fun poll(): ProjectSessionEvent {
        if (!isOpen) return ProjectSessionEvent.NoAction
        if (!handle.isValid()) return invalidate()
        val disk =
            try {
                store.open(snapshot.path)
            } catch (exception: ProjectDocumentException) {
                return if (!handle.isValid() || exception.reason == ProjectDocumentFailure.MISSING) {
                    invalidate()
                } else {
                    failure(exception)
                }
            }
        if (disk.revision != snapshot.revision) {
            if (dirty) {
                return enterConflict(snapshot.revision, disk.revision)
            }
            snapshot = disk
            editor.reset(disk.text)
            autosave.saveSucceeded()
            return ProjectSessionEvent.Reloaded(disk)
        }
        return saveIfRequested(autosave.poll())
    }

    fun mouseActivity(): ProjectSessionEvent = if (isOpen) saveIfRequested(autosave.mouseActivity()) else ProjectSessionEvent.NoAction

    fun focusLost(): ProjectSessionEvent = if (isOpen) saveIfRequested(autosave.focusLost()) else ProjectSessionEvent.NoAction

    fun activeFileChanging(): ProjectSessionEvent =
        if (isOpen) saveIfRequested(autosave.activeFileChanging()) else ProjectSessionEvent.NoAction

    fun fileRenamed(
        previous: ProjectPath,
        current: ProjectPath,
    ) {
        if (!isOpen || snapshot.path != previous) return
        snapshot = snapshot.copy(path = current)
    }

    fun fileDeleted(path: ProjectPath): ProjectSessionEvent =
        if (isOpen && snapshot.path == path) invalidate() else ProjectSessionEvent.NoAction

    fun prepareBuild(): ProjectSessionEvent {
        if (!isOpen) return ProjectSessionEvent.Closed(null)
        conflict?.let { return it }
        val saved = saveIfRequested(autosave.buildRequested())
        return when (saved) {
            ProjectSessionEvent.NoAction -> ProjectSessionEvent.ReadyToBuild(snapshot)
            is ProjectSessionEvent.Saved -> ProjectSessionEvent.ReadyToBuild(saved.snapshot)
            else -> saved
        }
    }

    fun reloadFromDisk(): ProjectSessionEvent {
        if (!isOpen) return ProjectSessionEvent.Closed(null)
        return try {
            val reloaded = store.open(snapshot.path)
            snapshot = reloaded
            editor.reset(reloaded.text)
            dirty = false
            conflict = null
            autosave.saveSucceeded()
            ProjectSessionEvent.Reloaded(reloaded)
        } catch (exception: ProjectDocumentException) {
            if (!handle.isValid()) invalidate() else failure(exception)
        }
    }

    fun saveAs(path: ProjectPath): ProjectSessionEvent {
        if (!isOpen) return ProjectSessionEvent.Closed(null)
        return when (val result = store.save(path, FileRevision.Absent, editor.materialize())) {
            is DocumentSaveResult.Saved -> {
                snapshot = result.snapshot
                dirty = false
                conflict = null
                editor.breakUndoGroup()
                autosave.saveSucceeded()
                ProjectSessionEvent.Saved(result.snapshot)
            }

            is DocumentSaveResult.Conflict -> {
                enterConflict(result.expected, result.actual)
            }

            DocumentSaveResult.ProjectInvalidated -> {
                invalidate()
            }
        }
    }

    fun close(decision: CloseDecision): ProjectSessionEvent {
        if (!isOpen) return ProjectSessionEvent.Closed(null)
        if (decision == CloseDecision.Cancel) return ProjectSessionEvent.CloseCancelled
        if (decision == CloseDecision.Discard || !dirty) {
            finishClose()
            return ProjectSessionEvent.Closed(null)
        }
        conflict?.let { return it }
        return when (val saved = saveIfRequested(autosave.closeRequested())) {
            is ProjectSessionEvent.Saved -> {
                finishClose()
                ProjectSessionEvent.Closed(null)
            }

            else -> {
                saved
            }
        }
    }

    private fun saveIfRequested(action: AutosaveAction): ProjectSessionEvent {
        if (action == AutosaveAction.NoAction) return ProjectSessionEvent.NoAction
        return try {
            when (val result = store.save(snapshot.path, snapshot.revision, editor.materialize())) {
                is DocumentSaveResult.Saved -> {
                    snapshot = result.snapshot
                    dirty = false
                    conflict = null
                    editor.breakUndoGroup()
                    autosave.saveSucceeded()
                    ProjectSessionEvent.Saved(result.snapshot)
                }

                is DocumentSaveResult.Conflict -> {
                    enterConflict(result.expected, result.actual)
                }

                DocumentSaveResult.ProjectInvalidated -> {
                    invalidate()
                }
            }
        } catch (exception: ProjectDocumentException) {
            autosave.saveFailed()
            failure(exception)
        }
    }

    private fun enterConflict(
        expected: FileRevision,
        actual: FileRevision,
    ): ProjectSessionEvent.Conflict {
        autosave.conflicted()
        return ProjectSessionEvent.Conflict(expected, actual).also { conflict = it }
    }

    private fun invalidate(): ProjectSessionEvent.Closed {
        val recovery = if (dirty) UnsavedRecovery(snapshot.path, editor.materialize()) else null
        finishClose()
        return ProjectSessionEvent.Closed(recovery)
    }

    private fun finishClose() {
        isOpen = false
        editorSubscription.close()
        editor.close()
    }

    private fun failure(exception: Exception): ProjectSessionEvent.Failure =
        ProjectSessionEvent.Failure(exception.message ?: "project document operation failed")

    companion object {
        fun open(
            handle: ProjectHandle,
            path: ProjectPath,
            clockNanos: () -> Long,
            limits: ProjectLimits = ProjectLimits(),
        ): ProjectDocumentSession {
            val store = ProjectDocumentStore(handle, limits)
            val editorLimits =
                EditorLimits(
                    maxCodeUnits = limits.projectFileBytes,
                    maxUtf8Bytes = limits.projectFileBytes,
                    initialGapCodeUnits = minOf(4 * 1024, limits.projectFileBytes),
                    maxUndoEntries = 256,
                    maxUndoCodeUnits = limits.projectFileBytes,
                    tabWidth = 4,
                )
            return ProjectDocumentSession(handle, store, AutosaveController(clockNanos), store.open(path), editorLimits)
        }
    }
}
