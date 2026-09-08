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

package ru.lazyhat.compukters.ide.analysis.controller

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import java.util.concurrent.CompletableFuture

interface AnalysisRequestCoordinator : AutoCloseable {
    fun sourceChanged(
        snapshot: AdmittedAnalysisSnapshot,
        activePath: VirtualSourcePath,
    )

    fun automaticCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    )

    fun manualCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult>

    fun hoverInfo(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> = unsupportedInteractiveRequest()

    fun declarationProbe(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> = unsupportedInteractiveRequest()

    fun declaration(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> = unsupportedInteractiveRequest()

    fun format(
        path: VirtualSourcePath,
        source: String,
        caretOffsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> = unsupportedInteractiveRequest()

    fun parameterInfo(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> = unsupportedInteractiveRequest()

    fun cancelPointerInteraction() = Unit

    fun cancelParameterInfo() = Unit
}

private fun unsupportedInteractiveRequest(): CompletableFuture<AnalysisClientResult> =
    CompletableFuture.failedFuture(UnsupportedOperationException("interactive analysis requests are not configured"))

fun interface AnalysisResultSink {
    fun publish(result: AnalysisClientResult)
}

class DefaultAnalysisRequestCoordinator(
    private val client: AnalysisClient,
    private val scheduler: AnalysisTaskScheduler,
    private val presentationDebounceNanos: Long,
    private val automaticCompletionDebounceNanos: Long,
    private val hoverDebounceNanos: Long = 400_000_000L,
    private val resultSink: AnalysisResultSink = AnalysisResultSink {},
) : AnalysisRequestCoordinator {
    private val lock = Any()
    private var snapshot: AdmittedAnalysisSnapshot? = null
    private var presentationTask: AnalysisScheduledTask? = null
    private var presentationFuture: CompletableFuture<AnalysisClientResult>? = null
    private var completionTask: AnalysisScheduledTask? = null
    private var completionFuture: CompletableFuture<AnalysisClientResult>? = null
    private var completionTrigger: CompletionTrigger? = null
    private var pointerTask: AnalysisScheduledTask? = null
    private var pointerFuture: CompletableFuture<AnalysisClientResult>? = null
    private var pointerResult: CompletableFuture<AnalysisClientResult>? = null
    private var navigationFuture: CompletableFuture<AnalysisClientResult>? = null
    private var navigationResult: CompletableFuture<AnalysisClientResult>? = null
    private var parameterInfoFuture: CompletableFuture<AnalysisClientResult>? = null
    private var closed = false

    init {
        require(presentationDebounceNanos >= 0) { "presentation debounce must not be negative" }
        require(automaticCompletionDebounceNanos >= 0) { "automatic-completion debounce must not be negative" }
        require(hoverDebounceNanos >= 0) { "hover debounce must not be negative" }
    }

    override fun sourceChanged(
        snapshot: AdmittedAnalysisSnapshot,
        activePath: VirtualSourcePath,
    ) {
        val oldPresentation: CompletableFuture<AnalysisClientResult>?
        val oldCompletion: CompletableFuture<AnalysisClientResult>?
        val oldPointer: CompletableFuture<AnalysisClientResult>?
        val oldNavigation: CompletableFuture<AnalysisClientResult>?
        val oldParameterInfo: CompletableFuture<AnalysisClientResult>?
        synchronized(lock) {
            check(!closed) { "analysis request coordinator is closed" }
            this.snapshot = snapshot
            presentationTask?.cancel()
            completionTask?.cancel()
            pointerTask?.cancel()
            oldPresentation = presentationFuture
            oldCompletion = completionFuture
            oldPointer = pointerFuture
            oldNavigation = navigationFuture
            oldParameterInfo = parameterInfoFuture
            pointerResult?.cancel(false)
            navigationResult?.cancel(false)
            presentationFuture = null
            completionFuture = null
            pointerTask = null
            pointerFuture = null
            pointerResult = null
            navigationFuture = null
            navigationResult = null
            parameterInfoFuture = null
            completionTrigger = null
            completionTask = null
            presentationTask =
                scheduler.schedule(presentationDebounceNanos) {
                    dispatchPresentation(snapshot, activePath)
                }
        }
        oldPresentation?.let(client::cancel)
        oldCompletion?.let(client::cancel)
        oldPointer?.let(client::cancel)
        oldNavigation?.let(client::cancel)
        oldParameterInfo?.let(client::cancel)
    }

    override fun automaticCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ) {
        val current: AdmittedAnalysisSnapshot
        val oldCompletion: CompletableFuture<AnalysisClientResult>?
        val oldPresentation: CompletableFuture<AnalysisClientResult>?
        synchronized(lock) {
            check(!closed) { "analysis request coordinator is closed" }
            current = snapshot ?: return
            if (completionTrigger == CompletionTrigger.Manual && completionFuture?.isDone == false) return
            completionTask?.cancel()
            oldCompletion = completionFuture
            oldPresentation = presentationFuture
            completionFuture = null
            completionTrigger = null
            presentationFuture = null
            val query = AnalysisQuery.Completion(current.identity, path, offsetUtf16, CompletionTrigger.Automatic)
            completionTask = scheduler.schedule(automaticCompletionDebounceNanos) { dispatchCompletion(current, query) }
        }
        oldPresentation?.let(client::cancel)
        oldCompletion?.let(client::cancel)
    }

    override fun manualCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> {
        val current: AdmittedAnalysisSnapshot
        val oldCompletion: CompletableFuture<AnalysisClientResult>?
        val oldPresentation: CompletableFuture<AnalysisClientResult>?
        val future: CompletableFuture<AnalysisClientResult>
        synchronized(lock) {
            check(!closed) { "analysis request coordinator is closed" }
            current = checkNotNull(snapshot) { "analysis snapshot is not open" }
            completionTask?.cancel()
            completionTask = null
            oldCompletion = completionFuture
            oldPresentation = presentationFuture
            presentationFuture = null
            future = client.query(current, AnalysisQuery.Completion(current.identity, path, offsetUtf16, CompletionTrigger.Manual))
            completionFuture = future
            completionTrigger = CompletionTrigger.Manual
        }
        oldPresentation?.let(client::cancel)
        oldCompletion?.let(client::cancel)
        publishCompletion(current, future)
        return future
    }

    override fun hoverInfo(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> =
        pointerRequest(hoverDebounceNanos) { identity -> AnalysisQuery.ExpressionInfo(identity, path, offsetUtf16) }

    override fun declarationProbe(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> = pointerRequest(0) { identity -> AnalysisQuery.Declaration(identity, path, offsetUtf16) }

    override fun declaration(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> {
        val expected: AdmittedAnalysisSnapshot
        val result = CompletableFuture<AnalysisClientResult>()
        val oldPointer: CompletableFuture<AnalysisClientResult>?
        val oldNavigation: CompletableFuture<AnalysisClientResult>?
        val future: CompletableFuture<AnalysisClientResult>
        synchronized(lock) {
            check(!closed) { "analysis request coordinator is closed" }
            expected = checkNotNull(snapshot) { "analysis snapshot is not open" }
            pointerTask?.cancel()
            pointerTask = null
            oldPointer = pointerFuture
            pointerFuture = null
            pointerResult?.cancel(false)
            pointerResult = null
            oldNavigation = navigationFuture
            navigationResult?.cancel(false)
            val query = AnalysisQuery.Declaration(expected.identity, path, offsetUtf16)
            future = client.query(expected, query)
            navigationFuture = future
            navigationResult = result
        }
        oldPointer?.let(client::cancel)
        oldNavigation?.let(client::cancel)
        completeNavigation(expected, future, result)
        return result
    }

    override fun format(
        path: VirtualSourcePath,
        source: String,
        caretOffsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> {
        val current =
            synchronized(lock) {
                check(!closed) { "analysis request coordinator is closed" }
                checkNotNull(snapshot) { "analysis snapshot is not open" }
            }
        return client.query(current, AnalysisQuery.Format(current.identity, path, source, caretOffsetUtf16))
    }

    override fun cancelPointerInteraction() {
        val oldPointer: CompletableFuture<AnalysisClientResult>?
        synchronized(lock) {
            if (closed) return
            pointerTask?.cancel()
            pointerTask = null
            oldPointer = pointerFuture
            pointerFuture = null
            pointerResult?.cancel(false)
            pointerResult = null
        }
        oldPointer?.let(client::cancel)
    }

    override fun parameterInfo(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> {
        val expected: AdmittedAnalysisSnapshot
        val previous: CompletableFuture<AnalysisClientResult>?
        val future: CompletableFuture<AnalysisClientResult>
        synchronized(lock) {
            check(!closed) { "analysis request coordinator is closed" }
            expected = checkNotNull(snapshot) { "analysis snapshot is not open" }
            previous = parameterInfoFuture
            future = client.query(expected, AnalysisQuery.ParameterInfo(expected.identity, path, offsetUtf16))
            parameterInfoFuture = future
        }
        previous?.let(client::cancel)
        future.whenComplete { _, _ ->
            synchronized(lock) {
                if (parameterInfoFuture === future) parameterInfoFuture = null
            }
        }
        return future
    }

    override fun cancelParameterInfo() {
        val previous = synchronized(lock) { parameterInfoFuture.also { parameterInfoFuture = null } }
        previous?.let(client::cancel)
    }

    override fun close() {
        val futures: List<CompletableFuture<AnalysisClientResult>>
        synchronized(lock) {
            if (closed) return
            closed = true
            presentationTask?.cancel()
            completionTask?.cancel()
            pointerTask?.cancel()
            presentationTask = null
            completionTask = null
            pointerResult?.cancel(false)
            navigationResult?.cancel(false)
            futures = listOfNotNull(presentationFuture, completionFuture, pointerFuture, navigationFuture, parameterInfoFuture)
            presentationFuture = null
            completionFuture = null
            pointerTask = null
            pointerFuture = null
            pointerResult = null
            navigationFuture = null
            navigationResult = null
            parameterInfoFuture = null
            completionTrigger = null
            snapshot = null
        }
        futures.forEach(client::cancel)
    }

    private fun pointerRequest(
        delayNanos: Long,
        query: (AnalysisSnapshotIdentity) -> AnalysisQuery,
    ): CompletableFuture<AnalysisClientResult> {
        val expected: AdmittedAnalysisSnapshot
        val result = CompletableFuture<AnalysisClientResult>()
        val oldPointer: CompletableFuture<AnalysisClientResult>?
        synchronized(lock) {
            check(!closed) { "analysis request coordinator is closed" }
            expected = checkNotNull(snapshot) { "analysis snapshot is not open" }
            pointerTask?.cancel()
            oldPointer = pointerFuture
            pointerFuture = null
            pointerResult?.cancel(false)
            pointerResult = result
            val request = query(expected.identity)
            pointerTask = scheduler.schedule(delayNanos) { dispatchPointer(expected, request, result) }
        }
        oldPointer?.let(client::cancel)
        return result
    }

    private fun dispatchPointer(
        expected: AdmittedAnalysisSnapshot,
        query: AnalysisQuery,
        result: CompletableFuture<AnalysisClientResult>,
    ) {
        val future =
            synchronized(lock) {
                if (closed || snapshot !== expected || pointerResult !== result) return
                pointerTask = null
                client.query(expected, query).also { pointerFuture = it }
            }
        completePointer(expected, future, result)
    }

    private fun completePointer(
        expected: AdmittedAnalysisSnapshot,
        future: CompletableFuture<AnalysisClientResult>,
        result: CompletableFuture<AnalysisClientResult>,
    ) {
        future.whenComplete { value, failure ->
            val admitted =
                synchronized(lock) {
                    if (closed || snapshot !== expected || pointerFuture !== future || pointerResult !== result) {
                        return@synchronized false
                    }
                    pointerFuture = null
                    pointerResult = null
                    true
                }
            if (admitted) completeResult(result, value, failure)
        }
    }

    private fun completeNavigation(
        expected: AdmittedAnalysisSnapshot,
        future: CompletableFuture<AnalysisClientResult>,
        result: CompletableFuture<AnalysisClientResult>,
    ) {
        future.whenComplete { value, failure ->
            val admitted =
                synchronized(lock) {
                    if (closed || snapshot !== expected || navigationFuture !== future || navigationResult !== result) {
                        return@synchronized false
                    }
                    navigationFuture = null
                    navigationResult = null
                    true
                }
            if (admitted) completeResult(result, value, failure)
        }
    }

    private fun completeResult(
        result: CompletableFuture<AnalysisClientResult>,
        value: AnalysisClientResult?,
        failure: Throwable?,
    ) {
        if (failure != null) {
            result.completeExceptionally(failure)
        } else {
            result.complete(requireNotNull(value) { "analysis request completed without a result" })
        }
    }

    private fun dispatchPresentation(
        expected: AdmittedAnalysisSnapshot,
        activePath: VirtualSourcePath,
    ) {
        val future =
            synchronized(lock) {
                if (closed || snapshot !== expected) return
                presentationTask = null
                client.query(expected, AnalysisQuery.Presentation(expected.identity, activePath)).also { presentationFuture = it }
            }
        future.whenComplete { result, failure ->
            if (failure == null && result != null && admitPresentation(expected, future)) resultSink.publish(result)
        }
    }

    private fun dispatchCompletion(
        expected: AdmittedAnalysisSnapshot,
        query: AnalysisQuery.Completion,
    ) {
        val future =
            synchronized(lock) {
                if (closed || snapshot !== expected) return
                completionTask = null
                client.query(expected, query).also {
                    completionFuture = it
                    completionTrigger = query.trigger
                }
            }
        publishCompletion(expected, future)
    }

    private fun publishCompletion(
        expected: AdmittedAnalysisSnapshot,
        future: CompletableFuture<AnalysisClientResult>,
    ) {
        future.whenComplete { result, failure ->
            if (failure == null && result != null && admitCompletion(expected, future)) resultSink.publish(result)
        }
    }

    private fun admitPresentation(
        expected: AdmittedAnalysisSnapshot,
        future: CompletableFuture<AnalysisClientResult>,
    ): Boolean =
        synchronized(lock) {
            if (closed || snapshot !== expected || presentationFuture !== future) return@synchronized false
            presentationFuture = null
            true
        }

    private fun admitCompletion(
        expected: AdmittedAnalysisSnapshot,
        future: CompletableFuture<AnalysisClientResult>,
    ): Boolean =
        synchronized(lock) {
            if (closed || snapshot !== expected || completionFuture !== future) return@synchronized false
            completionFuture = null
            completionTrigger = null
            true
        }
}
