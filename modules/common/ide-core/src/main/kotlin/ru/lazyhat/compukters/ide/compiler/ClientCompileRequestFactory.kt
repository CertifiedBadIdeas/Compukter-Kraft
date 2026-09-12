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

package ru.lazyhat.compukters.ide.compiler

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfile
import ru.lazyhat.compukters.ide.compiler.profile.ResolvedPlatformModule

data class ClientBuildSnapshot(
    val sources: ProjectSnapshot,
    val manifestBytes: BinaryValue,
    val lockBytes: BinaryValue,
    val profile: CompileProfile,
    val target: TargetSettings = TargetSettings.KOTLIN_2_4_JVM_17,
)

data class PreparedClientCompilation(
    val request: CompileRequest,
    val identity: Hash256,
    val sourceSnapshotId: SourceSnapshotId,
)

object ClientCompileRequestFactory {
    fun prepare(input: ClientBuildSnapshot): PreparedClientCompilation {
        val toolchain = input.profile.toolchain
        val request =
            CompileRequest(
                requestId = IDENTITY_REQUEST_ID,
                sources = input.sources.sources,
                target = input.target,
                expectedIdentity =
                    WorkerIdentity(
                        toolchain.compilerVersion,
                        toolchain.languageVersion,
                        toolchain.codegenAbi,
                        toolchain.artifactWriterVersion,
                        toolchain.payloadHash,
                        toolchain.platformAbi,
                    ),
                limits = input.profile.limits,
                platformModules = input.profile.modules.map(::trustedIdentity),
            )
        return PreparedClientCompilation(
            request,
            ClientCompilationIdentity.compute(request, input.manifestBytes, input.lockBytes, input.profile),
            SourceSnapshotIdentity.of(input.sources),
        )
    }

    private fun trustedIdentity(module: ResolvedPlatformModule) =
        TrustedBundleIdentity.of(module.identity.id.value, module.identity.contentHash)

    private val IDENTITY_REQUEST_ID = RequestId.of(1uL)
}
