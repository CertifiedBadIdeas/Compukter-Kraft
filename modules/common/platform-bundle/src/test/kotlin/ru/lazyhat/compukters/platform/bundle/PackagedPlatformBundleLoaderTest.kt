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

package ru.lazyhat.compukters.platform.bundle

import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackagedPlatformBundleLoaderTest {
    @Test
    fun `loads the sole classpath bundle and admits its compiler identity`() {
        val root = createTempDirectory("compukters-platform-loader-")
        try {
            val bundle = fixture()
            val jar = root.resolve("platform.jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { output ->
                output.putNextEntry(ZipEntry("compukters-platform/compukters-platform.cpb"))
                output.write(PlatformBundleCodec.encode(bundle))
                output.closeEntry()
            }

            assertEquals(
                bundle,
                PackagedPlatformBundleLoader.load(
                    listOf(jar),
                    bundle.identity.languageVersion,
                    bundle.identity.contentHash,
                ),
            )
            assertFailsWith<IllegalStateException> {
                PackagedPlatformBundleLoader.load(
                    listOf(jar),
                    "2.5",
                    bundle.identity.contentHash,
                )
            }
            assertFailsWith<IllegalStateException> {
                PackagedPlatformBundleLoader.load(
                    listOf(jar, jar),
                    bundle.identity.languageVersion,
                    bundle.identity.contentHash,
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun fixture(): PlatformBundle {
        val builtins =
            PlatformModule(
                id = PlatformModuleId("kotlin", "builtins"),
                version = "1.0.0",
                dependencies = emptyList(),
                metadata = ImmutableBytes.of(byteArrayOf(1)),
                libraryFragment = null,
                sources = emptyList(),
                declarations = emptyList(),
                completionDeclarations = emptyList(),
            )
        return PlatformBundleCodec.assemble(
            "2.4",
            PlatformBundleCodec.SUPPORTED_PLATFORM_ABI,
            builtins,
            emptyList(),
        )
    }
}
