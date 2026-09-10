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

import ru.lazyhat.compukters.lang.runtime.vm.GuestTrap
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.QuotaKind
import ru.lazyhat.compukters.lang.runtime.vm.VmFault
import ru.lazyhat.compukters.lang.runtime.vm.VmValue

data class ProgramTickBudget(
    val guestBudgetPerAdvance: Int = 4_096,
    val maintenanceBudgetPerAdvance: Int = 256,
    val maximumAdvancesPerTick: Int = 32,
    val hostRequestsPerTick: Int = 16,
) {
    init {
        require(guestBudgetPerAdvance > 0) { "guest budget per advance must be positive" }
        require(maintenanceBudgetPerAdvance > 0) { "maintenance budget per advance must be positive" }
        require(maximumAdvancesPerTick > 0) { "maximum advances per tick must be positive" }
        require(hostRequestsPerTick > 0) { "host requests per tick must be positive" }
    }
}

sealed interface ProgramResourceSnapshot {
    val state: ProgramRuntimeState
    val configuredBudget: ProgramTickBudget

    data class Available(
        override val state: ProgramRuntimeState,
        override val configuredBudget: ProgramTickBudget,
        val grantedGuestUnits: Long,
        val grantedMaintenanceUnits: Long,
        val fixedGuestUnits: Long,
        val dynamicGuestUnits: Long,
        val maintenanceUnits: Long,
        val enteredBlocks: Long,
        val executedInstructions: Long,
        val heapCapacityBytes: Long,
        val heapUsedBytes: Long,
        val liveObjects: Long,
        val mutableExecutionResidentBytes: Long,
        val filesystemLogicalBytes: Long,
        val filesystemLogicalCapacityBytes: Long,
        val filesystemNodes: Long,
        val filesystemNodeCapacity: Long,
        val countersSaturated: Boolean,
    ) : ProgramResourceSnapshot {
        init {
            require(
                listOf(
                    grantedGuestUnits,
                    grantedMaintenanceUnits,
                    fixedGuestUnits,
                    dynamicGuestUnits,
                    maintenanceUnits,
                    enteredBlocks,
                    executedInstructions,
                    heapCapacityBytes,
                    heapUsedBytes,
                    liveObjects,
                    mutableExecutionResidentBytes,
                    filesystemLogicalBytes,
                    filesystemLogicalCapacityBytes,
                    filesystemNodes,
                    filesystemNodeCapacity,
                ).all { it >= 0 },
            ) { "resource snapshot values must not be negative" }
            require(heapUsedBytes <= heapCapacityBytes) { "heap usage exceeds capacity" }
            require(filesystemLogicalBytes <= filesystemLogicalCapacityBytes) { "filesystem usage exceeds capacity" }
            require(filesystemNodes <= filesystemNodeCapacity) { "filesystem node usage exceeds capacity" }
        }
    }

    data class Unavailable(
        override val state: ProgramRuntimeState,
        override val configuredBudget: ProgramTickBudget,
    ) : ProgramResourceSnapshot
}

sealed interface ProgramRuntimeState {
    data object Idle : ProgramRuntimeState

    data object Running : ProgramRuntimeState

    data object WaitingForInput : ProgramRuntimeState

    data object WaitingForCompiler : ProgramRuntimeState

    data class Halted(
        val value: VmValue?,
    ) : ProgramRuntimeState

    data class Failed(
        val failure: ProgramFailure,
    ) : ProgramRuntimeState

    data object Closed : ProgramRuntimeState
}

sealed interface ProgramStartResult {
    data object Started : ProgramStartResult

    data class Rejected(
        val failure: ProgramFailure,
    ) : ProgramStartResult

    data object Closed : ProgramStartResult
}

sealed interface ProgramFailure {
    data object Verification : ProgramFailure

    data class Admission(
        val code: Int,
    ) : ProgramFailure

    data class Start(
        val code: Int,
    ) : ProgramFailure

    data class Bridge(
        val detail: String,
    ) : ProgramFailure

    data class Allocation(
        val collectionAttempted: Boolean,
    ) : ProgramFailure

    data class Quota(
        val kind: QuotaKind,
        val limit: Long,
        val consumed: Long,
    ) : ProgramFailure

    data class Trap(
        val trap: GuestTrap,
    ) : ProgramFailure

    data class Fault(
        val fault: VmFault,
    ) : ProgramFailure

    data class Host(
        val kind: HostFailureKind,
        val code: Long,
    ) : ProgramFailure
}
