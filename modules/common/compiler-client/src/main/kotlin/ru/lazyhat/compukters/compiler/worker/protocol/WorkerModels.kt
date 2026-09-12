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

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class BinaryValue private constructor(
    bytes: ByteArray,
) {
    private val bytes = bytes.copyOf()
    val size: Int get() = bytes.size

    fun toByteArray(): ByteArray = bytes.copyOf()

    internal fun decodeUtf8(): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    override fun equals(other: Any?): Boolean = other is BinaryValue && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        fun of(bytes: ByteArray): BinaryValue = BinaryValue(bytes)
    }
}

class Hash256 private constructor(
    private val value: BinaryValue,
) {
    fun toByteArray(): ByteArray = value.toByteArray()

    fun hex(): String = toByteArray().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    override fun equals(other: Any?): Boolean = other is Hash256 && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun of(bytes: ByteArray): Hash256 {
            require(bytes.size == 32) { "SHA-256 value must contain 32 bytes" }
            return Hash256(BinaryValue.of(bytes))
        }

        fun fromHex(value: String): Hash256 {
            require(value.length == 64) { "SHA-256 text must contain 64 lowercase hexadecimal digits" }
            require(value.all { it in '0'..'9' || it in 'a'..'f' }) {
                "SHA-256 text must contain 64 lowercase hexadecimal digits"
            }
            return of(
                ByteArray(32) { index ->
                    val offset = index * 2
                    ((value[offset].digitToInt(16) shl 4) or value[offset + 1].digitToInt(16)).toByte()
                },
            )
        }

        fun zero(): Hash256 = of(ByteArray(32))
    }
}

@JvmInline
value class RequestId private constructor(
    val value: ULong,
) {
    companion object {
        fun of(value: ULong): RequestId {
            require(value != 0uL) { "request ID zero is reserved" }
            return RequestId(value)
        }
    }
}

data class WorkerLimits(
    val sourceFiles: Int = 64,
    val sourceFileBytes: Int = 256 * 1024,
    val sourceBytes: Int = 256 * 1024,
    val frameBytes: Int = 20 * 1024 * 1024,
    val artifactBytes: Int = 16 * 1024 * 1024,
    val diagnostics: Int = 64,
    val diagnosticTextBytes: Int = 64 * 1024,
    val stderrBytes: Int = 64 * 1024,
    val temporaryBytes: Long = 64L * 1024 * 1024,
    val temporaryFiles: Int = 128,
) {
    init {
        require(
            sourceFiles >= 0 && sourceFileBytes >= 0 && sourceBytes >= 0 && frameBytes >= 0 && artifactBytes >= 0 && diagnostics >= 0 &&
                diagnosticTextBytes >= 0 && stderrBytes >= 0 && temporaryBytes >= 0 && temporaryFiles >= 0,
        ) { "worker limits must be non-negative" }
    }
}

data class WorkerIdentity(
    val compilerVersion: String,
    val languageVersion: String,
    val codegenAbi: UInt,
    val artifactWriterVersion: UInt,
    val payloadHash: Hash256,
    val platformAbi: Hash256,
)

enum class WorkerFeature { PROJECT_SNAPSHOT, KOTLIN_IR }

enum class TargetSettings { KOTLIN_2_4_JVM_17 }

enum class DiagnosticSeverity { INFO, WARNING, ERROR }

enum class DiagnosticCategory { SYNTAX, TYPE, TARGET, INTERNAL }

enum class PlatformFailureClass {
    WORKER_STARTUP,
    PROTOCOL,
    TIMEOUT,
    MEMORY_LIMIT,
    CANCELLED,
    INTERNAL_COMPILER,
    OUTPUT_LIMIT,
    WORKER_EXIT,
}

data class WorkerDiagnostic(
    val severity: DiagnosticSeverity,
    val category: DiagnosticCategory,
    val code: String?,
    val message: String,
    val path: VirtualSourcePath?,
    val startUtf16: UInt?,
    val endUtf16: UInt?,
)

data class CompilationMetrics(
    val wallNanos: ULong,
    val heapBytes: ULong,
    val metaspaceBytes: ULong,
)

sealed interface WorkerMessage {
    val type: WorkerMessageType
}

data class WorkerHandshake(
    val identity: WorkerIdentity,
    val features: Set<WorkerFeature>,
    val limits: WorkerLimits,
) : WorkerMessage {
    override val type = WorkerMessageType.HANDSHAKE
}

sealed interface CompileResult : WorkerMessage {
    val requestId: RequestId
}

class TrustedBundleIdentity private constructor(
    val name: String,
    val hash: Hash256,
) {
    override fun equals(other: Any?): Boolean = other is TrustedBundleIdentity && name == other.name && hash == other.hash

    override fun hashCode(): Int = 31 * name.hashCode() + hash.hashCode()

    companion object {
        fun of(
            name: String,
            hash: Hash256,
        ): TrustedBundleIdentity {
            require(name.isNotEmpty()) { "trusted bundle name must not be empty" }
            require('\u0000' !in name) { "trusted bundle name contains NUL" }
            require(name.encodeToByteArray().decodeToString() == name) { "trusted bundle name must be strict UTF-8" }
            return TrustedBundleIdentity(name, hash)
        }
    }
}

class CompileRequest(
    val requestId: RequestId,
    sources: List<ProjectSource>,
    val target: TargetSettings,
    val expectedIdentity: WorkerIdentity,
    val limits: WorkerLimits,
    platformModules: List<TrustedBundleIdentity> = emptyList(),
) : WorkerMessage {
    override val type = WorkerMessageType.COMPILE_REQUEST
    val sources: List<ProjectSource> = ProjectSnapshot.of(sources, limits).sources
    val platformModules: List<TrustedBundleIdentity> = platformModules.toList()

    init {
        requireUniqueBundles(this.platformModules, "platform modules")
    }

    fun copy(
        requestId: RequestId = this.requestId,
        sources: List<ProjectSource> = this.sources,
        target: TargetSettings = this.target,
        expectedIdentity: WorkerIdentity = this.expectedIdentity,
        limits: WorkerLimits = this.limits,
        platformModules: List<TrustedBundleIdentity> = this.platformModules,
    ): CompileRequest = CompileRequest(requestId, sources, target, expectedIdentity, limits, platformModules)

    override fun equals(other: Any?): Boolean =
        other is CompileRequest &&
            requestId == other.requestId &&
            sources == other.sources &&
            target == other.target &&
            expectedIdentity == other.expectedIdentity &&
            limits == other.limits &&
            platformModules == other.platformModules

    override fun hashCode(): Int = listOf(requestId, sources, target, expectedIdentity, limits, platformModules).hashCode()

    private fun requireUniqueBundles(
        bundles: List<TrustedBundleIdentity>,
        description: String,
    ) {
        require(bundles.map(TrustedBundleIdentity::name).toSet().size == bundles.size) { "$description must have unique names" }
    }
}

data class CompileSuccess(
    override val requestId: RequestId,
    val artifact: BinaryValue,
    val artifactHash: Hash256,
    val warnings: List<WorkerDiagnostic>,
    val metrics: CompilationMetrics,
) : CompileResult {
    override val type = WorkerMessageType.COMPILE_SUCCESS
}

data class CompilerFailure(
    override val requestId: RequestId,
    val diagnostics: List<WorkerDiagnostic>,
    val metrics: CompilationMetrics,
) : CompileResult {
    override val type = WorkerMessageType.COMPILER_FAILURE
}

data class PlatformFailure(
    override val requestId: RequestId,
    val failureClass: PlatformFailureClass,
    val detail: String,
) : CompileResult {
    override val type = WorkerMessageType.PLATFORM_FAILURE
}

@JvmInline
value class VirtualSourcePath private constructor(
    val value: String,
) {
    companion object {
        fun of(value: String): VirtualSourcePath {
            require(value.isNotEmpty()) { "virtual source path must not be empty" }
            require(!value.startsWith('/')) { "virtual source path must be relative" }
            require('\\' !in value && '\u0000' !in value) { "virtual source path contains a forbidden character" }
            require(value.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                "virtual source path contains a non-canonical segment"
            }
            try {
                StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value))
            } catch (exception: CharacterCodingException) {
                throw IllegalArgumentException("virtual source path must be strict UTF-8", exception)
            }
            return VirtualSourcePath(value)
        }

        /**
         * Creates a canonical guest Kotlin source path. Protocol diagnostic paths use [of]
         * because diagnostics may refer to non-source virtual files.
         */
        fun kotlin(value: String): VirtualSourcePath {
            val path = of(value)
            require(!DRIVE_PATH.matches(value)) { "virtual source path must not be drive-qualified" }
            require(value.endsWith(".kt")) { "guest source path must end in .kt" }
            return path
        }

        private val DRIVE_PATH = Regex("^[A-Za-z]:.*")
    }
}

enum class WorkerMessageType(
    internal val wireValue: Int,
) {
    HANDSHAKE(1),
    COMPILE_REQUEST(2),
    COMPILE_SUCCESS(3),
    COMPILER_FAILURE(4),
    PLATFORM_FAILURE(5),
    ;

    companion object {
        internal fun fromWire(value: Int): WorkerMessageType? = entries.firstOrNull { it.wireValue == value }
    }
}

class WorkerFrame(
    val type: WorkerMessageType,
    payload: ByteArray,
) {
    val payload: ByteArray = payload.copyOf()

    override fun equals(other: Any?): Boolean = other is WorkerFrame && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}

enum class WorkerProtocolError {
    TRUNCATED_FRAME,
    FRAME_TOO_LARGE,
    TRAILING_BYTES,
    BAD_MAGIC,
    WRONG_VERSION,
    UNKNOWN_MESSAGE_TYPE,
    INVALID_UTF8,
    INVALID_PATH,
    UNKNOWN_ENUM_VALUE,
    INVALID_MESSAGE_VALUE,
    TRUNCATED_MESSAGE,
    TRAILING_MESSAGE_BYTES,
    COUNT_LIMIT,
}

class WorkerProtocolException(
    val error: WorkerProtocolError,
    message: String,
) : IllegalArgumentException(message)
