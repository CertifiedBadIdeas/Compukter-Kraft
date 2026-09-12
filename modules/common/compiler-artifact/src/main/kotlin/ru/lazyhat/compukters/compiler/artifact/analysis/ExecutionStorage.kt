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

package ru.lazyhat.compukters.compiler.artifact.analysis

import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
import ru.lazyhat.compukters.compiler.artifact.model.Module

object ExecutionStorage {
    fun requiredStackBytes(
        modules: List<Module>,
        maximumCallDepth: UInt,
    ): UInt {
        val maximumFrameBytes =
            modules
                .asSequence()
                .flatMap { module -> module.functions.asSequence() }
                .filterNot { function -> FunctionFlag.ABSTRACT in function.flags }
                .maxOfOrNull { function -> compactFrameBytes(function.values) } ?: 0uL
        require(
            maximumFrameBytes <= UInt.MAX_VALUE.toULong() / maximumCallDepth.toULong(),
        ) { "required frame storage exceeds u32" }
        return (maximumFrameBytes * maximumCallDepth.toULong()).toUInt()
    }

    private fun compactFrameBytes(values: List<FunctionValue>): ULong {
        var offset = 0uL
        values.forEach { value ->
            value.physicalShape.components.forEach { component ->
                val alignment = component.alignment.toULong()
                require(offset <= ULong.MAX_VALUE - (alignment - 1uL)) { "physical frame alignment overflow" }
                offset = (offset + alignment - 1uL) and (alignment - 1uL).inv()
                require(offset <= ULong.MAX_VALUE - component.byteSize.toULong()) { "physical frame size overflow" }
                offset += component.byteSize
            }
        }
        require(offset <= ULong.MAX_VALUE - 7uL) { "physical frame alignment overflow" }
        return (offset + 7uL) and 7uL.inv()
    }
}
