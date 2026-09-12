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

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeRuntimeLoaderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `explicit path is normalized and loaded without resource extraction`() {
        val library =
            temporaryDirectory
                .resolve("nested")
                .createDirectory()
                .resolve("..")
                .resolve("compukter.so")
                .createFile()
        val loadedPaths = mutableListOf<Path>()
        val bridge = FakeBridge()
        var resourceCalls = 0
        var temporaryCalls = 0
        val loader =
            loader(
                resource = {
                    resourceCalls++
                    null
                },
                createTempDirectory = {
                    temporaryCalls++
                    temporaryDirectory.resolve("unused")
                },
                nativeLoad = { path ->
                    loadedPaths.add(path)
                    bridge
                },
            )

        val result = assertIs<VmRuntimeLoadResult.Loaded>(loader.ensureExplicitLoaded(library))

        val expected = library.toAbsolutePath().normalize()
        assertEquals(VmRuntimeLoadSource.ExplicitPath(expected), result.source)
        assertEquals(listOf(expected), loadedPaths)
        assertSame(bridge, loader.requireBridge())
        assertEquals(0, resourceCalls)
        assertEquals(0, temporaryCalls)
    }

    @Test
    fun `invalid explicit path fails before native loading`() {
        val nativeCalls = AtomicInteger()
        val cases = listOf(temporaryDirectory.resolve("missing"), temporaryDirectory.resolve("directory").createDirectory())

        cases.forEach { path ->
            val result =
                loader(nativeLoad = {
                    nativeCalls.incrementAndGet()
                    FakeBridge()
                }).ensureExplicitLoaded(path)

            assertIs<VmRuntimeLoadFailure.InvalidExplicitPath>(assertIs<VmRuntimeLoadResult.Failed>(result).failure)
        }
        assertEquals(0, nativeCalls.get())
    }

    @Test
    fun `packaged resource uses trusted filename and exact bytes`() {
        val nativeBytes = byteArrayOf(0, 1, 2, 3, -1)
        val extractionDirectory = temporaryDirectory.resolve("private")
        var loadedPath: Path? = null
        val loader =
            loader(
                resource = { ByteArrayInputStream(nativeBytes) },
                createTempDirectory = { extractionDirectory.createDirectory() },
                nativeLoad = { path ->
                    loadedPath = path
                    FakeBridge()
                },
            )

        val result = assertIs<VmRuntimeLoadResult.Loaded>(loader.ensurePackagedLoaded())

        assertEquals(
            VmRuntimeLoadSource.PackagedResource("/META-INF/natives/linux/x86_64/libcompukter_ffi.so"),
            result.source,
        )
        val extracted = requireNotNull(loadedPath)
        assertEquals("libcompukter_ffi.so", extracted.fileName.toString())
        assertEquals(extractionDirectory.toAbsolutePath().normalize(), extracted.parent)
        assertContentEquals(nativeBytes, extracted.readBytes())
    }

    @Test
    fun `unsupported platform does not access resources or temporary storage`() {
        var resourceCalls = 0
        var temporaryCalls = 0
        val loader =
            loader(
                osArch = { "riscv64" },
                resource = {
                    resourceCalls++
                    null
                },
                createTempDirectory = {
                    temporaryCalls++
                    temporaryDirectory
                },
            )

        val failure = assertIs<VmRuntimeLoadResult.Failed>(loader.ensurePackagedLoaded()).failure

        assertIs<VmRuntimeLoadFailure.UnsupportedPlatform>(failure)
        assertEquals(0, resourceCalls)
        assertEquals(0, temporaryCalls)
    }

    @Test
    fun `missing resource fails before temporary storage`() {
        var temporaryCalls = 0
        val loader =
            loader(
                resource = { null },
                createTempDirectory = {
                    temporaryCalls++
                    temporaryDirectory
                },
            )

        val failure = assertIs<VmRuntimeLoadResult.Failed>(loader.ensurePackagedLoaded()).failure

        assertIs<VmRuntimeLoadFailure.MissingResource>(failure)
        assertEquals(0, temporaryCalls)
    }

    @Test
    fun `oversized resource fails before native load and removes extraction directory`() {
        val extractionDirectory = temporaryDirectory.resolve("oversized")
        val nativeCalls = AtomicInteger()
        val loader =
            loader(
                resource = { ByteArrayInputStream(ByteArray(5)) },
                createTempDirectory = { extractionDirectory.createDirectory() },
                nativeLoad = {
                    nativeCalls.incrementAndGet()
                    FakeBridge()
                },
                maximumPackagedNativeBytes = 4,
            )

        val failure = assertIs<VmRuntimeLoadResult.Failed>(loader.ensurePackagedLoaded()).failure

        assertIs<VmRuntimeLoadFailure.ResourceExtraction>(failure)
        assertEquals(0, nativeCalls.get())
        assertFalse(extractionDirectory.exists())
    }

    @Test
    fun `link failure is typed bounded and removes extracted files`() {
        val extractionDirectory = temporaryDirectory.resolve("link-failure")
        val loader =
            loader(
                resource = { ByteArrayInputStream(byteArrayOf(1)) },
                createTempDirectory = { extractionDirectory.createDirectory() },
                nativeLoad = { throw UnsatisfiedLinkError("x".repeat(300) + "\nignored") },
            )

        val failure = assertIs<VmRuntimeLoadResult.Failed>(loader.ensurePackagedLoaded()).failure

        val link = assertIs<VmRuntimeLoadFailure.NativeLink>(failure)
        assertEquals("x".repeat(256), link.detail)
        assertFalse(extractionDirectory.exists())
    }

    @Test
    fun `FFM lookup failures are typed native link failures`() {
        val failures =
            listOf(
                IllegalArgumentException("not a native library"),
                NoSuchElementException("missing ABI symbol"),
                IllegalCallerException("native access denied"),
                VmBridgeException("unsupported ABI"),
            )

        failures.forEach { error ->
            val result = loader(nativeLoad = { throw error }).ensurePackagedLoaded()

            assertIs<VmRuntimeLoadFailure.NativeLink>(assertIs<VmRuntimeLoadResult.Failed>(result).failure)
        }
    }

    @Test
    fun `resource access and temporary directory failures are typed`() {
        val resourceFailure =
            loader(resource = { throw SecurityException("resource denied") }).ensurePackagedLoaded()
        assertIs<VmRuntimeLoadFailure.ResourceExtraction>(assertIs<VmRuntimeLoadResult.Failed>(resourceFailure).failure)

        val directoryFailure =
            loader(
                resource = { ByteArrayInputStream(byteArrayOf(1)) },
                createTempDirectory = { throw IOException("disk unavailable") },
            ).ensurePackagedLoaded()
        assertIs<VmRuntimeLoadFailure.ResourceExtraction>(assertIs<VmRuntimeLoadResult.Failed>(directoryFailure).failure)
    }

    @Test
    fun `success and failure are cached by identity`() {
        val successCalls = AtomicInteger()
        val successLoader =
            loader(nativeLoad = {
                successCalls.incrementAndGet()
                FakeBridge()
            })
        val explicit = temporaryDirectory.resolve("later.so").createFile()

        val firstSuccess = successLoader.ensurePackagedLoaded()
        val secondSuccess = successLoader.ensureExplicitLoaded(explicit)

        assertSame(firstSuccess, secondSuccess)
        assertEquals(1, successCalls.get())
        assertIs<VmRuntimeLoadSource.PackagedResource>(assertIs<VmRuntimeLoadResult.Loaded>(secondSuccess).source)

        val failureLoader = loader(resource = { null })
        val firstFailure = failureLoader.ensurePackagedLoaded()
        val secondFailure = failureLoader.ensureExplicitLoaded(explicit)
        assertSame(firstFailure, secondFailure)
    }

    @Test
    fun `concurrent requests execute one native load and share one result`() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loader =
            loader(
                nativeLoad = {
                    calls.incrementAndGet()
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    FakeBridge()
                },
            )
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = List(8) { executor.submit<VmRuntimeLoadResult> { loader.ensurePackagedLoaded() } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, calls.get())
            assertTrue(results.all { it === results.first() })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `unexpected fatal error propagates and is not cached`() {
        val calls = AtomicInteger()
        val loader =
            loader(
                nativeLoad = {
                    calls.incrementAndGet()
                    throw AssertionError("fatal")
                },
            )

        assertFailsWith<AssertionError> { loader.ensurePackagedLoaded() }
        assertFailsWith<AssertionError> { loader.ensurePackagedLoaded() }
        assertEquals(2, calls.get())
    }

    private fun loader(
        osName: () -> String = { "Linux" },
        osArch: () -> String = { "amd64" },
        resource: (String) -> ByteArrayInputStream? = { ByteArrayInputStream(byteArrayOf(1)) },
        createTempDirectory: () -> Path = {
            Files.createTempDirectory(temporaryDirectory, "compukters-native-")
        },
        nativeLoad: (Path) -> LowLevelVmBridge = { FakeBridge() },
        maximumPackagedNativeBytes: Long = 64L * 1024 * 1024,
    ): NativeRuntimeLoader =
        NativeRuntimeLoader(
            osName = osName,
            osArch = osArch,
            resource = resource,
            createTempDirectory = createTempDirectory,
            nativeLoad = nativeLoad,
            maximumPackagedNativeBytes = maximumPackagedNativeBytes,
        )

    private class FakeBridge : LowLevelVmBridge {
        override fun create(artifact: ByteArray): ByteArray = error("unused")

        override fun advance(
            handle: Long,
            guestBudget: Int,
            maintenanceBudget: Int,
            hostRequestBudget: Int,
        ): ByteArray = error("unused")

        override fun resumeUnit(
            handle: Long,
            taskId: Int,
            requestId: Long,
        ) = error("unused")

        override fun resumeString(
            handle: Long,
            taskId: Int,
            requestId: Long,
            value: CharArray,
        ) = error("unused")

        override fun resumeFailure(
            handle: Long,
            taskId: Int,
            requestId: Long,
            kind: Int,
            code: Long,
        ) = error("unused")

        override fun close(handle: Long) = error("unused")
    }
}
