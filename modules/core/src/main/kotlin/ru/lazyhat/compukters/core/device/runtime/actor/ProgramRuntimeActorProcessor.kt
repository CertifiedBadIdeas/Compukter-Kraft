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

package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadException
import ru.lazyhat.compukters.lang.runtime.vm.TerminalChange
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmCanonicalLineException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentAdmissionException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentConflictException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentFileSystemException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentProfileChangedException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentWrongMachineException
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import java.util.concurrent.CompletableFuture

internal class ProgramRuntimeActorProcessor(
    private val host: ProgramRuntimeHost,
    private val redstonePort: ActorRedstoneHostPort? = null,
) : VmActorProcessor<ProgramRuntimeActorCommand, ProgramRuntimeActorReply> {
    private val deploymentCandidates = mutableMapOf<ProgramDeploymentToken, ProgramDeploymentCandidate>()
    private var nextDeploymentToken = 0L
    private var pendingRedstoneRequest: ProgramRuntimeRequestId? = null
    val closed = CompletableFuture<Long?>()
    private var lastFileSystemGeneration: Long? = null

    override fun process(command: ProgramRuntimeActorCommand): ProgramRuntimeActorReply {
        val value =
            try {
                execute(command).also { captureGeneration() }
            } catch (failure: VmFileSystemReadException) {
                ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.FileSystem(failure.failure))
            } catch (_: VmVerificationException) {
                ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.Verification)
            } catch (failure: VmCanonicalLineException) {
                ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.CanonicalLine(failure.failure))
            } catch (_: VmDeploymentConflictException) {
                deploymentFailure(ProgramDeploymentFailure.CONFLICT)
            } catch (_: VmDeploymentWrongMachineException) {
                deploymentFailure(ProgramDeploymentFailure.WRONG_MACHINE)
            } catch (_: VmDeploymentProfileChangedException) {
                deploymentFailure(ProgramDeploymentFailure.PROFILE_CHANGED)
            } catch (_: VmDeploymentFileSystemException) {
                deploymentFailure(ProgramDeploymentFailure.FILESYSTEM)
            } catch (_: VmDeploymentAdmissionException) {
                deploymentFailure(ProgramDeploymentFailure.ADMISSION)
            } catch (failure: IllegalArgumentException) {
                ProgramRuntimeActorValue.Rejected(
                    ProgramRuntimeActorFailure.InvalidRequest(failure.message ?: "invalid runtime request"),
                )
            } catch (failure: VmBridgeException) {
                ProgramRuntimeActorValue.Rejected(
                    ProgramRuntimeActorFailure.Bridge(failure.message ?: "native VM bridge failure"),
                )
            }
        return ProgramRuntimeActorReply(command.requestId, host.state, value, lastFileSystemGeneration)
    }

    private fun execute(command: ProgramRuntimeActorCommand): ProgramRuntimeActorValue =
        when (command) {
            is ProgramRuntimeActorCommand.Start -> {
                captureGeneration()
                pendingRedstoneRequest = null
                discardAllCandidates()
                ProgramRuntimeActorValue.Start(host.start(command.artifactBytes()))
            }

            is ProgramRuntimeActorCommand.StartBoot -> {
                captureGeneration()
                pendingRedstoneRequest = null
                discardAllCandidates()
                ProgramRuntimeActorValue.Start(host.startBoot())
            }

            is ProgramRuntimeActorCommand.Advance -> {
                host.serverTick()
                redstonePort
                    ?.takeRequestedOutput()
                    ?.let { packed ->
                        pendingRedstoneRequest = command.requestId
                        ProgramRuntimeActorValue.RedstoneOutputRequested(packed)
                    }
                    ?: ProgramRuntimeActorValue.None
            }

            is ProgramRuntimeActorCommand.TerminalFullState -> {
                ProgramRuntimeActorValue.TerminalStateValue(host.terminalFullState()?.immutableCopy())
            }

            is ProgramRuntimeActorCommand.TerminalChangesSince -> {
                ProgramRuntimeActorValue.TerminalUpdateValue(host.terminalChangesSince(command.revision)?.immutableCopy())
            }

            is ProgramRuntimeActorCommand.SendTerminalKey -> {
                ProgramRuntimeActorValue.Accepted(host.sendTerminalKey(command.key, command.action, command.modifiers))
            }

            is ProgramRuntimeActorCommand.SendTerminalText -> {
                ProgramRuntimeActorValue.Accepted(host.sendTerminalText(command.value))
            }

            is ProgramRuntimeActorCommand.FileSystemGeneration -> {
                ProgramRuntimeActorValue.FileSystemGeneration(host.filesystemGeneration())
            }

            is ProgramRuntimeActorCommand.ResourceSnapshot -> {
                ProgramRuntimeActorValue.ResourceSnapshotValue(host.resourceSnapshot())
            }

            is ProgramRuntimeActorCommand.FileStat -> {
                ProgramRuntimeActorValue.FileStatValue(host.fileStat(command.path))
            }

            is ProgramRuntimeActorCommand.FileList -> {
                val listing = host.fileList(command.path, command.startAfter, command.maximumEntries)
                ProgramRuntimeActorValue.FileListValue(listing?.copy(entries = listing.entries.toList()))
            }

            is ProgramRuntimeActorCommand.FileRead -> {
                ProgramRuntimeActorValue.FileChunkValue(
                    host.fileRead(command.path, command.offset, command.maximumBytes, command.expectedGeneration),
                )
            }

            is ProgramRuntimeActorCommand.PrepareDeployment -> {
                prepareDeployment(command)
            }

            is ProgramRuntimeActorCommand.DiscardDeployment -> {
                ProgramRuntimeActorValue.Accepted(deploymentCandidates.remove(command.token)?.closeAndConfirm() == true)
            }

            is ProgramRuntimeActorCommand.ExecutableRevision -> {
                ProgramRuntimeActorValue.ExecutableRevisionValue(host.executableRevision(command.path))
            }

            is ProgramRuntimeActorCommand.Deploy -> {
                deploy(command)
            }

            is ProgramRuntimeActorCommand.SubmitCanonicalLine -> {
                ProgramRuntimeActorValue.Accepted(host.submitCanonicalLine(command.lineChars()))
            }

            is ProgramRuntimeActorCommand.SubmitRedstoneInput -> {
                ProgramRuntimeActorValue.Accepted(host.submitRedstoneInput(command.packet))
            }

            is ProgramRuntimeActorCommand.CompleteRedstoneOutput -> {
                val accepted =
                    pendingRedstoneRequest == command.outputRequestId &&
                        host.completeRedstoneOutput(command.packed, command.result)
                if (accepted) pendingRedstoneRequest = null
                ProgramRuntimeActorValue.Accepted(accepted)
            }

            is ProgramRuntimeActorCommand.Shutdown -> {
                captureGeneration()
                pendingRedstoneRequest = null
                discardAllCandidates()
                host.shutdown()
                ProgramRuntimeActorValue.None
            }

            is ProgramRuntimeActorCommand.Reboot -> {
                captureGeneration()
                pendingRedstoneRequest = null
                discardAllCandidates()
                host.shutdown()
                ProgramRuntimeActorValue.Start(host.startBoot())
            }
        }

    private fun prepareDeployment(command: ProgramRuntimeActorCommand.PrepareDeployment): ProgramRuntimeActorValue {
        val candidate =
            host.verifyForDeploy(command.artifactBytes())
                ?: return ProgramRuntimeActorValue.DeploymentPrepared(null)
        nextDeploymentToken = Math.incrementExact(nextDeploymentToken)
        val token = ProgramDeploymentToken(nextDeploymentToken)
        deploymentCandidates[token] = candidate
        return ProgramRuntimeActorValue.DeploymentPrepared(token)
    }

    private fun deploy(command: ProgramRuntimeActorCommand.Deploy): ProgramRuntimeActorValue {
        val candidate =
            deploymentCandidates.remove(command.token)
                ?: return ProgramRuntimeActorValue.Rejected(
                    ProgramRuntimeActorFailure.InvalidRequest("unknown or consumed deployment token"),
                )
        return candidate.use {
            ProgramRuntimeActorValue.DeployedRevision(host.deploy(command.path, command.expected, candidate))
        }
    }

    override fun close() {
        try {
            val generation =
                try {
                    captureGeneration()
                    lastFileSystemGeneration
                } finally {
                    try {
                        discardAllCandidates()
                    } finally {
                        host.close()
                    }
                }
            closed.complete(generation)
        } catch (failure: Throwable) {
            closed.completeExceptionally(failure)
            throw failure
        }
    }

    private fun discardAllCandidates() {
        val candidates = deploymentCandidates.values.toList()
        deploymentCandidates.clear()
        candidates.forEach(ProgramDeploymentCandidate::close)
    }

    private fun captureGeneration() {
        (host.filesystemGeneration() ?: host.lastClosedFileSystemGeneration)?.let { lastFileSystemGeneration = it }
    }

    private fun ProgramDeploymentCandidate.closeAndConfirm(): Boolean {
        close()
        return true
    }

    private fun deploymentFailure(failure: ProgramDeploymentFailure): ProgramRuntimeActorValue =
        ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.Deployment(failure))

    private fun TerminalState.immutableCopy(): TerminalState =
        copy(
            cells = cells.toList(),
            cursor = cursor.copy(),
        )

    private fun TerminalUpdate.immutableCopy(): TerminalUpdate =
        when (this) {
            is TerminalUpdate.Unchanged -> {
                copy()
            }

            is TerminalUpdate.Full -> {
                copy(state = state.immutableCopy())
            }

            is TerminalUpdate.Delta -> {
                copy(
                    changes =
                        changes.map { change ->
                            when (change) {
                                is TerminalChange.Patch -> change.copy(cells = change.cells.toList())
                                is TerminalChange.Fill -> change.copy()
                                is TerminalChange.Scroll -> change.copy()
                                is TerminalChange.Cursor -> change.copy(position = change.position.copy())
                                TerminalChange.Reset -> TerminalChange.Reset
                            }
                        },
                )
            }
        }
}
