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

import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.k2.formatter.IsolatedKotlinFormatter
import ru.lazyhat.compukters.ide.analysis.k2.formatter.KotlinSourceFormatter
import ru.lazyhat.compukters.ide.analysis.k2.server.AnalysisQueryHandler
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailure
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQuerySuccess

internal class K2AnalysisQueryHandler(
    private val limits: AnalysisLimits,
    private val createFormatter: () -> KotlinSourceFormatter = { IsolatedKotlinFormatter.packaged() },
) : AnalysisQueryHandler,
    AutoCloseable {
    private var formatter: KotlinSourceFormatter? = null

    override fun execute(
        request: ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQueryRequest,
        snapshot: ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot,
        cancellation: ru.lazyhat.compukters.ide.analysis.k2.server.AnalysisCancellation,
    ) = when (request.query) {
        is AnalysisQuery.Presentation,
        is AnalysisQuery.Completion,
        is AnalysisQuery.ExpressionInfo,
        is AnalysisQuery.Declaration,
        is AnalysisQuery.References,
        -> {
            try {
                AnalysisQuerySuccess(request.requestId, K2QueryDispatcher.execute(request.query, snapshot, limits))
            } catch (exception: AnalysisOutputLimitException) {
                AnalysisFailure(
                    request.requestId,
                    request.query.identity,
                    AnalysisFailureKind.OutputLimit,
                    "analysis output exceeds negotiated limit",
                )
            }
        }

        is AnalysisQuery.Format -> {
            val query = request.query as AnalysisQuery.Format
            try {
                AnalysisQuerySuccess(request.requestId, FormatQuery.execute(query, snapshot, limits, formatter()))
            } catch (exception: AnalysisOutputLimitException) {
                AnalysisFailure(
                    request.requestId,
                    query.identity,
                    AnalysisFailureKind.OutputLimit,
                    "analysis output exceeds negotiated limit",
                )
            }
        }
    }

    @Synchronized
    private fun formatter(): KotlinSourceFormatter = formatter ?: createFormatter().also { formatter = it }

    override fun close() {
        (formatter as? AutoCloseable)?.close()
    }
}
