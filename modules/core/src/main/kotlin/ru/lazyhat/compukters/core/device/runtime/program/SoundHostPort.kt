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

package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind

data class SoundRequest(
    val note: Int,
    val volume: Int,
) {
    init {
        require(note in 0..24) { "sound note must be between 0 and 24" }
        require(volume in 1..100) { "sound volume must be between 1 and 100" }
    }
}

fun interface SoundHostPort {
    fun emit(requests: List<SoundRequest>): SoundCommitResult
}

sealed interface SoundCommitResult {
    class Completed(
        admissions: List<Boolean>,
    ) : SoundCommitResult {
        val admissions: List<Boolean> = admissions.toList()
    }

    data object Deferred : SoundCommitResult

    data class Failed(
        val kind: HostFailureKind,
        val code: Long,
    ) : SoundCommitResult {
        init {
            require(code in 0..UInt.MAX_VALUE.toLong()) { "sound failure code must fit u32" }
        }
    }
}
