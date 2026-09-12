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

package ru.lazyhat.compukters.worker.payload

import ru.lazyhat.compukters.worker.value.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ToolingBundleManifestTest {
    @Test
    fun `bundle creation is canonical and preserves profile classpath order`() {
        val files =
            linkedMapOf(
                "analysis/lib/analysis.jar" to byteArrayOf(3),
                "common/lib/kotlin-compiler.jar" to byteArrayOf(1),
                "compiler/lib/compiler.jar" to byteArrayOf(2),
            )
        val profiles =
            linkedMapOf(
                "compiler" to
                    ToolingProfileDefinition(
                        identityProperties = mapOf("language" to "2.4", "compiler" to "2.4.10"),
                        mainClass = "example.CompilerMain",
                        classpath = listOf("compiler/lib/compiler.jar", "common/lib/kotlin-compiler.jar"),
                    ),
                "analysis" to
                    ToolingProfileDefinition(
                        identityProperties = mapOf("language" to "2.4", "compiler" to "2.4.10"),
                        mainClass = "example.AnalysisMain",
                        classpath = listOf("common/lib/kotlin-compiler.jar", "analysis/lib/analysis.jar"),
                    ),
            )

        val first = ToolingBundleManifest.create(files, profiles)
        val second = ToolingBundleManifest.create(files.toList().reversed().toMap(), profiles.toList().reversed().toMap())

        assertEquals(first, second)
        assertEquals(listOf("analysis", "compiler"), first.profiles.keys.toList())
        assertEquals(
            listOf("compiler/lib/compiler.jar", "common/lib/kotlin-compiler.jar"),
            first.profiles.getValue("compiler").classpath,
        )
        assertEquals(first.canonicalBundleText(), second.canonicalBundleText())
        assertEquals(
            ToolingBundleIdentity(first.format, first.bundleHash),
            ToolingBundleManifestCodec.decodeIdentity(first.canonicalBundleText().encodeToByteArray()),
        )
        assertEquals(
            first.profiles.getValue("analysis").canonicalText(),
            second.profiles.getValue("analysis").canonicalText(),
        )
        assertEquals(first, ToolingBundleManifestCodec.decode(first.encodedFiles()))
    }

    @Test
    fun `profile order contributes to its identity`() {
        val files =
            mapOf(
                "common/lib/kotlin-compiler.jar" to byteArrayOf(1),
                "compiler/lib/compiler.jar" to byteArrayOf(2),
                "analysis/lib/analysis.jar" to byteArrayOf(3),
            )
        val original = ToolingBundleManifest.create(files, profiles())
        val reordered =
            ToolingBundleManifest.create(
                files,
                profiles() +
                    (
                        "compiler" to
                            profiles().getValue("compiler").copy(
                                classpath = listOf("compiler/lib/compiler.jar", "common/lib/kotlin-compiler.jar"),
                            )
                    ),
            )

        assertNotEquals(
            original.profiles.getValue("compiler").payloadHash,
            reordered.profiles.getValue("compiler").payloadHash,
        )
        assertNotEquals(original.bundleHash, reordered.bundleHash)
    }

    @Test
    fun `bundle rejects unsafe files and cross-profile private references`() {
        listOf(
            "/absolute.jar",
            "../escape.jar",
            "common/lib/../escape.jar",
            "common\\lib\\escape.jar",
            "common/lib/not-a-jar.txt",
            "lib/legacy.jar",
        ).forEach { path ->
            assertFailsWith<IllegalArgumentException> {
                ToolingBundleManifest.create(mapOf(path to byteArrayOf(1)), profiles())
            }
        }

        val files =
            mapOf(
                "common/lib/kotlin-compiler.jar" to byteArrayOf(1),
                "compiler/lib/compiler.jar" to byteArrayOf(2),
                "analysis/lib/analysis.jar" to byteArrayOf(3),
            )
        assertFailsWith<ToolingBundleException> {
            ToolingBundleManifest.create(
                files,
                profiles() +
                    (
                        "compiler" to
                            profiles().getValue("compiler").copy(
                                classpath = listOf("common/lib/kotlin-compiler.jar", "analysis/lib/analysis.jar"),
                            )
                    ),
            )
        }
        assertFailsWith<ToolingBundleException> {
            ToolingBundleManifest.create(files, profiles() - "analysis")
        }
    }

    @Test
    fun `codec rejects noncanonical and forged manifests`() {
        val manifest = ToolingBundleManifest.create(files(), profiles())
        val encoded = manifest.encodedFiles()
        listOf(
            "format=01\nbundleSha256=${manifest.bundleHash.hex()}\n",
            "format=2\nbundleSha256=${manifest.bundleHash.hex()}\n",
            "format=1\nbundleSha256=invalid\n",
            "bundleSha256=${manifest.bundleHash.hex()}\nformat=1\n",
        ).forEach { document ->
            assertFailsWith<ToolingBundleException> {
                ToolingBundleManifestCodec.decodeIdentity(document.encodeToByteArray())
            }
        }
        assertFailsWith<ToolingBundleException> {
            ToolingBundleManifestCodec.decode(encoded + ("unexpected" to byteArrayOf(1)))
        }
        assertFailsWith<ToolingBundleException> {
            ToolingBundleManifestCodec.decode(
                encoded +
                    (
                        TOOLING_BUNDLE_MANIFEST_FILE to
                            encoded
                                .getValue(TOOLING_BUNDLE_MANIFEST_FILE)
                                .decodeToString()
                                .replace("format=1", "format=01")
                                .encodeToByteArray()
                    ),
            )
        }

        val zero = Sha256.of(ByteArray(32))
        val forged =
            ToolingBundleManifest(
                format = ToolingBundleManifest.FORMAT,
                files = manifest.files,
                profiles = manifest.profiles,
                bundleHash = zero,
            )
        assertFailsWith<ToolingBundleException> { ToolingBundleManifestCodec.validate(forged) }
    }

    private fun files(): Map<String, ByteArray> =
        mapOf(
            "common/lib/kotlin-compiler.jar" to byteArrayOf(1),
            "compiler/lib/compiler.jar" to byteArrayOf(2),
            "analysis/lib/analysis.jar" to byteArrayOf(3),
        )

    private fun profiles(): Map<String, ToolingProfileDefinition> =
        mapOf(
            "compiler" to
                ToolingProfileDefinition(
                    identityProperties = mapOf("compiler" to "2.4.10", "language" to "2.4"),
                    mainClass = "example.CompilerMain",
                    classpath = listOf("common/lib/kotlin-compiler.jar", "compiler/lib/compiler.jar"),
                ),
            "analysis" to
                ToolingProfileDefinition(
                    identityProperties = mapOf("compiler" to "2.4.10", "language" to "2.4"),
                    mainClass = "example.AnalysisMain",
                    classpath = listOf("common/lib/kotlin-compiler.jar", "analysis/lib/analysis.jar"),
                ),
        )
}
