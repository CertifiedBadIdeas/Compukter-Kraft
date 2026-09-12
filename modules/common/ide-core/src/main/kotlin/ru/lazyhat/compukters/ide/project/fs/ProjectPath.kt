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

package ru.lazyhat.compukters.ide.project.fs

import ru.lazyhat.compukters.ide.project.TomlSupport

class ProjectPath private constructor(
    val value: String,
    internal val components: List<String>,
) {
    val isKotlinSource: Boolean
        get() = components.size >= 2 && components.first() == "src" && components.last().endsWith(".kt")

    override fun equals(other: Any?): Boolean = other is ProjectPath && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        fun file(value: String): ProjectPath = parse(value)

        fun source(value: String): ProjectPath =
            file(value).also { path ->
                require(path.isKotlinSource) { "source path must name a Kotlin file below src" }
            }

        internal fun direct(value: String): ProjectPath {
            val path = file(value)
            require(path.components.size == 1) { "path must contain exactly one component" }
            return path
        }

        private fun parse(value: String): ProjectPath {
            require(value.isNotEmpty() && !value.startsWith('/') && '\\' !in value) { "project path must be relative" }
            val components = value.split('/')
            require(components.none { it.isEmpty() || it == "." || it == ".." }) { "project path is not canonical" }
            components.forEach { component ->
                val bytes =
                    try {
                        TomlSupport.strictUtf8(component)
                    } catch (exception: Exception) {
                        throw IllegalArgumentException("project path must be strict UTF-8", exception)
                    }
                require(bytes.size <= 255) { "project path component exceeds 255 UTF-8 bytes" }
                require(component.codePoints().noneMatch(Character::isISOControl)) {
                    "project path cannot contain control characters"
                }
            }
            return ProjectPath(components.joinToString("/"), components.toList())
        }
    }
}
