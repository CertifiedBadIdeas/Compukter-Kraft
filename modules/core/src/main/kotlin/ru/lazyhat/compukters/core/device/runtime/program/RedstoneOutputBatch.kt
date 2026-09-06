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

import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue

internal data class RedstoneOutputBatch(
    val packed: Int,
    val identities: List<VmHostRequestIdentity>,
) {
    companion object {
        private val CAPABILITY = CapabilityIdentity("compukter", "redstone", 1, 0)

        fun reduce(
            confirmed: Int,
            requests: List<VmHostRequest>,
        ): RedstoneOutputBatch {
            var candidate = RedstoneWire.requireOutputRegister(confirmed)
            require(requests.isNotEmpty()) { "redstone output batch must not be empty" }
            requests.forEach { request ->
                require(request.capability == CAPABILITY) { "request is not a redstone request" }
                candidate =
                    when (request.operation) {
                        6 -> {
                            require(request.arguments.size == 2) { "setOutput requires side and output" }
                            val side = request.arguments[0].i32("redstone side")
                            val output = request.arguments[1].i32("redstone output")
                            require(side in 0 until RedstoneWire.SIDE_COUNT) { "redstone side is out of range" }
                            require(output in 0..RedstoneWire.OUTPUT_MASK) { "redstone output is out of range" }
                            RedstoneWire.replaceOutput(candidate, side, output)
                        }

                        7 -> {
                            require(request.arguments.size == 1) { "setOutputs requires one packed register" }
                            RedstoneWire.requireOutputRegister(request.arguments[0].i32("redstone output register"))
                        }

                        else -> {
                            throw IllegalArgumentException("request is not a redstone output operation")
                        }
                    }
            }
            return RedstoneOutputBatch(candidate, requests.map(VmHostRequest::identity))
        }

        private fun VmValue.i32(label: String): Int = (this as? VmValue.I32)?.value ?: throw IllegalArgumentException("$label must be i32")
    }
}
