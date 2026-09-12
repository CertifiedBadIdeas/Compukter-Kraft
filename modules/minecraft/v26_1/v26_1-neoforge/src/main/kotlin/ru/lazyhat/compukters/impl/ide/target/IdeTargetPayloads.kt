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
 */

package ru.lazyhat.compukters.impl.ide.target

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetDirectoryEntry
import ru.lazyhat.compukters.ide.client.target.IdeTargetDirectoryListing
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileChunk
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileMetadata
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileStat
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ResolvedModule
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity

internal data class IdeTargetReference(
    val id: IdeTargetId,
    val profile: IdeTargetProfileId,
)

internal class IdeCanonicalLine private constructor(
    value: CharArray,
) {
    private val value = value.copyOf()

    fun chars(): CharArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is IdeCanonicalLine && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    companion object {
        fun of(value: CharArray): IdeCanonicalLine {
            require(value.size <= IdeTargetWireProtocol.MAXIMUM_CANONICAL_LINE_CODE_UNITS) { "canonical line is too long" }
            return IdeCanonicalLine(value)
        }
    }
}

internal sealed interface IdeTargetRequest {
    data class Attach(
        val claim: BinaryValue,
    ) : IdeTargetRequest

    data class BeginUpload(
        val target: IdeTargetReference,
        val artifactHash: Hash256,
        val bytes: Int,
    ) : IdeTargetRequest {
        init {
            require(bytes > 0) { "artifact size must be positive" }
        }
    }

    data class UploadChunk(
        val target: IdeTargetReference,
        val offset: Int,
        val bytes: BinaryValue,
    ) : IdeTargetRequest {
        init {
            require(offset >= 0) { "artifact chunk offset must not be negative" }
        }
    }

    data class Verify(
        val target: IdeTargetReference,
    ) : IdeTargetRequest

    data class ExecutableRevision(
        val target: IdeTargetReference,
        val path: IdeDeploymentPath,
    ) : IdeTargetRequest

    data class Deploy(
        val target: IdeTargetReference,
        val ticket: BinaryValue,
        val artifactHash: Hash256,
        val artifactBytes: Int,
        val path: IdeDeploymentPath,
        val expected: IdeExecutableRevision,
    ) : IdeTargetRequest {
        init {
            require(artifactBytes > 0) { "ticket artifact size must be positive" }
        }
    }

    data class SubmitCanonicalLine(
        val target: IdeTargetReference,
        val line: IdeCanonicalLine,
    ) : IdeTargetRequest

    data class Heartbeat(
        val target: IdeTargetReference,
    ) : IdeTargetRequest

    data class Detach(
        val target: IdeTargetReference,
    ) : IdeTargetRequest

    data class FileStat(
        val target: IdeTargetReference,
        val path: IdeTargetVirtualPath,
    ) : IdeTargetRequest

    data class FileList(
        val target: IdeTargetReference,
        val path: IdeTargetVirtualPath,
        val startAfter: String?,
        val maximumEntries: Int,
    ) : IdeTargetRequest {
        init {
            require(maximumEntries in 1..IdeTargetWireProtocol.MAXIMUM_FILE_LIST_ENTRIES)
            startAfter?.let { name ->
                require(name.isNotEmpty() && name != "." && name != "..")
                require(name.none { it == '/' || it == '\\' || it.isISOControl() })
            }
        }
    }

    data class FileRead(
        val target: IdeTargetReference,
        val path: IdeTargetVirtualPath,
        val offset: Long,
        val maximumBytes: Int,
        val expectedGeneration: Long,
    ) : IdeTargetRequest {
        init {
            require(offset >= 0 && expectedGeneration >= 0)
            require(maximumBytes in 1..IdeTargetWireProtocol.MAXIMUM_FILE_READ_BYTES)
        }
    }
}

internal data class IdeTargetRequestPayload(
    val requestId: Long,
    val request: IdeTargetRequest,
) : CustomPacketPayload {
    init {
        require(requestId > 0) { "request ID zero and negative IDs are reserved" }
    }

    override fun type(): CustomPacketPayload.Type<IdeTargetRequestPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<IdeTargetRequestPayload>(Identifier.fromNamespaceAndPath(MOD_ID, "ide_target_request"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, IdeTargetRequestPayload> =
            StreamCodec.of(
                StreamEncoder { buffer, payload -> IdeTargetWireProtocol.writeRequest(buffer, payload) },
                StreamDecoder { buffer -> IdeTargetWireProtocol.readRequest(buffer) },
            )
    }
}

internal sealed interface IdeTargetReply {
    data class Attached(
        val target: IdeAttachedTarget,
    ) : IdeTargetReply

    data object UploadAccepted : IdeTargetReply

    data class Verified(
        val ticket: BinaryValue,
        val target: IdeTargetReference,
        val artifactHash: Hash256,
        val artifactBytes: Int,
    ) : IdeTargetReply {
        init {
            require(artifactBytes > 0) { "ticket artifact size must be positive" }
        }
    }

    data class RevisionObserved(
        val revision: IdeExecutableRevision,
    ) : IdeTargetReply

    data class Deployed(
        val revision: IdeExecutableRevision.Present,
    ) : IdeTargetReply

    data class StaleRevision(
        val actual: IdeExecutableRevision,
    ) : IdeTargetReply

    data object Submitted : IdeTargetReply

    data object Alive : IdeTargetReply

    data object Detached : IdeTargetReply

    data class Failed(
        val failure: IdeTargetFailure,
        val retryable: Boolean,
    ) : IdeTargetReply

    data class FileStatObserved(
        val stat: IdeTargetFileStat,
    ) : IdeTargetReply

    data class FileListed(
        val listing: IdeTargetDirectoryListing,
    ) : IdeTargetReply

    data class FileRead(
        val chunk: IdeTargetFileChunk,
    ) : IdeTargetReply
}

internal data class IdeTargetReplyPayload(
    val requestId: Long,
    val reply: IdeTargetReply,
) : CustomPacketPayload {
    init {
        require(requestId > 0) { "request ID zero and negative IDs are reserved" }
    }

    override fun type(): CustomPacketPayload.Type<IdeTargetReplyPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<IdeTargetReplyPayload>(Identifier.fromNamespaceAndPath(MOD_ID, "ide_target_reply"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, IdeTargetReplyPayload> =
            StreamCodec.of(
                StreamEncoder { buffer, payload -> IdeTargetWireProtocol.writeReply(buffer, payload) },
                StreamDecoder { buffer -> IdeTargetWireProtocol.readReply(buffer) },
            )
    }
}

internal object IdeTargetWireProtocol {
    const val MAXIMUM_CHUNK_BYTES = 32 * 1024
    const val MAXIMUM_CANONICAL_LINE_CODE_UNITS = 4_096
    const val MAXIMUM_FILE_LIST_ENTRIES = 256
    const val MAXIMUM_FILE_READ_BYTES = 32 * 1024
    private const val MAXIMUM_CLAIM_BYTES = 256
    private const val MAXIMUM_TICKET_BYTES = 256
    const val MAXIMUM_TARGET_ID_CODE_UNITS = 128
    const val MAXIMUM_PATH_CODE_UNITS = 134

    fun writeRequest(
        buffer: RegistryFriendlyByteBuf,
        payload: IdeTargetRequestPayload,
    ) {
        buffer.writeVarLong(payload.requestId)
        when (val request = payload.request) {
            is IdeTargetRequest.Attach -> {
                buffer.writeByte(ATTACH)
                buffer.writeBounded(request.claim, MAXIMUM_CLAIM_BYTES, "target claim")
            }

            is IdeTargetRequest.BeginUpload -> {
                buffer.writeByte(BEGIN_UPLOAD)
                buffer.writeTarget(request.target)
                buffer.writeHash(request.artifactHash)
                buffer.writeVarInt(request.bytes)
            }

            is IdeTargetRequest.UploadChunk -> {
                buffer.writeByte(UPLOAD_CHUNK)
                buffer.writeTarget(request.target)
                buffer.writeVarInt(request.offset)
                buffer.writeBounded(request.bytes, MAXIMUM_CHUNK_BYTES, "artifact chunk")
            }

            is IdeTargetRequest.Verify -> {
                buffer.writeByte(VERIFY)
                buffer.writeTarget(request.target)
            }

            is IdeTargetRequest.ExecutableRevision -> {
                buffer.writeByte(EXECUTABLE_REVISION)
                buffer.writeTarget(request.target)
                buffer.writePath(request.path)
            }

            is IdeTargetRequest.Deploy -> {
                buffer.writeByte(DEPLOY)
                buffer.writeTarget(request.target)
                buffer.writeBounded(request.ticket, MAXIMUM_TICKET_BYTES, "verification ticket")
                buffer.writeHash(request.artifactHash)
                buffer.writeVarInt(request.artifactBytes)
                buffer.writePath(request.path)
                buffer.writeRevision(request.expected)
            }

            is IdeTargetRequest.SubmitCanonicalLine -> {
                buffer.writeByte(SUBMIT_CANONICAL_LINE)
                buffer.writeTarget(request.target)
                buffer.writeCanonicalLine(request.line)
            }

            is IdeTargetRequest.Heartbeat -> {
                buffer.writeByte(HEARTBEAT)
                buffer.writeTarget(request.target)
            }

            is IdeTargetRequest.Detach -> {
                buffer.writeByte(DETACH)
                buffer.writeTarget(request.target)
            }

            is IdeTargetRequest.FileStat -> {
                buffer.writeByte(FILE_STAT)
                buffer.writeTarget(request.target)
                buffer.writeVirtualPath(request.path)
            }

            is IdeTargetRequest.FileList -> {
                buffer.writeByte(FILE_LIST)
                buffer.writeTarget(request.target)
                buffer.writeVirtualPath(request.path)
                buffer.writeBoolean(request.startAfter != null)
                request.startAfter?.let { buffer.writeUtf(it, MAXIMUM_PATH_CODE_UNITS) }
                buffer.writeVarInt(request.maximumEntries)
            }

            is IdeTargetRequest.FileRead -> {
                buffer.writeByte(FILE_READ)
                buffer.writeTarget(request.target)
                buffer.writeVirtualPath(request.path)
                buffer.writeVarLong(request.offset)
                buffer.writeVarInt(request.maximumBytes)
                buffer.writeVarLong(request.expectedGeneration)
            }
        }
    }

    fun readRequest(buffer: RegistryFriendlyByteBuf): IdeTargetRequestPayload {
        val requestId = buffer.readVarLong()
        val request =
            when (val kind = buffer.readUnsignedByte().toInt()) {
                ATTACH -> {
                    IdeTargetRequest.Attach(buffer.readBounded(MAXIMUM_CLAIM_BYTES, "target claim"))
                }

                BEGIN_UPLOAD -> {
                    IdeTargetRequest.BeginUpload(buffer.readTarget(), buffer.readHash(), buffer.readVarInt())
                }

                UPLOAD_CHUNK -> {
                    IdeTargetRequest.UploadChunk(
                        buffer.readTarget(),
                        buffer.readVarInt(),
                        buffer.readBounded(MAXIMUM_CHUNK_BYTES, "artifact chunk"),
                    )
                }

                VERIFY -> {
                    IdeTargetRequest.Verify(buffer.readTarget())
                }

                EXECUTABLE_REVISION -> {
                    IdeTargetRequest.ExecutableRevision(buffer.readTarget(), buffer.readPath())
                }

                DEPLOY -> {
                    IdeTargetRequest.Deploy(
                        buffer.readTarget(),
                        buffer.readBounded(MAXIMUM_TICKET_BYTES, "verification ticket"),
                        buffer.readHash(),
                        buffer.readVarInt(),
                        buffer.readPath(),
                        buffer.readRevision(),
                    )
                }

                SUBMIT_CANONICAL_LINE -> {
                    IdeTargetRequest.SubmitCanonicalLine(buffer.readTarget(), buffer.readCanonicalLine())
                }

                HEARTBEAT -> {
                    IdeTargetRequest.Heartbeat(buffer.readTarget())
                }

                DETACH -> {
                    IdeTargetRequest.Detach(buffer.readTarget())
                }

                FILE_STAT -> {
                    IdeTargetRequest.FileStat(buffer.readTarget(), buffer.readVirtualPath())
                }

                FILE_LIST -> {
                    IdeTargetRequest.FileList(
                        buffer.readTarget(),
                        buffer.readVirtualPath(),
                        if (buffer.readBoolean()) buffer.readUtf(MAXIMUM_PATH_CODE_UNITS) else null,
                        buffer.readVarInt(),
                    )
                }

                FILE_READ -> {
                    IdeTargetRequest.FileRead(
                        buffer.readTarget(),
                        buffer.readVirtualPath(),
                        buffer.readVarLong(),
                        buffer.readVarInt(),
                        buffer.readVarLong(),
                    )
                }

                else -> {
                    throw IllegalArgumentException("unknown IDE target request kind $kind")
                }
            }
        return IdeTargetRequestPayload(requestId, request)
    }

    fun writeReply(
        buffer: RegistryFriendlyByteBuf,
        payload: IdeTargetReplyPayload,
    ) {
        buffer.writeVarLong(payload.requestId)
        when (val reply = payload.reply) {
            is IdeTargetReply.Attached -> {
                buffer.writeByte(REPLY_ATTACHED)
                buffer.writeAttachedTarget(reply.target)
            }

            IdeTargetReply.UploadAccepted -> {
                buffer.writeByte(REPLY_UPLOAD_ACCEPTED)
            }

            is IdeTargetReply.Verified -> {
                buffer.writeByte(REPLY_VERIFIED)
                buffer.writeBounded(reply.ticket, MAXIMUM_TICKET_BYTES, "verification ticket")
                buffer.writeTarget(reply.target)
                buffer.writeHash(reply.artifactHash)
                buffer.writeVarInt(reply.artifactBytes)
            }

            is IdeTargetReply.RevisionObserved -> {
                buffer.writeByte(REPLY_REVISION)
                buffer.writeRevision(reply.revision)
            }

            is IdeTargetReply.Deployed -> {
                buffer.writeByte(REPLY_DEPLOYED)
                buffer.writeRevision(reply.revision)
            }

            is IdeTargetReply.StaleRevision -> {
                buffer.writeByte(REPLY_STALE_REVISION)
                buffer.writeRevision(reply.actual)
            }

            IdeTargetReply.Submitted -> {
                buffer.writeByte(REPLY_SUBMITTED)
            }

            IdeTargetReply.Alive -> {
                buffer.writeByte(REPLY_ALIVE)
            }

            IdeTargetReply.Detached -> {
                buffer.writeByte(REPLY_DETACHED)
            }

            is IdeTargetReply.Failed -> {
                buffer.writeByte(REPLY_FAILED)
                buffer.writeFailure(reply.failure)
                buffer.writeBoolean(reply.retryable)
            }

            is IdeTargetReply.FileStatObserved -> {
                buffer.writeByte(REPLY_FILE_STAT)
                buffer.writeFileStat(reply.stat)
            }

            is IdeTargetReply.FileListed -> {
                buffer.writeByte(REPLY_FILE_LIST)
                buffer.writeDirectoryListing(reply.listing)
            }

            is IdeTargetReply.FileRead -> {
                buffer.writeByte(REPLY_FILE_READ)
                buffer.writeFileChunk(reply.chunk)
            }
        }
    }

    fun readReply(buffer: RegistryFriendlyByteBuf): IdeTargetReplyPayload {
        val requestId = buffer.readVarLong()
        val reply =
            when (val kind = buffer.readUnsignedByte().toInt()) {
                REPLY_ATTACHED -> {
                    IdeTargetReply.Attached(buffer.readAttachedTarget())
                }

                REPLY_UPLOAD_ACCEPTED -> {
                    IdeTargetReply.UploadAccepted
                }

                REPLY_VERIFIED -> {
                    IdeTargetReply.Verified(
                        buffer.readBounded(MAXIMUM_TICKET_BYTES, "verification ticket"),
                        buffer.readTarget(),
                        buffer.readHash(),
                        buffer.readVarInt(),
                    )
                }

                REPLY_REVISION -> {
                    IdeTargetReply.RevisionObserved(buffer.readRevision())
                }

                REPLY_DEPLOYED -> {
                    val revision = buffer.readRevision()
                    require(revision is IdeExecutableRevision.Present) { "deployed reply requires a present revision" }
                    IdeTargetReply.Deployed(revision)
                }

                REPLY_STALE_REVISION -> {
                    IdeTargetReply.StaleRevision(buffer.readRevision())
                }

                REPLY_SUBMITTED -> {
                    IdeTargetReply.Submitted
                }

                REPLY_ALIVE -> {
                    IdeTargetReply.Alive
                }

                REPLY_DETACHED -> {
                    IdeTargetReply.Detached
                }

                REPLY_FAILED -> {
                    IdeTargetReply.Failed(buffer.readFailure(), buffer.readBoolean())
                }

                REPLY_FILE_STAT -> {
                    IdeTargetReply.FileStatObserved(buffer.readFileStat())
                }

                REPLY_FILE_LIST -> {
                    IdeTargetReply.FileListed(buffer.readDirectoryListing())
                }

                REPLY_FILE_READ -> {
                    IdeTargetReply.FileRead(buffer.readFileChunk())
                }

                else -> {
                    throw IllegalArgumentException("unknown IDE target reply kind $kind")
                }
            }
        return IdeTargetReplyPayload(requestId, reply)
    }

    private const val ATTACH = 0
    private const val BEGIN_UPLOAD = 1
    private const val UPLOAD_CHUNK = 2
    private const val VERIFY = 3
    private const val EXECUTABLE_REVISION = 4
    private const val DEPLOY = 5
    private const val SUBMIT_CANONICAL_LINE = 6
    private const val HEARTBEAT = 7
    private const val DETACH = 8
    private const val FILE_STAT = 9
    private const val FILE_LIST = 10
    private const val FILE_READ = 11
    private const val REPLY_ATTACHED = 0
    private const val REPLY_UPLOAD_ACCEPTED = 1
    private const val REPLY_VERIFIED = 2
    private const val REPLY_REVISION = 3
    private const val REPLY_DEPLOYED = 4
    private const val REPLY_STALE_REVISION = 5
    private const val REPLY_SUBMITTED = 6
    private const val REPLY_ALIVE = 7
    private const val REPLY_DETACHED = 8
    private const val REPLY_FAILED = 9
    private const val REPLY_FILE_STAT = 10
    private const val REPLY_FILE_LIST = 11
    private const val REPLY_FILE_READ = 12
}

internal fun RegistryFriendlyByteBuf.writeTarget(target: IdeTargetReference) {
    writeUtf(target.id.value, IdeTargetWireProtocol.MAXIMUM_TARGET_ID_CODE_UNITS)
    writeHash(target.profile.value)
}

internal fun RegistryFriendlyByteBuf.readTarget(): IdeTargetReference =
    IdeTargetReference(
        IdeTargetId(readUtf(IdeTargetWireProtocol.MAXIMUM_TARGET_ID_CODE_UNITS)),
        IdeTargetProfileId(readHash()),
    )

private fun RegistryFriendlyByteBuf.writeHash(hash: Hash256) = writeBytes(hash.toByteArray())

private fun RegistryFriendlyByteBuf.readHash(): Hash256 = Hash256.of(ByteArray(32).also(::readBytes))

private fun RegistryFriendlyByteBuf.writeBounded(
    value: BinaryValue,
    maximum: Int,
    description: String,
) {
    val bytes = value.toByteArray()
    require(bytes.isNotEmpty() && bytes.size <= maximum) { "$description must contain 1..$maximum bytes" }
    writeVarInt(bytes.size)
    writeBytes(bytes)
}

private fun RegistryFriendlyByteBuf.readBounded(
    maximum: Int,
    description: String,
): BinaryValue {
    val size = readVarInt()
    require(size in 1..maximum) { "$description must contain 1..$maximum bytes" }
    return BinaryValue.of(ByteArray(size).also(::readBytes))
}

private fun RegistryFriendlyByteBuf.writePath(path: IdeDeploymentPath) = writeUtf(path.value, IdeTargetWireProtocol.MAXIMUM_PATH_CODE_UNITS)

private fun RegistryFriendlyByteBuf.readPath(): IdeDeploymentPath {
    val value = readUtf(IdeTargetWireProtocol.MAXIMUM_PATH_CODE_UNITS)
    require(value.startsWith("/home/")) { "deployment path must be rooted in /home" }
    return IdeDeploymentPath.fromProgramName(value.removePrefix("/home/"))
}

private fun RegistryFriendlyByteBuf.writeVirtualPath(path: IdeTargetVirtualPath) = writeUtf(path.value, 4_096)

private fun RegistryFriendlyByteBuf.readVirtualPath(): IdeTargetVirtualPath = IdeTargetVirtualPath.of(readUtf(4_096))

private fun RegistryFriendlyByteBuf.writeFileMetadata(metadata: IdeTargetFileMetadata) {
    writeByte(if (metadata.kind == IdeTargetFileKind.File) 0 else 1)
    writeBoolean(metadata.executable)
    writeVarLong(metadata.logicalBytes)
    writeVarLong(metadata.generation)
}

private fun RegistryFriendlyByteBuf.readFileMetadata(): IdeTargetFileMetadata {
    val kind =
        when (val kind = readUnsignedByte().toInt()) {
            0 -> IdeTargetFileKind.File
            1 -> IdeTargetFileKind.Directory
            else -> throw IllegalArgumentException("unknown target file kind $kind")
        }
    val executable = readBoolean()
    return IdeTargetFileMetadata(kind, readVarLong(), readVarLong(), executable)
}

private fun RegistryFriendlyByteBuf.writeFileStat(stat: IdeTargetFileStat) {
    writeVarLong(stat.fileSystemGeneration)
    writeFileMetadata(stat.metadata)
}

private fun RegistryFriendlyByteBuf.readFileStat() = IdeTargetFileStat(readVarLong(), readFileMetadata())

private fun RegistryFriendlyByteBuf.writeDirectoryListing(listing: IdeTargetDirectoryListing) {
    require(listing.entries.size <= IdeTargetWireProtocol.MAXIMUM_FILE_LIST_ENTRIES)
    writeVarLong(listing.fileSystemGeneration)
    writeVarLong(listing.directoryGeneration)
    writeBoolean(listing.complete)
    writeVarInt(listing.entries.size)
    listing.entries.forEach { entry ->
        writeUtf(entry.name, 4_096)
        writeFileMetadata(entry.metadata)
    }
}

private fun RegistryFriendlyByteBuf.readDirectoryListing(): IdeTargetDirectoryListing {
    val filesystemGeneration = readVarLong()
    val directoryGeneration = readVarLong()
    val complete = readBoolean()
    val count = readVarInt()
    require(count in 0..IdeTargetWireProtocol.MAXIMUM_FILE_LIST_ENTRIES)
    return IdeTargetDirectoryListing(
        filesystemGeneration,
        directoryGeneration,
        complete,
        List(count) { IdeTargetDirectoryEntry(readUtf(4_096), readFileMetadata()) },
    )
}

private fun RegistryFriendlyByteBuf.writeFileChunk(chunk: IdeTargetFileChunk) {
    val bytes = chunk.bytes()
    require(bytes.size <= IdeTargetWireProtocol.MAXIMUM_FILE_READ_BYTES)
    writeVarLong(chunk.generation)
    writeVarLong(chunk.nextOffset)
    writeBoolean(chunk.eof)
    writeVarInt(bytes.size)
    writeBytes(bytes)
}

private fun RegistryFriendlyByteBuf.readFileChunk(): IdeTargetFileChunk {
    val generation = readVarLong()
    val nextOffset = readVarLong()
    val eof = readBoolean()
    val size = readVarInt()
    require(size in 0..IdeTargetWireProtocol.MAXIMUM_FILE_READ_BYTES)
    return IdeTargetFileChunk(generation, nextOffset, eof, ByteArray(size).also(::readBytes))
}

private fun RegistryFriendlyByteBuf.writeRevision(revision: IdeExecutableRevision) {
    when (revision) {
        IdeExecutableRevision.Absent -> {
            writeByte(0)
        }

        is IdeExecutableRevision.Present -> {
            writeByte(1)
            writeVarLong(revision.generation)
        }
    }
}

private fun RegistryFriendlyByteBuf.readRevision(): IdeExecutableRevision =
    when (val kind = readUnsignedByte().toInt()) {
        0 -> IdeExecutableRevision.Absent
        1 -> IdeExecutableRevision.Present(readVarLong())
        else -> throw IllegalArgumentException("unknown executable revision kind $kind")
    }

private fun RegistryFriendlyByteBuf.writeCanonicalLine(line: IdeCanonicalLine) {
    val chars = line.chars()
    require(chars.size <= IdeTargetWireProtocol.MAXIMUM_CANONICAL_LINE_CODE_UNITS) { "canonical line is too long" }
    writeVarInt(chars.size)
    chars.forEach { value -> writeShort(value.code) }
}

private fun RegistryFriendlyByteBuf.readCanonicalLine(): IdeCanonicalLine {
    val size = readVarInt()
    require(size in 0..IdeTargetWireProtocol.MAXIMUM_CANONICAL_LINE_CODE_UNITS) { "canonical line is too long" }
    return IdeCanonicalLine.of(CharArray(size) { readUnsignedShort().toChar() })
}

private fun RegistryFriendlyByteBuf.writeAttachedTarget(target: IdeAttachedTarget) {
    writeTarget(IdeTargetReference(target.id, target.profile))
    writeProfile(target.compileProfile)
    writeBoolean(target.capabilities.writableFileSystem)
    writeBoolean(target.capabilities.canonicalInput)
    writeBoolean(target.capabilities.terminal)
    writeBoolean(target.capabilities.readableFileSystem)
    writeUtf(target.displayName, 128)
}

private fun RegistryFriendlyByteBuf.readAttachedTarget(): IdeAttachedTarget {
    val target = readTarget()
    return IdeAttachedTarget(
        target.id,
        target.profile,
        readProfile(),
        IdeTargetCapabilities(readBoolean(), readBoolean(), readBoolean(), readBoolean()),
        readUtf(128),
    )
}

private fun RegistryFriendlyByteBuf.writeProfile(profile: TargetCompileProfile) {
    writeToolchain(profile.toolchain)
    require(profile.modules.size <= 128) { "target profile has too many modules" }
    writeVarInt(profile.modules.size)
    profile.modules.forEach { module ->
        writeUtf(module.id.value, 129)
        writeVarInt(module.major.value)
        writeUtf(module.version, 128)
        writeHash(module.contentHash)
    }
    writeLimits(profile.limits)
}

private fun RegistryFriendlyByteBuf.readProfile(): TargetCompileProfile {
    val toolchain = readToolchain()
    val count = readVarInt()
    require(count in 0..128) { "target profile has too many modules" }
    val modules =
        List(count) {
            ResolvedModule(
                ModuleId.parse(readUtf(129)),
                ApiMajor(readVarInt()),
                readUtf(128),
                readHash(),
            )
        }
    return TargetCompileProfile(toolchain, modules, readLimits())
}

private fun RegistryFriendlyByteBuf.writeToolchain(toolchain: ToolchainLockIdentity) {
    writeUtf(toolchain.compilerVersion, 128)
    writeUtf(toolchain.languageVersion, 128)
    writeInt(toolchain.codegenAbi.toInt())
    writeInt(toolchain.artifactAbi.toInt())
    writeInt(toolchain.artifactWriterVersion.toInt())
    writeHash(toolchain.payloadHash)
    writeHash(toolchain.platformAbi)
}

private fun RegistryFriendlyByteBuf.readToolchain(): ToolchainLockIdentity =
    ToolchainLockIdentity(
        readUtf(128),
        readUtf(128),
        readInt().toUInt(),
        readInt().toUInt(),
        readInt().toUInt(),
        readHash(),
        readHash(),
    )

private fun RegistryFriendlyByteBuf.writeLimits(limits: WorkerLimits) {
    writeVarInt(limits.sourceFiles)
    writeVarInt(limits.sourceFileBytes)
    writeVarInt(limits.sourceBytes)
    writeVarInt(limits.frameBytes)
    writeVarInt(limits.artifactBytes)
    writeVarInt(limits.diagnostics)
    writeVarInt(limits.diagnosticTextBytes)
    writeVarInt(limits.stderrBytes)
    writeVarLong(limits.temporaryBytes)
    writeVarInt(limits.temporaryFiles)
}

private fun RegistryFriendlyByteBuf.readLimits(): WorkerLimits =
    WorkerLimits(
        sourceFiles = readVarInt(),
        sourceFileBytes = readVarInt(),
        sourceBytes = readVarInt(),
        frameBytes = readVarInt(),
        artifactBytes = readVarInt(),
        diagnostics = readVarInt(),
        diagnosticTextBytes = readVarInt(),
        stderrBytes = readVarInt(),
        temporaryBytes = readVarLong(),
        temporaryFiles = readVarInt(),
    )

private fun RegistryFriendlyByteBuf.writeFailure(failure: IdeTargetFailure) {
    writeVarInt(failure.kind.ordinal)
    writeUtf(failure.detail, 512)
}

private fun RegistryFriendlyByteBuf.readFailure(): IdeTargetFailure {
    val kind = readVarInt()
    require(kind in IdeTargetFailureKind.entries.indices) { "unknown IDE target failure kind" }
    return IdeTargetFailure(IdeTargetFailureKind.entries[kind], readUtf(512))
}
