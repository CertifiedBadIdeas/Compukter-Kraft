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

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TerminalCapabilityTest {
    @Test
    fun `output replaces unpaired UTF-16 and flushes before acknowledging`() =
        runBlocking {
            val output = FlushingOutput()
            val terminal = TerminalCapability(ByteArrayInputStream(byteArrayOf()), output)

            val response = terminal.invoke(request(0, VmValue.StringValue("A\ud800B")))

            assertEquals(HostResponse.UnitSuccess, response)
            assertContentEquals("A\ufffdB".encodeToByteArray(), output.toByteArray())
            assertEquals(1, output.flushes)
        }

    @Test
    fun `input replaces invalid UTF-8 removes CRLF and reports EOF`() =
        runBlocking {
            val input = ByteArrayInputStream(byteArrayOf(0x41, 0xc3.toByte(), 0x0d, 0x0a))
            val terminal = TerminalCapability(input, ByteArrayOutputStream())

            assertEquals(HostResponse.StringSuccess("A\ufffd"), terminal.invoke(request(2)))
            assertEquals(HostResponse.Failure(HostFailureKind.END_OF_FILE, 0), terminal.invoke(request(2)))
        }

    @Test
    fun `registry dispatches only an exact capability identity`() =
        runBlocking {
            val terminal = TerminalCapability(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
            val registry = CapabilityRegistry(listOf(terminal))

            assertEquals(HostResponse.UnitSuccess, registry.dispatch(request(0, VmValue.StringValue("ok"))))
            assertEquals(
                HostResponse.Failure(HostFailureKind.UNAVAILABLE, 0),
                registry.dispatch(request(0, VmValue.StringValue("x"), namespace = "guest")),
            )
        }

    @Test
    fun `registry permits a capability to suspend before responding`() =
        runBlocking {
            val identity = CapabilityIdentity("addon", "delayed", 1, 0)
            val delayed =
                object : IdentifiedHostCapability {
                    override val identity = identity

                    override suspend fun invoke(request: VmHostRequest): HostResponse {
                        yield()
                        return HostResponse.UnitSuccess
                    }
                }

            assertEquals(
                HostResponse.UnitSuccess,
                CapabilityRegistry(listOf(delayed)).dispatch(
                    VmHostRequest(1, identity, 0, emptyList()),
                ),
            )
        }

    @Test
    fun `terminal enforces input and output byte limits before returning success`() =
        runBlocking {
            val output = ByteArrayOutputStream()
            val outputLimited =
                TerminalCapability(
                    ByteArrayInputStream(byteArrayOf()),
                    output,
                    TerminalLimits(maximumOutputBytes = 2),
                )
            val inputLimited =
                TerminalCapability(
                    ByteArrayInputStream("abc\n".encodeToByteArray()),
                    ByteArrayOutputStream(),
                    TerminalLimits(maximumInputLineBytes = 2),
                )

            assertEquals(
                HostResponse.Failure(HostFailureKind.OTHER, 3),
                outputLimited.invoke(request(0, VmValue.StringValue("abc"))),
            )
            assertEquals(0, output.size())
            assertEquals(HostResponse.Failure(HostFailureKind.OTHER, 2), inputLimited.invoke(request(2)))
        }

    private fun request(
        operation: Int,
        vararg arguments: VmValue,
        namespace: String = "compukter",
    ) = VmHostRequest(
        id = 1,
        capability = CapabilityIdentity(namespace, "terminal", 1, 0),
        operation = operation,
        arguments = arguments.toList(),
    )

    private class FlushingOutput : ByteArrayOutputStream() {
        var flushes = 0

        override fun flush() {
            flushes++
            super.flush()
        }
    }
}
