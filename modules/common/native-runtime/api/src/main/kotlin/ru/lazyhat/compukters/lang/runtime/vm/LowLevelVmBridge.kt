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

interface LowLevelVmBridge {
    fun openTerminalTransport(): TerminalWireTransport = ByteArrayTerminalWireTransport(this)

    fun storeOpen(
        rootUtf8: ByteArray,
        limitsWire: ByteArray,
    ): ByteArray = error("filesystem store open is unavailable")

    fun storeHealth(handle: Long): ByteArray = error("filesystem store health is unavailable")

    fun storeDurableGeneration(
        handle: Long,
        id: ByteArray,
    ): ByteArray = error("filesystem durable generation is unavailable")

    fun storeFlush(
        handle: Long,
        id: ByteArray,
        generation: Long,
    ): Unit = error("filesystem flush is unavailable")

    fun storeTombstone(
        handle: Long,
        id: ByteArray,
    ): Unit = error("filesystem tombstone is unavailable")

    fun storeRecover(
        handle: Long,
        id: ByteArray,
    ): Unit = error("filesystem recovery is unavailable")

    fun storeClose(handle: Long): Unit = error("filesystem store close is unavailable")

    fun verifyArtifact(artifact: ByteArray): Boolean = error("artifact verification is unavailable")

    fun create(artifact: ByteArray): ByteArray

    fun createInStore(
        storeHandle: Long,
        id: ByteArray,
        rom: ByteArray,
        artifact: ByteArray,
    ): ByteArray = error("persistent VM creation is unavailable")

    fun createBootInStore(
        storeHandle: Long,
        id: ByteArray,
        rom: ByteArray,
    ): ByteArray = error("persistent VM boot is unavailable")

    fun filesystemGeneration(handle: Long): ByteArray = error("filesystem generation is unavailable")

    fun resourceSnapshot(handle: Long): ByteArray = error("resource snapshot is unavailable")

    fun fileStat(
        handle: Long,
        pathUtf8: ByteArray,
    ): ByteArray = error("filesystem stat is unavailable")

    fun fileList(
        handle: Long,
        pathUtf8: ByteArray,
        startAfterUtf8: ByteArray,
        maximumEntries: Int,
    ): ByteArray = error("filesystem list is unavailable")

    fun fileRead(
        handle: Long,
        pathUtf8: ByteArray,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): ByteArray = error("filesystem read is unavailable")

    fun verifyForDeploy(
        handle: Long,
        artifact: ByteArray,
    ): Long = error("deployment verification is unavailable")

    fun deploymentCandidateClose(handle: Long): Unit = error("deployment candidate close is unavailable")

    fun executableRevision(
        handle: Long,
        pathUtf8: ByteArray,
    ): ByteArray = error("executable revision is unavailable")

    fun deploy(
        handle: Long,
        candidateHandle: Long,
        pathUtf8: ByteArray,
        expectedKind: Int,
        expectedGeneration: Long,
    ): ByteArray = error("deployment is unavailable")

    fun submitCanonicalLine(
        handle: Long,
        line: CharArray,
    ): Unit = error("canonical line submission is unavailable")

    fun submitRedstoneInput(
        handle: Long,
        packet: Int,
    ): Unit = error("redstone input is unavailable")

    fun confirmRedstoneOutput(
        handle: Long,
        packed: Int,
    ): Unit = error("redstone output is unavailable")

    fun advance(
        handle: Long,
        guestBudget: Int,
        maintenanceBudget: Int,
        hostRequestBudget: Int,
    ): ByteArray

    fun compilationRequest(
        handle: Long,
        token: Long,
    ): ByteArray = error("compilation requests are unavailable")

    fun completeCompilationArtifact(
        handle: Long,
        token: Long,
        artifact: ByteArray,
    ): Unit = error("compilation completion is unavailable")

    fun completeCompilationFailure(
        handle: Long,
        token: Long,
        diagnostics: String,
    ): Unit = error("compilation completion is unavailable")

    fun resumeUnit(
        handle: Long,
        taskId: Int,
        requestId: Long,
    )

    fun resumeBool(
        handle: Long,
        taskId: Int,
        requestId: Long,
        value: Boolean,
    ): Unit = error("Boolean host responses are unavailable")

    fun resumeString(
        handle: Long,
        taskId: Int,
        requestId: Long,
        value: CharArray,
    )

    fun resumeFailure(
        handle: Long,
        taskId: Int,
        requestId: Long,
        kind: Int,
        code: Long,
    )

    fun close(handle: Long)

    fun terminalCommit(handle: Long): Unit = error("terminal commit is unavailable")

    fun terminalFullState(handle: Long): ByteArray = error("terminal state is unavailable")

    fun terminalChangesSince(
        handle: Long,
        revision: Long,
    ): ByteArray = error("terminal changes are unavailable")

    fun terminalKey(
        handle: Long,
        key: Int,
        action: Int,
        modifiers: Int,
    ): Unit = error("terminal key input is unavailable")

    fun terminalText(
        handle: Long,
        codePoints: IntArray,
    ): Unit = error("terminal text input is unavailable")
}
