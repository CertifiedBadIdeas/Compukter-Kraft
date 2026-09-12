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

package ru.lazyhat.compukters.lang.runtime.integration

import ru.lazyhat.compukters.lang.runtime.vm.JniBridge
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JniBridgeIntegrationTest {
    @Test
    fun `Java 21 JNI preserves typed create failures`() {
        val bridge = JniBridge.open(Path.of(requiredProperty("compukter.jni.library")))

        assertFailsWith<VmVerificationException> { VmSession.open(byteArrayOf(0), bridge) }
    }

    @Test
    fun `resource and terminal state cross the real JNI boundary`() {
        val bridge = JniBridge.open(Path.of(requiredProperty("compukter.jni.library")))
        val artifact = Path.of(requiredProperty("compukters.shell.artifact")).readBytes()
        VmSession.open(artifact, bridge).use { session ->
            val initialResources = session.resourceSnapshot()
            val initialTerminal = session.terminalFullState()

            repeat(10_000) {
                when (session.advance(64, 64, Int.MAX_VALUE)) {
                    VmOutcome.SliceExhausted -> Unit
                    VmOutcome.WaitingForTerminalEvent -> return@repeat
                    else -> Unit
                }
            }

            val advancedResources = session.resourceSnapshot()
            assertTrue(initialResources.heapCapacityBytes > 0)
            assertTrue(advancedResources.executedInstructions > initialResources.executedInstructions)
            assertTrue(session.terminalFullState().revision >= initialTerminal.revision)
        }
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing $name test property" }
}
