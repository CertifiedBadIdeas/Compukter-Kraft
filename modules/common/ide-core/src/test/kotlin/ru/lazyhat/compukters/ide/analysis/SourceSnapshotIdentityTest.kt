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

package ru.lazyhat.compukters.ide.analysis

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SourceSnapshotIdentityTest {
    @Test
    fun `identity is deterministic path and content sensitive`() {
        val original = snapshot("src/a.kt" to "fun a() = 1", "src/b.kt" to "fun b() = 2")

        assertEquals(SourceSnapshotIdentity.of(original), SourceSnapshotIdentity.of(original))
        assertNotEquals(
            SourceSnapshotIdentity.of(original),
            SourceSnapshotIdentity.of(
                snapshot(
                    "src/c.kt" to "fun a() = 1",
                    "src/b.kt" to "fun b() = 2",
                ),
            ),
        )
        assertNotEquals(
            SourceSnapshotIdentity.of(original),
            SourceSnapshotIdentity.of(
                snapshot(
                    "src/a.kt" to "fun a() = 0",
                    "src/b.kt" to "fun b() = 2",
                ),
            ),
        )
    }

    @Test
    fun `identity uses the documented domain and little-endian explicit lengths`() {
        val snapshot = snapshot("src/main.kt" to "fun main() = Unit\n")
        val source = snapshot.sources.single()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("Compukters source snapshot v1\u0000".encodeToByteArray())
        digest.updateLittleEndian(1)
        val path = source.path.value.encodeToByteArray()
        val content = source.content.toByteArray()
        digest.updateLittleEndian(path.size)
        digest.update(path)
        digest.updateLittleEndian(content.size)
        digest.update(content)

        assertEquals(SourceSnapshotId(Hash256.of(digest.digest())), SourceSnapshotIdentity.of(snapshot))
    }

    private fun snapshot(vararg sources: Pair<String, String>): ProjectSnapshot =
        ProjectSnapshot.of(
            sources
                .sortedBy { it.first }
                .map { (path, text) -> ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(text.encodeToByteArray())) },
            WorkerLimits(),
        )

    private fun MessageDigest.updateLittleEndian(value: Int) {
        repeat(Int.SIZE_BYTES) { shift -> update((value ushr (shift * 8)).toByte()) }
    }
}
