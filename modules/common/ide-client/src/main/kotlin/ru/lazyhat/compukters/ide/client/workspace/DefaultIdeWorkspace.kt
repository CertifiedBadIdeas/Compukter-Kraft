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

package ru.lazyhat.compukters.ide.client.workspace

import ru.lazyhat.compukters.compiler.project.ProjectSnapshotLoader
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.ProjectLimits
import ru.lazyhat.compukters.ide.project.document.ProjectDocumentException
import ru.lazyhat.compukters.ide.project.document.ProjectDocumentFailure
import ru.lazyhat.compukters.ide.project.document.ProjectDocumentStore
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectFileKind
import ru.lazyhat.compukters.ide.project.tree.ProjectImport
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeStore
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories

class DefaultIdeWorkspace internal constructor(
    private val catalog: ProjectCatalog,
    private val projectLimits: ProjectLimits,
    private val workerLimits: WorkerLimits,
    clientLimits: IdeClientLimits,
    private val operationObserver: (String) -> Unit,
) : IdeWorkspace {
    private val closed = AtomicBoolean()
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(clientLimits.workspaceQueue),
            namedDaemonThreadFactory("compukters-ide-workspace"),
            ThreadPoolExecutor.AbortPolicy(),
        )

    constructor(
        projectsRoot: Path,
        projectLimits: ProjectLimits = ProjectLimits(),
        workerLimits: WorkerLimits = WorkerLimits(),
        clientLimits: IdeClientLimits = IdeClientLimits(),
    ) : this(ProjectCatalog.open(projectsRoot.createDirectories(), projectLimits), projectLimits, workerLimits, clientLimits, {})

    override fun projects() = submit("projects", catalog::projects)

    override fun createProject(name: String) = submit("createProject") { catalog.create(name) }

    override fun tree(project: ProjectHandle) = submit("tree") { ProjectTreeStore(project, projectLimits).scan() }

    override fun open(
        project: ProjectHandle,
        path: ProjectPath,
    ) = submit("open") {
        val entry = ProjectTreeStore(project, projectLimits).scan().entry(path)
        when (val kind = entry.kind) {
            ProjectFileKind.Directory -> throw ProjectDocumentException("project path names a directory: $path")
            is ProjectFileKind.Text -> ProjectFileOpenResult.Text(ProjectDocumentStore(project, projectLimits).open(path))
            is ProjectFileKind.Binary -> ProjectFileOpenResult.Binary(path, kind.bytes)
        }
    }

    override fun save(request: IdeSaveRequest) =
        submit("save") {
            ProjectDocumentStore(request.project, projectLimits).save(
                request.path,
                request.expected,
                request.text,
            )
        }

    override fun admitDelete(
        project: ProjectHandle,
        path: ProjectPath,
    ) = submit("admitDelete") { ProjectTreeStore(project, projectLimits).admitDelete(path) }

    override fun mutate(request: IdeMutationRequest) =
        submit("mutate") {
            val store = ProjectTreeStore(request.project, projectLimits)
            when (request) {
                is IdeMutationRequest.CreateText -> store.createText(request.path)
                is IdeMutationRequest.CreateDirectory -> store.createDirectory(request.path)
                is IdeMutationRequest.Rename -> store.rename(request.source, request.target)
                is IdeMutationRequest.Delete -> store.delete(request.admitted)
            }
        }

    override fun importTree(
        project: ProjectHandle,
        import: ProjectImport,
    ) = submit("importTree") { ProjectTreeStore(project, projectLimits).importTree(import) }

    override fun buildInput(project: ProjectHandle) =
        submit("buildInput") {
            check(project.isValid()) { "project was invalidated" }
            val documents = ProjectDocumentStore(project, projectLimits)
            val manifest = documents.open(ProjectPath.file("compukter.toml")).text.encodeToByteArray()
            val lock =
                try {
                    documents.open(ProjectPath.file("compukter.lock")).text.encodeToByteArray()
                } catch (exception: ProjectDocumentException) {
                    if (exception.reason == ProjectDocumentFailure.MISSING) null else throw exception
                }
            val sources = ProjectSnapshotLoader.loadSourceSet(project.canonicalPath, workerLimits)
            check(project.isValid()) { "project was invalidated" }
            IdeBuildInput(project, manifest, lock, sources)
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdown()
    }

    private fun <T> submit(
        operation: String,
        action: () -> T,
    ): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (closed.get()) return future.failed(IdeWorkspaceFailure.Closed())
        try {
            executor.execute {
                try {
                    operationObserver(operation)
                    future.complete(action())
                } catch (exception: Throwable) {
                    future.completeExceptionally(exception)
                }
            }
        } catch (_: RejectedExecutionException) {
            future.completeExceptionally(if (closed.get()) IdeWorkspaceFailure.Closed() else IdeWorkspaceFailure.Busy())
        }
        return future
    }

    private fun <T> CompletableFuture<T>.failed(exception: Throwable): CompletableFuture<T> = also { completeExceptionally(exception) }
}

private fun namedDaemonThreadFactory(prefix: String): ThreadFactory {
    val sequence = AtomicLong()
    return ThreadFactory { task ->
        Thread(task, "$prefix-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }
}
