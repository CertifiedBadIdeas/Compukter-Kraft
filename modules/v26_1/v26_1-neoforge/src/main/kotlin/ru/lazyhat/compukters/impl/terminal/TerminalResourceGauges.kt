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

package ru.lazyhat.compukters.impl.terminal

import ru.lazyhat.compukters.core.device.runtime.program.ProgramResourceSnapshot
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import java.math.BigInteger

internal enum class TerminalResourceActivity {
    ACTIVE,
    COLLECTING,
    WAITING_INPUT,
    WAITING_COMPILER,
    HALTED,
    FAILED,
    UNAVAILABLE,
}

internal data class TerminalResourceGauges(
    val vmBudgetBasisPoints: Int?,
    val heapUsedBytes: Long,
    val heapCapacityBytes: Long,
    val diskUsedBytes: Long,
    val diskCapacityBytes: Long,
    val activity: TerminalResourceActivity,
    val countersSaturated: Boolean,
) {
    init {
        require(vmBudgetBasisPoints == null || vmBudgetBasisPoints in 0..BASIS_POINTS) {
            "VM budget gauge must contain 0..10000 basis points"
        }
        require(heapUsedBytes in 0..heapCapacityBytes) { "heap usage exceeds capacity" }
        require(diskUsedBytes in 0..diskCapacityBytes) { "disk usage exceeds capacity" }
    }

    val heapBasisPoints: Int?
        get() = ratioBasisPoints(heapUsedBytes, heapCapacityBytes)

    val diskBasisPoints: Int?
        get() = ratioBasisPoints(diskUsedBytes, diskCapacityBytes)

    companion object {
        const val BASIS_POINTS = 10_000

        fun unavailable(activity: TerminalResourceActivity = TerminalResourceActivity.UNAVAILABLE) =
            TerminalResourceGauges(null, 0, 0, 0, 0, activity, false)
    }
}

internal class TerminalResourceGaugeWindow {
    private var previous: ProgramResourceSnapshot.Available? = null

    fun accept(snapshot: ProgramResourceSnapshot): TerminalResourceGauges =
        when (snapshot) {
            is ProgramResourceSnapshot.Available -> acceptAvailable(snapshot)
            is ProgramResourceSnapshot.Unavailable -> {
                previous = null
                TerminalResourceGauges.unavailable(snapshot.state.activity())
            }
        }

    fun reset() {
        previous = null
    }

    private fun acceptAvailable(current: ProgramResourceSnapshot.Available): TerminalResourceGauges {
        val baseline = previous
        previous = current
        val interval = baseline?.let { interval(it, current) }
        val activity =
            if (current.state == ProgramRuntimeState.Running && interval?.maintenanceUnits?.let { it > 0 } == true) {
                TerminalResourceActivity.COLLECTING
            } else {
                current.state.activity()
            }
        return TerminalResourceGauges(
            vmBudgetBasisPoints = interval?.budgetBasisPoints,
            heapUsedBytes = current.heapUsedBytes,
            heapCapacityBytes = current.heapCapacityBytes,
            diskUsedBytes = current.filesystemLogicalBytes,
            diskCapacityBytes = current.filesystemLogicalCapacityBytes,
            activity = activity,
            countersSaturated = current.countersSaturated,
        )
    }

    private fun interval(
        previous: ProgramResourceSnapshot.Available,
        current: ProgramResourceSnapshot.Available,
    ): ResourceInterval? {
        if (previous.countersSaturated || current.countersSaturated) return null
        val previousConsumed = addExactOrNull(previous.fixedGuestUnits, previous.dynamicGuestUnits) ?: return null
        val currentConsumed = addExactOrNull(current.fixedGuestUnits, current.dynamicGuestUnits) ?: return null
        val consumed = differenceOrNull(currentConsumed, previousConsumed) ?: return null
        val granted = differenceOrNull(current.grantedGuestUnits, previous.grantedGuestUnits) ?: return null
        val maintenance = differenceOrNull(current.maintenanceUnits, previous.maintenanceUnits) ?: return null
        if (granted == 0L || consumed > granted) return ResourceInterval(null, maintenance)
        return ResourceInterval(ratioBasisPoints(consumed, granted), maintenance)
    }

    private data class ResourceInterval(
        val budgetBasisPoints: Int?,
        val maintenanceUnits: Long,
    )
}

private fun ProgramRuntimeState.activity(): TerminalResourceActivity =
    when (this) {
        ProgramRuntimeState.Running -> TerminalResourceActivity.ACTIVE
        ProgramRuntimeState.WaitingForInput -> TerminalResourceActivity.WAITING_INPUT
        ProgramRuntimeState.WaitingForCompiler -> TerminalResourceActivity.WAITING_COMPILER
        is ProgramRuntimeState.Halted -> TerminalResourceActivity.HALTED
        is ProgramRuntimeState.Failed -> TerminalResourceActivity.FAILED
        ProgramRuntimeState.Idle,
        ProgramRuntimeState.Closed,
        -> TerminalResourceActivity.UNAVAILABLE
    }

private fun addExactOrNull(
    first: Long,
    second: Long,
): Long? = if (first > Long.MAX_VALUE - second) null else first + second

private fun differenceOrNull(
    current: Long,
    previous: Long,
): Long? = if (current < previous) null else current - previous

private fun ratioBasisPoints(
    used: Long,
    capacity: Long,
): Int? {
    if (capacity == 0L) return null
    return BigInteger
        .valueOf(used)
        .multiply(BigInteger.valueOf(TerminalResourceGauges.BASIS_POINTS.toLong()))
        .divide(BigInteger.valueOf(capacity))
        .toInt()
}
