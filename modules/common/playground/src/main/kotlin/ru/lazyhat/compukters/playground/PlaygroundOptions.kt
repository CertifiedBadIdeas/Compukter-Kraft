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

package ru.lazyhat.compukters.playground

import java.nio.file.Path

data class PlaygroundOptions(
    val project: Path,
    val emit: Path?,
    val debug: Boolean,
) {
    companion object {
        fun parse(arguments: List<String>): PlaygroundOptions {
            var project: Path? = null
            var emit: Path? = null
            var debug = false
            var index = 0
            while (index < arguments.size) {
                when (val argument = arguments[index]) {
                    "--debug" -> {
                        if (debug) usage("--debug may be specified only once")
                        debug = true
                    }

                    "--emit" -> {
                        if (emit != null) usage("--emit may be specified only once")
                        val value = arguments.getOrNull(++index) ?: usage("--emit requires a path")
                        if (value.startsWith("--")) usage("--emit requires a path")
                        emit = path(value)
                    }

                    else -> {
                        if (argument.startsWith("--")) usage("unknown option: $argument")
                        if (project != null) usage("exactly one project directory is required")
                        project = path(argument)
                    }
                }
                index++
            }
            return PlaygroundOptions(project ?: usage("project directory is required"), emit, debug)
        }

        private fun usage(message: String): Nothing = throw PlaygroundUsageException(message)

        private fun path(value: String): Path {
            if (value.isBlank()) usage("path must not be blank")
            return try {
                Path.of(value)
            } catch (exception: Exception) {
                throw PlaygroundUsageException("invalid path: $value").apply { initCause(exception) }
            }
        }
    }
}

class PlaygroundUsageException(
    message: String,
) : IllegalArgumentException(message)
