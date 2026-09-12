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
 */

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.project.fs.ProjectPath

sealed interface IdePromptKind {
    data object CreateProject : IdePromptKind

    data object CreateText : IdePromptKind

    data object CreateDirectory : IdePromptKind

    data class Rename(
        val source: ProjectPath,
    ) : IdePromptKind
}

data class IdePromptState(
    val kind: IdePromptKind,
    val value: String,
    val error: String? = null,
)

class IdePromptController {
    var state: IdePromptState? = null
        private set

    fun open(
        kind: IdePromptKind,
        initial: String = "",
    ) {
        state = IdePromptState(kind, bounded(initial))
    }

    fun type(text: String): Boolean {
        val current = state ?: return false
        val admitted = appendBounded(current.value, text)
        state = current.copy(value = admitted, error = null)
        return true
    }

    fun backspace(): Boolean {
        val current = state ?: return false
        if (current.value.isEmpty()) return true
        val end = current.value.offsetByCodePoints(current.value.length, -1)
        state = current.copy(value = current.value.substring(0, end), error = null)
        return true
    }

    fun cancel(): Boolean {
        if (state == null) return false
        state = null
        return true
    }

    fun confirm(): IdeCommand? {
        val current = state ?: return null
        val value = current.value.trim()
        if (value.isEmpty()) {
            state = current.copy(error = "Name must not be blank")
            return null
        }
        val command =
            runCatching {
                when (val kind = current.kind) {
                    IdePromptKind.CreateProject -> IdeCommand.CreateProject(value)
                    IdePromptKind.CreateText -> IdeCommand.CreateText(ProjectPath.file(value))
                    IdePromptKind.CreateDirectory -> IdeCommand.CreateDirectory(ProjectPath.file(value))
                    is IdePromptKind.Rename -> IdeCommand.Rename(kind.source, ProjectPath.file(value))
                }
            }.getOrElse { failure ->
                state = current.copy(error = failure.message ?: "Invalid name")
                return null
            }
        state = null
        return command
    }

    private fun bounded(value: String): String = appendBounded("", value)

    private fun appendBounded(
        prefix: String,
        suffix: String,
    ): String {
        val result = StringBuilder(minOf(MAXIMUM_CODE_UNITS, prefix.length + suffix.length))
        result.append(prefix)
        var offset = 0
        while (offset < suffix.length) {
            val codePoint = suffix.codePointAt(offset)
            val units = Character.charCount(codePoint)
            if (result.length + units > MAXIMUM_CODE_UNITS) break
            result.appendCodePoint(codePoint)
            offset += units
        }
        return result.toString()
    }

    private companion object {
        const val MAXIMUM_CODE_UNITS = 256
    }
}
