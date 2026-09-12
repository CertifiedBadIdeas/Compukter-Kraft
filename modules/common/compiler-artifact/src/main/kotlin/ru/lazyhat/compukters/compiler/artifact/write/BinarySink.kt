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

package ru.lazyhat.compukters.compiler.artifact.write

import java.io.ByteArrayOutputStream

internal class BinarySink(
    private val maximumBytes: Int,
) {
    private val output = ByteArrayOutputStream(minOf(maximumBytes, 4_096))

    val size: Int
        get() = output.size()

    fun writeU8(value: UInt) {
        require(value <= 0xffu) { "value does not fit u8" }
        ensure(1)
        output.write(value.toInt())
    }

    fun writeU16(value: UInt) {
        require(value <= 0xffffu) { "value does not fit u16" }
        ensure(2)
        repeat(2) { output.write((value shr (it * 8)).toInt() and 0xff) }
    }

    fun writeU32(value: UInt) {
        ensure(4)
        repeat(4) { output.write((value shr (it * 8)).toInt() and 0xff) }
    }

    fun writeU64(value: ULong) {
        ensure(8)
        repeat(8) { output.write(((value shr (it * 8)) and 0xffu).toInt()) }
    }

    fun writeBytes(bytes: ByteArray) {
        ensure(bytes.size)
        output.write(bytes)
    }

    fun writeUleb128(value: UInt) {
        var remaining = value
        do {
            var byte = (remaining and 0x7fu).toInt()
            remaining = remaining shr 7
            if (remaining != 0u) byte = byte or 0x80
            writeU8(byte.toUInt())
        } while (remaining != 0u)
    }

    fun align8() {
        val aligned = checkedAlign8(size)
        repeat(aligned - size) { writeU8(0u) }
    }

    fun toByteArray(): ByteArray = output.toByteArray()

    private fun ensure(additional: Int) {
        val resulting = size.toLong() + additional.toLong()
        if (additional < 0 || resulting > maximumBytes || resulting > Int.MAX_VALUE) {
            throw ArtifactEncodingException(ArtifactWriteErrorCode.LIMIT_EXCEEDED, "encoded output exceeds $maximumBytes bytes")
        }
    }
}

internal fun encodeIndexed(
    records: List<ByteArray>,
    maximumBytes: Int,
): ByteArray {
    require(records.size <= UInt.MAX_VALUE.toLong()) { "record count exceeds u32" }
    val recordBytes = records.fold(0L) { total, record -> Math.addExact(total, record.size.toLong()) }
    if (recordBytes > UInt.MAX_VALUE.toLong()) {
        throw ArtifactEncodingException(ArtifactWriteErrorCode.OVERFLOW, "indexed record bytes exceed u32")
    }

    val sink = BinarySink(maximumBytes)
    sink.writeU32(records.size.toUInt())
    sink.writeU32(0u)
    sink.writeU64(recordBytes.toULong())
    var offset = 0u
    sink.writeU32(offset)
    for (record in records) {
        offset += record.size.toUInt()
        sink.writeU32(offset)
    }
    sink.align8()
    records.forEach(sink::writeBytes)
    return sink.toByteArray()
}

internal fun checkedAlign8(value: Int): Int {
    val aligned = (value.toLong() + 7L) and -8L
    if (aligned > Int.MAX_VALUE) {
        throw ArtifactEncodingException(ArtifactWriteErrorCode.OVERFLOW, "alignment exceeds Int range")
    }
    return aligned.toInt()
}
