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

package ru.lazyhat.compukters.compiler.k2.engine

import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.IntrinsicBlockingMode

internal data class LoweredCapabilityIdentity(
    val namespace: String,
    val name: String,
    val abiMajor: UShort,
    val abiMinor: UShort,
    val operationCount: UInt,
) : Comparable<LoweredCapabilityIdentity> {
    override fun compareTo(other: LoweredCapabilityIdentity): Int =
        compareValuesBy(this, other, { it.namespace }, { it.name }, { it.abiMajor }, { it.abiMinor }, { it.operationCount })
}

internal data class LoweredCapabilityOperation(
    val capability: LoweredCapabilityIdentity,
    val operation: UInt,
    val blocking: IntrinsicBlockingMode,
    val terminal: Boolean,
)
