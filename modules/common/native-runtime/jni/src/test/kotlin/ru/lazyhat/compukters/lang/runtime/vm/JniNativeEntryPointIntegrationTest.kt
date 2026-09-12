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

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JniNativeEntryPointIntegrationTest {
    @Test
    fun `every Java 21 JNI entry point resolves against ABI v12 adapter`() {
        JniBridge.open(Path.of(requiredProperty("compukter.jni.library")))
        val bytes = ByteArray(0)
        val output = ByteArray(1)
        val id = ByteArray(16)
        val invalidLimits = byteArrayOf(0xff.toByte())
        val written = LongArray(1)
        val candidate = LongArray(1)

        assertEquals(12, JniNative.abiVersion())
        assertTrue(JniNative.maximumOutcomeBytes() > 0)
        assertTrue(JniNative.maximumCreateBytes() > 0)
        statuses(
            JniNative.verifyArtifact(bytes),
            JniNative.storeOpen(bytes, invalidLimits, output, written),
            JniNative.storeHealth(0, output, written),
            JniNative.storeDurableGeneration(0, id, output, written),
            JniNative.storeFlush(0, id, 0),
            JniNative.storeTombstone(0, id),
            JniNative.storeRecover(0, id),
            JniNative.storeClose(0),
            JniNative.create(bytes, output, written),
            JniNative.createInStore(0, id, bytes, bytes, output, written),
            JniNative.createBootInStore(0, id, bytes, output, written),
            JniNative.close(0),
            JniNative.submitRedstoneInput(0, 0),
            JniNative.confirmRedstoneOutput(0, 0),
            JniNative.verifyForDeploy(0, bytes, candidate),
            JniNative.deploymentCandidateClose(0),
            JniNative.executableRevision(0, bytes, output, written),
            JniNative.deploy(0, 0, bytes, 0, 0, output, written),
            JniNative.submitCanonicalLine(0, charArrayOf()),
            JniNative.filesystemGeneration(0, output, written),
            JniNative.resourceSnapshot(0, output, written),
            JniNative.filesystemStat(0, bytes, output, written),
            JniNative.filesystemList(0, bytes, bytes, 1, output, written),
            JniNative.filesystemRead(0, bytes, 0, 1, 0, output, written),
            JniNative.advance(0, 1, 1, 1, output, written),
            JniNative.compilationRequestSize(0, 0, written),
            JniNative.compilationRequestCopy(0, 0, output, written),
            JniNative.compilationComplete(0, 0, 0, bytes),
            JniNative.resumeUnit(0, 1, 0),
            JniNative.resumeBool(0, 1, 0, false),
            JniNative.resumeString(0, 1, 0, charArrayOf()),
            JniNative.resumeFailure(0, 1, 0, 0, 0),
            JniNative.terminalCommit(0),
            JniNative.terminalFullState(0, output, written),
            JniNative.terminalChangesSince(0, 0, output, written),
            JniNative.terminalKey(0, 0, 0, 0),
            JniNative.terminalText(0, intArrayOf()),
        )
    }

    private fun statuses(vararg values: Int) {
        values.forEach { assertTrue(it in 0..42, "unexpected FFI status $it") }
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing $name test property" }
}
