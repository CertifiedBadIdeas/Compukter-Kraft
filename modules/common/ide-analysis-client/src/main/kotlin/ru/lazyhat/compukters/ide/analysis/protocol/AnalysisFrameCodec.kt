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

package ru.lazyhat.compukters.ide.analysis.protocol

enum class AnalysisMessageType(
    internal val wireValue: Int,
) {
    Handshake(1),
    OpenSnapshot(2),
    SnapshotReady(3),
    Query(4),
    QuerySuccess(5),
    Cancel(6),
    Cancelled(7),
    CloseSnapshot(8),
    SnapshotClosed(9),
    Failure(10),
    UpdateSnapshot(11),
    SnapshotUpdated(12),
    SnapshotReopenRequired(13),
    ;

    companion object {
        internal fun fromWire(value: Int): AnalysisMessageType? = entries.firstOrNull { it.wireValue == value }
    }
}

class AnalysisFrame(
    val type: AnalysisMessageType,
    payload: ByteArray,
) {
    val payload: ByteArray = payload.copyOf()

    fun copy(
        type: AnalysisMessageType = this.type,
        payload: ByteArray = this.payload,
    ): AnalysisFrame = AnalysisFrame(type, payload)

    override fun equals(other: Any?): Boolean = other is AnalysisFrame && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}

enum class AnalysisProtocolError {
    TruncatedFrame,
    FrameTooLarge,
    TrailingBytes,
    BadMagic,
    WrongVersion,
    UnknownMessageType,
    InvalidUtf8,
    InvalidPath,
    InvalidRange,
    UnknownEnumValue,
    InvalidMessageValue,
    TruncatedMessage,
    TrailingMessageBytes,
    CountLimit,
}

class AnalysisProtocolException(
    val error: AnalysisProtocolError,
    message: String,
) : IllegalArgumentException(message)

object AnalysisFrameCodec {
    private const val HEADER_BYTES = 12
    private const val VERSION = 2
    private val magic = byteArrayOf(0x43, 0x50, 0x4b, 0x41)

    fun encode(frame: AnalysisFrame): ByteArray {
        require(frame.payload.size <= ProtocolLimits.MAX_FRAME_PAYLOAD_BYTES) { "analysis frame exceeds protocol limit" }
        val result = ByteArray(HEADER_BYTES + frame.payload.size)
        magic.copyInto(result)
        writeU16(result, 4, VERSION)
        writeU16(result, 6, frame.type.wireValue)
        writeU32(result, 8, frame.payload.size)
        frame.payload.copyInto(result, HEADER_BYTES)
        return result
    }

    fun decode(
        bytes: ByteArray,
        maximumPayloadBytes: Int,
    ): AnalysisFrame {
        require(maximumPayloadBytes in 0..ProtocolLimits.MAX_FRAME_PAYLOAD_BYTES) { "invalid maximum frame payload" }
        if (bytes.size < HEADER_BYTES) fail(AnalysisProtocolError.TruncatedFrame, "frame header is truncated")
        if (!bytes.copyOfRange(0, magic.size).contentEquals(magic)) fail(AnalysisProtocolError.BadMagic, "bad frame magic")
        if (readU16(bytes, 4) != VERSION) fail(AnalysisProtocolError.WrongVersion, "unsupported frame version")
        val type =
            AnalysisMessageType.fromWire(readU16(bytes, 6))
                ?: fail(AnalysisProtocolError.UnknownMessageType, "unknown analysis message type")
        val payloadSize = readU32(bytes, 8)
        if (payloadSize > maximumPayloadBytes) fail(AnalysisProtocolError.FrameTooLarge, "frame exceeds payload limit")
        val expected = HEADER_BYTES.toLong() + payloadSize
        if (bytes.size.toLong() < expected) fail(AnalysisProtocolError.TruncatedFrame, "frame payload is truncated")
        if (bytes.size.toLong() > expected) fail(AnalysisProtocolError.TrailingBytes, "frame contains trailing bytes")
        return AnalysisFrame(type, bytes.copyOfRange(HEADER_BYTES, bytes.size))
    }

    private fun writeU16(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeU32(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun readU16(
        bytes: ByteArray,
        offset: Int,
    ): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(
        bytes: ByteArray,
        offset: Int,
    ): Long = (0 until 4).fold(0L) { result, index -> result or ((bytes[offset + index].toLong() and 0xff) shl (index * 8)) }

    private fun fail(
        error: AnalysisProtocolError,
        message: String,
    ): Nothing = throw AnalysisProtocolException(error, message)
}
