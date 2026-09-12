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

package ru.lazyhat.compukters.compiler.project

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.charset.CharacterCodingException
import java.util.Collections

data class ProjectSource(
    val path: VirtualSourcePath,
    val content: BinaryValue,
)

class ProjectSnapshot private constructor(
    sources: List<ProjectSource>,
) {
    val sources: List<ProjectSource> =
        Collections.unmodifiableList(
            sources.map { source ->
                ProjectSource(source.path, BinaryValue.of(source.content.toByteArray()))
            },
        )

    val totalSourceBytes: Long = this.sources.sumOf { it.content.size.toLong() }

    override fun equals(other: Any?): Boolean = other is ProjectSnapshot && sources == other.sources

    override fun hashCode(): Int = sources.hashCode()

    companion object {
        fun of(
            sources: List<ProjectSource>,
            limits: WorkerLimits,
        ): ProjectSnapshot {
            require(sources.isNotEmpty()) { "project must contain at least one Kotlin source" }
            require(sources.size <= limits.sourceFiles) { "project source count exceeds limit" }
            var previous: VirtualSourcePath? = null
            var total = 0L
            sources.forEach { source ->
                VirtualSourcePath.kotlin(source.path.value)
                val prior = previous
                require(prior == null || compareUtf8(prior.value, source.path.value) < 0) {
                    "project sources must be uniquely ordered by canonical UTF-8 path"
                }
                previous = source.path
                require(source.content.size <= limits.sourceFileBytes) { "project source exceeds per-file limit" }
                try {
                    source.content.decodeUtf8()
                } catch (exception: CharacterCodingException) {
                    throw IllegalArgumentException("project source must be strict UTF-8", exception)
                }
                total = Math.addExact(total, source.content.size.toLong())
                require(total <= limits.sourceBytes.toLong()) { "project sources exceed total limit" }
            }
            return ProjectSnapshot(sources)
        }

        internal fun compareUtf8(
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
    }
}
