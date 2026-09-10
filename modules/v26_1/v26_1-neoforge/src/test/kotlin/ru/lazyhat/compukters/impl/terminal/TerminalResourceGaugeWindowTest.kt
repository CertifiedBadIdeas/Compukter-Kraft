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
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalResourceGaugeWindowTest {
    @Test
    fun `consecutive samples expose semantic budget delta and current capacities`() {
        val window = TerminalResourceGaugeWindow()

        val first = window.accept(snapshot(fixed = 100, dynamic = 20, granted = 200))
        val second =
            window.accept(
                snapshot(
                    fixed = 150,
                    dynamic = 30,
                    granted = 300,
                    heapUsed = 40,
                    heapCapacity = 100,
                    diskUsed = 3,
                    diskCapacity = 4,
                ),
            )

        assertNull(first.vmBudgetBasisPoints)
        assertEquals(6_000, second.vmBudgetBasisPoints)
        assertEquals(4_000, second.heapBasisPoints)
        assertEquals(7_500, second.diskBasisPoints)
        assertEquals(TerminalResourceActivity.ACTIVE, second.activity)
    }

    @Test
    fun `maintenance and lifecycle map to deterministic activity states`() {
        val window = TerminalResourceGaugeWindow()
        window.accept(snapshot())

        assertEquals(
            TerminalResourceActivity.COLLECTING,
            window.accept(snapshot(granted = 200, maintenance = 1)).activity,
        )
        assertEquals(
            TerminalResourceActivity.WAITING_INPUT,
            window.accept(snapshot(state = ProgramRuntimeState.WaitingForInput, granted = 300, maintenance = 2)).activity,
        )
        assertEquals(
            TerminalResourceActivity.WAITING_COMPILER,
            window.accept(snapshot(state = ProgramRuntimeState.WaitingForCompiler, granted = 400)).activity,
        )
        assertEquals(
            TerminalResourceActivity.HALTED,
            window.accept(snapshot(state = ProgramRuntimeState.Halted(null), granted = 500)).activity,
        )
        assertEquals(
            TerminalResourceActivity.FAILED,
            window.accept(
                snapshot(
                    state =
                        ProgramRuntimeState.Failed(
                            ru.lazyhat.compukters.core.device.runtime.program.ProgramFailure.Bridge("failed"),
                        ),
                    granted = 600,
                ),
            ).activity,
        )
    }

    @Test
    fun `resets saturation and invalid intervals never publish a misleading budget`() {
        val window = TerminalResourceGaugeWindow()
        window.accept(snapshot(fixed = 10, granted = 20))
        assertNull(window.accept(snapshot(fixed = 5, granted = 10)).vmBudgetBasisPoints)
        assertNull(window.accept(snapshot(fixed = 30, granted = 20)).vmBudgetBasisPoints)
        assertNull(window.accept(snapshot(fixed = 40, granted = 30, saturated = true)).vmBudgetBasisPoints)

        window.reset()

        assertNull(window.accept(snapshot(fixed = Long.MAX_VALUE, dynamic = 1, granted = Long.MAX_VALUE)).vmBudgetBasisPoints)
    }

    @Test
    fun `zero and maximum capacities use bounded overflow safe ratios`() {
        val zero = TerminalResourceGaugeWindow().accept(snapshot(heapCapacity = 0, diskCapacity = 0))
        val maximum =
            TerminalResourceGaugeWindow().accept(
                snapshot(
                    heapUsed = Long.MAX_VALUE,
                    heapCapacity = Long.MAX_VALUE,
                    diskUsed = Long.MAX_VALUE,
                    diskCapacity = Long.MAX_VALUE,
                ),
            )

        assertNull(zero.heapBasisPoints)
        assertNull(zero.diskBasisPoints)
        assertEquals(10_000, maximum.heapBasisPoints)
        assertEquals(10_000, maximum.diskBasisPoints)
    }

    @Test
    fun `unavailable samples clear the rolling baseline`() {
        val window = TerminalResourceGaugeWindow()
        window.accept(snapshot(fixed = 10, granted = 20))

        val unavailable =
            window.accept(
                ProgramResourceSnapshot.Unavailable(ProgramRuntimeState.Idle, ProgramTickBudget()),
            )

        assertEquals(TerminalResourceActivity.UNAVAILABLE, unavailable.activity)
        assertNull(window.accept(snapshot(fixed = 20, granted = 40)).vmBudgetBasisPoints)
    }

    private fun snapshot(
        state: ProgramRuntimeState = ProgramRuntimeState.Running,
        fixed: Long = 0,
        dynamic: Long = 0,
        granted: Long = 100,
        maintenance: Long = 0,
        heapUsed: Long = 0,
        heapCapacity: Long = 100,
        diskUsed: Long = 0,
        diskCapacity: Long = 100,
        saturated: Boolean = false,
    ): ProgramResourceSnapshot.Available =
        ProgramResourceSnapshot.Available(
            state = state,
            configuredBudget = ProgramTickBudget(),
            grantedGuestUnits = granted,
            grantedMaintenanceUnits = maintenance,
            fixedGuestUnits = fixed,
            dynamicGuestUnits = dynamic,
            maintenanceUnits = maintenance,
            enteredBlocks = 0,
            executedInstructions = 0,
            heapCapacityBytes = heapCapacity,
            heapUsedBytes = heapUsed,
            liveObjects = 0,
            mutableExecutionResidentBytes = 0,
            filesystemLogicalBytes = diskUsed,
            filesystemLogicalCapacityBytes = diskCapacity,
            filesystemNodes = 0,
            filesystemNodeCapacity = 0,
            countersSaturated = saturated,
        )
}
