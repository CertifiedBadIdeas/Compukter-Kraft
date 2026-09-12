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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.name

data class RuntimeBundleContract(
    val runtimeVersion: String,
    val ffiAbi: Int,
    val vmCommit: String,
    val formats: Map<String, Int>,
) {
    val releaseTag: String
        get() = "v$runtimeVersion"
}

data class StagedRuntimeNative(
    val target: String,
    val resourcePath: String,
    val size: Long,
    val sha256: String,
)

enum class RuntimeTransport(val id: String) {
    FFI("ffi"),
    JNI("jni"),
}

enum class RuntimeBundleDownloadResult {
    CACHED,
    DOWNLOADED,
}

fun currentRuntimeBundleContract(vmCommit: String): RuntimeBundleContract =
    RuntimeBundleContract(
        runtimeVersion = "0.12.0",
        ffiAbi = 12,
        vmCommit = vmCommit,
        formats =
            sortedMapOf(
                "artifact" to 2,
                "compilation-request" to 1,
                "executable-revision" to 1,
                "filesystem-generation" to 1,
                "resource-snapshot" to 2,
            ),
    )

fun runtimeBundleAssetNames(contract: RuntimeBundleContract): List<String> {
    require(Regex("""0\.(0|[1-9]\d*)\.(0|[1-9]\d*)""").matches(contract.runtimeVersion)) {
        "runtime version is not canonical"
    }
    return listOf(
        "compukter-runtime-${contract.runtimeVersion}-checksums.sha256",
        "compukter-runtime-${contract.runtimeVersion}-linux-x86_64.tar.gz",
        "compukter-runtime-${contract.runtimeVersion}-windows-x86_64.zip",
    )
}

object RuntimeBundleDownloadSupport {
    private const val RELEASE_DOWNLOAD_BASE =
        "https://github.com/CertifiedBadIdeas/Compukter-VM/releases/download"
    private const val MAXIMUM_ASSET_BYTES = 256L * 1024 * 1024
    private val httpClient: HttpClient by lazy {
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun download(
        destination: Path,
        contract: RuntimeBundleContract,
        open: (URI) -> InputStream = ::openReleaseAsset,
    ): RuntimeBundleDownloadResult {
        val assets = runtimeBundleAssetNames(contract)
        Files.createDirectories(destination)
        require(Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            "runtime cache destination is not a directory"
        }
        if (assets.all { Files.isRegularFile(destination.resolve(it), LinkOption.NOFOLLOW_LINKS) }) {
            return RuntimeBundleDownloadResult.CACHED
        }

        var downloaded = false
        assets.forEach { asset ->
            val target = destination.resolve(asset)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    "runtime cache asset is not a regular file: $asset"
                }
                return@forEach
            }
            val temporary = Files.createTempFile(destination, ".$asset.", ".part")
            try {
                open(releaseAssetUri(contract, asset)).use { input ->
                    Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAXIMUM_ASSET_BYTES) { "runtime release asset exceeds its byte limit: $asset" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                try {
                    Files.move(temporary, target)
                } catch (_: FileAlreadyExistsException) {
                    require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                        "concurrently published runtime cache asset is not a regular file: $asset"
                    }
                }
                downloaded = true
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        return if (downloaded) RuntimeBundleDownloadResult.DOWNLOADED else RuntimeBundleDownloadResult.CACHED
    }

    private fun releaseAssetUri(contract: RuntimeBundleContract, asset: String): URI =
        URI("$RELEASE_DOWNLOAD_BASE/${contract.releaseTag}/$asset")

    private fun openReleaseAsset(uri: URI): InputStream {
        val request =
            HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "Compukters-Gradle-Runtime-Downloader")
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw IOException("Runtime release download failed with HTTP ${response.statusCode()}: $uri")
        }
        return response.body()
    }
}

object RuntimeBundleSupport {
    private const val MAXIMUM_NATIVE_BYTES = 128L * 1024 * 1024
    private const val MAXIMUM_METADATA_BYTES = 1024L * 1024
    private const val MAXIMUM_ARCHIVE_BYTES = 2 * MAXIMUM_NATIVE_BYTES + 3 * MAXIMUM_METADATA_BYTES
    private val json = Json { ignoreUnknownKeys = false }
    private val manifestKeys =
        setOf(
            "schema",
            "runtime_version",
            "release_tag",
            "vm_commit",
            "ffi_abi",
            "formats",
            "rustc",
            "target",
            "libraries",
            "profile",
        )
    private val libraryKeys = setOf("filename", "size", "sha256")

    fun validateAndStage(
        bundleDirectory: Path,
        stagingDirectory: Path,
        contract: RuntimeBundleContract,
        transport: RuntimeTransport,
    ): List<StagedRuntimeNative> {
        validateContract(contract)
        require(!Files.exists(stagingDirectory, LinkOption.NOFOLLOW_LINKS)) {
            "runtime staging directory already exists"
        }
        val platforms = platforms(contract.runtimeVersion)
        val checksumFile = bundleDirectory.resolve("compukter-runtime-${contract.runtimeVersion}-checksums.sha256")
        val expectedChecksums = readChecksums(checksumFile, platforms.map(Platform::archiveName))
        val validated =
            platforms.map { platform ->
                val archive = bundleDirectory.resolve(platform.archiveName)
                requireRegularBounded(archive, MAXIMUM_ARCHIVE_BYTES, "runtime archive")
                require(sha256(archive) == expectedChecksums.getValue(platform.archiveName)) {
                    "runtime archive checksum does not match: ${platform.archiveName}"
                }
                validateArchive(archive, platform, contract)
            }

        val selected = validated.map { platform -> platform.getValue(transport) }
        selected.forEach { native ->
            val output = stagingDirectory.resolve(native.result.resourcePath)
            Files.createDirectories(output.parent)
            Files.write(output, native.bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
        return selected.map(ValidatedNative::result)
    }

    private fun validateArchive(
        archive: Path,
        platform: Platform,
        contract: RuntimeBundleContract,
    ): Map<RuntimeTransport, ValidatedNative> {
        val contents =
            when (platform.archiveKind) {
                ArchiveKind.TAR_GZ -> readTarGz(archive)
                ArchiveKind.ZIP -> readZip(archive)
            }
        val nativeEntries = platform.libraries.values.map { library -> "native/${library.filename}" }.toSet()
        require(contents.keys == nativeEntries + setOf("manifest.json", "LICENSE.txt", "NOTICE.txt")) {
            "runtime bundle entries do not match the fixed layout"
        }
        require(contents.getValue("LICENSE.txt").isNotEmpty()) { "runtime license must not be empty" }
        require(contents.getValue("NOTICE.txt").isNotEmpty()) { "runtime notice must not be empty" }
        val manifest = parseManifest(contents.getValue("manifest.json"))
        require(manifest.keys == manifestKeys) { "runtime manifest fields do not match schema 2" }
        require(manifest.int("schema") == 2) { "runtime manifest schema must be 2" }
        require(manifest.string("runtime_version") == contract.runtimeVersion) { "runtime version mismatch" }
        require(manifest.string("release_tag") == contract.releaseTag) { "runtime release tag mismatch" }
        require(manifest.string("vm_commit") == contract.vmCommit) { "runtime VM commit mismatch" }
        require(manifest.int("ffi_abi") == contract.ffiAbi) { "runtime FFI ABI mismatch" }
        require(manifest.string("profile") == "release") { "runtime profile must be release" }
        require(manifest.string("rustc").isNotEmpty()) { "runtime rustc identity must not be empty" }
        require(manifest.string("target") == platform.target) { "runtime target mismatch" }
        val libraries = manifest.getValue("libraries").jsonObject
        require(libraries.keys == RuntimeTransport.entries.map(RuntimeTransport::id).toSet()) {
            "runtime manifest must contain exactly the FFI and JNI libraries"
        }
        val validated =
            RuntimeTransport.entries.associateWith { transport ->
                val expected = platform.libraries.getValue(transport)
                val library = libraries.getValue(transport.id).jsonObject
                require(library.keys == libraryKeys) { "runtime library fields do not match schema 2" }
                require(library.string("filename") == expected.filename) { "runtime native filename mismatch" }
                val native = contents.getValue("native/${expected.filename}")
                require(native.isNotEmpty()) { "runtime native payload must not be empty" }
                require(library.long("size") == native.size.toLong()) { "runtime native size mismatch" }
                val digest = sha256(native)
                require(library.string("sha256") == digest) { "runtime native SHA-256 mismatch" }
                ValidatedNative(
                    StagedRuntimeNative(platform.target, expected.resourcePath, native.size.toLong(), digest),
                    native,
                )
            }
        val formats =
            manifest.getValue("formats").jsonObject.mapValues { (_, value) ->
                require(!value.jsonPrimitive.isString) { "runtime format version must be an integer" }
                value.jsonPrimitive.int
            }
        require(formats == contract.formats) { "runtime format versions mismatch" }
        return validated
    }

    private fun readChecksums(path: Path, expectedNames: List<String>): Map<String, String> {
        requireRegularBounded(path, MAXIMUM_METADATA_BYTES, "runtime checksum file")
        val lines = strictUtf8(Files.readAllBytes(path)).removeSuffix("\n").lines()
        require(lines.size == expectedNames.size) { "runtime checksum file must contain exactly two entries" }
        val parsed =
            lines.associate { line ->
                val parts = line.split("  ")
                require(parts.size == 2 && isLowerHex(parts[0], 64) && safeEntryName(parts[1])) {
                    "runtime checksum entry is not canonical"
                }
                parts[1] to parts[0]
            }
        require(parsed.size == expectedNames.size && parsed.keys == expectedNames.toSet()) {
            "runtime checksum entries do not match the required archives"
        }
        return parsed
    }

    private fun readTarGz(path: Path): Map<String, ByteArray> =
        Files.newInputStream(path).use { file ->
            GzipCompressorInputStream(file).use { gzip ->
                TarArchiveInputStream(gzip).use { archive -> readEntries(archive) { it.isFile } }
            }
        }

    private fun readZip(path: Path): Map<String, ByteArray> =
        Files.newInputStream(path).use { file ->
            ZipArchiveInputStream(file).use { archive ->
                readEntries(archive) { entry ->
                    !entry.isDirectory && (entry.unixMode and 0xF000) != 0xA000
                }
            }
        }

    private fun <E : ArchiveEntry> readEntries(
        archive: ArchiveInputStream<E>,
        isRegular: (E) -> Boolean,
    ): Map<String, ByteArray> {
        val contents = linkedMapOf<String, ByteArray>()
        while (true) {
            val entry = archive.nextEntry ?: break
            require(isRegular(entry)) { "runtime archive entries must be regular files" }
            require(safeEntryName(entry.name)) { "runtime archive entry path is unsafe" }
            val maximum = if (entry.name.startsWith("native/")) MAXIMUM_NATIVE_BYTES else MAXIMUM_METADATA_BYTES
            require(entry.size < 0 || entry.size <= maximum) { "runtime archive entry exceeds its byte limit" }
            val bytes = readBounded(archive, maximum)
            require(contents.put(entry.name, bytes) == null) { "runtime archive contains duplicate entries" }
            require(contents.size <= 5) { "runtime archive contains too many entries" }
        }
        return contents
    }

    private fun readBounded(input: InputStream, maximum: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maximum) { "runtime archive entry exceeds its byte limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseManifest(bytes: ByteArray): JsonObject =
        json.parseToJsonElement(strictUtf8(bytes)).jsonObject

    private fun strictUtf8(bytes: ByteArray): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun requireRegularBounded(path: Path, maximum: Long, description: String) {
        val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile) { "$description must be a regular file" }
        require(attributes.size() <= maximum) { "$description exceeds its byte limit" }
    }

    private fun validateContract(contract: RuntimeBundleContract) {
        require(Regex("0\\.[1-9][0-9]*\\.(0|[1-9][0-9]*)").matches(contract.runtimeVersion)) {
            "runtime version is not canonical"
        }
        require(contract.ffiAbi == contract.runtimeVersion.split('.')[1].toInt()) { "runtime ABI mismatch" }
        require(isLowerHex(contract.vmCommit, 40)) { "runtime VM commit is not canonical" }
        require(contract.formats.isNotEmpty() && contract.formats.all { (name, version) ->
            Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(name) && version > 0
        }) { "runtime format contract is not canonical" }
    }

    private fun platforms(version: String): List<Platform> =
        listOf(
            Platform(
                target = "x86_64-unknown-linux-gnu",
                archiveName = "compukter-runtime-$version-linux-x86_64.tar.gz",
                libraries =
                    mapOf(
                        RuntimeTransport.FFI to
                            PlatformLibrary(
                                "libcompukter_ffi.so",
                                "META-INF/natives/linux/x86_64/libcompukter_ffi.so",
                            ),
                        RuntimeTransport.JNI to
                            PlatformLibrary(
                                "libcompukter_jni.so",
                                "META-INF/natives/linux/x86_64/libcompukter_jni.so",
                            ),
                    ),
                archiveKind = ArchiveKind.TAR_GZ,
            ),
            Platform(
                target = "x86_64-pc-windows-msvc",
                archiveName = "compukter-runtime-$version-windows-x86_64.zip",
                libraries =
                    mapOf(
                        RuntimeTransport.FFI to
                            PlatformLibrary(
                                "compukter_ffi.dll",
                                "META-INF/natives/windows/x86_64/compukter_ffi.dll",
                            ),
                        RuntimeTransport.JNI to
                            PlatformLibrary(
                                "compukter_jni.dll",
                                "META-INF/natives/windows/x86_64/compukter_jni.dll",
                            ),
                    ),
                archiveKind = ArchiveKind.ZIP,
            ),
        )

    private fun safeEntryName(name: String): Boolean =
        name.isNotEmpty() &&
            '\\' !in name &&
            name.split('/').all { component -> component.isNotEmpty() && component != "." && component != ".." }

    private fun isLowerHex(value: String, length: Int): Boolean =
        value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun sha256(path: Path): String =
        Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().hex()
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(name: String): String {
        val value = getValue(name).jsonPrimitive
        require(value.isString) { "runtime manifest field $name must be a string" }
        return value.content
    }

    private fun JsonObject.int(name: String): Int {
        val value = getValue(name).jsonPrimitive
        require(!value.isString) { "runtime manifest field $name must be an integer" }
        return value.int
    }

    private fun JsonObject.long(name: String): Long {
        val value = getValue(name).jsonPrimitive
        require(!value.isString) { "runtime manifest field $name must be an integer" }
        return value.long
    }

    private data class Platform(
        val target: String,
        val archiveName: String,
        val libraries: Map<RuntimeTransport, PlatformLibrary>,
        val archiveKind: ArchiveKind,
    )

    private data class PlatformLibrary(
        val filename: String,
        val resourcePath: String,
    )

    private data class ValidatedNative(val result: StagedRuntimeNative, val bytes: ByteArray)

    private enum class ArchiveKind { TAR_GZ, ZIP }
}
