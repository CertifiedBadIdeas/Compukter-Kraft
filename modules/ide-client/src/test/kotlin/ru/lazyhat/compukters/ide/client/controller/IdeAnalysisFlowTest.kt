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

package ru.lazyhat.compukters.ide.client.controller

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.CompletionSymbol
import ru.lazyhat.compukters.ide.analysis.CompletionTextEdit
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisRequestCoordinator
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisResultSink
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisCoordinator
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisInputLoader
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisRequestFactory
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisSnapshotFactory
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.build.IdeBuildCoordinator
import ru.lazyhat.compukters.ide.client.build.IdeBuildServices
import ru.lazyhat.compukters.ide.client.state.IdeBusyOperation
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.compiler.ClientBuildResult
import ru.lazyhat.compukters.ide.compiler.ClientBuildSnapshot
import ru.lazyhat.compukters.ide.compiler.ClientCompilationService
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfileResolver
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ProjectLockService
import ru.lazyhat.compukters.ide.project.ProjectManifestCodec
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeAnalysisFlowTest {
    @Test
    fun `explicit format changes Kotlin atomically and leaves saving separate`() {
        val requests = FlowAnalysisRequests()
        val fixture =
            ControllerFixture(preferences("demo", "src/main.kt"), analysisCoordinatorFactory = { workspace ->
                coordinator(workspace, requests)
            })
        fixture.startAndTick()
        val source = "fun main(){println(1)}"
        val formatted = "fun main() {\n    println(1)\n}\n"
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type(source)))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SetCaret(source.indexOf("println"), false)))

        fixture.controller.dispatch(IdeCommand.Format)

        assertTrue(fixture.workspace.saveRequests.isEmpty())
        assertTrue(IdeBusyOperation.Format in fixture.controller.viewState().busy)
        assertEquals(source, requests.formatRequests.single().source)
        assertEquals(source.indexOf("println"), requests.formatRequests.single().caretOffsetUtf16)
        fixture.clock.now = 1_000
        requests.completeFormat(formatted, formatted.indexOf("println"))
        fixture.controller.tick()

        assertEquals(listOf("fun main() {", "    println(1)", "}", ""), fixture.textEditor().visibleLines)
        assertEquals(formatted.indexOf("println"), fixture.textEditor().caretUtf16)
        assertTrue(fixture.textEditor().dirty)
        assertTrue(fixture.workspace.saveRequests.isEmpty())

        fixture.controller.dispatch(IdeCommand.Save)
        assertEquals(
            formatted,
            fixture.workspace.saveRequests
                .single()
                .text,
        )
        fixture.workspace.completeSave()
        fixture.controller.tick()
        assertTrue(!fixture.textEditor().dirty)
        fixture.controller.close()
    }

    @Test
    fun `stale format result never overwrites or saves newer typing`() {
        val requests = FlowAnalysisRequests()
        val fixture =
            ControllerFixture(preferences("demo", "src/main.kt"), analysisCoordinatorFactory = { workspace ->
                coordinator(workspace, requests)
            })
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("fun main(){}")))
        fixture.controller.dispatch(IdeCommand.Format)
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type(" // newer")))

        requests.completeFormat("fun main() {\n}\n", 13)
        fixture.controller.tick()

        assertEquals("fun main(){} // newer", fixture.textEditor().visibleLines.single())
        assertTrue(fixture.workspace.saveRequests.isEmpty())
        fixture.controller.close()
    }

    @Test
    fun `format failure warns without saving the unformatted Kotlin source`() {
        val requests = FlowAnalysisRequests()
        val fixture =
            ControllerFixture(preferences("demo", "src/main.kt"), analysisCoordinatorFactory = { workspace ->
                coordinator(workspace, requests)
            })
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("fun main(){}")))
        fixture.controller.dispatch(IdeCommand.Format)

        requests.failFormat("syntactically incomplete")
        fixture.controller.tick()

        assertTrue(fixture.workspace.saveRequests.isEmpty())
        assertTrue(
            fixture
                .workspaceView()
                .status
                ?.message
                .orEmpty()
                .contains("Kotlin formatting failed"),
        )
        fixture.controller.close()
    }

    @Test
    fun `format command leaves non-Kotlin text untouched and unsaved`() {
        val requests = FlowAnalysisRequests()
        val fixture =
            ControllerFixture(preferences("demo", "notes.txt"), analysisCoordinatorFactory = { workspace ->
                coordinator(workspace, requests)
            })
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type(" unformatted")))

        fixture.controller.dispatch(IdeCommand.Format)

        assertTrue(requests.formatRequests.isEmpty())
        assertTrue(fixture.workspace.saveRequests.isEmpty())
        assertEquals(" unformattednotes", fixture.textEditor().visibleLines.single())
        fixture.controller.close()
    }

    @Test
    fun `explicit save bypasses the asynchronous formatter`() {
        val requests = FlowAnalysisRequests()
        val fixture =
            ControllerFixture(preferences("demo", "src/main.kt"), analysisCoordinatorFactory = { workspace ->
                coordinator(workspace, requests)
            })
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("fun main(){}")))

        fixture.controller.dispatch(IdeCommand.Save)

        assertTrue(requests.formatRequests.isEmpty())
        assertEquals(
            "fun main(){}",
            fixture.workspace.saveRequests
                .single()
                .text,
        )
        fixture.controller.close()
    }

    @Test
    fun `close waits for formatting and then saves its result`() {
        val requests = FlowAnalysisRequests()
        val fixture =
            ControllerFixture(preferences("demo", "src/main.kt"), analysisCoordinatorFactory = { workspace ->
                coordinator(workspace, requests)
            })
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("fun main(){}")))
        fixture.controller.dispatch(IdeCommand.Format)

        fixture.controller.dispatch(IdeCommand.CloseRequested)

        assertTrue(!fixture.controller.isCloseReady())
        assertTrue(fixture.workspace.saveRequests.isEmpty())
        requests.completeFormat("fun main() {\n}\n", 14)
        fixture.controller.tick()
        assertEquals(
            "fun main() {\n}\n",
            fixture.workspace.saveRequests
                .single()
                .text,
        )
        assertTrue(!fixture.controller.isCloseReady())

        fixture.workspace.completeSave()
        fixture.controller.tick()
        assertTrue(fixture.controller.isCloseReady())
        fixture.controller.close()
    }

    @Test
    fun `completion Tab is atomic while Tab without popup indents and manual has priority`() {
        val requests = FlowAnalysisRequests()
        val fixture =
            ControllerFixture(preferences("demo", "src/main.kt"), analysisCoordinatorFactory = { workspace ->
                coordinator(workspace, requests)
            })
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("pr")))

        val active = assertIs<IdeAnalysisState.Active>(fixture.analysisCoordinator?.state())
        assertEquals(listOf(2), requests.automaticOffsets)
        requests.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Completion.create(
                    active.identity,
                    EditorRange(0, 2),
                    listOf(CompletionItem("println", "println", CompletionKind.Function)),
                    2,
                ),
            ),
        )
        fixture.controller.tick()
        assertTrue(assertIs<IdeAnalysisState.Active>(fixture.textEditor().analysis).completion != null)

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Tab))
        assertEquals("println", fixture.textEditor().visibleLines.single())
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Undo))
        assertEquals("pr", fixture.textEditor().visibleLines.single())

        fixture.controller.dispatch(IdeCommand.ManualCompletion)
        assertEquals(listOf(2), requests.manualOffsets)
        fixture.controller.dispatch(IdeCommand.EditorFocusLost)
        assertNull((fixture.textEditor().analysis as? IdeAnalysisState.Active)?.completion)

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Tab))
        assertEquals("pr  ", fixture.textEditor().visibleLines.single())
        fixture.controller.close()
    }

    @Test
    fun `import completion applies one undoable compound editor change`() {
        val requests = FlowAnalysisRequests()
        val fixture =
            ControllerFixture(preferences("demo", "src/main.kt"), analysisCoordinatorFactory = { workspace ->
                coordinator(workspace, requests)
            })
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("fun main() { Re }")))
        val active = assertIs<IdeAnalysisState.Active>(fixture.analysisCoordinator?.state())
        val start = "fun main() { ".length
        requests.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Completion.create(
                    active.identity,
                    EditorRange(start, start + 2),
                    listOf(
                        CompletionItem(
                            "Redstone",
                            "Redstone",
                            CompletionKind.Object,
                            symbol = CompletionSymbol("compukter.redstone.Redstone", "compukter.redstone.Redstone"),
                            additionalEdits = listOf(CompletionTextEdit(EditorRange(0, 0), "import compukter.redstone.Redstone\n\n")),
                        ),
                    ),
                    "fun main() { Re }".length,
                ),
            ),
        )
        fixture.controller.tick()

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Tab))

        assertEquals("import compukter.redstone.Redstone\n\nfun main() { Redstone }", fixture.textEditor().visibleLines.joinToString("\n"))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Undo))
        assertEquals("fun main() { Re }", fixture.textEditor().visibleLines.single())
        fixture.controller.close()
    }

    @Test
    fun `undeclared module completion publishes dependencies before applying source`() {
        val requests = FlowAnalysisRequests()
        val fixture =
            ControllerFixture(
                preferences("demo", "src/main.kt"),
                analysisCoordinatorFactory = { workspace -> coordinator(workspace, requests) },
                buildCoordinatorFactory = { _, clock ->
                    IdeBuildCoordinator(
                        IdeBuildServices(
                            TEST_PROJECT_RESOLUTION,
                            CompileProfileResolver(TEST_TOOLCHAIN, TEST_PLATFORM_CATALOG, WorkerLimits()),
                            { project -> ProjectLockService(project.lockFileWriter()) },
                            NoopCompilationService,
                        ),
                        clock,
                    )
                },
            )
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("fun main() { Re }")))
        val active = assertIs<IdeAnalysisState.Active>(fixture.analysisCoordinator?.state())
        val module = TEST_PLATFORM_CATALOG.entries.single { it.identity.id == ModuleId.parse("compukter:redstone") }
        val start = "fun main() { ".length
        requests.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Completion.create(
                    active.identity,
                    EditorRange(start, start + 2),
                    listOf(
                        CompletionItem(
                            "Redstone",
                            "Redstone",
                            CompletionKind.Object,
                            origin =
                                DeclarationOrigin.Platform(
                                    AnalysisModuleIdentity(module.identity.id.value, module.identity.contentHash),
                                ),
                            symbol = CompletionSymbol("compukter.redstone.Redstone", "compukter.redstone.Redstone"),
                            additionalEdits = listOf(CompletionTextEdit(EditorRange(0, 0), "import compukter.redstone.Redstone\n\n")),
                        ),
                    ),
                    "fun main() { Re }".length,
                ),
            ),
        )
        fixture.controller.tick()

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Tab))
        assertTrue(IdeBusyOperation.Resolve in fixture.controller.viewState().busy)
        fixture.tickUntil { IdeBusyOperation.Resolve !in fixture.controller.viewState().busy }

        assertEquals("import compukter.redstone.Redstone\n\nfun main() { Redstone }", fixture.textEditor().visibleLines.joinToString("\n"))
        val manifest =
            ProjectManifestCodec.decode(
                fixture.workspace.descriptor.handle.canonicalPath
                    .resolve("compukter.toml")
                    .toFile()
                    .readText(),
            )
        assertEquals(module.identity.major, manifest.modules[module.identity.id])
        assertTrue(
            fixture.workspace.descriptor.handle.canonicalPath
                .resolve("compukter.lock")
                .toFile()
                .isFile,
        )
        fixture.controller.close()
    }

    private fun ControllerFixture.tickUntil(predicate: () -> Boolean) {
        repeat(500) {
            controller.tick()
            if (predicate()) return
            Thread.sleep(5)
        }
        error("condition was not reached")
    }

    private fun coordinator(
        workspace: ControlledWorkspace,
        requests: FlowAnalysisRequests,
    ) = IdeAnalysisCoordinator(
        IdeAnalysisInputLoader(workspace::buildInput),
        IdeAnalysisSnapshotFactory { input, path, text -> analysisSnapshot(input.sources, path, text) },
        IdeAnalysisRequestFactory { sink -> requests.apply { this.sink = sink } },
        platformCatalog = TEST_PLATFORM_CATALOG,
    )
}

private object NoopCompilationService : ClientCompilationService {
    override fun build(input: ClientBuildSnapshot): CompletableFuture<ClientBuildResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("not used"))

    override fun cancel(future: CompletableFuture<ClientBuildResult>): Boolean = false

    override fun close() = Unit
}

private class FlowAnalysisRequests : AnalysisRequestCoordinator {
    lateinit var sink: AnalysisResultSink
    val automaticOffsets = mutableListOf<Int>()
    val manualOffsets = mutableListOf<Int>()
    val formatRequests = mutableListOf<FlowFormatRequest>()
    private var snapshot: AdmittedAnalysisSnapshot? = null
    private var formatFuture: CompletableFuture<AnalysisClientResult>? = null

    fun publish(result: AnalysisClientResult) = sink.publish(result)

    override fun sourceChanged(
        snapshot: AdmittedAnalysisSnapshot,
        activePath: VirtualSourcePath,
    ) {
        this.snapshot = snapshot
    }

    override fun automaticCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ) {
        automaticOffsets += offsetUtf16
    }

    override fun manualCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> {
        manualOffsets += offsetUtf16
        return CompletableFuture()
    }

    override fun format(
        path: VirtualSourcePath,
        source: String,
        caretOffsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> {
        val identity = checkNotNull(snapshot).identity
        formatRequests += FlowFormatRequest(identity, path, source, caretOffsetUtf16)
        return CompletableFuture<AnalysisClientResult>().also { formatFuture = it }
    }

    fun completeFormat(
        source: String,
        caretOffsetUtf16: Int,
    ) {
        val request = formatRequests.last()
        checkNotNull(formatFuture).complete(
            AnalysisClientResult.Success(AnalysisResult.Format.create(request.identity, source, caretOffsetUtf16)),
        )
    }

    fun failFormat(detail: String) {
        checkNotNull(formatFuture).complete(AnalysisClientResult.Failure(AnalysisFailureKind.InternalAnalysis, detail))
    }

    override fun close() = Unit
}

private data class FlowFormatRequest(
    val identity: AnalysisSnapshotIdentity,
    val path: VirtualSourcePath,
    val source: String,
    val caretOffsetUtf16: Int,
)

private fun analysisSnapshot(
    original: ProjectSnapshot,
    path: VirtualSourcePath,
    text: String,
): AdmittedAnalysisSnapshot {
    val limits = WorkerLimits(sourceFiles = 8, sourceFileBytes = 4096, sourceBytes = 8192)
    val sources =
        ProjectSnapshot.of(
            original.sources.map { source ->
                if (source.path == path) ProjectSource(path, BinaryValue.of(text.encodeToByteArray())) else source
            },
            limits,
        )
    val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 3 }))
    val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profile)
    return AdmittedAnalysisSnapshot(
        identity,
        sources,
        AdmittedAnalysisProfile(
            profile,
            ru.lazyhat.compukters.ide.analysis.protocol
                .AdmittedAnalysisPlatform(Hash256.zero(), emptyList()),
        ),
        AnalysisLimits(),
    )
}
