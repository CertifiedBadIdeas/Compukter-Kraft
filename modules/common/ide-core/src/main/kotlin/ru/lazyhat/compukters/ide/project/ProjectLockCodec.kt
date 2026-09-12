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

package ru.lazyhat.compukters.ide.project

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256

class ProjectLockException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object ProjectLockCodec {
    fun decode(
        source: String,
        limits: ProjectLimits = ProjectLimits(),
    ): ProjectLock {
        val bytes = validated("lock must be strict UTF-8") { TomlSupport.strictUtf8(source) }
        if (bytes.size > limits.lockBytes) throw ProjectLockException("lock byte count exceeds limit")
        val parsed = Toml.parse(source)
        if (parsed.hasErrors()) {
            val error = parsed.errors().first()
            throw ProjectLockException("lock TOML error at ${error.position()}: ${error.message}", error)
        }
        rejectUnknown(parsed, ROOT_FIELDS, "lock")
        val format = requiredLong(parsed, "format", "lock")
        if (format != ProjectLock.FORMAT.toLong()) throw ProjectLockException("unsupported lock format: $format")
        val toolchain = parsed["toolchain"] as? TomlTable ?: throw ProjectLockException("lock toolchain must be a table")
        rejectUnknown(toolchain, TOOLCHAIN_FIELDS, "toolchain")
        val identity =
            validated("invalid toolchain identity") {
                ToolchainLockIdentity(
                    compilerVersion = requiredString(toolchain, "compiler", "toolchain"),
                    languageVersion = requiredString(toolchain, "language", "toolchain"),
                    codegenAbi = requiredUInt(toolchain, "codegen_abi", "toolchain"),
                    artifactAbi = requiredUInt(toolchain, "artifact_abi", "toolchain"),
                    artifactWriterVersion = requiredUInt(toolchain, "artifact_writer", "toolchain"),
                    payloadHash = requiredHash(toolchain, "payload_sha256", "toolchain"),
                    platformAbi = requiredHash(toolchain, "platform_abi_sha256", "toolchain"),
                )
            }
        val modules = parseModules(parsed["modules"], limits)
        return validated("invalid project lock") { ProjectLock.of(identity, modules, limits) }
    }

    fun encode(lock: ProjectLock): String =
        buildString {
            append("format = ").append(lock.format).append("\n\n")
            append("[toolchain]\n")
            append("compiler = ").append(TomlSupport.quoted(lock.toolchain.compilerVersion)).append('\n')
            append("language = ").append(TomlSupport.quoted(lock.toolchain.languageVersion)).append('\n')
            append("codegen_abi = ").append(lock.toolchain.codegenAbi).append('\n')
            append("artifact_abi = ").append(lock.toolchain.artifactAbi).append('\n')
            append("artifact_writer = ").append(lock.toolchain.artifactWriterVersion).append('\n')
            append("payload_sha256 = ").append(TomlSupport.quoted(lock.toolchain.payloadHash.hex())).append('\n')
            append("platform_abi_sha256 = ").append(TomlSupport.quoted(lock.toolchain.platformAbi.hex())).append('\n')
            lock.modules.forEach { locked ->
                val module = locked.identity
                append("\n[[modules]]\n")
                append("id = ").append(TomlSupport.quoted(module.id.value)).append('\n')
                append("major = ").append(module.major.value).append('\n')
                append("version = ").append(TomlSupport.quoted(module.version)).append('\n')
                append("content_sha256 = ").append(TomlSupport.quoted(module.contentHash.hex())).append('\n')
                append("direct = ").append(locked.direct).append('\n')
            }
        }

    private fun parseModules(
        raw: Any?,
        limits: ProjectLimits,
    ): List<LockedModule> {
        if (raw == null) return emptyList()
        val array = raw as? TomlArray ?: throw ProjectLockException("lock modules must be an array of tables")
        if (array.size() > limits.modules) throw ProjectLockException("project module count exceeds limit")
        return List(array.size()) { index ->
            val table = array[index] as? TomlTable ?: throw ProjectLockException("lock module $index must be a table")
            rejectUnknown(table, MODULE_FIELDS, "module")
            validated("invalid locked module at index $index") {
                LockedModule(
                    identity =
                        ResolvedModule(
                            id = ModuleId.parse(requiredString(table, "id", "module")),
                            major = ApiMajor(requiredUInt(table, "major", "module", ApiMajor.MAXIMUM.toUInt()).toInt()),
                            version = requiredString(table, "version", "module"),
                            contentHash = requiredHash(table, "content_sha256", "module"),
                        ),
                    direct = requiredBoolean(table, "direct", "module"),
                )
            }
        }
    }

    private fun requiredHash(
        table: TomlTable,
        key: String,
        owner: String,
    ): Hash256 = validated("invalid $owner $key") { Hash256.fromHex(requiredString(table, key, owner)) }

    private fun requiredUInt(
        table: TomlTable,
        key: String,
        owner: String,
        maximum: UInt = UInt.MAX_VALUE,
    ): UInt {
        val value = requiredLong(table, key, owner)
        if (value < 0 || value.toULong() > maximum.toULong()) throw ProjectLockException("$owner $key is outside range")
        return value.toUInt()
    }

    private fun requiredLong(
        table: TomlTable,
        key: String,
        owner: String,
    ): Long = table[key] as? Long ?: throw ProjectLockException("$owner $key must be an integer")

    private fun requiredString(
        table: TomlTable,
        key: String,
        owner: String,
    ): String = table[key] as? String ?: throw ProjectLockException("$owner $key must be a string")

    private fun requiredBoolean(
        table: TomlTable,
        key: String,
        owner: String,
    ): Boolean = table[key] as? Boolean ?: throw ProjectLockException("$owner $key must be a boolean")

    private fun rejectUnknown(
        table: TomlTable,
        allowed: Set<String>,
        owner: String,
    ) {
        val unknown = table.keySet() - allowed
        if (unknown.isNotEmpty()) throw ProjectLockException("unknown $owner key: ${unknown.sorted().first()}")
    }

    private inline fun <T> validated(
        message: String,
        action: () -> T,
    ): T =
        try {
            action()
        } catch (exception: ProjectLockException) {
            throw exception
        } catch (exception: Exception) {
            throw ProjectLockException("$message: ${exception.message}", exception)
        }

    private val ROOT_FIELDS = setOf("format", "toolchain", "modules")
    private val TOOLCHAIN_FIELDS =
        setOf("compiler", "language", "codegen_abi", "artifact_abi", "artifact_writer", "payload_sha256", "platform_abi_sha256")
    private val MODULE_FIELDS = setOf("id", "major", "version", "content_sha256", "direct")
}
