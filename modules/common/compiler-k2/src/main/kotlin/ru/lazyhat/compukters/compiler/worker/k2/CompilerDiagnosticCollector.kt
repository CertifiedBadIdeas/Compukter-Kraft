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

package ru.lazyhat.compukters.compiler.worker.k2

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Path

data class DiagnosticSource(
    val content: String,
    val virtualPath: VirtualSourcePath,
)

class CompilerDiagnosticCollector(
    sources: Map<Path, DiagnosticSource>,
    private val limits: WorkerLimits,
) : MessageCollector {
    private val sources = sources.mapKeys { (path, _) -> path.toAbsolutePath().normalize() }
    private val collected = mutableListOf<WorkerDiagnostic>()
    private var textBytes = 0
    private var errors = false

    val diagnostics: List<WorkerDiagnostic> get() = collected.toList()

    override fun clear() {
        collected.clear()
        textBytes = 0
        errors = false
    }

    override fun hasErrors(): Boolean = errors

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        if (severity == CompilerMessageSeverity.LOGGING || severity == CompilerMessageSeverity.OUTPUT) return
        val mappedSeverity =
            when {
                severity.isError -> DiagnosticSeverity.ERROR
                severity.isWarning -> DiagnosticSeverity.WARNING
                else -> DiagnosticSeverity.INFO
            }
        val sanitized =
            sources.entries.fold(message) { text, (physical, source) ->
                text.replace(physical.toString(), source.virtualPath.value)
            }
        val diagnosticCategory = category(sanitized)
        val sourceLocation = location?.let { locate(it.path) }
        admit(
            WorkerDiagnostic(
                mappedSeverity,
                diagnosticCategory,
                null,
                sanitized,
                sourceLocation?.virtualPath,
                sourceLocation?.let { offset(it.content, location.line, location.column) },
                sourceLocation?.let { offset(it.content, location.lineEnd, location.columnEnd) },
            ),
        )
    }

    fun report(diagnostic: WorkerDiagnostic) = admit(diagnostic)

    private fun locate(path: String): DiagnosticSource? = runCatching { sources[Path.of(path).toAbsolutePath().normalize()] }.getOrNull()

    private fun admit(diagnostic: WorkerDiagnostic) {
        errors = errors || diagnostic.severity == DiagnosticSeverity.ERROR
        if (collected.size >= limits.diagnostics || textBytes >= limits.diagnosticTextBytes) return
        val admitted = truncateUtf8(diagnostic.message, limits.diagnosticTextBytes - textBytes)
        textBytes += admitted.encodeToByteArray().size
        collected += diagnostic.copy(message = admitted)
    }

    private fun offset(
        source: String,
        line: Int,
        column: Int,
    ): UInt? {
        if (line < 1 || column < 1) return null
        var currentLine = 1
        var index = 0
        while (currentLine < line && index < source.length) if (source[index++] == '\n') currentLine++
        if (currentLine != line) return null
        return (index + column - 1).coerceAtMost(source.length).toUInt()
    }

    constructor(
        source: String,
        physicalPath: Path,
        virtualPath: VirtualSourcePath,
        limits: WorkerLimits,
    ) : this(mapOf(physicalPath to DiagnosticSource(source, virtualPath)), limits)

    private fun category(message: String): DiagnosticCategory =
        if (
            message.contains("syntax", ignoreCase = true) ||
            message.contains("expecting", ignoreCase = true) ||
            message.contains("initializer required", ignoreCase = true) ||
            message.contains("unexpected tokens", ignoreCase = true)
        ) {
            DiagnosticCategory.SYNTAX
        } else {
            DiagnosticCategory.TYPE
        }
}

internal fun Iterable<WorkerDiagnostic>.bounded(limits: WorkerLimits): List<WorkerDiagnostic> {
    val result = mutableListOf<WorkerDiagnostic>()
    var remaining = limits.diagnosticTextBytes
    for (diagnostic in this) {
        if (result.size >= limits.diagnostics || remaining <= 0) break
        val message = truncateUtf8(diagnostic.message, remaining)
        remaining -= message.encodeToByteArray().size
        result += diagnostic.copy(message = message)
    }
    return result
}

internal fun truncateUtf8(
    value: String,
    maximumBytes: Int,
): String {
    val result = StringBuilder()
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val text = String(Character.toChars(codePoint))
        val encoded = text.encodeToByteArray().size
        if (bytes + encoded > maximumBytes) break
        result.append(text)
        bytes += encoded
        index += Character.charCount(codePoint)
    }
    return result.toString()
}
