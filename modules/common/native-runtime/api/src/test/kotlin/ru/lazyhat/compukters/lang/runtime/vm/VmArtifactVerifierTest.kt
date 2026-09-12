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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VmArtifactVerifierTest {
    @Test
    fun `verification delegates without creating a VM session`() {
        val bridge = RecordingBridge()
        val artifact = byteArrayOf(1, 2, 3)

        assertTrue(VmArtifactVerifier.verify(artifact, bridge))
        assertTrue(bridge.verified!!.contentEquals(artifact))
        assertFalse(bridge.created)
    }

    private class RecordingBridge : LowLevelVmBridge {
        var verified: ByteArray? = null
        var created = false

        override fun verifyArtifact(artifact: ByteArray): Boolean {
            verified = artifact
            return true
        }

        override fun create(artifact: ByteArray): ByteArray {
            created = true
            error("verification must not create a VM session")
        }

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
