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

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RuntimeBundleSupportTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun pinsTheCurrentAbi12RuntimeRelease() {
        val contract = currentRuntimeBundleContract("0".repeat(40))

        assertEquals("0.12.0", contract.runtimeVersion)
        assertEquals("v0.12.0", contract.releaseTag)
        assertEquals(12, contract.ffiAbi)
        assertEquals(2, contract.formats["resource-snapshot"])
    }

    @Test
    fun downloadsTheExactPinnedReleaseAssetsAndReusesTheCompleteCache() {
        val contract = currentRuntimeBundleContract("0".repeat(40))
        val destination = temporary.resolve("downloaded")
        val requested = mutableListOf<URI>()
        val payloads =
            runtimeBundleAssetNames(contract).associateWith { name ->
                "payload:$name".encodeToByteArray()
            }

        val first =
            RuntimeBundleDownloadSupport.download(destination, contract) { uri ->
                requested += uri
                ByteArrayInputStream(payloads.getValue(uri.path.substringAfterLast('/')))
            }

        assertEquals(RuntimeBundleDownloadResult.DOWNLOADED, first)
        assertEquals(
            runtimeBundleAssetNames(contract).map { name ->
                URI("https://github.com/CertifiedBadIdeas/Compukter-VM/releases/download/${contract.releaseTag}/$name")
            },
            requested,
        )
        payloads.forEach { (name, bytes) -> assertArrayEquals(bytes, destination.resolve(name).readBytes()) }

        val second =
            RuntimeBundleDownloadSupport.download(destination, contract) {
                error("complete Runtime cache must not access the network")
            }

        assertEquals(RuntimeBundleDownloadResult.CACHED, second)
    }

    @Test
    fun failedAssetDownloadDoesNotPublishAPartialFileAndCanResume() {
        val contract = currentRuntimeBundleContract("0".repeat(40))
        val destination = temporary.resolve("resume")
        val names = runtimeBundleAssetNames(contract)
        var fail = true

        assertThrows(IOException::class.java) {
            RuntimeBundleDownloadSupport.download(destination, contract) { uri ->
                val name = uri.path.substringAfterLast('/')
                if (fail && name == names[1]) throw IOException("connection lost")
                ByteArrayInputStream("payload:$name".encodeToByteArray())
            }
        }
        assertEquals("payload:${names[0]}", destination.resolve(names[0]).readText())
        assertEquals(false, Files.exists(destination.resolve(names[1])))
        assertEquals(false, Files.list(destination).use { files -> files.anyMatch { it.fileName.toString().endsWith(".part") } })

        fail = false
        assertEquals(
            RuntimeBundleDownloadResult.DOWNLOADED,
            RuntimeBundleDownloadSupport.download(destination, contract) { uri ->
                ByteArrayInputStream("payload:${uri.path.substringAfterLast('/')}".encodeToByteArray())
            },
        )
    }

    @Test
    fun acceptsAnAssetPublishedConcurrentlyByAnotherDownloader() {
        val contract = currentRuntimeBundleContract("0".repeat(40))
        val destination = temporary.resolve("concurrent")
        val firstAsset = runtimeBundleAssetNames(contract).first()

        assertEquals(
            RuntimeBundleDownloadResult.DOWNLOADED,
            RuntimeBundleDownloadSupport.download(destination, contract) { uri ->
                val name = uri.path.substringAfterLast('/')
                if (name == firstAsset) {
                    Files.createDirectories(destination)
                    Files.writeString(destination.resolve(name), "concurrent winner")
                }
                ByteArrayInputStream("losing download:$name".encodeToByteArray())
            },
        )

        assertEquals("concurrent winner", destination.resolve(firstAsset).readText())
    }

    @Test
    fun validatesCompleteBundlesAndStagesOnlyTheSelectedTransport() {
        val fixture = Fixture(temporary)

        val stagedFfi =
            RuntimeBundleSupport.validateAndStage(
                fixture.bundles,
                fixture.staging.resolve("ffi"),
                fixture.contract,
                RuntimeTransport.FFI,
            )

        assertEquals(
            listOf(
                "META-INF/natives/linux/x86_64/libcompukter_ffi.so",
                "META-INF/natives/windows/x86_64/compukter_ffi.dll",
            ),
            stagedFfi.map { it.resourcePath },
        )
        assertArrayEquals(Fixture.LINUX_FFI, fixture.staging.resolve("ffi/${stagedFfi[0].resourcePath}").readBytes())
        assertArrayEquals(Fixture.WINDOWS_FFI, fixture.staging.resolve("ffi/${stagedFfi[1].resourcePath}").readBytes())

        val stagedJni =
            RuntimeBundleSupport.validateAndStage(
                fixture.bundles,
                fixture.staging.resolve("jni"),
                fixture.contract,
                RuntimeTransport.JNI,
            )
        assertEquals(
            listOf(
                "META-INF/natives/linux/x86_64/libcompukter_jni.so",
                "META-INF/natives/windows/x86_64/compukter_jni.dll",
            ),
            stagedJni.map { it.resourcePath },
        )
        assertArrayEquals(Fixture.LINUX_JNI, fixture.staging.resolve("jni/${stagedJni[0].resourcePath}").readBytes())
        assertArrayEquals(Fixture.WINDOWS_JNI, fixture.staging.resolve("jni/${stagedJni[1].resourcePath}").readBytes())
    }

    @Test
    fun rejectsWrongOuterChecksumBeforeStagingAnything() {
        val fixture = Fixture(temporary)
        fixture.checksums.writeText(fixture.checksums.toFile().readText().replaceFirst(Regex("[0-9a-f]{64}"), "0".repeat(64)))

        assertThrows(IllegalArgumentException::class.java) {
            RuntimeBundleSupport.validateAndStage(fixture.bundles, fixture.staging, fixture.contract, RuntimeTransport.FFI)
        }
        assertEquals(false, Files.exists(fixture.staging))
    }

    @Test
    fun rejectsManifestIdentityAndUnexpectedArchiveEntries() {
        val wrongCommit = Fixture(temporary.resolve("wrong-commit"), vmCommit = "1".repeat(40))
        val extraEntry = Fixture(temporary.resolve("extra-entry"), extraWindowsEntry = "../escape.dll")

        assertThrows(IllegalArgumentException::class.java) {
            RuntimeBundleSupport.validateAndStage(
                wrongCommit.bundles,
                wrongCommit.staging,
                Fixture.CONTRACT,
                RuntimeTransport.FFI,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeBundleSupport.validateAndStage(
                extraEntry.bundles,
                extraEntry.staging,
                extraEntry.contract,
                RuntimeTransport.FFI,
            )
        }
    }

    @Test
    fun rejectsMissingInputsAndEmptyNativePayloads() {
        val missing = Fixture(temporary.resolve("missing"))
        val empty = Fixture(temporary.resolve("empty"), linuxJni = byteArrayOf())
        Files.delete(missing.checksums)

        assertThrows(Exception::class.java) {
            RuntimeBundleSupport.validateAndStage(missing.bundles, missing.staging, missing.contract, RuntimeTransport.JNI)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeBundleSupport.validateAndStage(empty.bundles, empty.staging, empty.contract, RuntimeTransport.FFI)
        }
    }

    private class Fixture(
        root: Path,
        vmCommit: String = COMMIT,
        extraWindowsEntry: String? = null,
        linuxJni: ByteArray = LINUX_JNI,
    ) {
        val bundles: Path = root.resolve("bundles")
        val staging: Path = root.resolve("staging")
        val contract: RuntimeBundleContract = CONTRACT.copy(vmCommit = COMMIT)
        val checksums: Path = bundles.resolve("compukter-runtime-0.10.0-checksums.sha256")

        init {
            Files.createDirectories(bundles)
            val linux = bundles.resolve("compukter-runtime-0.10.0-linux-x86_64.tar.gz")
            val windows = bundles.resolve("compukter-runtime-0.10.0-windows-x86_64.zip")
            writeTar(
                linux,
                entries(
                    "x86_64-unknown-linux-gnu",
                    "libcompukter_ffi.so",
                    "libcompukter_jni.so",
                    LINUX_FFI,
                    linuxJni,
                    vmCommit,
                ),
            )
            writeZip(
                windows,
                entries(
                    "x86_64-pc-windows-msvc",
                    "compukter_ffi.dll",
                    "compukter_jni.dll",
                    WINDOWS_FFI,
                    WINDOWS_JNI,
                    vmCommit,
                ) +
                    listOfNotNull(extraWindowsEntry?.let { it to byteArrayOf(1) }),
            )
            checksums.writeText(
                "${sha256(linux.readBytes())}  ${linux.fileName}\n" +
                    "${sha256(windows.readBytes())}  ${windows.fileName}\n",
            )
        }

        private fun entries(
            target: String,
            ffiFilename: String,
            jniFilename: String,
            ffiNative: ByteArray,
            jniNative: ByteArray,
            vmCommit: String,
        ): List<Pair<String, ByteArray>> =
            listOf(
                "native/$ffiFilename" to ffiNative,
                "native/$jniFilename" to jniNative,
                "manifest.json" to
                    manifest(target, ffiFilename, jniFilename, ffiNative, jniNative, vmCommit).encodeToByteArray(),
                "LICENSE.txt" to "Apache-2.0\n".encodeToByteArray(),
                "NOTICE.txt" to "Compukter Runtime\n".encodeToByteArray(),
            )

        private fun manifest(
            target: String,
            ffiFilename: String,
            jniFilename: String,
            ffiNative: ByteArray,
            jniNative: ByteArray,
            vmCommit: String,
        ): String =
            """
            {
              "schema": 2,
              "runtime_version": "0.10.0",
              "release_tag": "v0.10.0",
              "vm_commit": "$vmCommit",
              "ffi_abi": 10,
              "formats": {
                "artifact": 2,
                "compilation-request": 1,
                "executable-revision": 1,
                "filesystem-generation": 1
              },
              "rustc": "rustc 1.98.0",
              "target": "$target",
              "libraries": {
                "ffi": {
                  "filename": "$ffiFilename",
                  "size": ${ffiNative.size},
                  "sha256": "${sha256(ffiNative)}"
                },
                "jni": {
                  "filename": "$jniFilename",
                  "size": ${jniNative.size},
                  "sha256": "${sha256(jniNative)}"
                }
              },
              "profile": "release"
            }
            """.trimIndent() + "\n"

        private fun writeTar(path: Path, entries: List<Pair<String, ByteArray>>) {
            Files.newOutputStream(path).use { output ->
                GzipCompressorOutputStream(output).use { gzip ->
                    TarArchiveOutputStream(gzip).use { tar ->
                        entries.forEach { (name, bytes) ->
                            val entry = TarArchiveEntry(name).apply {
                                size = bytes.size.toLong()
                                mode = 0b110100100
                            }
                            tar.putArchiveEntry(entry)
                            tar.write(bytes)
                            tar.closeArchiveEntry()
                        }
                    }
                }
            }
        }

        private fun writeZip(path: Path, entries: List<Pair<String, ByteArray>>) {
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

        companion object {
            const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
            val LINUX_FFI = "linux-ffi".encodeToByteArray()
            val LINUX_JNI = "linux-jni".encodeToByteArray()
            val WINDOWS_FFI = "windows-ffi".encodeToByteArray()
            val WINDOWS_JNI = "windows-jni".encodeToByteArray()
            val CONTRACT =
                RuntimeBundleContract(
                    runtimeVersion = "0.10.0",
                    ffiAbi = 10,
                    vmCommit = COMMIT,
                    formats =
                        sortedMapOf(
                            "artifact" to 2,
                            "compilation-request" to 1,
                            "executable-revision" to 1,
                            "filesystem-generation" to 1,
                        ),
                )
        }
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
