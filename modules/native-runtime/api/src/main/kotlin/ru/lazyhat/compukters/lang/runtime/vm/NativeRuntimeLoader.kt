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

package ru.lazyhat.compukters.lang.runtime.vm

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

internal class NativeRuntimeLoader(
    private val osName: () -> String,
    private val osArch: () -> String,
    private val resource: (String) -> InputStream?,
    private val createTempDirectory: () -> Path,
    private val nativeLoad: (Path) -> LowLevelVmBridge,
    private val maximumPackagedNativeBytes: Long = DEFAULT_MAXIMUM_PACKAGED_NATIVE_BYTES,
) {
    init {
        require(maximumPackagedNativeBytes >= 0) { "maximum packaged native bytes must not be negative" }
    }

    private var cached: VmRuntimeLoadResult? = null
    private var bridge: LowLevelVmBridge? = null

    @Synchronized
    fun ensurePackagedLoaded(): VmRuntimeLoadResult = cached ?: loadPackaged().also { cached = it }

    @Synchronized
    fun ensureExplicitLoaded(path: Path): VmRuntimeLoadResult = cached ?: loadExplicit(path).also { cached = it }

    @Synchronized
    fun requireBridge(): LowLevelVmBridge = checkNotNull(bridge) { "native runtime is not loaded" }

    private fun loadExplicit(path: Path): VmRuntimeLoadResult {
        val normalized =
            try {
                path.toAbsolutePath().normalize()
            } catch (error: SecurityException) {
                return invalidExplicit(path, error.runtimeDetail("path access denied"))
            }
        val valid =
            try {
                Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(normalized)
            } catch (error: SecurityException) {
                return invalidExplicit(normalized, error.runtimeDetail("path access denied"))
            }
        if (!valid) return invalidExplicit(normalized, "path is not a readable regular file")

        val source = VmRuntimeLoadSource.ExplicitPath(normalized)
        return loadNative(normalized, source, normalized.toString())
    }

    private fun loadPackaged(): VmRuntimeLoadResult {
        val platform =
            when (val resolution = NativeRuntimePlatform.resolve(osName(), osArch())) {
                is NativePlatformResolution.Supported -> {
                    resolution.platform
                }

                is NativePlatformResolution.Unsupported -> {
                    return VmRuntimeLoadResult.Failed(
                        VmRuntimeLoadFailure.UnsupportedPlatform(resolution.osName, resolution.osArch),
                    )
                }
            }
        val input =
            try {
                resource(platform.resourcePath)
            } catch (error: IOException) {
                return extractionFailure(platform.resourcePath, error.runtimeDetail("resource access failed"))
            } catch (error: SecurityException) {
                return extractionFailure(platform.resourcePath, error.runtimeDetail("resource access denied"))
            } ?: return VmRuntimeLoadResult.Failed(VmRuntimeLoadFailure.MissingResource(platform.resourcePath))

        var directory: Path? = null
        var target: Path? = null
        try {
            input.use { stream ->
                directory = createTempDirectory().toAbsolutePath().normalize()
                applyOwnerOnlyPermissions(requireNotNull(directory))
                target = requireNotNull(directory).resolve(platform.filename)
                copyBounded(stream, requireNotNull(target))
                applyOwnerOnlyPermissions(requireNotNull(target))
                requireNotNull(directory).toFile().deleteOnExit()
                requireNotNull(target).toFile().deleteOnExit()
            }
        } catch (error: PackagedNativeTooLargeException) {
            cleanup(target, directory)
            return extractionFailure(platform.resourcePath, error.message.runtimeDiagnostic("native resource is too large"))
        } catch (error: IOException) {
            cleanup(target, directory)
            return extractionFailure(platform.resourcePath, error.runtimeDetail("resource extraction failed"))
        } catch (error: SecurityException) {
            cleanup(target, directory)
            return extractionFailure(platform.resourcePath, error.runtimeDetail("resource extraction denied"))
        }

        val source = VmRuntimeLoadSource.PackagedResource(platform.resourcePath)
        val result = loadNative(requireNotNull(target), source, platform.resourcePath)
        if (result is VmRuntimeLoadResult.Failed) cleanup(target, directory)
        return result
    }

    private fun copyBounded(
        input: InputStream,
        target: Path,
    ) {
        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var written = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (written > maximumPackagedNativeBytes - count) {
                    throw PackagedNativeTooLargeException(maximumPackagedNativeBytes)
                }
                output.write(buffer, 0, count)
                written += count
            }
        }
    }

    private fun loadNative(
        path: Path,
        source: VmRuntimeLoadSource,
        sourceDetail: String,
    ): VmRuntimeLoadResult =
        try {
            bridge = nativeLoad(path)
            VmRuntimeLoadResult.Loaded(source)
        } catch (error: UnsatisfiedLinkError) {
            nativeLinkFailure(sourceDetail, error.runtimeDetail("native link failed"))
        } catch (error: SecurityException) {
            nativeLinkFailure(sourceDetail, error.runtimeDetail("native link denied"))
        } catch (error: IllegalCallerException) {
            nativeLinkFailure(sourceDetail, error.runtimeDetail("native access denied"))
        } catch (error: IllegalArgumentException) {
            nativeLinkFailure(sourceDetail, error.runtimeDetail("native library load failed"))
        } catch (error: NoSuchElementException) {
            nativeLinkFailure(sourceDetail, error.runtimeDetail("native ABI symbol is missing"))
        } catch (error: VmBridgeException) {
            nativeLinkFailure(sourceDetail, error.runtimeDetail("native ABI is incompatible"))
        }

    private fun invalidExplicit(
        path: Path,
        detail: String,
    ): VmRuntimeLoadResult =
        VmRuntimeLoadResult.Failed(
            VmRuntimeLoadFailure.InvalidExplicitPath(
                path = path.toString().runtimeDiagnostic("invalid path"),
                detail = detail.runtimeDiagnostic("invalid explicit native path"),
            ),
        )

    private fun extractionFailure(
        resourcePath: String,
        detail: String,
    ): VmRuntimeLoadResult =
        VmRuntimeLoadResult.Failed(
            VmRuntimeLoadFailure.ResourceExtraction(
                resourcePath = resourcePath,
                detail = detail.runtimeDiagnostic("resource extraction failed"),
            ),
        )

    private fun nativeLinkFailure(
        source: String,
        detail: String,
    ): VmRuntimeLoadResult =
        VmRuntimeLoadResult.Failed(
            VmRuntimeLoadFailure.NativeLink(
                source = source.runtimeDiagnostic("native source"),
                detail = detail.runtimeDiagnostic("native link failed"),
            ),
        )

    private fun applyOwnerOnlyPermissions(path: Path) {
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) ?: return
        view.setPermissions(OWNER_ONLY_PERMISSIONS)
    }

    private fun cleanup(
        target: Path?,
        directory: Path?,
    ) {
        tryDelete(target)
        tryDelete(directory)
    }

    private fun tryDelete(path: Path?) {
        if (path == null) return
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // Preserve the original load failure.
        } catch (_: SecurityException) {
            // Preserve the original load failure.
        }
    }

    private fun Throwable.runtimeDetail(fallback: String): String = message.runtimeDiagnostic(fallback)

    private class PackagedNativeTooLargeException(
        limit: Long,
    ) : IOException("native resource exceeds $limit bytes")

    companion object {
        fun production(
            resourceAnchor: Class<*>,
            backend: NativeRuntimeBackend,
        ): NativeRuntimeLoader =
            NativeRuntimeLoader(
                osName = { System.getProperty("os.name").orEmpty() },
                osArch = { System.getProperty("os.arch").orEmpty() },
                resource = resourceAnchor::getResourceAsStream,
                createTempDirectory = { Files.createTempDirectory("compukters-native-") },
                nativeLoad = backend::open,
            )

        private const val COPY_BUFFER_BYTES = 8 * 1024
        private const val DEFAULT_MAXIMUM_PACKAGED_NATIVE_BYTES = 64L * 1024 * 1024
        private val OWNER_ONLY_PERMISSIONS =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
    }
}
