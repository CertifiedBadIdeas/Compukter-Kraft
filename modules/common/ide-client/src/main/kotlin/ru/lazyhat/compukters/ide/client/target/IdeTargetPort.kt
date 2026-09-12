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

package ru.lazyhat.compukters.ide.client.target

import java.util.concurrent.CompletableFuture

interface IdeTargetPort {
    fun attach(claim: IdeTargetClaim): CompletableFuture<IdeAttachResult>

    fun verify(
        target: IdeAttachedTarget,
        artifact: IdeTargetArtifact,
    ): CompletableFuture<IdeVerifyResult>

    fun executableRevision(
        target: IdeAttachedTarget,
        path: IdeDeploymentPath,
    ): CompletableFuture<IdeRevisionResult>

    fun fileStat(
        target: IdeAttachedTarget,
        path: IdeTargetVirtualPath,
    ): CompletableFuture<IdeFileStatResult> = CompletableFuture.completedFuture(IdeFileStatResult.Failed(unsupportedFiles()))

    fun fileList(
        target: IdeAttachedTarget,
        path: IdeTargetVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ): CompletableFuture<IdeFileListResult> = CompletableFuture.completedFuture(IdeFileListResult.Failed(unsupportedFiles()))

    fun fileRead(
        target: IdeAttachedTarget,
        path: IdeTargetVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): CompletableFuture<IdeFileReadResult> = CompletableFuture.completedFuture(IdeFileReadResult.Failed(unsupportedFiles()))

    fun deploy(
        target: IdeAttachedTarget,
        ticket: IdeVerificationTicket,
        path: IdeDeploymentPath,
        expected: IdeExecutableRevision,
    ): CompletableFuture<IdeDeployResult>

    fun submitCanonicalLine(
        target: IdeAttachedTarget,
        line: CharArray,
    ): CompletableFuture<IdeSubmissionResult>

    fun heartbeat(target: IdeAttachedTarget): CompletableFuture<IdeHeartbeatResult>

    fun detach(target: IdeAttachedTarget): CompletableFuture<Unit>

    private fun unsupportedFiles() = IdeTargetFailure(IdeTargetFailureKind.Unsupported, "Target filesystem is unavailable")
}
