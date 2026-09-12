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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class TerminalLimits(
    val maximumInputLineBytes: Int = 4096,
    val maximumOutputBytes: Long = 1024L * 1024L,
) {
    init {
        require(maximumInputLineBytes > 0)
        require(maximumOutputBytes >= 0)
    }
}

class TerminalCapability(
    input: InputStream,
    private val output: OutputStream,
    private val limits: TerminalLimits = TerminalLimits(),
    private val blockingIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IdentifiedHostCapability {
    override val identity = CapabilityIdentity("compukter", "terminal", 1, 0)
    private val input = PushbackInputStream(input, 1)
    private var outputBytes = 0L

    override suspend fun invoke(request: VmHostRequest): HostResponse =
        try {
            withContext(blockingIoDispatcher) {
                when (request.operation) {
                    0 -> write(request, newline = false)
                    1 -> write(request, newline = true)
                    2 -> read(request)
                    else -> invalidRequest()
                }
            }
        } catch (_: InputLimitExceeded) {
            HostResponse.Failure(HostFailureKind.OTHER, INPUT_LIMIT_CODE)
        } catch (_: OutputLimitExceeded) {
            HostResponse.Failure(HostFailureKind.OTHER, OUTPUT_LIMIT_CODE)
        } catch (_: IOException) {
            HostResponse.Failure(HostFailureKind.INPUT_OUTPUT, 0)
        }

    private fun write(
        request: VmHostRequest,
        newline: Boolean,
    ): HostResponse {
        val value = (request.arguments.singleOrNull() as? VmValue.StringValue)?.value ?: return invalidRequest()
        val remaining = limits.maximumOutputBytes - outputBytes
        val minimumBytes = value.length.toLong() + if (newline) 1 else 0
        if (minimumBytes > remaining) throw OutputLimitExceeded
        val suffix = if (newline) "\n" else ""
        val bytes = sanitizeUtf16(value + suffix).encodeToByteArray()
        if (bytes.size.toLong() > remaining) throw OutputLimitExceeded
        output.write(bytes)
        output.flush()
        outputBytes += bytes.size
        return HostResponse.UnitSuccess
    }

    private fun read(request: VmHostRequest): HostResponse {
        if (request.arguments.isNotEmpty()) return invalidRequest()
        val bytes = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            when (byte) {
                -1 -> {
                    if (bytes.size() == 0) return HostResponse.Failure(HostFailureKind.END_OF_FILE, 0)
                    break
                }

                '\n'.code -> {
                    break
                }

                '\r'.code -> {
                    val following = input.read()
                    if (following != -1 && following != '\n'.code) input.unread(following)
                    break
                }

                else -> {
                    if (bytes.size() >= limits.maximumInputLineBytes) throw InputLimitExceeded
                    bytes.write(byte)
                }
            }
        }
        val decoded =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        return HostResponse.StringSuccess(decoded)
    }

    private fun invalidRequest() = HostResponse.Failure(HostFailureKind.OTHER, INVALID_REQUEST_CODE)

    private fun sanitizeUtf16(value: String): String =
        buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val current = value[index]
                when {
                    current.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                        append(current)
                        append(value[index + 1])
                        index += 2
                    }

                    current.isSurrogate() -> {
                        append('\ufffd')
                        index++
                    }

                    else -> {
                        append(current)
                        index++
                    }
                }
            }
        }

    private data object InputLimitExceeded : RuntimeException()

    private data object OutputLimitExceeded : RuntimeException()

    private companion object {
        const val INVALID_REQUEST_CODE = 1L
        const val INPUT_LIMIT_CODE = 2L
        const val OUTPUT_LIMIT_CODE = 3L
    }
}
