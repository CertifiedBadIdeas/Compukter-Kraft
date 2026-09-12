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

package ru.lazyhat.compukters.ide.analysis.k2.query

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.k2.standalone.IncrementalK2Workspace
import ru.lazyhat.compukters.ide.analysis.k2.standalone.K2SourceUpdater
import ru.lazyhat.compukters.ide.analysis.k2.standalone.SnapshotAdmission
import ru.lazyhat.compukters.ide.analysis.k2.testAdmittedPlatform
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisPlatform
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import ru.lazyhat.compukters.ide.analysis.protocol.UpdateSnapshotRequest
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

internal class K2QueryFixture private constructor(
    initialSources: Map<VirtualSourcePath, String>,
    val workspace: IncrementalK2Workspace,
    private val limits: AnalysisLimits,
    private val root: Path,
) : AutoCloseable {
    private var requestId = 2uL
    var sources: Map<VirtualSourcePath, String> = initialSources.toMap()
        private set

    val identity: AnalysisSnapshotIdentity
        get() = workspace.identity

    val snapshot: AdmittedK2Snapshot
        get() = workspace.view()

    fun execute(
        query: AnalysisQuery,
        limits: AnalysisLimits = AnalysisLimits(),
    ): AnalysisResult = K2QueryDispatcher.execute(query, snapshot, limits)

    fun presentation(path: String = sources.keys.single().value): AnalysisQuery.Presentation =
        AnalysisQuery.Presentation(identity, VirtualSourcePath.kotlin(path))

    fun update(vararg changedSources: Pair<String, String>) {
        val request = updateRequest(*changedSources)
        workspace.update(request, limits)
        sources = sources + changedSources.associate { (path, text) -> VirtualSourcePath.kotlin(path) to text }
    }

    fun updateRequest(vararg changedSources: Pair<String, String>): UpdateSnapshotRequest {
        val changed = changedSources.associate { (path, text) -> VirtualSourcePath.kotlin(path) to text }
        val targetSources = sources + changed
        val project = projectSnapshot(targetSources, limits)
        val targetIdentity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(project), identity.profile)
        return UpdateSnapshotRequest(
            RequestId.of(requestId++),
            identity,
            targetIdentity,
            changed.entries
                .sortedWith { left, right -> compareUtf8(left.key.value, right.key.value) }
                .map { (path, text) -> ProjectSource(path, BinaryValue.of(text.encodeToByteArray())) },
        )
    }

    override fun close() {
        workspace.close()
        root.toFile().deleteRecursively()
    }

    companion object {
        fun source(vararg sources: Pair<String, String>): K2QueryFixture = create(testAdmittedPlatform(), AnalysisLimits(), null, *sources)

        fun sourceWithUpdater(
            sourceUpdater: K2SourceUpdater,
            vararg sources: Pair<String, String>,
        ): K2QueryFixture = create(testAdmittedPlatform(), AnalysisLimits(), sourceUpdater, *sources)

        fun sourceWithUpdater(
            sourceUpdater: K2SourceUpdater,
            limits: AnalysisLimits,
            vararg sources: Pair<String, String>,
        ): K2QueryFixture = create(testAdmittedPlatform(), limits, sourceUpdater, *sources)

        fun sourceWithGuestApi(
            attachedSources: Boolean,
            vararg sources: Pair<String, String>,
        ): K2QueryFixture =
            create(
                testAdmittedPlatform(selectAllModules = true, attachedSources = attachedSources),
                AnalysisLimits(),
                sourceUpdater = null,
                sources = sources,
            )

        private fun create(
            platform: AdmittedAnalysisPlatform,
            limits: AnalysisLimits,
            sourceUpdater: K2SourceUpdater?,
            vararg sources: Pair<String, String>,
        ): K2QueryFixture {
            val root = createTempDirectory("compukters-k2-query-")
            val mapped = sources.associate { (path, text) -> VirtualSourcePath.kotlin(path) to text }
            val project = projectSnapshot(mapped, limits)
            val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 1 }))
            val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(project), profile)
            val request =
                OpenSnapshotRequest(
                    RequestId.of(1uL),
                    identity,
                    project,
                    AdmittedAnalysisProfile(profile, platform),
                    limits,
                )
            val platform =
                PlatformBundleCodec.decode(
                    Files.readAllBytes(Path.of(requireNotNull(System.getProperty("compukters.test.platformBundle")))),
                )
            val admission =
                if (sourceUpdater == null) {
                    SnapshotAdmission(root.resolve("snapshots"), platform)
                } else {
                    SnapshotAdmission(
                        root.resolve("snapshots"),
                        platform,
                        sourceUpdater,
                    )
                }
            return K2QueryFixture(mapped, admission.admit(request), limits, root)
        }
    }
}

private fun projectSnapshot(
    sources: Map<VirtualSourcePath, String>,
    limits: AnalysisLimits,
): ProjectSnapshot =
    ProjectSnapshot.of(
        sources.entries
            .sortedWith { left, right -> compareUtf8(left.key.value, right.key.value) }
            .map { (path, text) -> ProjectSource(path, BinaryValue.of(text.encodeToByteArray())) },
        WorkerLimits(
            sourceFiles = limits.sourceFiles,
            sourceFileBytes = limits.sourceFileBytes,
            sourceBytes = limits.sourceBytes,
        ),
    )

private fun compareUtf8(
    left: String,
    right: String,
): Int {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    val common = minOf(leftBytes.size, rightBytes.size)
    repeat(common) { index ->
        val comparison = (leftBytes[index].toInt() and 0xff).compareTo(rightBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return leftBytes.size.compareTo(rightBytes.size)
}
