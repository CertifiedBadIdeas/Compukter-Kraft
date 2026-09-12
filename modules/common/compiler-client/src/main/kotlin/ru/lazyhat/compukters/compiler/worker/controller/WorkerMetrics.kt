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

package ru.lazyhat.compukters.compiler.worker.controller

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.createDirectories

data class WorkerMeasurementReport(
    val payloadBytes: Long,
    val payloadSha256: String,
    val coldStartupMillis: Long,
    val firstCompilationMillis: Long,
    val warmCompilationMillis: Long,
    val workerHeapBytes: ULong,
    val workerMetaspaceBytes: ULong,
    val peakRssBytes: Long?,
    val requestBytes: Int,
    val resultBytes: Int,
    val artifactBytes: Int,
) {
    fun toJson(): String =
        buildString {
            appendLine("{")
            appendLine("  \"payloadBytes\": $payloadBytes,")
            appendLine("  \"payloadSha256\": \"$payloadSha256\",")
            appendLine("  \"coldStartupMillis\": $coldStartupMillis,")
            appendLine("  \"firstCompilationMillis\": $firstCompilationMillis,")
            appendLine("  \"warmCompilationMillis\": $warmCompilationMillis,")
            appendLine("  \"workerHeapBytes\": $workerHeapBytes,")
            appendLine("  \"workerMetaspaceBytes\": $workerMetaspaceBytes,")
            appendLine("  \"peakRssBytes\": ${peakRssBytes ?: "null"},")
            appendLine("  \"requestBytes\": $requestBytes,")
            appendLine("  \"resultBytes\": $resultBytes,")
            appendLine("  \"artifactBytes\": $artifactBytes")
            appendLine("}")
        }
}

object WorkerMetrics {
    fun write(
        report: WorkerMeasurementReport,
        target: Path,
    ) {
        val normalized = target.toAbsolutePath().normalize()
        normalized.parent.createDirectories()
        val staging = normalized.resolveSibling(".${normalized.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(staging, report.toJson())
            try {
                Files.move(staging, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, normalized, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staging)
        }
    }
}
