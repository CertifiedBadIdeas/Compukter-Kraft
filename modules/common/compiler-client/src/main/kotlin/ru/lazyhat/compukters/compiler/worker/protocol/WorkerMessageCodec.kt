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

package ru.lazyhat.compukters.compiler.worker.protocol

import ru.lazyhat.compukters.compiler.project.ProjectSource
import java.io.ByteArrayOutputStream
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val MAX_WIRE_DIAGNOSTICS = 4096
private const val MAX_WIRE_SOURCES = 4096
private const val MAX_WIRE_BUNDLES = 1024

object WorkerMessageCodec {
    fun encode(message: WorkerMessage): WorkerFrame {
        val sink = MessageSink()
        when (message) {
            is WorkerHandshake -> {
                sink.identity(message.identity)
                sink.u64(message.features.fold(0uL) { bits, feature -> bits or (1uL shl feature.ordinal) })
                sink.limits(message.limits)
            }

            is CompileRequest -> {
                sink.u64(message.requestId.value)
                sink.sources(message.sources)
                sink.u16(message.target.ordinal)
                sink.identity(message.expectedIdentity)
                sink.limits(message.limits)
                sink.bundles(message.platformModules)
            }

            is CompileSuccess -> {
                sink.u64(message.requestId.value)
                sink.bytes(message.artifact)
                sink.hash(message.artifactHash)
                sink.diagnostics(message.warnings)
                sink.metrics(message.metrics)
            }

            is CompilerFailure -> {
                sink.u64(message.requestId.value)
                sink.diagnostics(message.diagnostics)
                sink.metrics(message.metrics)
            }

            is PlatformFailure -> {
                sink.u64(message.requestId.value)
                sink.u16(message.failureClass.ordinal)
                sink.string(message.detail)
            }
        }
        return WorkerFrame(message.type, sink.result())
    }

    fun decode(frame: WorkerFrame): WorkerMessage {
        val source = MessageSource(frame.payload)
        val message =
            when (frame.type) {
                WorkerMessageType.HANDSHAKE -> {
                    WorkerHandshake(source.identity(), source.features(), source.limits())
                }

                WorkerMessageType.COMPILE_REQUEST -> {
                    source.compileRequest()
                }

                WorkerMessageType.COMPILE_SUCCESS -> {
                    CompileSuccess(RequestId.of(source.u64()), source.bytes(), source.hash(), source.diagnostics(), source.metrics())
                }

                WorkerMessageType.COMPILER_FAILURE -> {
                    CompilerFailure(RequestId.of(source.u64()), source.diagnostics(), source.metrics())
                }

                WorkerMessageType.PLATFORM_FAILURE -> {
                    PlatformFailure(RequestId.of(source.u64()), source.enumValue<PlatformFailureClass>(), source.string())
                }
            }
        source.requireEnd()
        return message
    }
}

private class MessageSink {
    private val output = ByteArrayOutputStream()

    fun result(): ByteArray = output.toByteArray()

    fun u16(value: Int) {
        repeat(2) { output.write(value ushr (it * 8)) }
    }

    fun u32(value: Int) {
        require(value >= 0)
        u32(value.toUInt())
    }

    fun u32(value: UInt) {
        repeat(4) { output.write((value shr (it * 8)).toInt()) }
    }

    fun u64(value: ULong) {
        repeat(8) { output.write((value shr (it * 8)).toInt()) }
    }

    fun string(value: String) {
        val encoded =
            try {
                StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value))
            } catch (exception: CharacterCodingException) {
                throw IllegalArgumentException("protocol text must be strict UTF-8", exception)
            }
        val result = ByteArray(encoded.remaining())
        encoded.get(result)
        bytes(BinaryValue.of(result))
    }

    fun bytes(value: BinaryValue) {
        u32(value.size)
        output.write(value.toByteArray())
    }

    fun hash(value: Hash256) {
        output.write(value.toByteArray())
    }

    fun identity(value: WorkerIdentity) {
        string(value.compilerVersion)
        string(value.languageVersion)
        u32(value.codegenAbi)
        u32(value.artifactWriterVersion)
        hash(value.payloadHash)
        hash(value.platformAbi)
    }

    fun sources(values: List<ProjectSource>) {
        require(values.size <= MAX_WIRE_SOURCES) { "source count exceeds wire limit" }
        u32(values.size)
        values.forEach { source ->
            string(source.path.value)
            bytes(source.content)
        }
    }

    fun bundles(values: List<TrustedBundleIdentity>) {
        require(values.size <= MAX_WIRE_BUNDLES) { "trusted bundle count exceeds wire limit" }
        u32(values.size)
        values.forEach { bundle ->
            string(bundle.name)
            hash(bundle.hash)
        }
    }

    fun limits(value: WorkerLimits) {
        u32(value.sourceFiles)
        u32(value.sourceFileBytes)
        u32(value.sourceBytes)
        u32(value.frameBytes)
        u32(value.artifactBytes)
        u32(value.diagnostics)
        u32(value.diagnosticTextBytes)
        u32(value.stderrBytes)
        u64(value.temporaryBytes.toULong())
        u32(value.temporaryFiles)
    }

    fun diagnostics(values: List<WorkerDiagnostic>) {
        require(values.size <= MAX_WIRE_DIAGNOSTICS) { "diagnostic count exceeds wire limit" }
        u32(values.size)
        values.forEach(::diagnostic)
    }

    fun diagnostic(value: WorkerDiagnostic) {
        u16(value.severity.ordinal)
        u16(value.category.ordinal)
        nullableString(value.code)
        string(value.message)
        nullableString(value.path?.value)
        nullableUInt(value.startUtf16)
        nullableUInt(value.endUtf16)
    }

    fun nullableString(value: String?) {
        output.write(if (value == null) 0 else 1)
        if (value != null) string(value)
    }

    fun nullableUInt(value: UInt?) {
        output.write(if (value == null) 0 else 1)
        if (value != null) u32(value)
    }

    fun metrics(value: CompilationMetrics) {
        u64(value.wallNanos)
        u64(value.heapBytes)
        u64(value.metaspaceBytes)
    }
}

private class MessageSource(
    private val bytes: ByteArray,
) {
    private var offset = 0

    fun requireEnd() {
        if (offset != bytes.size) fail(WorkerProtocolError.TRAILING_MESSAGE_BYTES, "message contains trailing bytes")
    }

    fun u8(): Int {
        requireBytes(1)
        return bytes[offset++].toInt() and 0xff
    }

    fun u16(): Int = read(2).toInt()

    fun u32(): Int {
        val value = u32Bits()
        if (value >
            Int.MAX_VALUE.toUInt()
        ) {
            fail(WorkerProtocolError.FRAME_TOO_LARGE, "length exceeds JVM range")
        }
        return value.toInt()
    }

    fun u32Bits(): UInt = read(4).toUInt()

    fun u64(): ULong {
        requireBytes(8)
        var result = 0uL
        repeat(8) {
            result =
                result or ((bytes[offset++].toULong() and 0xffu) shl (it * 8))
        }
        return result
    }

    fun bytes(): BinaryValue {
        val size = u32()
        requireBytes(size)
        return BinaryValue.of(bytes.copyOfRange(offset, offset + size)).also {
            offset +=
                size
        }
    }

    fun string(): String =
        try {
            bytes().decodeUtf8()
        } catch (_: CharacterCodingException) {
            fail(WorkerProtocolError.INVALID_UTF8, "invalid UTF-8")
        }

    fun hash(): Hash256 {
        requireBytes(32)
        return Hash256.of(bytes.copyOfRange(offset, offset + 32)).also { offset += 32 }
    }

    fun identity() = WorkerIdentity(string(), string(), u32Bits(), u32Bits(), hash(), hash())

    fun limits() =
        WorkerLimits(
            sourceFiles = u32(),
            sourceFileBytes = u32(),
            sourceBytes = u32(),
            frameBytes = u32(),
            artifactBytes = u32(),
            diagnostics = u32(),
            diagnosticTextBytes = u32(),
            stderrBytes = u32(),
            temporaryBytes = u64().toLongChecked(),
            temporaryFiles = u32(),
        )

    fun features(): Set<WorkerFeature> {
        val bits = u64()
        val known = WorkerFeature.entries.fold(0uL) { value, feature -> value or (1uL shl feature.ordinal) }
        if (bits and known.inv() != 0uL) fail(WorkerProtocolError.INVALID_MESSAGE_VALUE, "unknown worker feature")
        return WorkerFeature.entries.filterTo(linkedSetOf()) { bits and (1uL shl it.ordinal) != 0uL }
    }

    fun compileRequest(): CompileRequest {
        val requestId = RequestId.of(u64())
        val sources = sources()
        val target = enumValue<TargetSettings>()
        val identity = identity()
        val limits = limits()
        val platformModules = bundles()
        return try {
            CompileRequest(requestId, sources, target, identity, limits, platformModules)
        } catch (exception: IllegalArgumentException) {
            fail(WorkerProtocolError.INVALID_MESSAGE_VALUE, exception.message ?: "invalid compile request")
        }
    }

    fun sources(): List<ProjectSource> {
        val count = boundedCount(MAX_WIRE_SOURCES, "source")
        return List(count) {
            val path =
                try {
                    VirtualSourcePath.kotlin(string())
                } catch (exception: IllegalArgumentException) {
                    fail(WorkerProtocolError.INVALID_PATH, exception.message ?: "invalid virtual source path")
                }
            val content = bytes()
            try {
                content.decodeUtf8()
            } catch (_: CharacterCodingException) {
                fail(WorkerProtocolError.INVALID_UTF8, "source is not strict UTF-8")
            }
            ProjectSource(path, content)
        }
    }

    fun bundles(): List<TrustedBundleIdentity> {
        val count = boundedCount(MAX_WIRE_BUNDLES, "trusted bundle")
        return List(count) {
            try {
                TrustedBundleIdentity.of(string(), hash())
            } catch (exception: IllegalArgumentException) {
                fail(WorkerProtocolError.INVALID_MESSAGE_VALUE, exception.message ?: "invalid trusted bundle identity")
            }
        }
    }

    private fun boundedCount(
        maximum: Int,
        description: String,
    ): Int {
        val count = u32()
        if (count > maximum) fail(WorkerProtocolError.COUNT_LIMIT, "$description count exceeds wire limit")
        return count
    }

    fun diagnostics(): List<WorkerDiagnostic> {
        val count = u32()
        if (count > MAX_WIRE_DIAGNOSTICS) {
            fail(WorkerProtocolError.COUNT_LIMIT, "diagnostic count exceeds wire limit")
        }
        return List(count) { diagnostic() }
    }

    fun diagnostic() =
        WorkerDiagnostic(
            enumValue(),
            enumValue(),
            nullableString(),
            string(),
            nullableString()?.let(VirtualSourcePath::of),
            nullableUInt(),
            nullableUInt(),
        )

    fun nullableString(): String? =
        when (u8()) {
            0 -> null
            1 -> string()
            else -> fail(WorkerProtocolError.INVALID_MESSAGE_VALUE, "non-canonical optional value")
        }

    fun nullableUInt(): UInt? =
        when (u8()) {
            0 -> null
            1 -> u32Bits()
            else -> fail(WorkerProtocolError.INVALID_MESSAGE_VALUE, "non-canonical optional value")
        }

    fun metrics() = CompilationMetrics(u64(), u64(), u64())

    inline fun <reified T : Enum<T>> enumValue(): T {
        val index = u16()
        return enumValues<T>().getOrNull(index)
            ?: fail(WorkerProtocolError.UNKNOWN_ENUM_VALUE, "unknown enum value")
    }

    private fun read(count: Int): Long {
        requireBytes(count)
        var result = 0L
        repeat(count) {
            result =
                result or ((bytes[offset++].toLong() and 0xff) shl (it * 8))
        }
        return result
    }

    private fun requireBytes(count: Int) {
        if (count < 0 ||
            offset > bytes.size - count
        ) {
            fail(WorkerProtocolError.TRUNCATED_MESSAGE, "message is truncated")
        }
    }

    fun fail(
        error: WorkerProtocolError,
        message: String,
    ): Nothing = throw WorkerProtocolException(error, message)
}

private fun ULong.toLongChecked(): Long {
    if (this > Long.MAX_VALUE.toULong()) throw WorkerProtocolException(WorkerProtocolError.FRAME_TOO_LARGE, "value exceeds JVM range")
    return toLong()
}
