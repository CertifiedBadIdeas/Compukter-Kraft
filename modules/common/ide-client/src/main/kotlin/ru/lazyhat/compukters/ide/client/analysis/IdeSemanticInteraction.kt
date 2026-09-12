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

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.EditorExpressionInfo
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

data class IdeSemanticAnchor(
    val identity: AnalysisSnapshotIdentity,
    val path: VirtualSourcePath,
    val documentRevision: Long,
    val offsetUtf16: Int,
    val tokenRange: EditorRange,
) {
    init {
        VirtualSourcePath.kotlin(path.value)
        require(documentRevision >= 0) { "semantic anchor revision must not be negative" }
        require(tokenRange.length > 0) { "semantic anchor token range must not be empty" }
        require(offsetUtf16 in tokenRange.startUtf16 until tokenRange.endUtf16) {
            "semantic anchor offset must belong to its token range"
        }
    }
}

sealed interface IdeDeclarationTarget {
    val range: EditorRange

    data class Project(
        val path: ProjectPath,
        override val range: EditorRange,
    ) : IdeDeclarationTarget {
        init {
            require(range.length > 0) { "project declaration range must not be empty" }
        }
    }

    data class AttachedSource(
        val module: AnalysisModuleIdentity,
        val path: VirtualSourcePath,
        override val range: EditorRange,
    ) : IdeDeclarationTarget {
        init {
            VirtualSourcePath.kotlin(path.value)
            require(range.length > 0) { "attached declaration range must not be empty" }
        }
    }
}

sealed interface IdeDeclarationOutcome {
    data object NotFound : IdeDeclarationOutcome

    data class SourceUnavailable(
        val module: AnalysisModuleIdentity,
    ) : IdeDeclarationOutcome

    class Targets(
        val anchor: IdeSemanticAnchor,
        values: List<IdeDeclarationTarget>,
    ) : IdeDeclarationOutcome {
        val values: List<IdeDeclarationTarget> = immutableList(values)

        init {
            require(values.isNotEmpty()) { "declaration targets must not be empty" }
        }
    }

    data class Failed(
        val detail: String,
    ) : IdeDeclarationOutcome {
        init {
            require(detail.isNotEmpty()) { "declaration failure detail must not be empty" }
        }
    }
}

sealed interface IdeSemanticInteraction {
    data object None : IdeSemanticInteraction

    data class Hover(
        val anchor: IdeSemanticAnchor,
        val info: EditorExpressionInfo,
    ) : IdeSemanticInteraction

    class Link(
        val anchor: IdeSemanticAnchor,
        locations: List<DeclarationLocation>,
    ) : IdeSemanticInteraction {
        val locations: List<DeclarationLocation> = immutableList(locations)

        init {
            require(locations.isNotEmpty()) { "semantic link locations must not be empty" }
        }
    }

    class Chooser(
        val anchor: IdeSemanticAnchor,
        targets: List<IdeDeclarationTarget>,
        val selectedIndex: Int,
        maximumTargets: Int,
    ) : IdeSemanticInteraction {
        val targets: List<IdeDeclarationTarget> = immutableList(targets)

        init {
            require(maximumTargets > 0) { "declaration target limit must be positive" }
            require(targets.isNotEmpty()) { "declaration chooser must not be empty" }
            require(targets.size <= maximumTargets) { "declaration target count exceeds limit" }
            require(selectedIndex in targets.indices) { "declaration selection is outside target list" }
        }
    }
}

class IdeAttachedSourceCatalog private constructor(
    private val sources: Map<AnalysisModuleIdentity, Map<VirtualSourcePath, String>>,
) {
    fun text(
        module: AnalysisModuleIdentity,
        path: VirtualSourcePath,
    ): String? = sources[module]?.get(path)

    companion object {
        fun empty(): IdeAttachedSourceCatalog = IdeAttachedSourceCatalog(emptyMap())

        fun of(
            sources: Map<AnalysisModuleIdentity, Map<VirtualSourcePath, String>>,
            maximumModules: Int,
            maximumFiles: Int,
            maximumFileBytes: Int,
            maximumTotalBytes: Int,
        ): IdeAttachedSourceCatalog {
            require(maximumModules >= 0) { "attached source module limit must not be negative" }
            require(maximumFiles >= 0) { "attached source file limit must not be negative" }
            require(maximumFileBytes >= 0) { "attached source file byte limit must not be negative" }
            require(maximumTotalBytes >= 0) { "attached source total byte limit must not be negative" }
            require(sources.size <= maximumModules) { "attached source module count exceeds limit" }
            var files = 0
            var totalBytes = 0L
            val admitted = linkedMapOf<AnalysisModuleIdentity, Map<VirtualSourcePath, String>>()
            sources.forEach { (module, moduleSources) ->
                val copied = linkedMapOf<VirtualSourcePath, String>()
                moduleSources.forEach { (path, text) ->
                    VirtualSourcePath.kotlin(path.value)
                    files = Math.incrementExact(files)
                    require(files <= maximumFiles) { "attached source file count exceeds limit" }
                    val bytes = strictSourceBytes(text)
                    require(bytes <= maximumFileBytes) { "attached source file exceeds byte limit" }
                    totalBytes = Math.addExact(totalBytes, bytes.toLong())
                    require(totalBytes <= maximumTotalBytes.toLong()) { "attached source bytes exceed limit" }
                    require(copied.put(path, text) == null) { "duplicate attached source path: ${path.value}" }
                }
                admitted[module] = Collections.unmodifiableMap(copied)
            }
            return IdeAttachedSourceCatalog(Collections.unmodifiableMap(admitted))
        }
    }
}

object KotlinSourceTokenRange {
    fun find(
        source: String,
        offsetUtf16: Int,
    ): EditorRange? {
        if (offsetUtf16 !in source.indices || splitsSurrogatePair(source, offsetUtf16)) return null
        backtickRange(source, offsetUtf16)?.let { return it }
        val codePoint = source.codePointAt(offsetUtf16)
        if (!isIdentifierPart(codePoint)) return null

        var start = offsetUtf16
        while (start > 0) {
            val previous = source.codePointBefore(start)
            if (!isIdentifierPart(previous)) break
            start -= Character.charCount(previous)
        }
        var end = offsetUtf16 + Character.charCount(codePoint)
        while (end < source.length) {
            val next = source.codePointAt(end)
            if (!isIdentifierPart(next)) break
            end += Character.charCount(next)
        }
        return EditorRange(start, end)
    }

    private fun backtickRange(
        source: String,
        offsetUtf16: Int,
    ): EditorRange? {
        val start = source.lastIndexOf('`', offsetUtf16)
        if (start < 0 || source.substring(start, offsetUtf16).any { it == '\n' || it == '\r' }) return null
        val end = source.indexOf('`', start + 1)
        if (end < offsetUtf16 || end == start + 1) return null
        return EditorRange(start, end + 1)
    }

    private fun splitsSurrogatePair(
        source: String,
        offsetUtf16: Int,
    ): Boolean =
        offsetUtf16 > 0 &&
            source[offsetUtf16].isLowSurrogate() &&
            source[offsetUtf16 - 1].isHighSurrogate()

    private fun isIdentifierPart(codePoint: Int): Boolean = codePoint != '$'.code && Character.isJavaIdentifierPart(codePoint)
}

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

private fun strictSourceBytes(value: String): Int =
    try {
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
            .remaining()
    } catch (exception: CharacterCodingException) {
        throw IllegalArgumentException("attached source must be strict UTF-8", exception)
    }
