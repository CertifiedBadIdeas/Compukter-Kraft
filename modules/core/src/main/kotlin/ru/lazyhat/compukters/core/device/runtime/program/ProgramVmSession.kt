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

package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryListing
import ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk
import ru.lazyhat.compukters.lang.runtime.fs.VmFileStat
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentCandidate
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmSession

internal interface ProgramVmSession : AutoCloseable {
    fun advance(
        guestBudget: Int,
        maintenanceBudget: Int,
        hostRequestBudget: Int,
    ): VmOutcome

    fun resume(
        identity: VmHostRequestIdentity,
        response: HostResponse,
    )

    fun completeCompilationArtifact(
        token: Long,
        artifact: ByteArray,
    )

    fun completeCompilationFailure(
        token: Long,
        diagnostics: String,
    )

    fun commitTerminal()

    fun terminalFullState(): TerminalState

    fun terminalChangesSince(revision: Long): TerminalUpdate

    fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    )

    fun sendTerminalText(value: String)

    fun filesystemGeneration(): Long

    fun fileStat(path: VmVirtualPath): VmFileStat

    fun fileList(
        path: VmVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ): VmDirectoryListing

    fun fileRead(
        path: VmVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): VmFileChunk

    fun verifyForDeploy(artifact: ByteArray): ProgramDeploymentCandidate

    fun executableRevision(path: String): VmExecutableRevision

    fun deploy(
        path: String,
        expected: VmExecutableRevision,
        candidate: ProgramDeploymentCandidate,
    ): VmExecutableRevision

    fun submitCanonicalLine(line: CharArray)

    fun submitRedstoneInput(packet: Int)

    fun confirmRedstoneOutput(packed: Int)
}

interface ProgramDeploymentCandidate : AutoCloseable

private class NativeProgramDeploymentCandidate(
    val delegate: VmDeploymentCandidate,
) : ProgramDeploymentCandidate {
    override fun close() = delegate.close()
}

internal fun interface ProgramVmSessionFactory {
    fun open(artifact: ByteArray): ProgramVmSession

    fun boot(): ProgramVmSession = throw VmBridgeException("ROM boot is not configured")
}

internal class ProgramFileSystemLaunchContext(
    val store: WorldFileSystemStore,
    val computerId: ComputerId,
    romImage: ByteArray,
) {
    private val romImage = romImage.copyOf()

    fun open(artifact: ByteArray): VmSession = VmSession.openInStore(artifact, store, computerId, romImage.copyOf())

    fun boot(): VmSession = VmSession.bootInStore(store, computerId, romImage.copyOf())
}

internal class NativeProgramVmSessionFactory(
    private val filesystem: ProgramFileSystemLaunchContext? = null,
) : ProgramVmSessionFactory {
    override fun open(artifact: ByteArray): ProgramVmSession =
        NativeProgramVmSession(filesystem?.open(artifact) ?: VmSession.open(artifact))

    override fun boot(): ProgramVmSession {
        val context = filesystem ?: throw VmBridgeException("ROM boot requires a persistent filesystem")
        return NativeProgramVmSession(context.boot())
    }
}

private class NativeProgramVmSession(
    private val session: VmSession,
) : ProgramVmSession {
    override fun advance(
        guestBudget: Int,
        maintenanceBudget: Int,
        hostRequestBudget: Int,
    ): VmOutcome = session.advance(guestBudget, maintenanceBudget, hostRequestBudget)

    override fun resume(
        identity: VmHostRequestIdentity,
        response: HostResponse,
    ) = session.resume(identity, response)

    override fun completeCompilationArtifact(
        token: Long,
        artifact: ByteArray,
    ) = session.completeCompilationArtifact(token, artifact)

    override fun completeCompilationFailure(
        token: Long,
        diagnostics: String,
    ) = session.completeCompilationFailure(token, diagnostics)

    override fun commitTerminal() = session.commitTerminal()

    override fun terminalFullState(): TerminalState = session.terminalFullState()

    override fun terminalChangesSince(revision: Long): TerminalUpdate = session.terminalChangesSince(revision)

    override fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ) = session.sendTerminalKey(key, action, modifiers)

    override fun sendTerminalText(value: String) = session.sendTerminalText(value)

    override fun filesystemGeneration(): Long = session.filesystemGeneration()

    override fun fileStat(path: VmVirtualPath): VmFileStat = session.fileStat(path)

    override fun fileList(
        path: VmVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ): VmDirectoryListing = session.fileList(path, startAfter, maximumEntries)

    override fun fileRead(
        path: VmVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): VmFileChunk = session.fileRead(path, offset, maximumBytes, expectedGeneration)

    override fun verifyForDeploy(artifact: ByteArray): ProgramDeploymentCandidate =
        NativeProgramDeploymentCandidate(session.verifyForDeploy(artifact))

    override fun executableRevision(path: String): VmExecutableRevision = session.executableRevision(path)

    override fun deploy(
        path: String,
        expected: VmExecutableRevision,
        candidate: ProgramDeploymentCandidate,
    ): VmExecutableRevision {
        require(candidate is NativeProgramDeploymentCandidate) { "deployment candidate was not produced by the native VM" }
        return session.deploy(path, expected, candidate.delegate)
    }

    override fun submitCanonicalLine(line: CharArray) = session.submitCanonicalLine(line)

    override fun submitRedstoneInput(packet: Int) = session.submitRedstoneInput(packet)

    override fun confirmRedstoneOutput(packed: Int) = session.confirmRedstoneOutput(packed)

    override fun close() = session.close()
}
