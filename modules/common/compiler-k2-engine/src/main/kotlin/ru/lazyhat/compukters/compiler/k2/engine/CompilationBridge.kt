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

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.platform.bundle.PlatformDefaultArgument
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformScalarConstant
import ru.lazyhat.compukters.platform.bundle.PlatformScalarType
import java.nio.file.Path
import ru.lazyhat.compukters.compiler.k2.engine.intrinsic.TrustedIntrinsicRegistry as CanonicalIntrinsicRegistry

fun interface CompilationIrSink {
    fun accept(
        module: IrModuleFragment,
        pluginContext: IrPluginContext,
    )
}

class CompilationSession(
    val irSink: CompilationIrSink,
    val artifactSink: (BinaryValue) -> Unit = {},
    val diagnosticSink: (WorkerDiagnostic) -> Unit = {},
    sourcePaths: Map<String, VirtualSourcePath> = emptyMap(),
    trustedPlatformSourceModules: Map<String, PlatformModuleId> = emptyMap(),
    val canonicalIntrinsicRegistry: CanonicalIntrinsicRegistry? = null,
    val selectedPlatformModules: Set<PlatformModuleId> = emptySet(),
    val platformFunctions: List<PlatformFunctionLink> = emptyList(),
    val platformTypes: List<PlatformTypeLink> = emptyList(),
    val platformFields: List<PlatformFieldLink> = emptyList(),
    val platformScalarTypes: List<PlatformScalarType> = emptyList(),
    val platformScalarConstants: List<PlatformScalarConstant> = emptyList(),
    val limits: WorkerLimits = WorkerLimits(),
) {
    private val sourcePaths = sourcePaths.mapKeys { (path, _) -> normalize(path) }
    private val trustedPlatformSourceModules = trustedPlatformSourceModules.mapKeys { (path, _) -> normalize(path) }

    fun virtualSourcePath(physicalPath: String?): VirtualSourcePath? = physicalPath?.let { sourcePaths[normalize(it)] }

    fun trustedPlatformModule(physicalPath: String?): PlatformModuleId? = physicalPath?.let { trustedPlatformSourceModules[normalize(it)] }

    private fun normalize(path: String): String =
        runCatching {
            Path
                .of(path)
                .toAbsolutePath()
                .normalize()
                .toString()
        }.getOrDefault(path)
}

data class PlatformFunctionLink(
    val symbol: String,
    val signature: String,
    val exportName: String,
    val moduleHash: ByteArray,
    val defaultArguments: List<PlatformDefaultArgument?> = emptyList(),
) {
    init {
        require(moduleHash.size == 32) { "platform function module hash must be SHA-256" }
    }
}

data class PlatformTypeLink(
    val symbol: String,
    val exportName: String,
    val moduleHash: ByteArray,
) {
    init {
        require(moduleHash.size == 32) { "platform type module hash must be SHA-256" }
    }
}

data class PlatformFieldLink(
    val symbol: String,
    val ownerSymbol: String,
    val exportName: String,
    val static: Boolean,
    val moduleHash: ByteArray,
) {
    init {
        require(moduleHash.size == 32) { "platform field module hash must be SHA-256" }
    }
}

object CompilationBridge {
    private val active = ThreadLocal<CompilationSession?>()

    fun <T> withSession(
        session: CompilationSession,
        action: () -> T,
    ): T {
        check(active.get() == null) { "a compiler session is already active on this thread" }
        active.set(session)
        return try {
            action()
        } finally {
            active.remove()
        }
    }

    internal fun requireSession(): CompilationSession =
        checkNotNull(active.get()) { "K2 IR extension ran without an active compilation session" }
}
