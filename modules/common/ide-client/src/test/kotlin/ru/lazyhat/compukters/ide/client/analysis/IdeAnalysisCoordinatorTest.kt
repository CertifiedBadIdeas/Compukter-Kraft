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

package ru.lazyhat.compukters.ide.client.analysis

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.EditorExpressionInfo
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.analysis.SemanticToken
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentation
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisRequestCoordinator
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisResultSink
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.client.controller.TEST_PLATFORM_CATALOG
import ru.lazyhat.compukters.ide.client.workspace.IdeBuildInput
import ru.lazyhat.compukters.ide.editor.EditorChange
import ru.lazyhat.compukters.ide.editor.EditorChangeOrigin
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.ProjectManifestCodec
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeAnalysisCoordinatorTest {
    @Test
    fun `hover link and explicit declaration admit only their exact anchors`() {
        val fixture = fixture("val answer = 42\nprintln(answer)")
        val active = fixture.open()
        val token = EditorRange(4, 10)

        fixture.coordinator.pointerMoved(token, offsetUtf16 = 6, controlDown = false)
        fixture.requests.completeHover(expressionInfo(active.identity, token))
        assertIs<IdeSemanticInteraction.Hover>(activeState(fixture).interaction)

        fixture.coordinator.pointerMoved(token, offsetUtf16 = 6, controlDown = true)
        fixture.requests.completeProbe(declaration(active.identity, projectLocation(4, 10)))
        assertIs<IdeSemanticInteraction.Link>(activeState(fixture).interaction)

        fixture.coordinator.pointerMoved(EditorRange(24, 30), offsetUtf16 = 26, controlDown = false)
        val pending = fixture.coordinator.goToDeclaration(token, offsetUtf16 = 6)
        fixture.coordinator.pointerMoved(EditorRange(24, 30), offsetUtf16 = 27, controlDown = true)
        fixture.requests.completeNavigation(declaration(active.identity, projectLocation(0, 3)))

        val outcome = assertIs<IdeDeclarationOutcome.Targets>(pending.join())
        assertEquals(listOf(IdeDeclarationTarget.Project(ProjectPath.file("src/main.kt"), EditorRange(0, 3))), outcome.values)
    }

    @Test
    fun `confirmed Ctrl probe is reused for declaration navigation`() {
        val fixture = fixture("val answer = 42")
        val active = fixture.open()
        val token = EditorRange(4, 10)
        fixture.coordinator.pointerMoved(token, offsetUtf16 = 6, controlDown = true)
        fixture.requests.completeProbe(declaration(active.identity, projectLocation(4, 10)))

        val outcome = fixture.coordinator.goToDeclaration(token, offsetUtf16 = 6).join()

        assertIs<IdeDeclarationOutcome.Targets>(outcome)
        assertTrue(fixture.requests.navigationRequests.isEmpty())
    }

    @Test
    fun `edits invalidate semantic interaction and reject late pointer results`() {
        val fixture = fixture("val answer = 42")
        val active = fixture.open()
        fixture.coordinator.pointerMoved(EditorRange(4, 10), offsetUtf16 = 6, controlDown = false)

        fixture.coordinator.sourceChanged(
            fixture.project,
            path(),
            "xval answer = 42",
            documentRevision = 1,
            insertedText = "x",
            caretOffsetUtf16 = 1,
            change = insertion(0, 0, 1),
        )
        fixture.requests.completeHover(expressionInfo(active.identity, EditorRange(4, 10)))

        assertIs<IdeSemanticInteraction.None>(activeState(fixture).interaction)
        assertTrue(fixture.requests.pointerCancelCount > 0)
    }

    @Test
    fun `multiple declarations publish a bounded chooser and selection can be dismissed`() {
        val fixture = fixture("fun one() = Unit\nfun two() = Unit\nval ref = ::one")
        val active = fixture.open()
        val token = EditorRange(46, 49)
        val pending = fixture.coordinator.goToDeclaration(token, offsetUtf16 = 47)
        fixture.requests.completeNavigation(
            AnalysisClientResult.Success(
                AnalysisResult.Declaration.create(
                    active.identity,
                    listOf(projectLocation(4, 7), projectLocation(21, 24)),
                    mapOf(path() to fixture.text.length),
                ),
            ),
        )

        assertIs<IdeDeclarationOutcome.Targets>(pending.join())
        assertEquals(0, assertIs<IdeSemanticInteraction.Chooser>(activeState(fixture).interaction).selectedIndex)
        fixture.coordinator.moveDeclarationChoice(1)
        assertEquals(1, assertIs<IdeSemanticInteraction.Chooser>(activeState(fixture).interaction).selectedIndex)
        assertEquals(
            IdeDeclarationTarget.Project(ProjectPath.file("src/main.kt"), EditorRange(21, 24)),
            fixture.coordinator.acceptDeclarationChoice(),
        )
        assertIs<IdeSemanticInteraction.None>(activeState(fixture).interaction)
    }

    @Test
    fun `unavailable and failed declaration requests produce explicit bounded outcomes`() {
        val fixture = fixture("val answer = 42")
        val active = fixture.open()
        val bundle =
            ru.lazyhat.compukters.ide.analysis
                .AnalysisModuleIdentity("std.core", hash(9))

        val unavailable = fixture.coordinator.goToDeclaration(EditorRange(4, 10), 6)
        fixture.requests.completeNavigation(
            AnalysisClientResult.Success(
                AnalysisResult.Declaration.create(
                    active.identity,
                    listOf(DeclarationLocation.SourceUnavailable(DeclarationOrigin.Platform(bundle))),
                    mapOf(path() to fixture.text.length),
                ),
            ),
        )
        assertEquals(IdeDeclarationOutcome.SourceUnavailable(bundle), unavailable.join())

        val failed = fixture.coordinator.goToDeclaration(EditorRange(4, 10), 6)
        fixture.requests.completeNavigation(AnalysisClientResult.Failure(AnalysisFailureKind.InternalAnalysis, "x".repeat(5_000)))
        assertTrue(assertIs<IdeDeclarationOutcome.Failed>(failed.join()).detail.length <= 4_096)
    }

    @Test
    fun `platform declaration becomes a lightweight target only when exact source was admitted`() {
        val bundle =
            ru.lazyhat.compukters.ide.analysis
                .AnalysisModuleIdentity("std.core", hash(9))
        val sourcePath = VirtualSourcePath.kotlin("compukter/api/Sample.kt")
        val sourceText = "class Sample"
        val catalog = IdeAttachedSourceCatalog.of(mapOf(bundle to mapOf(sourcePath to sourceText)), 1, 1, 64, 64)
        val fixture = fixture("val answer = 42", attachedSources = catalog)
        val active = fixture.open()
        val pending = fixture.coordinator.goToDeclaration(EditorRange(4, 10), 6)

        fixture.requests.completeNavigation(
            AnalysisClientResult.Success(
                AnalysisResult.Declaration.create(
                    active.identity,
                    listOf(
                        DeclarationLocation.Source(
                            DeclarationOrigin.Platform(bundle),
                            sourcePath,
                            EditorRange(6, 12),
                        ),
                    ),
                    mapOf(path() to fixture.text.length),
                    platformSourceLengthsUtf16 = mapOf(bundle to mapOf(sourcePath to sourceText.length)),
                ),
            ),
        )

        val target = assertIs<IdeDeclarationOutcome.Targets>(pending.join()).values.single()
        assertEquals(IdeDeclarationTarget.AttachedSource(bundle, sourcePath, EditorRange(6, 12)), target)
        assertEquals(sourceText, fixture.coordinator.attachedSource(bundle, sourcePath))
    }

    @Test
    fun `platform declaration with no exact catalog entry reports source unavailable`() {
        val bundle =
            ru.lazyhat.compukters.ide.analysis
                .AnalysisModuleIdentity("std.core", hash(9))
        val sourcePath = VirtualSourcePath.kotlin("compukter/api/Sample.kt")
        val fixture = fixture("val answer = 42")
        val active = fixture.open()
        val pending = fixture.coordinator.goToDeclaration(EditorRange(4, 10), 6)

        fixture.requests.completeNavigation(
            AnalysisClientResult.Success(
                AnalysisResult.Declaration.create(
                    active.identity,
                    listOf(DeclarationLocation.Source(DeclarationOrigin.Platform(bundle), sourcePath, EditorRange(6, 12))),
                    mapOf(path() to fixture.text.length),
                    platformSourceLengthsUtf16 = mapOf(bundle to mapOf(sourcePath to 12)),
                ),
            ),
        )

        assertEquals(IdeDeclarationOutcome.SourceUnavailable(bundle), pending.join())
    }

    @Test
    fun `presentation rebases compatible semantic tokens and drops transient diagnostics`() {
        val otherPath = VirtualSourcePath.kotlin("src/other.kt")
        val before = SemanticToken(path(), EditorRange(0, 3), SemanticCategory.Property)
        val intersecting = SemanticToken(path(), EditorRange(9, 11), SemanticCategory.LocalVariable)
        val after = SemanticToken(path(), EditorRange(12, 16), SemanticCategory.Function)
        val other = SemanticToken(otherPath, EditorRange(10, 12), SemanticCategory.Class)
        val diagnostic = EditorDiagnostic(EditorDiagnosticSeverity.Warning, "pending", path(), EditorRange(0, 3))
        val presentation = IdeAnalysisPresentation.of(listOf(diagnostic), listOf(before, intersecting, after, other))
        val change =
            EditorChange(
                oldRevision = 0,
                newRevision = 1,
                oldRange = EditorRange(10, 12),
                insertedCodeUnits = 5,
                oldAffectedLines = 0..0,
                newAffectedLines = 0..0,
                origin = EditorChangeOrigin.User,
            )

        val rebased = presentation.rebase(path(), change)

        assertTrue(rebased.diagnostics.isEmpty())
        assertEquals(
            listOf(
                before,
                SemanticToken(path(), EditorRange(15, 19), SemanticCategory.Function),
                other,
            ),
            rebased.semanticTokens,
        )
    }

    @Test
    fun `current presentation overrides lexical style and admits diagnostics`() {
        val fixture = fixture("val answer = 42")
        val active = fixture.open()
        val token = SemanticToken(path(), EditorRange(4, 10), SemanticCategory.LocalVariable)
        val diagnostic = EditorDiagnostic(EditorDiagnosticSeverity.Warning, "unused", path(), EditorRange(4, 10))
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    active.identity,
                    SnapshotPresentation.create(
                        active.identity,
                        mapOf(path() to fixture.text.length),
                        diagnostics = listOf(diagnostic),
                        semanticTokens = listOf(token),
                    ),
                ),
            ),
        )

        val state = assertIs<IdeAnalysisState.Active>(fixture.coordinator.state())
        assertEquals(listOf(diagnostic), state.presentation.diagnostics)
        assertEquals(
            IdeHighlightStyle.Semantic(SemanticCategory.LocalVariable),
            state.presentation.styleAt(path(), 5, KotlinLexicalKind.Identifier),
        )
        assertEquals(
            IdeHighlightStyle.Lexical(KotlinLexicalKind.Keyword),
            state.presentation.styleAt(path(), 0, KotlinLexicalKind.Keyword),
        )
    }

    @Test
    fun `pending analysis preserves rebased semantic tokens across repeated edits`() {
        val fixture = fixture("val answer = 42")
        val initial = fixture.open()
        val token = SemanticToken(path(), EditorRange(4, 10), SemanticCategory.LocalVariable)
        val diagnostic = EditorDiagnostic(EditorDiagnosticSeverity.Warning, "unused", path(), token.range)
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    initial.identity,
                    SnapshotPresentation.create(
                        initial.identity,
                        mapOf(path() to fixture.text.length),
                        diagnostics = listOf(diagnostic),
                        semanticTokens = listOf(token),
                    ),
                ),
            ),
        )
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Completion.create(
                    initial.identity,
                    EditorRange(4, 10),
                    listOf(CompletionItem("answer", "answer", CompletionKind.Property)),
                    fixture.text.length,
                ),
            ),
        )

        fixture.coordinator.sourceChanged(
            fixture.project,
            path(),
            "xval answer = 42",
            documentRevision = 1,
            insertedText = "x",
            change = insertion(0, 0, 1),
        )

        val firstPending = assertIs<IdeAnalysisState.Active>(fixture.coordinator.state())
        assertEquals(listOf(token.copy(range = EditorRange(5, 11))), firstPending.presentation.semanticTokens)
        assertTrue(firstPending.presentation.diagnostics.isEmpty())
        assertNull(firstPending.completion)

        fixture.coordinator.sourceChanged(
            fixture.project,
            path(),
            "yxval answer = 42",
            documentRevision = 2,
            insertedText = "y",
            change = insertion(0, 1, 2),
        )

        val secondPending = assertIs<IdeAnalysisState.Active>(fixture.coordinator.state())
        assertEquals(listOf(token.copy(range = EditorRange(6, 12))), secondPending.presentation.semanticTokens)

        val fresh = fixture.requests.snapshots.last()
        val freshToken = SemanticToken(path(), EditorRange(6, 12), SemanticCategory.Property)
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    fresh.identity,
                    SnapshotPresentation.create(
                        fresh.identity,
                        mapOf(path() to fixture.text.length),
                        semanticTokens = listOf(freshToken),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(freshToken),
            assertIs<IdeAnalysisState.Active>(fixture.coordinator.state()).presentation.semanticTokens,
        )
    }

    @Test
    fun `stale source and profile results cannot replace current presentation`() {
        val fixture = fixture("val answer = 42")
        val active = fixture.open()
        val staleSource = active.identity.copy(source = SourceSnapshotIdentity.of(source("val stale = 0")))
        val staleProfile = active.identity.copy(profile = AnalysisProfileIdentity(hash(99)))

        listOf(staleSource, staleProfile).forEach { identity ->
            fixture.publish(
                AnalysisClientResult.Success(
                    AnalysisResult.Presentation(
                        identity,
                        SnapshotPresentation.create(identity, mapOf(path() to fixture.text.length)),
                    ),
                ),
            )
        }

        val state = assertIs<IdeAnalysisState.Active>(fixture.coordinator.state())
        assertEquals(active.identity, state.identity)
        assertTrue(state.presentation.semanticTokens.isEmpty())
    }

    @Test
    fun `identifier and dot edits trigger automatic completion while manual completion is immediate`() {
        val fixture = fixture("val value = foo")
        fixture.open()

        fixture.coordinator.sourceChanged(fixture.project, path(), "val value = foo.", 1, ".")
        fixture.coordinator.sourceChanged(fixture.project, path(), "val value = foo.b", 2, "b")
        fixture.coordinator.sourceChanged(fixture.project, path(), "val value = foo.b ", 3, " ")
        fixture.coordinator.manualCompletion()

        assertEquals(listOf(16, 17), fixture.requests.automaticOffsets)
        assertEquals(listOf(18), fixture.requests.manualOffsets)

        fixture.coordinator.reload()
        assertEquals(listOf(18), fixture.requests.manualOffsets)
    }

    @Test
    fun `completion closes on focus and file changes while failures preserve lexical fallback`() {
        val fixture = fixture("pr")
        val active = fixture.open()
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Completion.create(
                    active.identity,
                    EditorRange(0, 2),
                    listOf(CompletionItem("println", "println", CompletionKind.Function)),
                    fixture.text.length,
                ),
            ),
        )
        assertIs<IdeCompletionState>(assertIs<IdeAnalysisState.Active>(fixture.coordinator.state()).completion)

        fixture.coordinator.focusLost()
        assertNull(assertIs<IdeAnalysisState.Active>(fixture.coordinator.state()).completion)

        fixture.publish(AnalysisClientResult.Failure(AnalysisFailureKind.InternalAnalysis, "boom"))
        val unavailable = assertIs<IdeAnalysisState.Unavailable>(fixture.coordinator.state())
        assertEquals("Analysis unavailable", unavailable.status)

        fixture.coordinator.closeFile()
        assertIs<IdeAnalysisState.Idle>(fixture.coordinator.state())
        fixture.publish(AnalysisClientResult.Stale)
        assertIs<IdeAnalysisState.Idle>(fixture.coordinator.state())
    }

    @Test
    fun `an edit made while project input loads becomes the only admitted snapshot`() {
        val fixture = AnalysisFixture("val old = 1", deferredInput = true)
        fixture.coordinator.open(fixture.project, path(), fixture.text, 0)

        fixture.coordinator.sourceChanged(fixture.project, path(), "val newest = 2", 1, "2")
        fixture.completeInput()

        val snapshot = fixture.requests.snapshots.single()
        assertEquals(
            "val newest = 2",
            snapshot.sources.sources
                .single()
                .content
                .toByteArray()
                .decodeToString(),
        )
        assertEquals(listOf("val newest = 2".length), fixture.requests.automaticOffsets)
    }

    @Test
    fun `completion admission rejects a document revision not represented by its snapshot`() {
        val fixture = fixture("pr")
        val active = fixture.open()
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Completion.create(
                    active.identity,
                    EditorRange(0, 2),
                    listOf(CompletionItem("println", "println", CompletionKind.Function)),
                    fixture.text.length,
                ),
            ),
        )
        val document = EditorDocument("pr")
        document.type("x")

        assertEquals(null, fixture.coordinator.selectCompletion(document, path()))
        assertEquals("xpr", document.materialize())
    }

    @Test
    fun `reload obtains fresh project input without closing the active editor`() {
        val fixture = fixture("val answer = 42")
        fixture.open()

        fixture.coordinator.reload()

        assertEquals(2, fixture.requests.snapshots.size)
        assertIs<IdeAnalysisState.Active>(fixture.coordinator.state())
    }

    @Test
    fun `completion selection does not mutate the document before controller acceptance`() {
        val fixture = AnalysisFixture("val old = 1", rejectedText = "val broken =")
        val old = fixture.open()

        fixture.coordinator.sourceChanged(fixture.project, path(), "val broken =", 1, "=")
        assertIs<IdeAnalysisState.Unavailable>(fixture.coordinator.state())
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    old.identity,
                    SnapshotPresentation.create(old.identity, mapOf(path() to "val old = 1".length)),
                ),
            ),
        )

        assertIs<IdeAnalysisState.Unavailable>(fixture.coordinator.state())
    }

    @Test
    fun `automatic completion expectation and fresh results use current document revision`() {
        val latency = RecordingVisibleLatencyTrace()
        val initial = "fun candidate() = Unit\nfun main() { can }"
        val fixture = fixture(initial, latency)
        fixture.open()
        val insertionOffset = initial.indexOf("can }") + "can".length

        fixture.coordinator.sourceChanged(
            fixture.project,
            path(),
            initial.replaceRange(insertionOffset, insertionOffset, "d"),
            documentRevision = 1,
            insertedText = "d",
            caretOffsetUtf16 = insertionOffset + 1,
            change = insertion(insertionOffset, 0, 1),
        )
        assertEquals(listOf(1L), latency.automaticExpected)

        val current = fixture.requests.snapshots.last()
        val token = SemanticToken(path(), EditorRange(4, 13), SemanticCategory.Function)
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    current.identity,
                    SnapshotPresentation.create(
                        current.identity,
                        mapOf(path() to fixture.text.length),
                        semanticTokens = listOf(token),
                    ),
                ),
            ),
        )
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Completion.create(
                    current.identity,
                    EditorRange(insertionOffset - 3, insertionOffset + 1),
                    listOf(CompletionItem("candidate", "candidate", CompletionKind.Function)),
                    fixture.text.length,
                ),
            ),
        )

        assertEquals(
            listOf(
                IdeVisibleLatencyKind.Presentation to 1L,
                IdeVisibleLatencyKind.AutomaticCompletion to 1L,
            ),
            latency.published.takeLast(2),
        )
    }

    @Test
    fun `rebased provisional and stale results publish no fresh latency event`() {
        val latency = RecordingVisibleLatencyTrace()
        val fixture = fixture("val answer = 42", latency)
        val initial = fixture.open()
        val token = SemanticToken(path(), EditorRange(4, 10), SemanticCategory.LocalVariable)
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    initial.identity,
                    SnapshotPresentation.create(
                        initial.identity,
                        mapOf(path() to fixture.text.length),
                        semanticTokens = listOf(token),
                    ),
                ),
            ),
        )
        latency.published.clear()

        fixture.coordinator.sourceChanged(
            fixture.project,
            path(),
            "xval answer = 42",
            documentRevision = 1,
            insertedText = "x",
            caretOffsetUtf16 = 1,
            change = insertion(0, 0, 1),
        )
        assertTrue(assertIs<IdeAnalysisState.Active>(fixture.coordinator.state()).presentation.semanticTokens.isNotEmpty())
        assertTrue(latency.published.isEmpty())

        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    initial.identity,
                    SnapshotPresentation.create(
                        initial.identity,
                        mapOf(path() to "val answer = 42".length),
                        semanticTokens = listOf(token),
                    ),
                ),
            ),
        )
        assertTrue(latency.published.isEmpty())
    }

    @Test
    fun `accepted completion starts its edit trace before rebuilding analysis`() {
        val latency = RecordingVisibleLatencyTrace()
        val fixture = fixture("pr", latency)
        val active = fixture.open()
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Completion.create(
                    active.identity,
                    EditorRange(0, 2),
                    listOf(CompletionItem("println", "println", CompletionKind.Function)),
                    fixture.text.length,
                ),
            ),
        )
        latency.events.clear()
        fixture.requests.onSourceChanged = { snapshot ->
            fixture.publish(
                AnalysisClientResult.Success(
                    AnalysisResult.Presentation(
                        snapshot.identity,
                        SnapshotPresentation.create(snapshot.identity, mapOf(path() to "println".length)),
                    ),
                ),
            )
        }

        val document = EditorDocument("pr")
        val selected = checkNotNull(fixture.coordinator.selectCompletion(document, path()))

        assertEquals("println", selected.entry.proposal.insertText)
        assertEquals("pr", document.materialize())
        assertEquals(emptyList(), latency.events)
    }

    @Test
    fun `empty automatic completion terminates its trace without publication`() {
        val latency = RecordingVisibleLatencyTrace()
        val fixture = fixture("pr", latency)
        fixture.open()
        fixture.coordinator.sourceChanged(
            fixture.project,
            path(),
            "pri",
            documentRevision = 1,
            insertedText = "i",
            caretOffsetUtf16 = 3,
            change = insertion(2, 0, 1),
        )
        val current = fixture.requests.snapshots.last()
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Completion.create(current.identity, EditorRange(0, 3), emptyList(), fixture.text.length),
            ),
        )

        assertEquals(listOf(IdeVisibleLatencyKind.AutomaticCompletion to 1L), latency.unavailable)
        assertTrue(IdeVisibleLatencyKind.AutomaticCompletion to 1L !in latency.published)
    }

    @Test
    fun `analysis unavailable terminates matching traces`() {
        val latency = RecordingVisibleLatencyTrace()
        val fixture = fixture("pr", latency)
        fixture.open()
        fixture.coordinator.sourceChanged(
            fixture.project,
            path(),
            "pri",
            documentRevision = 1,
            insertedText = "i",
            caretOffsetUtf16 = 3,
            change = insertion(2, 0, 1),
        )

        fixture.publish(AnalysisClientResult.Failure(AnalysisFailureKind.InternalAnalysis, "boom"))

        assertEquals(
            listOf(
                IdeVisibleLatencyKind.Presentation to 1L,
                IdeVisibleLatencyKind.AutomaticCompletion to 1L,
            ),
            latency.unavailable,
        )
        assertIs<IdeAnalysisState.Unavailable>(fixture.coordinator.state())
    }

    private fun fixture(
        text: String,
        visibleLatency: IdeVisibleLatencyTrace = IdeVisibleLatencyTrace.None,
        attachedSources: IdeAttachedSourceCatalog = IdeAttachedSourceCatalog.empty(),
    ) = AnalysisFixture(text, visibleLatency = visibleLatency, attachedSources = attachedSources)

    private fun source(text: String) =
        ProjectSnapshot.of(listOf(ProjectSource(path(), BinaryValue.of(text.encodeToByteArray()))), ANALYSIS_LIMITS)

    private fun insertion(
        offset: Int,
        oldRevision: Long,
        newRevision: Long,
    ) = EditorChange(
        oldRevision,
        newRevision,
        EditorRange(offset, offset),
        insertedCodeUnits = 1,
        oldAffectedLines = 0..0,
        newAffectedLines = 0..0,
        origin = EditorChangeOrigin.User,
    )

    private fun expressionInfo(
        identity: AnalysisSnapshotIdentity,
        range: EditorRange,
    ) = AnalysisClientResult.Success(
        AnalysisResult.ExpressionInfo.create(
            identity,
            EditorExpressionInfo(path(), range, "kotlin.Int", "val answer: kotlin.Int", DeclarationOrigin.Project),
            mapOf(path() to 64),
        ),
    )

    private fun declaration(
        identity: AnalysisSnapshotIdentity,
        location: DeclarationLocation,
    ) = AnalysisClientResult.Success(
        AnalysisResult.Declaration.create(identity, listOf(location), mapOf(path() to 64)),
    )

    private fun projectLocation(
        start: Int,
        end: Int,
    ) = DeclarationLocation.Source(DeclarationOrigin.Project, path(), EditorRange(start, end))

    private fun activeState(fixture: AnalysisFixture) = assertIs<IdeAnalysisState.Active>(fixture.coordinator.state())
}

private class AnalysisFixture(
    initialText: String,
    private val deferredInput: Boolean = false,
    private val rejectedText: String? = null,
    visibleLatency: IdeVisibleLatencyTrace = IdeVisibleLatencyTrace.None,
    attachedSources: IdeAttachedSourceCatalog = IdeAttachedSourceCatalog.empty(),
) {
    var text = initialText
    val descriptor = ProjectCatalog.open(createTempDirectory("compukters-analysis-")).create("demo")
    val project: ProjectHandle = descriptor.handle
    val requests = RecordingRequests()
    private val profile = AnalysisProfileIdentity(hash(2))
    private val inputFuture = CompletableFuture<IdeBuildInput>()
    val coordinator =
        IdeAnalysisCoordinator(
            inputLoader =
                IdeAnalysisInputLoader {
                    if (deferredInput) inputFuture else CompletableFuture.completedFuture(input())
                },
            snapshotFactory =
                IdeAnalysisSnapshotFactory { input, activePath, activeText ->
                    require(activeText != rejectedText) { "rejected analysis source" }
                    snapshot(input, activePath, activeText)
                },
            requestFactory = IdeAnalysisRequestFactory { sink -> requests.apply { this.sink = sink } },
            visibleLatency = visibleLatency,
            attachedSources = attachedSources,
            platformCatalog = TEST_PLATFORM_CATALOG,
        )

    fun open(): AdmittedAnalysisSnapshot {
        coordinator.open(project, path(), text, 0)
        return requests.snapshots.last()
    }

    fun publish(result: AnalysisClientResult) = requests.sink.publish(result)

    fun completeInput() {
        inputFuture.complete(input())
    }

    private fun input(): IdeBuildInput =
        IdeBuildInput(
            project,
            ProjectManifestCodec.encode(descriptor.manifest).encodeToByteArray(),
            null,
            source(text),
        )

    private fun snapshot(
        input: IdeBuildInput,
        activePath: VirtualSourcePath,
        activeText: String,
    ): AdmittedAnalysisSnapshot {
        text = activeText
        val sources =
            ProjectSnapshot.of(
                input.sources.sources.map { source ->
                    if (source.path == activePath) ProjectSource(activePath, BinaryValue.of(activeText.encodeToByteArray())) else source
                },
                ANALYSIS_LIMITS,
            )
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
}

private class RecordingRequests : AnalysisRequestCoordinator {
    lateinit var sink: AnalysisResultSink
    val snapshots = mutableListOf<AdmittedAnalysisSnapshot>()
    val automaticOffsets = mutableListOf<Int>()
    val manualOffsets = mutableListOf<Int>()
    val hoverRequests = mutableListOf<CompletableFuture<AnalysisClientResult>>()
    val probeRequests = mutableListOf<CompletableFuture<AnalysisClientResult>>()
    val navigationRequests = mutableListOf<CompletableFuture<AnalysisClientResult>>()
    var pointerCancelCount = 0
    var onSourceChanged: ((AdmittedAnalysisSnapshot) -> Unit)? = null

    override fun sourceChanged(
        snapshot: AdmittedAnalysisSnapshot,
        activePath: VirtualSourcePath,
    ) {
        snapshots += snapshot
        onSourceChanged?.invoke(snapshot)
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

    override fun hoverInfo(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> = CompletableFuture<AnalysisClientResult>().also(hoverRequests::add)

    override fun declarationProbe(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> = CompletableFuture<AnalysisClientResult>().also(probeRequests::add)

    override fun declaration(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> = CompletableFuture<AnalysisClientResult>().also(navigationRequests::add)

    override fun cancelPointerInteraction() {
        pointerCancelCount++
    }

    fun completeHover(result: AnalysisClientResult) {
        hoverRequests.last().complete(result)
    }

    fun completeProbe(result: AnalysisClientResult) {
        probeRequests.last().complete(result)
    }

    fun completeNavigation(result: AnalysisClientResult) {
        navigationRequests.last().complete(result)
    }

    override fun close() = Unit
}

private class RecordingVisibleLatencyTrace : IdeVisibleLatencyTrace {
    val edits = mutableListOf<Long>()
    val automaticExpected = mutableListOf<Long>()
    val published = mutableListOf<Pair<IdeVisibleLatencyKind, Long>>()
    val observed = mutableListOf<Long>()
    val unavailable = mutableListOf<Pair<IdeVisibleLatencyKind, Long>>()
    val events = mutableListOf<VisibleLatencyEvent>()
    var drops = 0

    override fun editApplied(documentRevision: Long) {
        edits += documentRevision
        events += VisibleLatencyEvent.EditApplied(documentRevision)
    }

    override fun automaticCompletionExpected(documentRevision: Long) {
        automaticExpected += documentRevision
    }

    override fun analysisPublished(
        kind: IdeVisibleLatencyKind,
        documentRevision: Long,
    ) {
        published += kind to documentRevision
        events += VisibleLatencyEvent.AnalysisPublished(kind, documentRevision)
    }

    override fun controllerObserved(documentRevision: Long) {
        observed += documentRevision
    }

    override fun frameExtracted(
        documentRevision: Long,
        presentationVisible: Boolean,
        completionVisible: Boolean,
    ) = Unit

    override fun resultUnavailable(
        kind: IdeVisibleLatencyKind,
        documentRevision: Long,
    ) {
        unavailable += kind to documentRevision
    }

    override fun dropActive() {
        drops++
    }
}

private sealed interface VisibleLatencyEvent {
    data class EditApplied(
        val documentRevision: Long,
    ) : VisibleLatencyEvent

    data class AnalysisPublished(
        val kind: IdeVisibleLatencyKind,
        val documentRevision: Long,
    ) : VisibleLatencyEvent
}

private fun path() = VirtualSourcePath.kotlin("src/main.kt")

private fun source(text: String) =
    ProjectSnapshot.of(
        listOf(ProjectSource(path(), BinaryValue.of(text.encodeToByteArray()))),
        ANALYSIS_LIMITS,
    )

private fun hash(seed: Int) = Hash256.of(ByteArray(32) { seed.toByte() })

private val ANALYSIS_LIMITS = WorkerLimits(sourceFiles = 8, sourceFileBytes = 4096, sourceBytes = 8192)
