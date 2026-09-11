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

package ru.lazyhat.compukters.lang.runtime.capability

import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest

sealed interface HostResponse {
    data object UnitSuccess : HostResponse

    data class BoolSuccess(
        val value: Boolean,
    ) : HostResponse

    data class StringSuccess(
        val value: String,
    ) : HostResponse

    data class Failure(
        val kind: HostFailureKind,
        val code: Long,
    ) : HostResponse
}

fun interface HostCapability {
    suspend fun invoke(request: VmHostRequest): HostResponse
}

interface IdentifiedHostCapability : HostCapability {
    val identity: CapabilityIdentity
}

class CapabilityRegistry(
    capabilities: List<IdentifiedHostCapability>,
) {
    private val capabilities =
        capabilities.associateBy(IdentifiedHostCapability::identity).also {
            require(it.size == capabilities.size) { "duplicate host capability identity" }
        }

    suspend fun dispatch(request: VmHostRequest): HostResponse =
        capabilities[request.capability]?.invoke(request)
            ?: HostResponse.Failure(HostFailureKind.UNAVAILABLE, 0)
}
