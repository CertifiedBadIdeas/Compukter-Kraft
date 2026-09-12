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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaygroundOptionsTest {
    @Test
    fun `parses project emit and debug in any order`() {
        assertEquals(
            PlaygroundOptions(Path.of("example"), Path.of("out.cpkt"), debug = true),
            PlaygroundOptions.parse(listOf("--debug", "example", "--emit", "out.cpkt")),
        )
    }

    @Test
    fun `rejects missing duplicate and unknown arguments`() {
        listOf(
            emptyList(),
            listOf("a", "b"),
            listOf("a", "--emit"),
            listOf("a", "--emit", "one", "--emit", "two"),
            listOf("a", "--debug", "--debug"),
            listOf("a", "--unknown"),
        ).forEach { arguments ->
            assertFailsWith<PlaygroundUsageException>(arguments.toString()) {
                PlaygroundOptions.parse(arguments)
            }
        }
    }
}
