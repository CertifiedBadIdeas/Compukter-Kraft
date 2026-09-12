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

package ru.lazyhat.compukters.compiler.k2.engine.intrinsic

import org.jetbrains.kotlin.name.CallableId
import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import java.util.Collections

@JvmInline
value class CanonicalCallableSignature(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "canonical callable signature must not be blank" }
    }

    override fun toString(): String = value
}

data class TrustedIntrinsicKey(
    val module: PlatformModuleId,
    val callableId: CallableId,
    val signature: CanonicalCallableSignature,
)

data class PlatformCapabilityId(
    val namespace: String,
    val name: String,
    val abiMajor: Int,
) {
    init {
        require(namespace.matches(COMPONENT)) { "invalid capability namespace: $namespace" }
        require(name.matches(COMPONENT)) { "invalid capability name: $name" }
        require(abiMajor > 0) { "capability ABI major must be positive" }
    }

    private companion object {
        val COMPONENT = Regex("[a-z][a-z0-9-]{0,63}")
    }
}

interface TrustedIntrinsicHandler {
    val requiredCapability: PlatformCapabilityId?
}

enum class IntrinsicBlockingMode {
    NONE,
    VM_TASK,
}

data class CompilerPrimitiveHandler(
    val identity: String,
) : TrustedIntrinsicHandler {
    override val requiredCapability: PlatformCapabilityId? = null
}

data class CapabilityOperationHandler(
    override val requiredCapability: PlatformCapabilityId,
    val operation: UInt,
    val blocking: IntrinsicBlockingMode,
    val terminal: Boolean = false,
) : TrustedIntrinsicHandler

data class TrustedIntrinsicRegistration(
    val key: TrustedIntrinsicKey,
    val handler: TrustedIntrinsicHandler,
)

class TrustedIntrinsicRegistry private constructor(
    handlers: Map<TrustedIntrinsicKey, TrustedIntrinsicHandler>,
) {
    val handlers: Map<TrustedIntrinsicKey, TrustedIntrinsicHandler> = Collections.unmodifiableMap(handlers.toMap())

    companion object {
        fun empty(): TrustedIntrinsicRegistry = TrustedIntrinsicRegistry(emptyMap())

        fun create(registrations: List<TrustedIntrinsicRegistration>): TrustedIntrinsicRegistry {
            val handlers = linkedMapOf<TrustedIntrinsicKey, TrustedIntrinsicHandler>()
            registrations.forEach { registration ->
                require(handlers.put(registration.key, registration.handler) == null) {
                    "duplicate intrinsic handler ${registration.key}"
                }
            }
            return TrustedIntrinsicRegistry(handlers)
        }
    }
}

object TrustedIntrinsicContract {
    fun validate(
        declarations: List<PlatformDeclaration>,
        registry: TrustedIntrinsicRegistry,
        executableCapabilities: Set<PlatformCapabilityId>,
    ) {
        val violations = mutableListOf<String>()
        registry.handlers.forEach { (key, handler) ->
            val symbol = key.callableId.asSingleFqName().asString()
            val candidates = declarations.filter { it.module == key.module && it.symbol == symbol }
            if (candidates.isEmpty()) {
                violations += "orphan intrinsic handler $key"
                return@forEach
            }
            val declaration = candidates.singleOrNull { it.signature == key.signature.value }
            if (declaration == null) {
                violations +=
                    "intrinsic signature mismatch for ${key.module} $symbol: handler ${key.signature}, declarations ${candidates.map(
                        PlatformDeclaration::signature,
                    )}"
                return@forEach
            }
            if (!declaration.trustedExternal) {
                violations += "intrinsic handler targets non-external declaration ${declaration.symbol}"
            }
            handler.requiredCapability?.let { capability ->
                if (capability !in executableCapabilities) {
                    violations += "module ${key.module} requires unavailable capability $capability"
                }
            }
        }

        declarations.filter(PlatformDeclaration::trustedExternal).forEach { declaration ->
            val match =
                registry.handlers.keys.any { key ->
                    key.module == declaration.module &&
                        key.callableId.asSingleFqName().asString() == declaration.symbol &&
                        key.signature.value == declaration.signature
                }
            if (!match) {
                violations += "missing intrinsic handler for ${declaration.module} ${declaration.symbol} ${declaration.signature}"
            }
        }
        require(violations.isEmpty()) { violations.joinToString(separator = "\n") }
    }
}
